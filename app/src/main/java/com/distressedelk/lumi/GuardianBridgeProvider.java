package com.distressedelk.lumi;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.security.MessageDigest;
import java.util.Locale;

public final class GuardianBridgeProvider extends ContentProvider {
    @Override public boolean onCreate() {
        try {
            if (getContext() != null) {
                SharedPreferences prefs=getContext().getSharedPreferences("lumi",0);
                MaintenanceFoundation.initialize(getContext(),prefs);
            }
        } catch (Throwable ignored) {}
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (getContext() == null) { out.putBoolean("ok", false); out.putString("error", "Lumi context unavailable"); return out; }
        SharedPreferences prefs = getContext().getSharedPreferences("lumi", 0);
        MaintenanceFoundation.initialize(getContext(), prefs);
        try {
            if (!trustedGuardianCaller()) {
                out.putBoolean("ok", false);
                out.putString("error", "Untrusted Guardian bridge caller");
                return out;
            }
            if ("bridge_ping".equals(method)) {
                String tx=extras==null?"":extras.getString("transaction_id","").trim();
                out.putBoolean("ok", true);
                out.putString("state", "LUMI_BRIDGE_REACHABLE");
                out.putString("transaction_id", tx);
                out.putString("package_name", getContext().getPackageName());
                try {
                    android.content.pm.PackageInfo pi=getContext().getPackageManager().getPackageInfo(getContext().getPackageName(),0);
                    long vc=android.os.Build.VERSION.SDK_INT>=28?pi.getLongVersionCode():pi.versionCode;
                    out.putLong("lumi_version_code",vc);
                    out.putString("lumi_version_name",pi.versionName==null?"":pi.versionName);
                } catch (Exception ignored) {}
                out.putBoolean("maintenance_host_ready",prefs.getBoolean("direct_maintenance_host_ready",false));
                out.putLong("completed_at",System.currentTimeMillis());
                return out;
            }
            if ("health".equals(method)) return BootstrapHealth.healthBundle(getContext(), prefs);
            if ("certify".equals(method)) return BootstrapHealth.certificationBundle(getContext(), prefs);
            if ("create_checkpoint".equals(method)) {
                RecoverySnapshotManager.create(getContext(), prefs, "guardian-pre-update");
                out.putBoolean("ok", true); out.putString("path", RecoverySnapshotManager.latestPath(prefs)); return out;
            }
            if ("restore_latest_checkpoint".equals(method)) {
                out.putBoolean("ok", RecoverySnapshotManager.restoreLatest(getContext(), prefs)); return out;
            }
            out.putBoolean("ok", false); out.putString("error", "Unsupported Guardian bridge method"); return out;
        } catch (Exception e) {
            out.putBoolean("ok", false); out.putString("error", e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())); return out;
        }
    }

    private boolean trustedGuardianCaller() {
        if (getContext() == null) return false;
        try {
            int uid = Binder.getCallingUid();
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages == null) return false;
            boolean guardianPackage = false;
            for (String pkg : packages) if ("com.distressedelk.lumi.guardian".equals(pkg)) guardianPackage = true;
            if (!guardianPackage) return false;
            PackageManager pm = getContext().getPackageManager();
            return pm.checkSignatures(getContext().getPackageName(), "com.distressedelk.lumi.guardian")
                    == PackageManager.SIGNATURE_MATCH;
        } catch (Exception e) { return false; }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
