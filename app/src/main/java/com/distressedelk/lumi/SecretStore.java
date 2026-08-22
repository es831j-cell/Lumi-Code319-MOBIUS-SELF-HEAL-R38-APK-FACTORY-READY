package com.distressedelk.lumi;

import android.content.SharedPreferences;

final class SecretStore {
    private static final String PREFIX = "secure_";
    private SecretStore() {}

    static void migratePrototypeSecrets(SharedPreferences prefs) {
        migrateOne(prefs, "openai_api_key");
        migrateOne(prefs, "opensource_api_key");
        migrateLegacyProviderSecrets(prefs);
    }

    /**
     * Code251 compatibility sweep. Older Lumi experiments used a few different preference
     * labels for the same user-supplied credential. Recover them locally without ever logging
     * or exporting the secret, then remove the plaintext legacy entry.
     */
    private static void migrateLegacyProviderSecrets(SharedPreferences prefs) {
        String[] openAiLegacy = {"openai_key", "chatgpt_api_key", "openai_token"};
        for (String old : openAiLegacy) {
            String value = prefs.getString(old, "");
            if (value != null && !value.trim().isEmpty() && get(prefs, "openai_api_key").trim().isEmpty()) {
                put(prefs, "openai_api_key", value.trim());
                prefs.edit().remove(old).apply();
            }
        }
        String[] remoteLegacy = {"remote_ai_key", "ollama_api_key", "booster_api_key"};
        for (String old : remoteLegacy) {
            String value = prefs.getString(old, "");
            if (value != null && !value.trim().isEmpty() && get(prefs, "opensource_api_key").trim().isEmpty()) {
                put(prefs, "opensource_api_key", value.trim());
                prefs.edit().remove(old).apply();
            }
        }
        String[] urlLegacy = {"remote_ai_url", "booster_url", "ollama_url"};
        if (prefs.getString("opensource_url", "").trim().isEmpty()) {
            for (String old : urlLegacy) {
                String value = prefs.getString(old, "");
                if (value != null && !value.trim().isEmpty()) {
                    prefs.edit().putString("opensource_url", value.trim()).remove(old).apply();
                    break;
                }
            }
        }
    }

    private static void migrateOne(SharedPreferences prefs, String key) {
        String plain = prefs.getString(key, "");
        if (plain != null && !plain.trim().isEmpty()) {
            PrivateStore.write(prefs, PREFIX + key, plain.trim());
            prefs.edit().remove(key).apply();
        }
    }

    static String get(SharedPreferences prefs, String key) { return PrivateStore.read(prefs, PREFIX + key); }
    static void put(SharedPreferences prefs, String key, String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) clear(prefs, key); else PrivateStore.write(prefs, PREFIX + key, v);
        prefs.edit().remove(key).apply();
    }
    static void clear(SharedPreferences prefs, String key) { prefs.edit().remove(PREFIX + key).remove(key).apply(); }
    static void remove(SharedPreferences prefs, String key) { clear(prefs, key); }
}
