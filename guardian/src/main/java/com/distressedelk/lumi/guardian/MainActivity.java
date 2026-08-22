package com.distressedelk.lumi.guardian;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class MainActivity extends Activity {
    private static final int REQ_APK = 410;
    private static final long MAX_IMPORT_BYTES = 750L * 1024L * 1024L;
    private SharedPreferences prefs;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("guardian", MODE_PRIVATE);
        render();
        handleIncomingIntent(getIntent());
        ensureInstallerPermission();
    }


    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if ("com.distressedelk.lumi.guardian.INSTALL_LUMI_CORE".equals(intent.getAction()) && intent.getData() != null) {
            Uri uri = intent.getData();
            intent.setAction(null);
            importAndInstall(uri);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        certifyIfPending();
        refreshStatus();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 40, 34, 40);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("Lumi Guardian"); title.setTextSize(28); title.setTextColor(Color.rgb(20,30,38));
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Independent update, verification, checkpoint and recovery companion."); sub.setTextSize(15); sub.setPadding(0,8,0,24);
        root.addView(sub);
        status = new TextView(this); status.setTextSize(14); status.setPadding(20,20,20,20); status.setBackgroundColor(Color.rgb(238,244,247)); root.addView(status, new LinearLayout.LayoutParams(-1,-2));

        Button checkpoint = button("Create Lumi checkpoint"); root.addView(checkpoint); checkpoint.setOnClickListener(v -> createCheckpoint());
        Button update = button("Install signed Lumi APK"); root.addView(update); update.setOnClickListener(v -> chooseApk());
        Button certify = button("Run Lumi certification"); root.addView(certify); certify.setOnClickListener(v -> showCertification());
        Button openLumi = button("Open Lumi"); root.addView(openLumi); openLumi.setOnClickListener(v -> openLumi());
        Button permission = button("Android install-source permission"); root.addView(permission); permission.setOnClickListener(v -> openInstallPermission());
        setContentView(scroll);
        refreshStatus();
    }

    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(15); b.setPadding(12,10,12,10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0,18,0,0); b.setLayoutParams(lp); return b;
    }

    private void refreshStatus() {
        if (status == null) return;
        long version = GuardianVerifier.currentLumiVersion(this);
        String fp = GuardianVerifier.installedLumiFingerprint(this);
        boolean trusted = TrustedIdentity.LUMI_CERT_SHA256.equals(fp);
        Bundle health = GuardianBridgeClient.call(this, "health");
        String healthText = health.getString("summary", health.getString("error", "Lumi bridge unavailable"));
        int installStatus = prefs.getInt("last_install_status", Integer.MIN_VALUE);
        String installLine = installStatus == Integer.MIN_VALUE ? "No Guardian install transaction recorded yet." : "Last install status: " + installStatus + " • " + prefs.getString("last_install_message", "");
        status.setText("Guardian 1.9 • Bridge Ping/Echo Maintenance\nLumi versionCode: " + version + "\nTrusted Lumi certificate: " + (trusted?"YES":"NO") + "\n\nLumi health: " + healthText + "\n\n" + installLine);
    }

    private void createCheckpoint() {
        Bundle b = GuardianBridgeClient.call(this, "create_checkpoint");
        boolean ok = b.getBoolean("ok", false);
        GuardianLedger.append(this, ok?"CHECKPOINT_OK":"CHECKPOINT_FAIL", b.toString());
        Toast.makeText(this, ok ? "Lumi checkpoint created." : "Checkpoint failed: " + b.getString("error", "unknown error"), Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void chooseApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/vnd.android.package-archive"); startActivityForResult(i, REQ_APK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_APK && resultCode == RESULT_OK && data != null && data.getData() != null) importAndInstall(data.getData());
    }

    private void importAndInstall(Uri uri) {
        new Thread(() -> {
            File out = null;
            try {
                File dir = new File(getFilesDir(), "pending"); if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create Guardian staging directory.");
                out = new File(dir, "lumi-candidate-" + System.currentTimeMillis() + ".apk");
                long total = 0;
                try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream fos = new FileOutputStream(out)) {
                    if (in == null) throw new Exception("Could not open selected APK.");
                    byte[] buf = new byte[1024 * 1024]; int n;
                    while ((n=in.read(buf)) >= 0) { if (n==0) continue; total += n; if (total > MAX_IMPORT_BYTES) throw new SecurityException("APK exceeds Guardian size limit."); fos.write(buf,0,n); }
                    fos.getFD().sync();
                }
                GuardianVerifier.VerifiedApk verified = GuardianVerifier.verifyLumiApk(this, out, true);
                Bundle checkpoint = GuardianBridgeClient.call(this, "create_checkpoint");
                if (!checkpoint.getBoolean("ok", false)) throw new SecurityException("Guardian refused to install without a verified Lumi checkpoint: " + checkpoint.getString("error", "checkpoint failed"));
                final File staged = out;
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Verified Lumi update")
                        .setMessage("Package, version and signing identity passed verification.\n\nTarget: " + verified.versionName + " (code " + verified.versionCode + ")\n\nInstall this update?")
                        .setPositiveButton("Install", (d,w) -> commitInstall(staged, verified))
                        .setNegativeButton("Cancel", null)
                        .show());
            } catch (Exception e) {
                if (out != null) out.delete();
                String msg = String.valueOf(e.getMessage()); GuardianLedger.append(this, "IMPORT_REJECT", msg);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Update rejected").setMessage(msg).setPositiveButton("OK",null).show());
            }
        }, "GuardianImport").start();
    }

    private void commitInstall(File apk, GuardianVerifier.VerifiedApk verified) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) { openInstallPermission(); Toast.makeText(this,"Allow Lumi Guardian to install updates, then try again.",Toast.LENGTH_LONG).show(); return; }
            int session = GuardianInstaller.installLumi(this, apk, verified, false);
            GuardianLedger.append(this, "INSTALL_COMMIT", "session="+session+" target="+verified.versionCode);
            Toast.makeText(this,"Guardian submitted Lumi update session " + session + ".",Toast.LENGTH_LONG).show();
        } catch (Exception e) { new AlertDialog.Builder(this).setTitle("Install could not start").setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK",null).show(); }
    }

    private void certifyIfPending() {
        if (!prefs.getBoolean("certification_pending", false)) return;
        new Thread(() -> {
            Bundle b = PostChangeCertification.run(this, "guardian_pending", prefs.getLong("certification_target", -1L));
            if (b.getBoolean("ok", false) && b.getBoolean("certified", false)) {
                prefs.edit().putBoolean("certification_pending", false).putLong("last_certified_version", b.getLong("version_code", -1)).apply();
                GuardianLedger.append(this,"CERTIFICATION_PASS",b.toString());
            } else GuardianLedger.append(this,"CERTIFICATION_FAIL",b.toString());
            runOnUiThread(this::refreshStatus);
        }, "GuardianPendingCertification").start();
    }

    private void showCertification() {
        Toast.makeText(this,"Running real Fast Brain certification probe…",Toast.LENGTH_LONG).show();
        new Thread(() -> {
            Bundle b = PostChangeCertification.run(this, "manual_guardian_certification", -1L);
            GuardianLedger.append(this, b.getBoolean("certified",false)?"CERTIFICATION_PASS":"CERTIFICATION_FAIL", b.toString());
            runOnUiThread(() -> {
                new AlertDialog.Builder(this).setTitle("Lumi certification").setMessage(b.getString("summary", b.toString())).setPositiveButton("OK",null).show();
                refreshStatus();
            });
        }, "GuardianCertification").start();
    }

    private void openLumi() {
        Intent i = getPackageManager().getLaunchIntentForPackage(TrustedIdentity.LUMI_PACKAGE); if (i != null) startActivity(i); else Toast.makeText(this,"Lumi is not installed.",Toast.LENGTH_LONG).show();
    }

    private void ensureInstallerPermission() {
        if (android.os.Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) return;
        if (prefs.getBoolean("install_permission_prompted", false)) return;
        prefs.edit().putBoolean("install_permission_prompted", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("One Android approval needed")
                .setMessage("Allow Lumi Guardian to install app updates. Once this is enabled and Guardian certification passes, APK Factory is emergency-only for normal Lumi development.")
                .setPositiveButton("Open Android setting", (d,w) -> openInstallPermission())
                .setNegativeButton("Later", null)
                .show();
    }

    private void openInstallPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 26) startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
    }
}
