package com.distressedelk.lumi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lumi 1.0 persistent memory vault.
 *
 * The vault is deliberately independent from any OpenAI/local-model session.  Model sessions
 * may come and go; the relationship history remains in this on-device database.  No API keys,
 * tokens, passwords or signing secrets are accepted by this class.
 */
final class LumiMemoryVault extends SQLiteOpenHelper {
    private static final String DB_NAME = "lumi_memory_vault.db";
    private static final int DB_VERSION = 1;
    private static volatile LumiMemoryVault instance;
    private final Context app;

    static LumiMemoryVault get(Context context) {
        if (instance == null) {
            synchronized (LumiMemoryVault.class) {
                if (instance == null) instance = new LumiMemoryVault(context.getApplicationContext());
            }
        }
        return instance;
    }

    private LumiMemoryVault(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        app = context;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, scope TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, source TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts DESC)");
        db.execSQL("CREATE INDEX idx_events_scope ON events(scope, ts DESC)");
        db.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, category TEXT NOT NULL, memory_key TEXT NOT NULL, value TEXT NOT NULL, importance INTEGER NOT NULL DEFAULT 50, source TEXT NOT NULL, UNIQUE(category,memory_key) ON CONFLICT REPLACE)");
        db.execSQL("CREATE INDEX idx_memories_category ON memories(category, ts DESC)");
        db.execSQL("CREATE TABLE ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kind TEXT NOT NULL, summary TEXT NOT NULL, detail TEXT NOT NULL, transaction_id TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_ledger_ts ON ledger(ts DESC)");
        db.execSQL("CREATE TABLE meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 is the launch schema. Future schema changes must be forward migrations, never DROP.
        if (oldVersion < 1) onCreate(db);
    }

    synchronized void initializeFromLegacy(android.content.SharedPreferences prefs) {
        SQLiteDatabase db = getWritableDatabase();
        if (!"1".equals(meta(db, "legacy_import_complete"))) {
            String normal = prefs.getString("talk_transcript", "");
            importLegacyTranscript(db, normal, "general");
            String priv = prefs.getString("private_talk_transcript", "");
            importLegacyTranscript(db, priv, "private");
            String learned = prefs.getString("learned_facts", "").trim();
            if (!learned.isEmpty()) upsertMemory(db, "learned", "legacy_learned_facts", learned, 70, "legacy-preferences");
            String people = prefs.getString("people_cards_json", "").trim();
            if (!people.isEmpty() && !"[]".equals(people)) upsertMemory(db, "people", "legacy_people_cards", people, 75, "legacy-preferences");
            String ownerNotes = prefs.getString("owner_intro_notes", "").trim();
            if (!ownerNotes.isEmpty()) upsertMemory(db, "identity", "owner_intro_notes", ownerNotes, 95, "legacy-preferences");
            setMeta(db, "legacy_import_complete", "1");
            appendLedger(db, "migration", "Imported legacy Lumi memory into Memory Vault", "Preference transcript/memory migration completed.", "");
        }
        if (!"1".equals(meta(db, "launch_seed_import_complete"))) {
            try {
                String seed = readAsset("lumi-memory-seed.txt");
                if (!seed.trim().isEmpty()) upsertMemory(db, "project_history", "lumi_launch_history_seed", seed, 90, "Lumi-1.0-launch-seed");
            } catch (Exception ignored) {}
            setMeta(db, "launch_seed_import_complete", "1");
        }
        setMeta(db, "schema", "1");
    }

    synchronized void recordTurn(String role, String content, boolean privateMode) {
        if (content == null || content.trim().isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis());
        v.put("scope", privateMode ? "private" : "general");
        v.put("role", role == null ? "unknown" : role);
        v.put("content", PrivateStore.encrypt(bounded(redactSecrets(content.trim()), 20000)));
        v.put("source", "conversation");
        getWritableDatabase().insertOrThrow("events", null, v);
        pruneEvents(getWritableDatabase());
    }

    synchronized void remember(String category, String key, String value, int importance, String source) {
        if (value == null || value.trim().isEmpty()) return;
        if (looksSecret(key) || looksSecret(value)) return;
        upsertMemory(getWritableDatabase(), safe(category, "general"), safe(key, "memory-" + System.currentTimeMillis()), bounded(value.trim(), 100000), clamp(importance, 1, 100), safe(source, "conversation"));
    }

    synchronized void ledger(String kind, String summary, String detail, String transactionId) {
        appendLedger(getWritableDatabase(), safe(kind, "event"), bounded(safe(summary, "Lumi event"), 1000), bounded(safe(detail, ""), 20000), bounded(safe(transactionId, ""), 200));
    }

    synchronized String contextPacket(String query, boolean privateMode, int maxChars) {
        int cap = clamp(maxChars, 1000, 16000);
        StringBuilder out = new StringBuilder();
        out.append("Persistent Lumi Memory Vault context:\n");
        ArrayList<String> terms = queryTerms(query);
        SQLiteDatabase db = getReadableDatabase();

        // Values are encrypted at rest, so relevance filtering happens after decryption in-process.
        Cursor m = db.query("memories", new String[]{"category","memory_key","value","importance"}, null,
                null, null, null, "importance DESC, ts DESC", "80");
        ArrayList<String> fallback = new ArrayList<>();
        try {
            while (m.moveToNext()) {
                String category=m.getString(0), key=m.getString(1), value=PrivateStore.decrypt(m.getString(2));
                if(value.isEmpty()) continue;
                String hay=(category+" "+key+" "+value).toLowerCase(Locale.US);
                boolean relevant=terms.isEmpty();
                for(String term:terms) if(hay.contains(term.toLowerCase(Locale.US))){ relevant=true; break; }
                String line="- ["+category+"] "+key+": "+value+"\n";
                if(relevant && out.length()<cap) appendBounded(out,line,cap);
                else if(fallback.size()<10) fallback.add(line);
            }
        } finally { m.close(); }
        if(out.length()<120 && !fallback.isEmpty()) for(String line:fallback) if(out.length()<cap) appendBounded(out,line,cap);

        String scopeSel = privateMode ? "scope IN ('general','private')" : "scope='general'";
        Cursor e = db.query("events", new String[]{"role","content"}, scopeSel, null, null, null, "ts DESC", "24");
        ArrayList<String> recent = new ArrayList<>();
        try { while(e.moveToNext()) { String text=PrivateStore.decrypt(e.getString(1)); if(!text.isEmpty())recent.add(e.getString(0)+": "+text); } } finally { e.close(); }
        if (!recent.isEmpty() && out.length()<cap) {
            appendBounded(out, "Recent cross-session continuity:\n", cap);
            for (int i=recent.size()-1;i>=0 && out.length()<cap;i--) appendBounded(out, recent.get(i)+"\n", cap);
        }
        return bounded(out.toString(), cap);
    }


    synchronized JSONObject exportJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "LumiMemoryVault");
        root.put("schemaVersion", 1);
        root.put("exportedAt", System.currentTimeMillis());
        SQLiteDatabase db = getReadableDatabase();
        root.put("events", exportEvents(db));
        root.put("memories", exportMemories(db));
        root.put("ledger", exportLedger(db));
        return root;
    }


    synchronized void importJson(JSONObject root) throws Exception {
        if (root == null || !"LumiMemoryVault".equals(root.optString("format"))) throw new IllegalArgumentException("Not a Lumi Memory Vault export");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONArray events = root.optJSONArray("events");
            if (events != null) for(int i=0;i<events.length();i++) {
                JSONObject x=events.getJSONObject(i); ContentValues v=new ContentValues();
                v.put("ts",x.optLong("ts",System.currentTimeMillis())); v.put("scope",safe(x.optString("scope"),"general"));
                v.put("role",safe(x.optString("role"),"unknown")); v.put("content",PrivateStore.encrypt(bounded(x.optString("content"),20000))); v.put("source","portable-restore");
                db.insert("events",null,v);
            }
            JSONArray memories = root.optJSONArray("memories");
            if (memories != null) for(int i=0;i<memories.length();i++) {
                JSONObject x=memories.getJSONObject(i);
                String key=x.optString("memory_key",""); String value=x.optString("value","");
                if (!looksSecret(key) && !looksSecret(value)) upsertMemory(db,safe(x.optString("category"),"restored"),safe(key,"restored-"+i),bounded(value,100000),clamp(x.optInt("importance",50),1,100),"portable-restore");
            }
            JSONArray ledger = root.optJSONArray("ledger");
            if (ledger != null) for(int i=0;i<ledger.length();i++) {
                JSONObject x=ledger.getJSONObject(i); appendLedger(db,safe(x.optString("kind"),"restored"),bounded(x.optString("summary"),1000),bounded(x.optString("detail"),20000),bounded(x.optString("transaction_id"),200));
            }
            setMeta(db,"last_restore_at",String.valueOf(System.currentTimeMillis()));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        pruneEvents(db);
    }

    synchronized JSONObject stats() {
        JSONObject o = new JSONObject(); SQLiteDatabase db=getReadableDatabase();
        try { o.put("events", count(db,"events")); o.put("memories", count(db,"memories")); o.put("ledgerEntries", count(db,"ledger")); o.put("schemaVersion",DB_VERSION); o.put("ready",true); }
        catch(Exception e) { try{o.put("ready",false);o.put("error",e.getClass().getSimpleName());}catch(Exception ignored){} }
        return o;
    }

    synchronized String recentLedger(int limit) {
        StringBuilder s=new StringBuilder(); Cursor c=getReadableDatabase().query("ledger",new String[]{"ts","kind","summary","detail","transaction_id"},null,null,null,null,"ts DESC",String.valueOf(clamp(limit,1,50)));
        try { while(c.moveToNext()) { String summary=PrivateStore.decrypt(c.getString(2));String detail=PrivateStore.decrypt(c.getString(3));s.append(c.getLong(0)).append(" | ").append(c.getString(1)).append(" | ").append(summary); if(!c.getString(4).isEmpty())s.append(" | tx=").append(c.getString(4)); if(!detail.isEmpty())s.append(" | ").append(detail); s.append('\n'); } }
        finally { c.close(); }
        return s.toString();
    }


    private void importLegacyTranscript(SQLiteDatabase db, String transcript, String scope) {
        if (transcript == null || transcript.trim().isEmpty()) return;
        String[] lines=transcript.split("\\r?\\n");
        for(String line:lines){ String t=line.trim(); if(t.isEmpty())continue; String role="legacy"; String content=t; int p=t.indexOf(':'); if(p>0&&p<20){role=t.substring(0,p).trim();content=t.substring(p+1).trim();}
            ContentValues v=new ContentValues();v.put("ts",System.currentTimeMillis());v.put("scope",scope);v.put("role",role);v.put("content",PrivateStore.encrypt(bounded(content,20000)));v.put("source","legacy-transcript");db.insert("events",null,v); }
    }

    private void upsertMemory(SQLiteDatabase db,String category,String key,String value,int importance,String source){ ContentValues v=new ContentValues();v.put("ts",System.currentTimeMillis());v.put("category",category);v.put("memory_key",key);v.put("value",PrivateStore.encrypt(value));v.put("importance",importance);v.put("source",source);db.insertWithOnConflict("memories",null,v,SQLiteDatabase.CONFLICT_REPLACE); }
    private void appendLedger(SQLiteDatabase db,String kind,String summary,String detail,String tx){ ContentValues v=new ContentValues();v.put("ts",System.currentTimeMillis());v.put("kind",kind);v.put("summary",PrivateStore.encrypt(summary));v.put("detail",PrivateStore.encrypt(detail));v.put("transaction_id",tx);db.insertOrThrow("ledger",null,v); }
    private JSONArray exportEvents(SQLiteDatabase db)throws Exception{JSONArray a=new JSONArray();Cursor c=db.query("events",new String[]{"ts","scope","role","content","source"},null,null,null,null,"ts ASC","12000");try{while(c.moveToNext()){a.put(new JSONObject().put("ts",c.getLong(0)).put("scope",c.getString(1)).put("role",c.getString(2)).put("content",PrivateStore.decrypt(c.getString(3))).put("source",c.getString(4)));}}finally{c.close();}return a;}
    private JSONArray exportMemories(SQLiteDatabase db)throws Exception{JSONArray a=new JSONArray();Cursor c=db.query("memories",new String[]{"ts","category","memory_key","value","importance","source"},null,null,null,null,"ts ASC","4000");try{while(c.moveToNext()){a.put(new JSONObject().put("ts",c.getLong(0)).put("category",c.getString(1)).put("memory_key",c.getString(2)).put("value",PrivateStore.decrypt(c.getString(3))).put("importance",c.getInt(4)).put("source",c.getString(5)));}}finally{c.close();}return a;}
    private JSONArray exportLedger(SQLiteDatabase db)throws Exception{JSONArray a=new JSONArray();Cursor c=db.query("ledger",new String[]{"ts","kind","summary","detail","transaction_id"},null,null,null,null,"ts ASC","5000");try{while(c.moveToNext()){a.put(new JSONObject().put("ts",c.getLong(0)).put("kind",c.getString(1)).put("summary",PrivateStore.decrypt(c.getString(2))).put("detail",PrivateStore.decrypt(c.getString(3))).put("transaction_id",c.getString(4)));}}finally{c.close();}return a;}

    private long count(SQLiteDatabase db,String table){ Cursor c=db.rawQuery("SELECT COUNT(*) FROM "+table,null);try{return c.moveToFirst()?c.getLong(0):0;}finally{c.close();} }
    private String meta(SQLiteDatabase db,String key){ Cursor c=db.query("meta",new String[]{"meta_value"},"meta_key=?",new String[]{key},null,null,null,"1");try{return c.moveToFirst()?c.getString(0):"";}finally{c.close();} }
    private void setMeta(SQLiteDatabase db,String key,String value){ ContentValues v=new ContentValues();v.put("meta_key",key);v.put("meta_value",value);db.insertWithOnConflict("meta",null,v,SQLiteDatabase.CONFLICT_REPLACE); }
    private void pruneEvents(SQLiteDatabase db){ db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY ts DESC LIMIT 12000)"); }
    private String readAsset(String name)throws Exception{ try(InputStream in=app.getAssets().open(name);BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder s=new StringBuilder();String line;while((line=r.readLine())!=null){s.append(line).append('\n');}return s.toString();} }
    private static ArrayList<String> queryTerms(String q){ Set<String> set=new LinkedHashSet<>();if(q!=null)for(String w:q.toLowerCase(Locale.US).replaceAll("[^a-z0-9_ -]"," ").split("\\s+")){if(w.length()>=4&&!STOP.contains(w))set.add(w);}return new ArrayList<>(set); }
    private static final Set<String> STOP=new java.util.HashSet<>(java.util.Arrays.asList("this","that","with","from","have","what","when","where","which","your","about","could","would","there","they","them","then","than","into","just","like","want","need","lumi","remember"));
    private static boolean looksSecret(String s){ if(s==null)return false;String l=s.toLowerCase(Locale.US);return l.contains("api_key")||l.contains("bearer ")||l.contains("github_pat_")||l.contains("password=")||l.contains("private key-----"); }
    private static String redactSecrets(String s){if(s==null)return "";return s.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]{12,}","Bearer [REDACTED]").replaceAll("(?i)(api[_ -]?key|token|password)\\s*[:=]\\s*[^\\s,;]{8,}","$1=[REDACTED]").replaceAll("github_pat_[A-Za-z0-9_]{10,}","[REDACTED_GITHUB_TOKEN]");}
    private static String safe(String s,String fallback){return s==null||s.trim().isEmpty()?fallback:s.trim();}
    private static int clamp(int v,int min,int max){return Math.max(min,Math.min(max,v));}
    private static String bounded(String s,int max){if(s==null)return "";return s.length()<=max?s:s.substring(0,max);}
    private static void appendBounded(StringBuilder out,String s,int max){if(out.length()>=max)return;int room=max-out.length();out.append(s.length()<=room?s:s.substring(0,room));}
}
