package com.distressedelk.lumi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Signed Lumi Update Package installer.
 *
 * A Lumi update package is a ZIP containing:
 *   lumi-update.json  - UTF-8 manifest
 *   lumi-update.sig   - optional Base64 SHA256withRSA signature of the manifest
 *   payload/...       - files referenced by the manifest
 *
 * Signed packages are authenticated with the certificate that signed the
 * currently installed Lumi APK. Lumi also supports user-selected local ZIP
 * packages without a manifest signature. Unsigned/local packages remain
 * restricted to the same checksum-verified, whitelisted content targets. A core
 * update may carry an APK, but the APK itself must still match Lumi's package
 * name and signing certificate and must have a newer version code.
 *
 * Content updates are restricted to a small whitelist of preferences and app-
 * private asset directories. Core updates may contain a full APK, but that APK
 * must use the same package name and signing certificate. Android still presents
 * its normal user-confirmed package installer UI for a core APK replacement.
 */
final class LumiUpdateManager {
    static final int FORMAT_VERSION = 1;
    static final String MANIFEST_NAME = "lumi-update.json";
    static final String SIGNATURE_NAME = "lumi-update.sig";
    static final String PROVIDER_AUTHORITY_SUFFIX = ".fileprovider";
    static final long MAX_IMPORT_BYTES = 800L * 1024L * 1024L;
    static final long MIN_FREE_SPACE_BYTES = 150L * 1024L * 1024L;

    interface Listener {
        void onProgress(String message);
        void onComplete(Result result);
        void onError(String message);
    }

    static final class Result {
        final String updateId;
        final String name;
        final String version;
        final String type;
        final String releaseNotes;
        final boolean coreInstallReady;

        Result(String updateId, String name, String version, String type, String releaseNotes, boolean coreInstallReady) {
            this.updateId = updateId;
            this.name = name;
            this.version = version;
            this.type = type;
            this.releaseNotes = releaseNotes;
            this.coreInstallReady = coreInstallReady;
        }
    }

    private static final Set<String> ALLOWED_PREFS = new HashSet<>(Arrays.asList(
            "reply_style",
            "speed_priority",
            "human_cues",
            "human_cue_rate",
            "proactivity",
            "profile",
            "private_tone",
            "developer_avatar_mobius",
            "fast_context_chars",
            "fast_max_tokens",
            "fast_threads_cap",
            "followup_linger_ms",
            "quick_ack_delay_ms",
            "lumi_local_prompt_overlay",
            "lumi_cloud_prompt_overlay",
            "direct_identity_reply",
            "direct_purpose_reply",
            "direct_capabilities_reply",
            "pending_conversation_note",
            "update_system_test_marker",
            "runtime_profile",
            "runtime_module_epoch",
            "ui_schema_version",
            "skill_schema_version"
    ));

    private LumiUpdateManager() {}

