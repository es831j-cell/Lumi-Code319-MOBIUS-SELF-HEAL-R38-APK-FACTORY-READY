package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 2 runtime module registry.
 *
 * Signed content packages may declare a `modules` array. Each module points at one of
 * LumiUpdateManager's whitelisted module targets. Activation is atomic at the registry
 * level: payloads are already transactionally copied by LumiUpdateManager, then this
 * class validates every declared module and publishes a new runtime epoch only when
 * the whole set is internally consistent.
 */
final class RuntimeModuleRegistry {
    static final String REGISTRY_RELATIVE = "lumi_updates/modules/active-modules.json";
    private static final Set<String> KINDS = new HashSet<>();
    static {
        String[] kinds = {"skills","prompts","ui","voice","home","models","migrations","scripts"};
        for (String k : kinds) KINDS.add(k);
    }

    private RuntimeModuleRegistry() {}

    static JSONObject activate(Context context, SharedPreferences prefs, JSONObject updateManifest, String updateId) throws Exception {
        JSONArray declared = updateManifest.optJSONArray("modules");
        if (declared == null) return current(context);

        JSONArray active = new JSONArray();
        for (int i = 0; i < declared.length(); i++) {
            JSONObject m = declared.getJSONObject(i);
            String id = safeId(m.getString("id"), "module id");
            String kind = m.getString("kind").trim().toLowerCase(Locale.US);
            if (!KINDS.contains(kind)) throw new SecurityException("Unsupported Lumi module kind: " + kind);
            String version = m.optString("version", updateId).trim();
            String entry = m.getString("entry").replace('\\','/').trim();
            if (!entry.startsWith(kind + "/") || entry.contains("../") || entry.startsWith("/")) {
                throw new SecurityException("Unsafe module entry: " + entry);
            }
            File file = new File(context.getFilesDir(), "lumi_updates/modules/" + entry);
            ensureInside(new File(context.getFilesDir(), "lumi_updates/modules"), file);
            if (!file.isFile() || file.length() <= 0) throw new SecurityException("Module payload is missing: " + id);

            String format = m.optString("format", inferFormat(entry)).toLowerCase(Locale.US);
            if ("json".equals(format)) {
                String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                new JSONObject(raw); // fail closed before publishing a broken runtime module
            }

            JSONObject rec = new JSONObject();
            rec.put("id", id);
            rec.put("kind", kind);
            rec.put("version", version);
            rec.put("entry", entry);
            rec.put("path", file.getAbsolutePath());
            rec.put("required", m.optBoolean("required", true));
            active.put(rec);
        }

        long epoch = Math.max(System.currentTimeMillis(), prefs.getLong("runtime_module_epoch", 0L) + 1L);
        JSONObject registry = new JSONObject();
        registry.put("schemaVersion", 1);
        registry.put("updateId", updateId);
        registry.put("activatedAt", System.currentTimeMillis());
        registry.put("epoch", epoch);
        registry.put("modules", active);
        File target = new File(context.getFilesDir(), REGISTRY_RELATIVE);
        File parent = target.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(parent, target.getName() + ".tmp");
        Files.write(tmp.toPath(), registry.toString(2).getBytes(StandardCharsets.UTF_8));
        if (target.exists() && !target.delete()) throw new java.io.IOException("Could not rotate old module registry");
        if (!tmp.renameTo(target)) throw new java.io.IOException("Could not publish module registry");
        prefs.edit().putLong("runtime_module_epoch", epoch).putString("active_module_update_id", updateId)
                .putInt("active_module_count", active.length()).putLong("active_module_activated_at", System.currentTimeMillis()).commit();
        return registry;
    }

    static JSONObject current(Context context) {
        try {
            File f = new File(context.getFilesDir(), REGISTRY_RELATIVE);
            if (!f.isFile()) return new JSONObject().put("schemaVersion",1).put("modules",new JSONArray());
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            try { return new JSONObject().put("schemaVersion",1).put("error",String.valueOf(t.getMessage())).put("modules",new JSONArray()); }
            catch (Exception ignored) { return new JSONObject(); }
        }
    }

    static File resolve(Context context, String moduleId) {
        try {
            JSONArray a = current(context).optJSONArray("modules");
            if (a == null) return null;
            for (int i=0;i<a.length();i++) {
                JSONObject m=a.optJSONObject(i); if(m==null) continue;
                if(moduleId.equals(m.optString("id"))) {
                    File f=new File(m.optString("path",""));
                    return f.isFile()?f:null;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static String health(Context context, SharedPreferences prefs) {
        try {
            JSONObject r=current(context); JSONArray a=r.optJSONArray("modules");
            int count=a==null?0:a.length();
            if (r.has("error")) return "module registry error: " + r.optString("error");
            for(int i=0;i<count;i++) {
                JSONObject m=a.getJSONObject(i); File f=new File(m.optString("path",""));
                if(m.optBoolean("required",true) && !f.isFile()) return "required module missing: " + m.optString("id");
            }
            long epoch=prefs.getLong("runtime_module_epoch",0L);
            return "ready • " + count + " active modules • epoch " + epoch;
        } catch (Throwable t) { return "module registry check failed"; }
    }

    private static String inferFormat(String entry) { return entry.toLowerCase(Locale.US).endsWith(".json") ? "json" : "raw"; }
    private static String safeId(String v,String what) { String s=v.trim(); if(!s.matches("[A-Za-z0-9._-]{1,96}")) throw new SecurityException("Invalid "+what); return s; }
    private static void ensureInside(File base, File child) throws Exception { String b=base.getCanonicalPath()+File.separator; String c=child.getCanonicalPath(); if(!c.startsWith(b)) throw new SecurityException("Unsafe module path"); }
}
