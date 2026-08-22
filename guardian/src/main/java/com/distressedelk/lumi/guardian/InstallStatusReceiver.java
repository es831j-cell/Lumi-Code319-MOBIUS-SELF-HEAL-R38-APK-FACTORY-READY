package com.distressedelk.lumi.guardian;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;

public final class InstallStatusReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!GuardianInstaller.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        int sessionId = intent.getIntExtra("session_id", -1);
        long target = intent.getLongExtra("target_version", -1L);
        boolean recovery = intent.getBooleanExtra("recovery", false);
        SharedPreferences prefs = context.getSharedPreferences("guardian", Context.MODE_PRIVATE);
        prefs.edit()
                .putInt("last_install_status", status)
                .putString("last_install_message", message == null ? "" : message)
                .putInt("last_install_session", sessionId)
                .putLong("last_install_target", target)
                .putBoolean("last_install_recovery", recovery)
                .putLong("last_install_at", System.currentTimeMillis())
                .apply();

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(confirm); } catch (Exception ignored) {}
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            GuardianLedger.append(context, "INSTALL_SUCCESS", "session=" + sessionId + " target=" + target + " recovery=" + recovery);
            prefs.edit().putBoolean("certification_pending", true).putLong("certification_target", target).putString("certification_reason","core_install").apply();
            new Thread(() -> {
                try {
                    Thread.sleep(1800L);
                    android.os.Bundle cert=PostChangeCertification.run(context,"core_install",target);
                    boolean pass=cert.getBoolean("certified",false);
                    GuardianLedger.append(context,pass?"POST_INSTALL_CERT_PASS":"POST_INSTALL_CERT_FAIL",cert.toString());
                    if(!pass){
                        // RecoverySnapshotManager protects Lumi state. Android does not permit a normal app to
                        // silently downgrade/reinstall an older APK, so core rollback remains a Guardian-guided
                        // recovery action rather than a false promise of unattended package downgrade.
                        android.os.Bundle restore=GuardianBridgeClient.call(context,"restore_latest_checkpoint");
                        prefs.edit().putBoolean("core_recovery_required",true)
                                .putString("core_recovery_reason",cert.getString("summary",cert.getString("error","Certification failed")))
                                .putBoolean("checkpoint_restore_attempted",true)
                                .putBoolean("checkpoint_restore_ok",restore.getBoolean("ok",false)).apply();
                        GuardianLedger.append(context,"POST_INSTALL_RECOVERY_REQUIRED","checkpoint="+restore.toString());
                    } else {
                        prefs.edit().putBoolean("core_recovery_required",false).remove("core_recovery_reason").apply();
                    }
                } catch(Exception e){ GuardianLedger.append(context,"POST_INSTALL_CERT_ERROR",String.valueOf(e)); }
            }, "GuardianPostInstallCert").start();
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(TrustedIdentity.LUMI_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(launch); } catch (Exception ignored) {}
            }
        } else {
            GuardianLedger.append(context, "INSTALL_FAILURE", "session=" + sessionId + " target=" + target + " status=" + status + " message=" + message);
        }
    }
}