    static void importPackage(Activity activity, SharedPreferences prefs, Uri sourceUri, Listener listener) {
        new Thread(() -> {
            File imported = null;
            try {
                post(activity, listener, "Reading Lumi update package…");
                File importDir = new File(activity.getCacheDir(), "lumi_update_import");
                if (!importDir.exists() && !importDir.mkdirs()) throw new IOException("Could not create update staging folder");
                imported = new File(importDir, "incoming-" + System.currentTimeMillis() + ".zip");
                try (InputStream in = new BufferedInputStream(activity.getContentResolver().openInputStream(sourceUri));
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(imported))) {
                    if (in == null) throw new IOException("Could not open the selected update file");
                    copyLimited(in, out, MAX_IMPORT_BYTES);
                }

                long usable = activity.getFilesDir().getUsableSpace();
                if (usable < imported.length() + MIN_FREE_SPACE_BYTES) throw new IOException("Not enough free storage to stage this Lumi update safely");
                Result result = verifyAndApply(activity, prefs, imported, listener, false);
                final Result done = result;
                activity.runOnUiThread(() -> listener.onComplete(done));
            } catch (Exception e) {
                final String message = cleanError(e);
                activity.runOnUiThread(() -> listener.onError(message));
            } finally {
                if (imported != null) imported.delete();
            }
        }, "LumiUpdateImport").start();
    }


    /**
     * Blocking path used only by Lumi's native maintenance tool host. Unlike the manual picker,
     * this path REQUIRES a Lumi-trusted manifest signature. Call from a background thread.
     */
    static Result applyTrustedPackageBlocking(Activity activity, SharedPreferences prefs, File packageZip, Listener listener) throws Exception {
        if (packageZip == null || !packageZip.isFile()) throw new FileNotFoundException("Signed Lumi update package is missing");
        if (packageZip.length() <= 0 || packageZip.length() > MAX_IMPORT_BYTES) throw new SecurityException("Signed Lumi update package size is invalid");
        long usable = activity.getFilesDir().getUsableSpace();
        if (usable < packageZip.length() + MIN_FREE_SPACE_BYTES) throw new IOException("Not enough free storage to stage this Lumi update safely");
        return verifyAndApply(activity, prefs, packageZip, listener, true);
    }

    private static Result verifyAndApply(Activity activity, SharedPreferences prefs, File packageZip, Listener listener, boolean requireManifestSignature) throws Exception {
        try (ZipFile zip = new ZipFile(packageZip)) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST_NAME);
            ZipEntry sigEntry = zip.getEntry(SIGNATURE_NAME);
            if (manifestEntry == null) {
                throw new SecurityException("This ZIP is not a Lumi update package: lumi-update.json is missing");
            }

            byte[] manifestBytes = readEntry(zip, manifestEntry, 2 * 1024 * 1024);
            if (sigEntry != null) {
                String signatureB64 = new String(readEntry(zip, sigEntry, 64 * 1024), StandardCharsets.UTF_8).trim();
                post(activity, listener, "Verifying signed Lumi update…");
                verifyManifestSignature(activity, manifestBytes, signatureB64);
            } else if (requireManifestSignature) {
                throw new SecurityException("Remote/AI maintenance packages must include lumi-update.sig");
            } else {
                post(activity, listener, "Using user-selected local update ZIP…");
            }

            JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
            validateManifestEnvelope(activity, manifest);

            String updateId = requiredSafeId(manifest, "updateId");
            String name = manifest.optString("name", "Lumi update").trim();
            String version = manifest.optString("version", updateId).trim();
            String type = manifest.optString("type", "content").trim().toLowerCase(Locale.US);
            String notes = manifest.optString("releaseNotes", "").trim();
            if (!"content".equals(type) && !"core".equals(type)) throw new SecurityException("Unsupported Lumi update type: " + type);

            File staging = new File(activity.getFilesDir(), "lumi_updates/staging/" + updateId);
            deleteRecursive(staging);
            if (!staging.mkdirs()) throw new IOException("Could not create update staging area");

            try {
                verifyDeclaredPayloads(zip, manifest, staging, listener, activity);
                if ("core".equals(type)) {
                    post(activity, listener, "Verifying signed core APK…");
                    stageCoreApk(activity, prefs, manifest, staging, updateId, name, version, notes);
                    recordSuccess(prefs, updateId, name, version, type, notes, true);
                    return new Result(updateId, name, version, type, notes, true);
                }

                post(activity, listener, "Applying Lumi content update…");
                applyContentTransaction(activity, prefs, manifest, staging, updateId);
                recordSuccess(prefs, updateId, name, version, type, notes, false);
                return new Result(updateId, name, version, type, notes, false);
            } finally {
                deleteRecursive(staging);
            }
        }
    }

    private static void validateManifestEnvelope(Activity activity, JSONObject manifest) throws Exception {
        int format = manifest.optInt("formatVersion", -1);
        if (format != FORMAT_VERSION) throw new SecurityException("Unsupported Lumi update format " + format);

        long installed = currentVersionCode(activity);
        long min = manifest.optLong("minAppVersionCode", 0L);
        long max = manifest.optLong("maxAppVersionCode", Long.MAX_VALUE);
        if (installed < min) throw new SecurityException("This update needs a newer Lumi core first");
        if (installed > max) throw new SecurityException("This update is for an older Lumi core");
    }

    private static void verifyDeclaredPayloads(ZipFile zip, JSONObject manifest, File staging, Listener listener, Activity activity) throws Exception {
        JSONArray files = manifest.optJSONArray("files");
        if (files == null) return;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            String path = requireSafeZipPath(f.getString("path"));
            String expected = f.getString("sha256").trim().toLowerCase(Locale.US);
            if (!expected.matches("[0-9a-f]{64}")) throw new SecurityException("Invalid checksum for " + path);
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.isDirectory()) throw new IOException("Update payload missing: " + path);
            if (entry.getSize() > MAX_IMPORT_BYTES) throw new SecurityException("Update payload is too large: " + path);
            post(activity, listener, "Checking " + new File(path).getName() + "…");
            File out = new File(staging, "payload/" + i + ".bin");
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                 OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                copyLimited(in, os, MAX_IMPORT_BYTES);
            }
            String actual = sha256(out);
            if (!expected.equals(actual)) throw new SecurityException("Checksum mismatch for " + path);
        }
    }

    private static void applyContentTransaction(Activity activity, SharedPreferences prefs, JSONObject manifest, File staging, String updateId) throws Exception {
        File rollback = new File(activity.getFilesDir(), "lumi_updates/rollback/" + updateId);
        deleteRecursive(rollback);
        if (!rollback.mkdirs()) throw new IOException("Could not create rollback point");

        JSONObject prefBackup = new JSONObject();
        List<FilePair> appliedFiles = new ArrayList<>();
        JSONArray rollbackFiles = new JSONArray();
        try {
            JSONObject preferences = manifest.optJSONObject("preferences");
            if (preferences != null) {
                Iterator<String> keys = preferences.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!ALLOWED_PREFS.contains(key)) throw new SecurityException("Update attempted to change protected setting: " + key);
                    Object old = prefs.getAll().get(key);
                    JSONObject record = new JSONObject();
                    record.put("present", prefs.contains(key));
                    if (old != null) record.put("value", old);
                    prefBackup.put(key, record);
                }
                writeUtf8(new File(rollback, "preferences.json"), prefBackup.toString(2));
            }

            JSONArray files = manifest.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    String target = f.optString("target", "").trim();
                    if (target.isEmpty()) continue;
                    File destination = resolveWhitelistedTarget(activity, target);
                    File staged = new File(staging, "payload/" + i + ".bin");
                    if (!staged.exists()) throw new IOException("Staged update payload vanished");
                    File backup = new File(rollback, "file-" + i + ".bak");
                    boolean existed = destination.exists();
                    if (existed) copyFile(destination, backup);
                    appliedFiles.add(new FilePair(destination, backup, existed));
                    JSONObject rb = new JSONObject();
                    rb.put("target", destination.getAbsolutePath());
                    rb.put("backup", backup.getAbsolutePath());
                    rb.put("existed", existed);
                    rollbackFiles.put(rb);
                    atomicCopy(staged, destination);
                }
            }

            if (preferences != null) {
                SharedPreferences.Editor editor = prefs.edit();
                Iterator<String> keys = preferences.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    putJsonPreference(editor, key, preferences.get(key));
                }
                if (!editor.commit()) throw new IOException("Could not commit Lumi update settings");
            }

            // Phase 2: publish the runtime module set only after every file and preference
            // has committed successfully. A malformed declared module aborts this transaction.
            JSONObject moduleRegistry = RuntimeModuleRegistry.activate(activity, prefs, manifest, updateId);

            JSONObject tx = new JSONObject();
            tx.put("updateId", updateId);
            tx.put("files", rollbackFiles);
            tx.put("moduleRegistry", moduleRegistry);
            writeUtf8(new File(rollback, "transaction.json"), tx.toString(2));
            prefs.edit().putString("last_lumi_update_rollback", rollback.getAbsolutePath()).apply();
        } catch (Exception e) {
            restoreFiles(appliedFiles);
            restorePreferences(prefs, prefBackup);
            throw e;
        }
    }

    private static void stageCoreApk(Activity activity, SharedPreferences prefs, JSONObject manifest, File staging,
                                     String updateId, String name, String version, String notes) throws Exception {
        JSONObject apk = manifest.optJSONObject("apk");
        if (apk == null) throw new SecurityException("Core update has no APK descriptor");
        String apkPath = requireSafeZipPath(apk.getString("path"));
        JSONArray files = manifest.optJSONArray("files");
        int fileIndex = -1;
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                if (apkPath.equals(files.getJSONObject(i).optString("path"))) { fileIndex = i; break; }
            }
        }
        if (fileIndex < 0) throw new SecurityException("Core APK is not declared in signed payload list");
        File stagedApk = new File(staging, "payload/" + fileIndex + ".bin");
        long incomingVersionCode = verifyApkIdentity(activity, stagedApk);

        File pendingDir = new File(activity.getFilesDir(), "lumi_updates/pending_core");
        if (!pendingDir.exists() && !pendingDir.mkdirs()) throw new IOException("Could not create core update staging folder");
        File pending = new File(pendingDir, "lumi-core-update.apk");
        atomicCopy(stagedApk, pending);
        prefs.edit()
                .putString("pending_core_apk", pending.getAbsolutePath())
                .putString("pending_core_update_id", updateId)
                .putString("pending_core_update_name", name)
                .putString("pending_core_update_version", version)
                .putString("pending_core_update_notes", notes)
                .putLong("pending_core_version_code", incomingVersionCode)
                .apply();
    }

    static File pendingCoreApkFile(SharedPreferences prefs) {
        String path = prefs.getString("pending_core_apk", "");
        return path.isEmpty() ? null : new File(path);
    }

    static boolean hasPendingCoreUpdate(Context context, SharedPreferences prefs) {
        String path = prefs.getString("pending_core_apk", "");
        if (path.isEmpty()) return false;
        File file = new File(path);
        if (!file.exists()) {
            clearPendingCorePrefs(prefs);
            return false;
        }
        try {
            long pending = prefs.getLong("pending_core_version_code", Long.MAX_VALUE);
            if (pending <= currentVersionCode(context)) {
                file.delete();
                clearPendingCorePrefs(prefs);
                return false;
            }
        } catch (Exception ignored) {}
        return true;
    }

    private static void clearPendingCorePrefs(SharedPreferences prefs) {
        prefs.edit()
                .remove("pending_core_apk")
                .remove("pending_core_update_id")
                .remove("pending_core_update_name")
                .remove("pending_core_update_version")
                .remove("pending_core_update_notes")
                .remove("pending_core_version_code")
                .apply();
    }

    static String pendingCoreLabel(SharedPreferences prefs) {
        String name = prefs.getString("pending_core_update_name", "Lumi core update");
        String version = prefs.getString("pending_core_update_version", "");
        return version.isEmpty() ? name : name + " • " + version;
    }

    static boolean launchPendingCoreInstaller(Activity activity, SharedPreferences prefs) throws Exception {
        String path = prefs.getString("pending_core_apk", "");
        if (path.isEmpty()) throw new FileNotFoundException("No verified core update is waiting");
        File apk = new File(path);
        if (!apk.exists()) throw new FileNotFoundException("The verified core update file is missing");
        verifyApkIdentity(activity, apk);

        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + PROVIDER_AUTHORITY_SUFFIX, apk);
        activity.grantUriPermission("com.distressedelk.lumi.guardian", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Bundle extras = new Bundle();
        extras.putString("uri", uri.toString());
        extras.putString("sha256", sha256Hex(apk));
        extras.putString("requested_change", pendingCoreLabel(prefs));
        Bundle result = GuardianControlClient.call(activity, "install_core_from_uri", extras);
        if (!result.getBoolean("ok", false)) throw new SecurityException("Guardian rejected core update: " + result.getString("error", result.getString("state", "unknown error")));
        prefs.edit().putInt("pending_core_guardian_session", result.getInt("session_id", -1)).putString("pending_core_guardian_state", result.getString("state", "")).apply();
        return true;
    }

    private static String sha256Hex(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) { byte[] b=new byte[1024*1024]; int n; while((n=in.read(b))>0) md.update(b,0,n); }
        StringBuilder out=new StringBuilder(); for(byte b:md.digest()) out.append(String.format(Locale.US,"%02x",b)); return out.toString();
    }

    private static long verifyApkIdentity(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null) throw new SecurityException("Android could not read the core update APK");
        if (!context.getPackageName().equals(archive.packageName)) throw new SecurityException("Core update package name does not match Lumi");
        long incoming = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        long current = currentVersionCode(context);
        if (incoming <= current) throw new SecurityException("Core update is not newer than installed Lumi");

        byte[] currentCert = signingCertificateBytes(context.getPackageManager().getPackageInfo(context.getPackageName(), flags));
        byte[] incomingCert = signingCertificateBytes(archive);
        if (!MessageDigest.isEqual(sha256Bytes(currentCert), sha256Bytes(incomingCert))) {
            throw new SecurityException("Core update signing certificate does not match Lumi");
        }
        return incoming;
    }

    private static void verifyManifestSignature(Context context, byte[] manifestBytes, String signatureB64) throws Exception {
        byte[] sigBytes;
        try { sigBytes = android.util.Base64.decode(signatureB64, android.util.Base64.DEFAULT); }
        catch (Exception e) { throw new SecurityException("Update signature is not valid Base64"); }

        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), flags);
        byte[] certBytes = signingCertificateBytes(info);
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certBytes));
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(cert.getPublicKey());
        verifier.update(manifestBytes);
        if (!verifier.verify(sigBytes)) throw new SecurityException("Lumi update signature check failed");
    }

    private static byte[] signingCertificateBytes(PackageInfo info) throws Exception {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            android.content.pm.Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures != null && signatures.length > 0) return signatures[0].toByteArray();
        }
        if (info.signatures != null && info.signatures.length > 0) return info.signatures[0].toByteArray();
        throw new SecurityException("Lumi signing certificate is unavailable");
    }

    private static long currentVersionCode(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static File resolveWhitelistedTarget(Context context, String target) throws Exception {
        String normalized = target.replace('\\', '/').trim();
        if (normalized.startsWith("avatar/")) {
            String mode = normalized.substring("avatar/".length()).toLowerCase(Locale.US);
            if (!Arrays.asList("home", "public", "work", "travel", "lockdown", "private", "mobius", "preview").contains(mode)) {
                throw new SecurityException("Unsupported avatar target: " + target);
            }
            File dir = new File(context.getFilesDir(), "lumi_updates/avatar");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, mode + ".img");
        }
        if (normalized.startsWith("asset/")) {
            String relative = normalized.substring("asset/".length());
            requireSafeRelativePath(relative);
            File base = new File(context.getFilesDir(), "lumi_updates/assets");
            File out = new File(base, relative);
            ensureInside(base, out);
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            return out;
        }
        if (normalized.startsWith("config/")) {
            String relative = normalized.substring("config/".length());
            requireSafeRelativePath(relative);
            File base = new File(context.getFilesDir(), "lumi_updates/config");
            File out = new File(base, relative);
            ensureInside(base, out);
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            return out;
        }
        String[] moduleRoots = {"skills", "prompts", "ui", "voice", "home", "models", "migrations", "scripts"};
        for (String root : moduleRoots) {
            String prefix = root + "/";
            if (normalized.startsWith(prefix)) {
                String relative = normalized.substring(prefix.length());
                requireSafeRelativePath(relative);
                File base = new File(context.getFilesDir(), "lumi_updates/modules/" + root);
                File out = new File(base, relative);
                ensureInside(base, out);
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                return out;
            }
        }
        throw new SecurityException("Update file target is not allowed: " + target);
    }

    private static void ensureInside(File base, File child) throws Exception {
        String b = base.getCanonicalPath() + File.separator;
        String c = child.getCanonicalPath();
        if (!c.startsWith(b)) throw new SecurityException("Unsafe update path");
    }

    private static String requireSafeRelativePath(String value) {
        String v = value.replace('\\', '/').trim();
        if (v.isEmpty() || v.startsWith("/") || v.contains("../") || v.contains("..\\") || v.equals("..") || v.contains(":")) {
            throw new SecurityException("Unsafe update path");
        }
        return v;
    }

    private static String requireSafeZipPath(String value) {
        String v = requireSafeRelativePath(value);
        if (!v.startsWith("payload/")) throw new SecurityException("Update payload must live under payload/");
        return v;
    }

    private static String requiredSafeId(JSONObject manifest, String key) throws Exception {
        String value = manifest.getString(key).trim();
        if (!value.matches("[A-Za-z0-9._-]{1,96}")) throw new SecurityException("Invalid Lumi update id");
        return value;
    }

    private static void putJsonPreference(SharedPreferences.Editor editor, String key, Object value) throws Exception {
        if (!ALLOWED_PREFS.contains(key)) throw new SecurityException("Protected setting: " + key);
        if (value == JSONObject.NULL) { editor.remove(key); return; }

        if ("followup_linger_ms".equals(key) || "quick_ack_delay_ms".equals(key)) {
            if (!(value instanceof Number)) throw new SecurityException("Expected numeric setting: " + key);
            editor.putLong(key, ((Number) value).longValue());
            return;
        }
        if ("human_cue_rate".equals(key) || "fast_context_chars".equals(key)
                || "fast_max_tokens".equals(key) || "fast_threads_cap".equals(key)) {
            if (!(value instanceof Number)) throw new SecurityException("Expected numeric setting: " + key);
            editor.putInt(key, ((Number) value).intValue());
            return;
        }
        if ("speed_priority".equals(key) || "human_cues".equals(key) || "developer_avatar_mobius".equals(key)) {
            if (!(value instanceof Boolean)) throw new SecurityException("Expected true/false setting: " + key);
            editor.putBoolean(key, (Boolean) value);
            return;
        }

        if (value instanceof String) {
            String s = (String) value;
            if (s.length() > 32768) throw new SecurityException("Update setting is too large: " + key);
            editor.putString(key, s);
        } else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Number) editor.putLong(key, ((Number) value).longValue());
        else throw new SecurityException("Unsupported setting type for " + key);
    }

    private static void restorePreferences(SharedPreferences prefs, JSONObject backup) {
        try {
            SharedPreferences.Editor editor = prefs.edit();
            Iterator<String> keys = backup.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject record = backup.getJSONObject(key);
                if (!record.optBoolean("present", false)) { editor.remove(key); continue; }
                putJsonPreference(editor, key, record.opt("value"));
            }
            editor.commit();
        } catch (Exception ignored) {}
    }

    private static void restoreFiles(List<FilePair> files) {
        for (int i = files.size() - 1; i >= 0; i--) {
            FilePair pair = files.get(i);
            try {
                if (pair.existed && pair.backup.exists()) atomicCopy(pair.backup, pair.target);
                else if (!pair.existed) pair.target.delete();
            } catch (Exception ignored) {}
        }
    }


    static boolean hasRollbackPoint(SharedPreferences prefs) {
        String path = prefs.getString("last_lumi_update_rollback", "");
        return !path.isEmpty() && new File(path, "transaction.json").exists();
    }

    static String rollbackLastContentUpdate(Context context, SharedPreferences prefs) throws Exception {
        String path = prefs.getString("last_lumi_update_rollback", "");
        if (path.isEmpty()) throw new FileNotFoundException("No Lumi rollback point is available");
        File rollback = new File(path);
        File txFile = new File(rollback, "transaction.json");
        if (!txFile.exists()) throw new FileNotFoundException("Rollback metadata is missing");
        JSONObject tx = new JSONObject(new String(java.nio.file.Files.readAllBytes(txFile.toPath()), StandardCharsets.UTF_8));
        JSONArray files = tx.optJSONArray("files");
        if (files != null) {
            for (int i = files.length() - 1; i >= 0; i--) {
                JSONObject item = files.getJSONObject(i);
                File target = new File(item.getString("target"));
                // Never restore outside Lumi's private files directory.
                ensureInside(context.getFilesDir(), target);
                File backup = new File(item.getString("backup"));
                boolean existed = item.optBoolean("existed", false);
                if (existed) {
                    if (!backup.exists()) throw new FileNotFoundException("Rollback backup is missing for " + target.getName());
                    atomicCopy(backup, target);
                } else if (target.exists() && !target.delete()) {
                    throw new IOException("Could not remove updated file " + target.getName());
                }
            }
        }
        File prefFile = new File(rollback, "preferences.json");
        if (prefFile.exists()) {
            JSONObject backupPrefs = new JSONObject(new String(java.nio.file.Files.readAllBytes(prefFile.toPath()), StandardCharsets.UTF_8));
            restorePreferences(prefs, backupPrefs);
        }
        String updateId = tx.optString("updateId", "previous update");
        prefs.edit()
                .putString("last_lumi_rollback_id", updateId)
                .putLong("last_lumi_rollback_at", System.currentTimeMillis())
                .remove("last_lumi_update_rollback")
                .apply();
        deleteRecursive(rollback);
        return updateId;
    }

    private static void recordSuccess(SharedPreferences prefs, String updateId, String name, String version,
                                      String type, String notes, boolean corePending) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putString("last_lumi_update_id", updateId)
                .putString("last_lumi_update_name", name)
                .putString("last_lumi_update_version", version)
                .putString("last_lumi_update_type", type)
                .putString("last_lumi_update_notes", notes)
                .putLong("last_lumi_update_at", now)
                .putBoolean("last_lumi_update_core_pending", corePending)
                .apply();
    }

    private static void atomicCopy(File source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create update target folder");
        File tmp = new File(destination.getAbsolutePath() + ".new");
        copyFile(source, tmp);
        if (destination.exists() && !destination.delete()) throw new IOException("Could not replace old update asset");
        if (!tmp.renameTo(destination)) {
            copyFile(tmp, destination);
            tmp.delete();
        }
    }

    private static void copyFile(File source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            copy(in, out);
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int maxBytes) throws Exception {
        if (entry.getSize() > maxBytes) throw new SecurityException("Update metadata is too large");
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > maxBytes) throw new SecurityException("Update metadata is too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) md.update(buffer, 0, n);
        }
        return hex(md.digest());
    }

    private static byte[] sha256Bytes(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static void copyLimited(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n == 0) continue;
            total += n;
            if (total > maxBytes) throw new IOException("Update package exceeds allowed size");
            out.write(buffer, 0, n);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        out.flush();
    }

    private static void writeUtf8(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(text);
        }
    }

    private static String cleanError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void post(Activity activity, Listener listener, String message) {
        activity.runOnUiThread(() -> listener.onProgress(message));
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    private static final class FilePair {
        final File target;
        final File backup;
        final boolean existed;
        FilePair(File target, File backup, boolean existed) {
            this.target = target;
            this.backup = backup;
            this.existed = existed;
        }
    }
}
