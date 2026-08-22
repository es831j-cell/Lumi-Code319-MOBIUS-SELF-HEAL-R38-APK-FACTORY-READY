package com.distressedelk.lumi.guardian;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.security.MessageDigest;
import java.util.Locale;

final class GuardianVerifier {
    static final class VerifiedApk {
        final long versionCode;
        final String versionName;
        final String fingerprint;
        VerifiedApk(long versionCode, String versionName, String fingerprint) {
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName;
            this.fingerprint = fingerprint;
        }
    }

    private GuardianVerifier() {}

    static VerifiedApk verifyLumiApk(Context context, File apk, boolean requireNewer) throws Exception {
        if (apk == null || !apk.isFile() || apk.length() < 4096) throw new SecurityException("The selected Lumi APK is missing or incomplete.");
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null) throw new SecurityException("Android could not read the selected APK.");
        if (!TrustedIdentity.LUMI_PACKAGE.equals(archive.packageName)) throw new SecurityException("This APK is not the Lumi package.");
        long incoming = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
        long current = currentLumiVersion(context);
        if (requireNewer && current > 0 && incoming <= current) throw new SecurityException("Lumi update versionCode must be newer than the installed build.");
        String fp = certificateFingerprint(archive);
        if (!TrustedIdentity.LUMI_CERT_SHA256.equals(fp)) throw new SecurityException("Lumi signing certificate does not match the trusted identity.");
        return new VerifiedApk(incoming, archive.versionName, fp);
    }

    static long currentLumiVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TrustedIdentity.LUMI_PACKAGE, 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0L;
        }
    }

    static String installedLumiFingerprint(Context context) {
        try {
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            return certificateFingerprint(context.getPackageManager().getPackageInfo(TrustedIdentity.LUMI_PACKAGE, flags));
        } catch (Exception e) {
            return "";
        }
    }

    private static String certificateFingerprint(PackageInfo info) throws Exception {
        byte[] cert;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            android.content.pm.Signature[] s = info.signingInfo.getApkContentsSigners();
            if (s == null || s.length == 0) throw new SecurityException("APK signing certificate is unavailable.");
            cert = s[0].toByteArray();
        } else {
            if (info.signatures == null || info.signatures.length == 0) throw new SecurityException("APK signing certificate is unavailable.");
            cert = info.signatures[0].toByteArray();
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(Locale.US, "%02X", b));
        return out.toString();
    }
}
