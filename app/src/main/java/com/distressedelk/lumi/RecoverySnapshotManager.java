package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RecoverySnapshotManager {
    private static final int FORMAT = 2;
    private static final int KEEP = 6;
    private static final Set<String> SECRET_KEYS = new HashSet<>();
    static {
        Collections.addAll(SECRET_KEYS,
                "openai_api_key", "opensource_api_key",
                "secure_openai_api_key", "secure_opensource_api_key");
    }

    private RecoverySnapshotManager() {}

    static JSONObject create(Context context, SharedPreferences prefs, String reason) throws Exception {
        File root = new File(context.getFilesDir(), "lumi_recovery/checkpoints");
        if (!root.exists() && !root.mkdirs()) throw new Exception("Could not create recovery checkpoint directory.");
        long now = System.currentTimeMillis();
        File dir = new File(root, String.valueOf(now));
        if (!dir.mkdirs()) throw new Exception("Could not create recovery checkpoint.");

        JSONObject snapshot = new JSONObject();
        snapshot.put("format", "LumiRecoverySnapshot");
        snapshot.put("formatVersion", FORMAT);
        snapshot.put("created", now);
        snapshot.put("reason", reason == null ? "guardian" : reason);
        snapshot.put("package", context.getPackageName());
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        snapshot.put("versionCode", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        snapshot.put("versionName", info.versionName == null ? "" : info.versionName);

        JSONObject data = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (isSecret(key) || isTransient(key)) continue;
            Object value = entry.getValue();
            JSONObject item = new JSONObject();
            if (value instanceof String) { item.put("type", "string"); item.put("value", value); }
            else if (value instanceof Boolean) { item.put("type", "boolean"); item.put("value", value); }
            else if (value instanceof Integer) { item.put("type", "int"); item.put("value", value); }
            else if (value instanceof Long) { item.put("type", "long"); item.put("value", value); }
            else if (value instanceof Float) { item.put("type", "float"); item.put("value", value); }
            else if (value instanceof Set) {
                item.put("type", "stringSet"); JSONArray a = new JSONArray();
                for (Object v : (Set<?>) value) if (v != null) a.put(String.valueOf(v));
                item.put("value", a);
            } else continue;
            data.put(key, item);
        }
        snapshot.put("preferences", data);
        // Memory Vault checkpoint is encrypted with Lumi's Android-Keystore key even inside app-private recovery storage.
        JSONObject vaultExport = LumiMemoryVault.get(context).exportJson();
        snapshot.put("memoryVaultEncrypted", PrivateStore.encrypt(vaultExport.toString()));

        File json = new File(dir, "state.json");
        byte[] bytes = snapshot.toString(2).getBytes(StandardCharsets.UTF_8);
        writeSync(json, bytes);
        writeSync(new File(dir, "state.sha256"), sha256(bytes).getBytes(StandardCharsets.US_ASCII));

        prefs.edit().putString("last_recovery_checkpoint", dir.getAbsolutePath()).putLong("last_recovery_checkpoint_at", now).apply();
        prune(root);
        return snapshot;
    }

    static String latestPath(SharedPreferences prefs) { return prefs.getString("last_recovery_checkpoint", ""); }

    static boolean restoreLatest(Context context, SharedPreferences prefs) throws Exception {
        String path = latestPath(prefs);
        if (path == null || path.isEmpty()) throw new Exception("No recovery checkpoint is available.");
        File dir = new File(path);
        File json = new File(dir, "state.json");
        File hash = new File(dir, "state.sha256");
        if (!json.isFile() || !hash.isFile()) throw new Exception("Recovery checkpoint is incomplete.");
        byte[] bytes = java.nio.file.Files.readAllBytes(json.toPath());
        String expected = new String(java.nio.file.Files.readAllBytes(hash.toPath()), StandardCharsets.US_ASCII).trim().toLowerCase(Locale.US);
        String actual = sha256(bytes);
        if (!expected.equals(actual)) throw new SecurityException("Recovery checkpoint integrity check failed.");
        JSONObject snapshot = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"LumiRecoverySnapshot".equals(snapshot.optString("format"))) throw new SecurityException("Recovery checkpoint format is invalid.");
        JSONObject data = snapshot.getJSONObject("preferences");
        SharedPreferences.Editor editor = prefs.edit();
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isSecret(key) || isTransient(key)) continue;
            JSONObject item = data.getJSONObject(key);
            String type = item.optString("type");
            switch (type) {
                case "string": editor.putString(key, item.optString("value", "")); break;
                case "boolean": editor.putBoolean(key, item.optBoolean("value", false)); break;
                case "int": editor.putInt(key, item.optInt("value", 0)); break;
                case "long": editor.putLong(key, item.optLong("value", 0L)); break;
                case "float": editor.putFloat(key, (float)item.optDouble("value", 0.0)); break;
                case "stringSet":
                    JSONArray a = item.optJSONArray("value"); Set<String> set = new HashSet<>();
                    if (a != null) for (int i=0;i<a.length();i++) set.add(a.optString(i));
                    editor.putStringSet(key, set); break;
            }
        }
        if (!editor.commit()) throw new Exception("Could not restore Lumi checkpoint preferences.");
        String encryptedVault = snapshot.optString("memoryVaultEncrypted", "");
        if (!encryptedVault.isEmpty()) {
            String plainVault = PrivateStore.decrypt(encryptedVault);
            if (plainVault.isEmpty()) throw new SecurityException("Recovery Memory Vault could not be decrypted.");
            LumiMemoryVault.get(context).importJson(new JSONObject(plainVault));
        }
        prefs.edit().putLong("last_recovery_restore_at", System.currentTimeMillis()).apply();
        LumiMemoryVault.get(context).ledger("recovery","Guardian recovery checkpoint restored","Preferences and Memory Vault restored from protected checkpoint.","");
        return true;
    }

    private static boolean isSecret(String key) {
        if (key == null) return false;
        String l = key.toLowerCase(Locale.US);
        return SECRET_KEYS.contains(key) || l.contains("api_key") || l.contains("token") || l.contains("password") || l.startsWith("secure_");
    }

    private static boolean isTransient(String key) {
        if (key == null) return false;
        return key.startsWith("pending_core_") || key.equals("pending_core_apk") || key.equals("last_lumi_update_rollback") || key.startsWith("bootstrap_last_");
    }

    private static void prune(File root) {
        File[] dirs = root.listFiles(File::isDirectory); if (dirs == null || dirs.length <= KEEP) return;
        ArrayList<File> list = new ArrayList<>(); Collections.addAll(list, dirs);
        list.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int i=KEEP;i<list.size();i++) deleteRecursive(list.get(i));
    }

    private static void writeSync(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder s = new StringBuilder(); for (byte b : digest) s.append(String.format(Locale.US, "%02x", b)); return s.toString();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return; File[] kids=f.listFiles(); if(kids!=null) for(File k:kids) deleteRecursive(k); f.delete();
    }
}
