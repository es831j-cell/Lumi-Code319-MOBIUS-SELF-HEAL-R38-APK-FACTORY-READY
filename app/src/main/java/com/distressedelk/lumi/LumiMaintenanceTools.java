package com.distressedelk.lumi;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tightly-scoped function tools available to the OpenAI reasoning path inside Lumi.
 * There is intentionally no shell, arbitrary file read/write, permission escalation or Guardian bypass.
 */
final class LumiMaintenanceTools {
    private LumiMaintenanceTools(){}

    static JSONArray definitions() throws Exception {
        JSONArray a=new JSONArray();
        a.put(tool("get_lumi_status","Read Lumi/Guardian/Memory Vault health. This is read-only.",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false)));
        a.put(tool("check_maintenance_bridge","Verify the live same-phone Lumi to Guardian maintenance bridge, tool host readiness, Guardian reachability, relay state, and pending maintenance transaction. This is read-only.",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false)));
        a.put(tool("read_lumi_diagnostics","Read a bounded, redacted tail of Lumi diagnostics.",new JSONObject().put("type","object").put("properties",new JSONObject().put("max_chars",new JSONObject().put("type","integer").put("minimum",1000).put("maximum",16000))).put("additionalProperties",false)));
        a.put(tool("read_maintenance_history","Read recent append-only technical maintenance ledger entries.",new JSONObject().put("type","object").put("properties",new JSONObject().put("limit",new JSONObject().put("type","integer").put("minimum",1).put("maximum",30))).put("additionalProperties",false)));
        JSONObject requestProps=new JSONObject();
        requestProps.put("requested_change",new JSONObject().put("type","string").put("description","Short owner-approved description of the requested Lumi change."));
        requestProps.put("change_type",new JSONObject().put("type","string").put("enum",new JSONArray().put("diagnose").put("runtime_tuning").put("content_update").put("core_update").put("rollback")));
        a.put(tool("submit_maintenance_request","Submit an owner-approved, bounded maintenance request to Guardian's private queue. This does not grant shell or arbitrary file access and does not install anything by itself.",new JSONObject().put("type","object").put("properties",requestProps).put("required",new JSONArray().put("requested_change").put("change_type")).put("additionalProperties",false)));
        a.put(tool("create_recovery_checkpoint","Ask the separate Guardian app to create a recovery checkpoint. Requires explicit owner approval.",new JSONObject().put("type","object").put("properties",new JSONObject().put("reason",new JSONObject().put("type","string"))).put("additionalProperties",false)));
        JSONObject installProps=new JSONObject();
        installProps.put("url",new JSONObject().put("type","string").put("description","HTTPS URL of a Lumi signed update ZIP."));
        installProps.put("sha256",new JSONObject().put("type","string").put("description","Optional expected SHA-256 of the ZIP."));
        installProps.put("requested_change",new JSONObject().put("type","string"));
        a.put(tool("install_signed_update","Download, verify, checkpoint, and apply a Lumi-signed update package. Core APK updates are staged for Android's required install approval. Requires explicit owner approval.",new JSONObject().put("type","object").put("properties",installProps).put("required",new JSONArray().put("url").put("requested_change")).put("additionalProperties",false)));
        a.put(tool("launch_pending_core_install","Launch Android's installer for an already verified/staged Lumi core APK. Requires explicit owner approval and Android may still require a tap.",new JSONObject().put("type","object").put("properties",new JSONObject()).put("additionalProperties",false)));
        a.put(tool("rollback_last_update","Rollback the last reversible Lumi content update and re-run Guardian certification. Requires explicit owner approval.",new JSONObject().put("type","object").put("properties",new JSONObject().put("reason",new JSONObject().put("type","string"))).put("additionalProperties",false)));
        a.put(tool("configure_trusted_build_relay","Enroll the exact HTTPS hostname allowed to deliver signed Lumi core builds to Guardian. Requires explicit owner approval.",new JSONObject().put("type","object").put("properties",new JSONObject().put("host",new JSONObject().put("type","string"))).put("required",new JSONArray().put("host")).put("additionalProperties",false)));
        JSONObject relayProps=new JSONObject(); relayProps.put("request_id",new JSONObject().put("type","string")); relayProps.put("url",new JSONObject().put("type","string")); relayProps.put("sha256",new JSONObject().put("type","string")); relayProps.put("requested_change",new JSONObject().put("type","string"));
        a.put(tool("install_trusted_relay_build","Ask Guardian to download a build artifact from the enrolled HTTPS relay host, verify its exact SHA-256 and Lumi signing identity, checkpoint, and submit installation. Requires an already queued maintenance request and explicit owner approval.",new JSONObject().put("type","object").put("properties",relayProps).put("required",new JSONArray().put("request_id").put("url").put("sha256")).put("additionalProperties",false)));
        return a;
    }

    private static JSONObject tool(String name,String description,JSONObject parameters)throws Exception{
        return new JSONObject().put("type","function").put("name",name).put("description",description).put("parameters",parameters).put("strict",false);
    }

    static String execute(Activity activity, SharedPreferences prefs, String name, JSONObject args, String currentUserText) {
        String tx="tx-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
        LumiMemoryVault vault=LumiMemoryVault.get(activity);
        try{
            if(activity instanceof MainActivity){
                ((MainActivity)activity).diag("maintenance-tool","invoke name="+safe(name)+" tx="+tx);
                ((MainActivity)activity).traceStage("MAINTENANCE_TOOL","START","name="+safe(name)+" tx="+tx);
            }
            if("get_lumi_status".equals(name)) return getStatus(activity,prefs).toString();
            if("check_maintenance_bridge".equals(name)) return bridgeStatus(activity,prefs).toString();
            if("read_lumi_diagnostics".equals(name)) return new JSONObject().put("ok",true).put("diagnostics",readDiagnostics(activity,args.optInt("max_chars",12000))).toString();
            if("read_maintenance_history".equals(name)) return new JSONObject().put("ok",true).put("ledger",vault.recentLedger(args.optInt("limit",12))).toString();

            MaintenanceAuthorization.Decision auth=MaintenanceAuthorization.authorizeWrite(activity,prefs,currentUserText);
            if(!auth.allowed){
                vault.ledger("maintenance-denied",name,auth.reason,tx);
                return new JSONObject().put("ok",false).put("state","AUTHORIZATION_REQUIRED").put("transactionId",tx).put("reason",auth.reason).toString();
            }

            if("submit_maintenance_request".equals(name)) return submitMaintenanceRequest(activity,args,tx).toString();
            if("create_recovery_checkpoint".equals(name)){
                Bundle b=GuardianControlClient.call(activity,"create_checkpoint");
                boolean ok=b.getBoolean("ok",false);
                vault.ledger(ok?"checkpoint":"checkpoint-failed","Guardian recovery checkpoint",ok?b.getString("path",""):b.getString("error","unknown Guardian error"),tx);
                return bundleResult(b,tx).toString();
            }
            if("install_signed_update".equals(name)) return installSignedUpdate(activity,prefs,args,currentUserText,tx).toString();
            if("launch_pending_core_install".equals(name)) return launchPendingCore(activity,prefs,tx).toString();
            if("configure_trusted_build_relay".equals(name)) { Bundle e=new Bundle(); e.putString("host",safe(args.optString("host",""))); return bundleResult(GuardianControlClient.call(activity,"configure_trusted_build_relay",e),tx).toString(); }
            if("install_trusted_relay_build".equals(name)) { Bundle e=new Bundle(); e.putString("request_id",safe(args.optString("request_id",""))); e.putString("url",safe(args.optString("url",""))); e.putString("sha256",safe(args.optString("sha256",""))); e.putString("requested_change",safe(args.optString("requested_change",""))); return bundleResult(GuardianControlClient.call(activity,"install_relay_build",e),tx).toString(); }
            if("rollback_last_update".equals(name)) return rollback(activity,prefs,args.optString("reason","owner requested rollback"),tx).toString();
            return new JSONObject().put("ok",false).put("state","FORBIDDEN_OR_UNKNOWN_TOOL").put("transactionId",tx).put("error","Tool is not in Lumi's maintenance allow-list").toString();
        }catch(Exception e){
            vault.ledger("maintenance-failed",name,e.getClass().getSimpleName()+": "+safe(e.getMessage()),tx);
            try{return new JSONObject().put("ok",false).put("state","FAILED").put("transactionId",tx).put("failedStage",name).put("error",e.getClass().getSimpleName()+": "+safe(e.getMessage())).toString();}
            catch(Exception ignored){return "{\"ok\":false,\"state\":\"FAILED\"}";}
        }
    }

    private static JSONObject submitMaintenanceRequest(Activity a,JSONObject args,String tx)throws Exception{
        String requested=safe(args.optString("requested_change","")).trim();
        String type=safe(args.optString("change_type","")).trim().toLowerCase(Locale.US);
        if(requested.length()<3 || requested.length()>500) throw new SecurityException("Maintenance request must be 3-500 characters");
        if(!("diagnose".equals(type)||"runtime_tuning".equals(type)||"content_update".equals(type)||"core_update".equals(type)||"rollback".equals(type))) throw new SecurityException("Maintenance request type is not allowed");
        long created=System.currentTimeMillis();
        String nonce=UUID.randomUUID().toString();
        String canonical=tx+"|"+type+"|"+created+"|"+nonce+"|"+requested;
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        StringBuilder hs=new StringBuilder();for(byte b:md.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)))hs.append(String.format(Locale.US,"%02x",b));
        Bundle e=new Bundle();e.putString("transaction_id",tx);e.putString("change_type",type);e.putString("requested_change",requested);e.putLong("created_at",created);e.putString("nonce",nonce);e.putString("request_hash",hs.toString());
        Bundle b=GuardianControlClient.call(a,"submit_maintenance_request",e);
        JSONObject o=bundleResult(b,tx);
        if(b.getBoolean("ok",false)) LumiMemoryVault.get(a).ledger("maintenance-request",requested,"Guardian accepted queued request type="+type,tx);
        return o;
    }

    private static JSONObject bridgeStatus(Activity a,SharedPreferences p)throws Exception{ return bridgeStatus(a,p,true); }

    static JSONObject diagnosticBridgeStatus(Activity a,SharedPreferences p)throws Exception{ return bridgeStatus(a,p,false); }

    private static JSONObject bridgeStatus(Activity a,SharedPreferences p,boolean writeTrace)throws Exception{
        String tx="bridge-probe-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
        Bundle e=new Bundle(); e.putString("transaction_id",tx);
        long started=System.currentTimeMillis();
        Bundle probe=GuardianControlClient.call(a,"bridge_probe",e);
        boolean host=p.getBoolean("direct_maintenance_host_ready",false);
        long guardianVersion=probe.getLong("guardianVersionCode",GuardianBootstrap.guardianVersion(a));
        boolean guardianCurrent=guardianVersion>=11L;
        boolean guardianReachable=probe.getBoolean("guardianReachable",probe.getBoolean("ok",false));
        boolean lumiRoundTrip=probe.getBoolean("lumiRoundTrip",false);
        boolean connected=host && guardianReachable && lumiRoundTrip && guardianCurrent;
        JSONObject o=new JSONObject();
        o.put("ok",connected);
        o.put("state",connected?"BRIDGE_CONNECTED":(guardianCurrent?"BRIDGE_NOT_READY":"GUARDIAN_UPDATE_REQUIRED"));
        o.put("transactionId",tx);
        o.put("maintenanceToolHostReady",host);
        o.put("guardianReachable",guardianReachable);
        o.put("guardianRoundTrip",lumiRoundTrip);
        o.put("transportPingOk",probe.getBoolean("transportPingOk",false));
        o.put("transactionEchoOk",probe.getBoolean("transactionEchoOk",false));
        o.put("lumiIdentityOk",probe.getBoolean("lumiIdentityOk",false));
        o.put("guardianObservedMaintenanceHostReady",probe.getBoolean("maintenanceHostReady",false));
        o.put("guardianObservedLumiVersionCode",probe.getLong("lumiVersionCode",-1L));
        o.put("guardianObservedLumiVersionName",probe.getString("lumiVersionName",""));
        o.put("guardianVersionCode",guardianVersion);
        o.put("guardianVersionName",probe.getString("guardianVersionName",""));
        o.put("guardianUpdateRequired",!guardianCurrent);
        o.put("pendingRequestState",probe.getString("pending_request_state","NONE"));
        o.put("pendingRequestId",probe.getString("pending_request_id",""));
        o.put("pendingRequestType",probe.getString("pending_request_type",""));
        o.put("pendingRequestChange",probe.getString("pending_request_change",""));
        o.put("completedAt",probe.getLong("completed_at",System.currentTimeMillis()));
        o.put("roundTripMs",probe.getLong("round_trip_ms",System.currentTimeMillis()-started));
        String diagnostic;
        if(!guardianCurrent) diagnostic="Guardian code 12 or newer is required for provider-authenticated bridge verification.";
        else if(connected) diagnostic="Lumi and Guardian completed a signed same-phone bridge round trip.";
        else diagnostic=probe.getString("error",probe.getString("lumiSummary","Guardian bridge probe did not complete."));
        o.put("failedStage",probe.getString("failedStage","UNKNOWN"));
        o.put("diagnostic",diagnostic);
        if(writeTrace && a instanceof MainActivity){
            ((MainActivity)a).diag("maintenance-tool","complete name=check_maintenance_bridge tx="+tx+" ok="+connected+" state="+o.getString("state"));
            ((MainActivity)a).traceStage("MAINTENANCE_TOOL","COMPLETE","name=check_maintenance_bridge tx="+tx+" ok="+connected+" state="+o.getString("state")+" guardian="+guardianVersion+" failedStage="+o.optString("failedStage","UNKNOWN"));
        }
        return o;
    }

    private static JSONObject getStatus(Activity a,SharedPreferences p)throws Exception{
        Bundle lumi=BootstrapHealth.healthBundle(a,p);
        Bundle guardian=GuardianControlClient.call(a,"health");
        JSONObject o=new JSONObject();o.put("ok",true);o.put("lumiCertified",lumi.getBoolean("certified",false));o.put("lumiSummary",lumi.getString("summary",""));
        o.put("guardianReachable",guardian.getBoolean("ok",false));o.put("guardianCertified",guardian.getBoolean("certified",false));o.put("guardianSummary",guardian.getString("summary",guardian.getString("error","")));
        o.put("memoryVault",LumiMemoryVault.get(a).stats());o.put("nativeMaintenanceHost",p.getBoolean("direct_maintenance_host_ready",false));o.put("maintenanceRevoked",p.getBoolean("remote_maintenance_revoked",false));
        o.put("pendingCoreUpdate",LumiUpdateManager.hasPendingCoreUpdate(a,p));o.put("launchFoundationReady",p.getBoolean("lumi_1_0_foundation_ready",false)); Bundle ms=GuardianControlClient.call(a,"maintenance_status"); Bundle relay=GuardianControlClient.call(a,"trusted_build_relay_status"); o.put("trustedBuildRelayConfigured",relay.getBoolean("configured",false)); o.put("trustedBuildRelayHost",relay.getString("host","")); o.put("trustedBuildRelayState",relay.getString("last_state","NONE")); o.put("guardianMaintenanceState", ms.getString("last_install_message", "")); o.put("guardianCertificationPending", ms.getBoolean("certification_pending", false)); o.put("guardianLastTarget", ms.getLong("last_install_target", -1)); o.put("guardianPendingRequest",ms.getString("pending_request_change", "")); o.put("guardianPendingRequestType",ms.getString("pending_request_type", "")); o.put("guardianPendingRequestId",ms.getString("pending_request_id", "")); return o;
    }

    private static JSONObject installSignedUpdate(Activity a,SharedPreferences p,JSONObject args,String userText,String tx)throws Exception{
        String url=args.optString("url","").trim();String requested=args.optString("requested_change","Lumi update").trim();String expected=args.optString("sha256","").trim().toLowerCase(Locale.US);
        if(!url.toLowerCase(Locale.US).startsWith("https://")) throw new SecurityException("Maintenance update downloads require HTTPS");
        if(!expected.isEmpty()&&!expected.matches("[0-9a-f]{64}"))throw new SecurityException("Expected SHA-256 is malformed");
        LumiMemoryVault vault=LumiMemoryVault.get(a);
        vault.ledger("maintenance-start",requested,"Signed update transaction started.",tx);

        Bundle checkpoint=GuardianControlClient.call(a,"create_checkpoint");
        if(!checkpoint.getBoolean("ok",false)) throw new SecurityException("Guardian checkpoint failed: "+checkpoint.getString("error","unknown error"));
        File dir=new File(a.getCacheDir(),"lumi_maintenance");if(!dir.exists())dir.mkdirs();File zip=new File(dir,tx+".zip");
        downloadHttps(url,zip,LumiUpdateManager.MAX_IMPORT_BYTES);
        String actual=sha256(zip);if(!expected.isEmpty()&&!expected.equals(actual))throw new SecurityException("Downloaded update SHA-256 does not match expected value");

        final StringBuilder progress=new StringBuilder();
        LumiUpdateManager.Result r=LumiUpdateManager.applyTrustedPackageBlocking(a,p,zip,new LumiUpdateManager.Listener(){
            @Override public void onProgress(String m){ if(progress.length()<4000)progress.append(m).append(" | "); }
            @Override public void onComplete(LumiUpdateManager.Result result){}
            @Override public void onError(String message){}
        });
        zip.delete();
        if(r.coreInstallReady){
            vault.ledger("maintenance-staged",requested,"Verified core APK staged; Android install approval is required.",tx);
            return new JSONObject().put("ok",true).put("state","WAITING_FOR_USER_APPROVAL").put("transactionId",tx).put("requestedChange",requested).put("updateId",r.updateId).put("resultingVersion",r.version).put("installStatus","VERIFIED_CORE_STAGED").put("guardianVerification",true).put("rollbackCheckpoint",checkpoint.getString("path","")).put("userActionRequired","Android package installer approval").put("progress",progress.toString());
        }

        Bundle cert=GuardianControlClient.call(a,"certify");
        boolean certified=cert.getBoolean("certified",false) && cert.getBoolean("probe_passed",cert.getBoolean("certified",false));
        if(!certified){
            String rolled="none";
            try{if(LumiUpdateManager.hasRollbackPoint(p))rolled=LumiUpdateManager.rollbackLastContentUpdate(a,p);}catch(Exception ignored){}
            try{GuardianControlClient.call(a,"restore_latest_checkpoint");}catch(Exception ignored){}
            vault.ledger("maintenance-rollback",requested,"Post-update certification failed; rollback="+rolled+"; "+cert.getString("summary",cert.getString("error","")),tx);
            return new JSONObject().put("ok",false).put("state","FAILED").put("transactionId",tx).put("requestedChange",requested).put("failedStage","health_check").put("postUpdateCertification",false).put("rollbackStatus",rolled).put("diagnostic",cert.getString("summary",cert.getString("error","Guardian certification failed")));
        }
        vault.ledger("maintenance-success",requested,"Content update applied and Guardian certification passed. updateId="+r.updateId,tx);
        return new JSONObject().put("ok",true).put("state","SUCCESS").put("transactionId",tx).put("requestedChange",requested).put("updateId",r.updateId).put("resultingVersion",r.version).put("installStatus","APPLIED").put("guardianVerification",true).put("postUpdateCertification",true).put("rollbackCheckpoint",checkpoint.getString("path","")).put("completedAt",System.currentTimeMillis());
    }

    private static JSONObject launchPendingCore(Activity a,SharedPreferences p,String tx)throws Exception{
        if(!LumiUpdateManager.hasPendingCoreUpdate(a,p))return new JSONObject().put("ok",false).put("state","NO_PENDING_CORE_UPDATE").put("transactionId",tx);
        CountDownLatch latch=new CountDownLatch(1);final boolean[] launched={false};final String[] err={""};
        a.runOnUiThread(()->{try{launched[0]=LumiUpdateManager.launchPendingCoreInstaller(a,p);}catch(Exception e){err[0]=e.getClass().getSimpleName()+": "+safe(e.getMessage());}finally{latch.countDown();}});
        latch.await(8,TimeUnit.SECONDS);
        LumiMemoryVault.get(a).ledger("core-install-request","Android core installer requested",launched[0]?"Installer launched":"Installer permission/settings flow opened or failed: "+err[0],tx);
        return new JSONObject().put("ok",err[0].isEmpty()).put("state",launched[0]?"ANDROID_INSTALLER_LAUNCHED":"WAITING_FOR_ANDROID_PERMISSION_OR_APPROVAL").put("transactionId",tx).put("error",err[0]);
    }

    private static JSONObject rollback(Activity a,SharedPreferences p,String reason,String tx)throws Exception{
        if(!LumiUpdateManager.hasRollbackPoint(p))return new JSONObject().put("ok",false).put("state","NO_ROLLBACK_POINT").put("transactionId",tx);
        String id=LumiUpdateManager.rollbackLastContentUpdate(a,p);Bundle cert=GuardianControlClient.call(a,"certify");boolean ok=cert.getBoolean("certified",false);
        LumiMemoryVault.get(a).ledger("rollback",reason,"Rolled back update="+id+"; certified="+ok,tx);
        return new JSONObject().put("ok",ok).put("state",ok?"SUCCESS":"ROLLED_BACK_BUT_CERTIFICATION_FAILED").put("transactionId",tx).put("rolledBackUpdate",id).put("postRollbackCertification",ok).put("guardianSummary",cert.getString("summary",cert.getString("error","")));
    }

    private static JSONObject bundleResult(Bundle b,String tx)throws Exception{JSONObject o=new JSONObject();o.put("ok",b.getBoolean("ok",false));o.put("transactionId",tx);for(String k:b.keySet()){Object v=b.get(k);if(v instanceof String||v instanceof Boolean||v instanceof Integer||v instanceof Long||v instanceof Double||v instanceof Float)o.put(k,v);}return o;}

    private static String readDiagnostics(Activity a,int maxChars)throws Exception{File f=new File(a.getFilesDir(),"lumi-diagnostics.log");if(!f.isFile())return "No diagnostics have been recorded yet.";int cap=Math.max(1000,Math.min(16000,maxChars));try(RandomAccessFile r=new RandomAccessFile(f,"r")){long start=Math.max(0,r.length()-cap*2L);r.seek(start);byte[] b=new byte[(int)Math.min(r.length()-start,cap*2L)];r.readFully(b);String s=new String(b,java.nio.charset.StandardCharsets.UTF_8);if(s.length()>cap)s=s.substring(s.length()-cap);return redact(s);}}
    private static String redact(String s){if(s==null)return "";return s.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]{12,}","Bearer [REDACTED]").replaceAll("(?i)(api[_ -]?key|token|password)\\s*[:=]\\s*[^\\s,;]{8,}","$1=[REDACTED]").replaceAll("github_pat_[A-Za-z0-9_]{10,}","[REDACTED_GITHUB_TOKEN]");}
    private static void downloadHttps(String urlText,File out,long max)throws Exception{String cur=urlText;for(int redirects=0;redirects<6;redirects++){URL u=new URL(cur);if(!"https".equalsIgnoreCase(u.getProtocol()))throw new SecurityException("Maintenance redirects must remain HTTPS");HttpURLConnection c=(HttpURLConnection)u.openConnection();try{c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setRequestProperty("User-Agent","Lumi-1.0-MaintenanceHost");int code=c.getResponseCode();if(code>=300&&code<400){String loc=c.getHeaderField("Location");if(loc==null)throw new java.io.IOException("Update redirect had no Location");cur=new URL(u,loc).toString();continue;}if(code<200||code>=300)throw new java.io.IOException("Update download returned HTTP "+code);try(BufferedInputStream in=new BufferedInputStream(c.getInputStream());BufferedOutputStream os=new BufferedOutputStream(new FileOutputStream(out))){byte[] b=new byte[65536];long total=0;int n;while((n=in.read(b))>0){total+=n;if(total>max)throw new java.io.IOException("Update package exceeds allowed size");os.write(b,0,n);}}return;}finally{c.disconnect();}}throw new java.io.IOException("Too many HTTPS redirects while downloading update");}
    private static String sha256(File f)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(java.io.InputStream in=new java.io.FileInputStream(f)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))>0)md.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.US,"%02x",b));return s.toString();}
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}
}
