package com.distressedelk.lumi.guardian;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

final class GuardianInstaller {
    static final String ACTION_INSTALL_STATUS = "com.distressedelk.lumi.guardian.INSTALL_STATUS";
    static final long MAX_APK_BYTES = 750L * 1024L * 1024L;

    private GuardianInstaller() {}

    static int installLumi(Context context, File apk, GuardianVerifier.VerifiedApk verified, boolean recovery) throws Exception {
        if (apk.length() > MAX_APK_BYTES) throw new SecurityException("APK exceeds Guardian's maximum allowed size.");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(TrustedIdentity.LUMI_PACKAGE);
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        if (Build.VERSION.SDK_INT >= 33) params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE);

        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             FileInputStream in = new FileInputStream(apk);
             OutputStream out = session.openWrite("lumi.apk", 0, apk.length())) {
            byte[] buf = new byte[1024 * 1024];
            long copied = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                copied += n;
                if (copied > MAX_APK_BYTES) throw new SecurityException("APK exceeded Guardian's maximum allowed size while copying.");
                out.write(buf, 0, n);
            }
            session.fsync(out);

            Intent status = new Intent(context, InstallStatusReceiver.class);
            status.setAction(ACTION_INSTALL_STATUS);
            status.putExtra("session_id", sessionId);
            status.putExtra("target_version", verified.versionCode);
            status.putExtra("target_name", verified.versionName);
            status.putExtra("recovery", recovery);
            PendingIntent pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        }
        return sessionId;
    }
}
