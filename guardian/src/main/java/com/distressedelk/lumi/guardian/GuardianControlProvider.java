package com.distressedelk.lumi.guardian;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Lumi 1.0 signature-protected Guardian control surface. Guardian remains a separate package and
 * independently calls Lumi's protected certification/checkpoint provider instead of trusting a
 * model/relay assertion.
 */
public final class GuardianControlProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        Bundle out=new Bundle();
        if(getContext()==null){out.putBoolean("ok",false);out.putString("error","Guardian context unavailable");return out;}
        try{
            if(!trustedLumiCaller()){ out.putBoolean("ok",false); out.putString("error","Untrusted Lumi bridge caller"); return out; }
            if("health".equals(method)) {
                Bundle lumi=GuardianBridgeClient.call(getContext(),"health");
                lumi.putBoolean("guardianInstalled", true);
                lumi.putBoolean("installerPermissionReady",
                        android.os.Build.VERSION.SDK_INT < 26 || getContext().getPackageManager().canRequestPackageInstalls());
                lumi.putLong("guardianVersionCode", 12L);
                lumi.putString("guardianVersionName", "2.1-provider-auth-fix");
                return lumi;
            }
            if("bridge_probe".equals(method)) {
                String tx=extras==null?"":extras.getString("transaction_id","").trim();
                long started=System.currentTimeMillis();
                Bundle pingArgs=new Bundle(); pingArgs.putString("transaction_id",tx);
                Bundle ping=GuardianBridgeClient.call(getContext(),"bridge_ping",pingArgs);
                boolean pingOk=ping.getBoolean("ok",false);
                boolean echoOk=tx.equals(ping.getString("transaction_id",""));
                boolean identityOk=TrustedIdentity.LUMI_PACKAGE.equals(ping.getString("package_name",""));
                boolean hostReady=ping.getBoolean("maintenance_host_ready",false);
                boolean transportOk=pingOk && echoOk && identityOk;
                String failedStage = !pingOk ? "GUARDIAN_TO_LUMI_PROVIDER_CALL" : !echoOk ? "TRANSACTION_ECHO" : !identityOk ? "LUMI_IDENTITY" : !hostReady ? "MAINTENANCE_HOST_READY" : "NONE";
                Bundle lumiHealth=transportOk?GuardianBridgeClient.call(getContext(),"health"):new Bundle();
                android.content.SharedPreferences gp=getContext().getSharedPreferences("guardian",0);
                String transportError=ping.getString("error","");
                GuardianLedger.append(getContext(), transportOk?"BRIDGE_PROBE_PASS":"BRIDGE_PROBE_FAIL",
                        "tx="+tx+" pingOk="+ping.getBoolean("ok",false)+" echo="+ping.getString("transaction_id","")+
                        " package="+ping.getString("package_name","")+" error="+transportError);
                out.putBoolean("ok",transportOk);
                out.putString("state",transportOk?"BRIDGE_CONNECTED":"LUMI_BRIDGE_UNREACHABLE");
                out.putString("transaction_id",tx);
                out.putBoolean("guardianReachable",true);
                out.putBoolean("lumiRoundTrip",transportOk);
                out.putBoolean("transportPingOk",pingOk);
                out.putBoolean("transactionEchoOk",echoOk);
                out.putBoolean("lumiIdentityOk",identityOk);
                out.putBoolean("maintenanceHostReady",hostReady);
                out.putLong("lumiVersionCode",ping.getLong("lumi_version_code",-1L));
                out.putString("lumiVersionName",ping.getString("lumi_version_name",""));
                out.putBoolean("lumiCertified",lumiHealth.getBoolean("certified",false));
                out.putString("lumiSummary",lumiHealth.getString("summary",lumiHealth.getString("error","")));
                if(!transportOk || !hostReady) out.putString("error", transportError.isEmpty()?"Bridge stage failed: "+failedStage:transportError);
                out.putString("failedStage", failedStage);
                out.putLong("guardianVersionCode",12L);
                out.putString("guardianVersionName","2.1-provider-auth-fix");
                out.putString("pending_request_state",gp.getString("pending_request_state","NONE"));
                out.putString("pending_request_id",gp.getString("pending_request_id",""));
                out.putString("pending_request_type",gp.getString("pending_request_type",""));
                out.putString("pending_request_change",gp.getString("pending_request_change",""));
                out.putLong("completed_at",System.currentTimeMillis());
                out.putLong("round_trip_ms",System.currentTimeMillis()-started);
                return out;
            }
            if("certify".equals(method)) return GuardianBridgeClient.call(getContext(),"certify");
            if("run_post_change_certification".equals(method)) {
                String reason=extras==null?"maintenance":extras.getString("reason","maintenance");
                long target=extras==null?-1L:extras.getLong("target_version",-1L);
                return PostChangeCertification.run(getContext(),reason,target);
            }
            if("create_checkpoint".equals(method)) return GuardianBridgeClient.call(getContext(),"create_checkpoint");
            if("restore_latest_checkpoint".equals(method)) return GuardianBridgeClient.call(getContext(),"restore_latest_checkpoint");
            if("submit_maintenance_request".equals(method)) {
                if(extras==null) throw new SecurityException("Missing maintenance request envelope");
                String tx=extras.getString("transaction_id","").trim();
                String type=extras.getString("change_type","").trim().toLowerCase(Locale.US);
                String requested=extras.getString("requested_change","").replace('\n',' ').replace('\r',' ').trim();
                long created=extras.getLong("created_at",0L);
                String nonce=extras.getString("nonce","").trim();
                String supplied=extras.getString("request_hash","").trim().toLowerCase(Locale.US);
                long now=System.currentTimeMillis();
                if(tx.length()<8||tx.length()>96||nonce.length()<20||nonce.length()>80) throw new SecurityException("Malformed maintenance request identity");
                if(requested.length()<3||requested.length()>500) throw new SecurityException("Maintenance request length is outside policy");
                if(!("diagnose".equals(type)||"runtime_tuning".equals(type)||"content_update".equals(type)||"core_update".equals(type)||"rollback".equals(type))) throw new SecurityException("Maintenance request type is not allowed");
                if(created<=0||Math.abs(now-created)>120000L) throw new SecurityException("Maintenance request timestamp is stale");
                String canonical=tx+"|"+type+"|"+created+"|"+nonce+"|"+requested;
                MessageDigest h=MessageDigest.getInstance("SHA-256");StringBuilder calc=new StringBuilder();for(byte x:h.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)))calc.append(String.format(Locale.US,"%02x",x));
                if(!calc.toString().equals(supplied)) throw new SecurityException("Maintenance request integrity check failed");
                android.content.SharedPreferences gp=getContext().getSharedPreferences("guardian",0);
                if(nonce.equals(gp.getString("last_request_nonce",""))) throw new SecurityException("Maintenance request replay blocked");
                gp.edit().putString("last_request_nonce",nonce).putString("pending_request_id",tx).putString("pending_request_type",type).putString("pending_request_change",requested).putString("pending_request_hash",supplied).putLong("pending_request_at",created).putString("pending_request_state","QUEUED_FOR_MAINTENANCE").apply();
                GuardianLedger.append(getContext(),"MAINTENANCE_REQUEST_QUEUED","id="+tx+" type="+type+" sha256="+supplied+" request="+requested);
                out.putBoolean("ok",true);out.putString("state","QUEUED_FOR_MAINTENANCE");out.putString("request_id",tx);out.putString("change_type",type);out.putString("request_hash",supplied);out.putLong("created_at",created);return out;
            }
            if("maintenance_request_status".equals(method)) {
                android.content.SharedPreferences gp=getContext().getSharedPreferences("guardian",0);
                out.putBoolean("ok",true);out.putString("state",gp.getString("pending_request_state","NONE"));out.putString("request_id",gp.getString("pending_request_id",""));out.putString("change_type",gp.getString("pending_request_type",""));out.putString("requested_change",gp.getString("pending_request_change",""));out.putString("request_hash",gp.getString("pending_request_hash",""));out.putLong("created_at",gp.getLong("pending_request_at",0L));return out;
            }
            if("install_core_from_uri".equals(method)) {
                if (extras == null) throw new SecurityException("Missing maintenance request");
                String uriText = extras.getString("uri", "");
                String expected = extras.getString("sha256", "").trim().toLowerCase(Locale.US);
                String requested = extras.getString("requested_change", "Lumi core update");
                if (uriText.isEmpty()) throw new SecurityException("Missing staged core update URI");
                File dir = new File(getContext().getFilesDir(), "bridge-staging");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Could not create Guardian staging folder");
                File apk = new File(dir, "lumi-core-" + System.currentTimeMillis() + ".apk");
                long copied = 0;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uriText)); FileOutputStream fos = new FileOutputStream(apk)) {
                    if (in == null) throw new java.io.FileNotFoundException("Guardian could not open staged Lumi APK");
                    byte[] b = new byte[1024 * 1024]; int n;
                    while ((n = in.read(b)) > 0) { copied += n; if (copied > GuardianInstaller.MAX_APK_BYTES) throw new SecurityException("APK exceeds Guardian size limit"); md.update(b,0,n); fos.write(b,0,n); }
                    fos.getFD().sync();
                }
                StringBuilder hs = new StringBuilder(); for (byte x : md.digest()) hs.append(String.format(Locale.US, "%02x", x));
                String actual = hs.toString();
                if (!expected.isEmpty() && !expected.equals(actual)) { apk.delete(); throw new SecurityException("Staged APK SHA-256 does not match expected value"); }
                GuardianVerifier.VerifiedApk verified = GuardianVerifier.verifyLumiApk(getContext(), apk, true);
                Bundle checkpoint = GuardianBridgeClient.call(getContext(), "create_checkpoint");
                if (!checkpoint.getBoolean("ok", false)) { apk.delete(); throw new SecurityException("Guardian checkpoint failed: " + checkpoint.getString("error", "unknown")); }
                int session = GuardianInstaller.installLumi(getContext(), apk, verified, false);
                GuardianLedger.append(getContext(), "BRIDGE_INSTALL_COMMIT", "session="+session+" target="+verified.versionCode+" sha256="+actual+" request="+requested);
                getContext().getSharedPreferences("guardian",0).edit().putString("last_bridge_request", requested).putString("last_bridge_sha256", actual).putLong("last_bridge_at", System.currentTimeMillis()).apply();
                out.putBoolean("ok", true); out.putString("state", "INSTALL_SUBMITTED"); out.putInt("session_id", session); out.putLong("target_version", verified.versionCode); out.putString("target_name", verified.versionName); out.putString("sha256", actual); out.putString("checkpoint", checkpoint.getString("path", "")); return out;
            }
            if("configure_trusted_build_relay".equals(method)) {
                if(extras==null) throw new SecurityException("Missing relay enrollment request");
                return TrustedBuildRelay.configure(getContext(),extras.getString("host",""));
            }
            if("trusted_build_relay_status".equals(method)) return TrustedBuildRelay.status(getContext());
            if("install_relay_build".equals(method)) {
                if(extras==null) throw new SecurityException("Missing relay build request");
                return TrustedBuildRelay.receiveAndInstall(getContext(),extras.getString("request_id",""),extras.getString("url",""),extras.getString("sha256",""),extras.getString("requested_change",""));
            }
            if("maintenance_status".equals(method)) {
                android.content.SharedPreferences gp=getContext().getSharedPreferences("guardian",0);
                out.putBoolean("ok", true); out.putInt("last_install_status", gp.getInt("last_install_status", Integer.MIN_VALUE)); out.putString("last_install_message", gp.getString("last_install_message", "")); out.putLong("last_install_target", gp.getLong("last_install_target", -1)); out.putBoolean("certification_pending", gp.getBoolean("certification_pending", false)); out.putString("last_bridge_request", gp.getString("last_bridge_request", "")); out.putString("last_bridge_sha256", gp.getString("last_bridge_sha256", "")); out.putLong("last_bridge_at", gp.getLong("last_bridge_at", 0)); out.putString("pending_request_state",gp.getString("pending_request_state","NONE")); out.putBoolean("last_certification_pass",gp.getBoolean("last_certification_pass",false)); out.putLong("last_certification_at",gp.getLong("last_certification_at",0L)); out.putInt("last_certification_attempts",gp.getInt("last_certification_attempts",0)); out.putString("last_certification_summary",gp.getString("last_certification_summary","")); out.putString("last_certification_probe",gp.getString("last_certification_probe","")); out.putBoolean("core_recovery_required",gp.getBoolean("core_recovery_required",false)); out.putString("core_recovery_reason",gp.getString("core_recovery_reason","")); out.putString("pending_request_id",gp.getString("pending_request_id","")); out.putString("pending_request_type",gp.getString("pending_request_type","")); out.putString("pending_request_change",gp.getString("pending_request_change","")); out.putLong("pending_request_at",gp.getLong("pending_request_at",0L)); return out;
            }
            out.putBoolean("ok",false);out.putString("error","Unsupported Guardian control method");return out;
        }catch(Exception e){out.putBoolean("ok",false);out.putString("error",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));return out;}
    }

    private boolean trustedLumiCaller(){
        if(getContext()==null) return false;
        try{
            int uid=Binder.getCallingUid();
            String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
            boolean lumi=false;
            if(packages!=null) for(String pkg:packages) if(TrustedIdentity.LUMI_PACKAGE.equals(pkg)) lumi=true;
            if(!lumi) return false;
            PackageManager pm=getContext().getPackageManager();
            return pm.checkSignatures(getContext().getPackageName(), TrustedIdentity.LUMI_PACKAGE)==PackageManager.SIGNATURE_MATCH;
        }catch(Exception e){ return false; }
    }
    @Override public String getType(Uri uri){return null;}
    @Override public Cursor query(Uri uri,String[] p,String s,String[] a,String sort){return null;}
    @Override public Uri insert(Uri uri,ContentValues v){return null;}
    @Override public int delete(Uri uri,String s,String[] a){return 0;}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){return 0;}
}
