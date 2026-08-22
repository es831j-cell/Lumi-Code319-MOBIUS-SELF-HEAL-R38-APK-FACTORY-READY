package com.distressedelk.lumi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class GuardianBootstrap {
    static final String PACKAGE = "com.distressedelk.lumi.guardian";
    private static final String TRUSTED_CERT = "0AD00784E2D593A469CB445D2CC1AFE94E31663768F96470A38144E84EFE8AD2";
    private static final long PROMPT_COOLDOWN_MS = 60_000L;
    private static final long REQUIRED_GUARDIAN_VERSION = 12L;

    private GuardianBootstrap() {}

    static boolean isGuardianInstalled(Context context) {
        try { context.getPackageManager().getPackageInfo(PACKAGE, 0); return true; } catch (Exception e) { return false; }
    }

    static boolean guardianSignatureMatches(Context context) {
        try { return TRUSTED_CERT.equals(packageFingerprint(context, PACKAGE)); } catch (Exception e) { return false; }
    }

    static long guardianVersion(Context context) {
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return -1L; }
    }

    static boolean isGuardianCurrent(Context context) { return guardianVersion(context) >= REQUIRED_GUARDIAN_VERSION; }

    static void maybePromptInstall(Activity activity, SharedPreferences prefs) {
        if (activity == null || activity.isFinishing()) return;
        if (isGuardianInstalled(activity)) {
            if (!guardianSignatureMatches(activity)) {
                if (!prefs.getBoolean("guardian_identity_warning_shown", false)) {
                    prefs.edit().putBoolean("guardian_identity_warning_shown", true).apply();
                    new AlertDialog.Builder(activity).setTitle("Guardian identity problem")
                            .setMessage("A Guardian package is installed, but its signing identity does not match Lumi's trusted certificate. Core self-updates are disabled until this is corrected.")
                            .setPositiveButton("OK", null).show();
                }
                return;
            }
            if (!isGuardianCurrent(activity)) {
                long now = System.currentTimeMillis();
                if (now - prefs.getLong("guardian_upgrade_last_prompt_at", 0L) >= PROMPT_COOLDOWN_MS) {
                    prefs.edit().putLong("guardian_upgrade_last_prompt_at", now).apply();
                    new AlertDialog.Builder(activity).setTitle("Update Lumi Guardian")
                            .setMessage("Guardian is trusted but too old for the new Lumi 1.0 maintenance and real Fast Brain certification. Install the embedded Guardian update before running certification.")
                            .setPositiveButton("Update Guardian", (d,w) -> installEmbeddedGuardian(activity, prefs))
                            .setNegativeButton("Later", null).show();
                }
                return;
            }
            if (!prefs.getBoolean("guardian_finish_setup_prompted_v3", false)) {
                prefs.edit().putBoolean("guardian_finish_setup_prompted_v3", true).apply();
                new AlertDialog.Builder(activity).setTitle("Guardian ready")
                        .setMessage("Lumi Guardian is installed, trusted, and current. Open Guardian to run the Lumi 1.0 maintenance and real Fast Brain certification.")
                        .setPositiveButton("Open Guardian", (d,w) -> openGuardian(activity))
                        .setNegativeButton("Later", null).show();
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("guardian_last_prompt_at", 0L) < PROMPT_COOLDOWN_MS) return;
        prefs.edit().putLong("guardian_last_prompt_at", now).apply();
        new AlertDialog.Builder(activity)
                .setTitle("Set up Lumi Guardian")
                .setMessage("This Strong Bootstrap uses a separate Guardian app for core updates and recovery. Android may ask you to approve the companion installation. Until Guardian is installed, Lumi stays in restricted bootstrap mode for core updates.")
                .setPositiveButton("Install Guardian", (d,w) -> installEmbeddedGuardian(activity, prefs))
                .setNegativeButton("Later", null)
                .show();
    }

    static void openGuardian(Activity activity) {
        try {
            Intent i = activity.getPackageManager().getLaunchIntentForPackage(PACKAGE);
            if (i == null) throw new Exception("Guardian launch activity is unavailable.");
            activity.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(activity, "Could not open Guardian: " + String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    static void installEmbeddedGuardian(Activity activity, SharedPreferences prefs) {
        try {
            File dir = new File(activity.getFilesDir(), "lumi_updates/guardian_bootstrap"); if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create Guardian staging directory.");
            File apk = new File(dir, "lumi-guardian.apk");
            try (InputStream in = activity.getResources().openRawResource(com.distressedelk.lumi.R.raw.lumi_guardian); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf=new byte[1024*1024]; int n; long total=0; while((n=in.read(buf))>=0){ if(n==0)continue; total+=n; if(total>100L*1024L*1024L)throw new SecurityException("Embedded Guardian package is unexpectedly large."); out.write(buf,0,n); } out.getFD().sync();
            }
            verifyEmbeddedGuardian(activity, apk);
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                prefs.edit().putBoolean("guardian_install_waiting_permission", true).apply();
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
                Toast.makeText(activity, "Allow Lumi to install Guardian, then return to Lumi.", Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName()+".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE); install.setData(uri); install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            prefs.edit().putBoolean("guardian_install_requested", true).putLong("guardian_install_requested_at", System.currentTimeMillis()).apply();
            activity.startActivity(install);
        } catch (Exception e) {
            Toast.makeText(activity, "Guardian setup failed: " + String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    static void handoffPendingCore(Activity activity, SharedPreferences prefs) throws Exception {
        if (!isGuardianInstalled(activity) || !guardianSignatureMatches(activity)) throw new SecurityException("Lumi Guardian is not installed with the trusted signing identity.");
        File apk = LumiUpdateManager.pendingCoreApkFile(prefs);
        if (apk == null || !apk.isFile()) throw new java.io.FileNotFoundException("The verified pending Lumi core APK is missing.");
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName()+".fileprovider", apk);
        Intent i = new Intent("com.distressedelk.lumi.guardian.INSTALL_LUMI_CORE");
        i.setClassName(PACKAGE, PACKAGE+".MainActivity");
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(i);
    }

    private static void verifyEmbeddedGuardian(Context context, File apk) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo p = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (p == null) throw new SecurityException("Embedded Guardian APK is unreadable.");
        if (!PACKAGE.equals(p.packageName)) throw new SecurityException("Embedded Guardian package identity is invalid.");
        long version = Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        if (version < REQUIRED_GUARDIAN_VERSION) throw new SecurityException("Embedded Guardian package is older than the required certification companion.");
        String fp = fingerprint(p);
        if (!TRUSTED_CERT.equals(fp)) throw new SecurityException("Embedded Guardian signing identity is invalid.");
    }

    private static String packageFingerprint(Context context, String pkg) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        return fingerprint(context.getPackageManager().getPackageInfo(pkg, flags));
    }

    private static String fingerprint(PackageInfo p) throws Exception {
        byte[] cert;
        if (Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) cert=p.signingInfo.getApkContentsSigners()[0].toByteArray();
        else cert=p.signatures[0].toByteArray();
        byte[] d=MessageDigest.getInstance("SHA-256").digest(cert); StringBuilder s=new StringBuilder(); for(byte b:d)s.append(String.format(Locale.US,"%02X",b)); return s.toString();
    }
}
