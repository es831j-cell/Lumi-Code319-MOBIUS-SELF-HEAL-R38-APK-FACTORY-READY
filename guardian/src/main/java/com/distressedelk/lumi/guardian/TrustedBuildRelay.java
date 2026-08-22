package com.distressedelk.lumi.guardian;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Phase 6 trusted build/package relay receiver.
 *
 * Security model:
 *  - Guardian accepts artifacts only for the currently queued maintenance transaction.
 *  - Relay transport must be HTTPS and the hostname must exactly match the owner-enrolled host.
 *  - Caller must supply a SHA-256 digest for the artifact.
 *  - Guardian independently verifies the downloaded APK is the Lumi package, is signed by the
 *    already installed Lumi signing identity, and is a forward version before installation.
 *  - A relay therefore transports bytes; it never gains install authority on its own.
 */
final class TrustedBuildRelay {
    private static final String PREF="guardian";
    private static final int CONNECT_TIMEOUT_MS=12000;
    private static final int READ_TIMEOUT_MS=45000;
    private TrustedBuildRelay(){}

    static Bundle configure(Context context,String host){
        Bundle out=new Bundle();
        try{
            String h=normalizeHost(host);
            context.getSharedPreferences(PREF,0).edit()
                    .putString("trusted_relay_host",h)
                    .putLong("trusted_relay_enrolled_at",System.currentTimeMillis())
                    .apply();
            GuardianLedger.append(context,"RELAY_TRUST_ENROLLED","host="+h);
            out.putBoolean("ok",true); out.putString("state","RELAY_TRUST_ENROLLED"); out.putString("host",h);
        }catch(Exception e){out.putBoolean("ok",false);out.putString("state","RELAY_TRUST_REJECTED");out.putString("error",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));}
        return out;
    }

    static Bundle status(Context context){
        SharedPreferences p=context.getSharedPreferences(PREF,0); Bundle b=new Bundle();
        String host=p.getString("trusted_relay_host","");
        b.putBoolean("ok",true); b.putBoolean("configured",!host.isEmpty()); b.putString("host",host);
        b.putLong("enrolled_at",p.getLong("trusted_relay_enrolled_at",0L));
        b.putString("last_state",p.getString("relay_last_state","NONE"));
        b.putString("last_sha256",p.getString("relay_last_sha256",""));
        b.putString("last_request_id",p.getString("relay_last_request_id",""));
        b.putLong("last_at",p.getLong("relay_last_at",0L));
        return b;
    }

    static Bundle receiveAndInstall(Context context,String requestId,String urlText,String expectedSha,String requestedChange)throws Exception{
        SharedPreferences p=context.getSharedPreferences(PREF,0);
        String pending=p.getString("pending_request_id","");
        if(requestId==null || requestId.trim().isEmpty() || !requestId.trim().equals(pending))
            throw new SecurityException("Relay artifact does not match the currently queued maintenance request");
        String pendingType=p.getString("pending_request_type","");
        if(!("core_update".equals(pendingType)||"content_update".equals(pendingType)||"runtime_tuning".equals(pendingType)))
            throw new SecurityException("Queued maintenance request is not eligible for a relay build artifact");
        String expected=expectedSha==null?"":expectedSha.trim().toLowerCase(Locale.US);
        if(!expected.matches("[0-9a-f]{64}")) throw new SecurityException("Relay artifact requires an exact SHA-256 digest");
        URL url=validatedUrl(p,urlText);
        File dir=new File(context.getFilesDir(),"trusted-relay");
        if(!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("Could not create trusted relay staging folder");
        File apk=new File(dir,"lumi-relay-"+System.currentTimeMillis()+".apk");
        String actual=download(url,apk);
        if(!expected.equals(actual)){apk.delete();throw new SecurityException("Relay artifact SHA-256 mismatch");}
        p.edit().putString("relay_last_state","ARTIFACT_HASH_VERIFIED").putString("relay_last_sha256",actual).putString("relay_last_request_id",requestId).putLong("relay_last_at",System.currentTimeMillis()).apply();
        GuardianLedger.append(context,"RELAY_ARTIFACT_HASH_VERIFIED","request="+requestId+" sha256="+actual+" host="+url.getHost());

        GuardianVerifier.VerifiedApk verified=GuardianVerifier.verifyLumiApk(context,apk,true);
        Bundle checkpoint=GuardianBridgeClient.call(context,"create_checkpoint");
        if(!checkpoint.getBoolean("ok",false)){apk.delete();throw new SecurityException("Guardian checkpoint failed: "+checkpoint.getString("error","unknown"));}
        int session=GuardianInstaller.installLumi(context,apk,verified,false);
        String change=(requestedChange==null||requestedChange.trim().isEmpty())?p.getString("pending_request_change","relay core update"):requestedChange.trim();
        p.edit().putString("relay_last_state","INSTALL_SUBMITTED")
                .putString("pending_request_state","RELAY_INSTALL_SUBMITTED")
                .putString("last_bridge_request",change).putString("last_bridge_sha256",actual)
                .putLong("last_bridge_at",System.currentTimeMillis()).apply();
        GuardianLedger.append(context,"RELAY_INSTALL_SUBMITTED","request="+requestId+" session="+session+" target="+verified.versionCode+" sha256="+actual);
        Bundle out=new Bundle(); out.putBoolean("ok",true); out.putString("state","INSTALL_SUBMITTED");
        out.putInt("session_id",session); out.putLong("target_version",verified.versionCode); out.putString("target_name",verified.versionName);
        out.putString("sha256",actual); out.putString("request_id",requestId); out.putString("checkpoint",checkpoint.getString("path",""));
        return out;
    }

    private static URL validatedUrl(SharedPreferences p,String text)throws Exception{
        String trusted=p.getString("trusted_relay_host",""); if(trusted.isEmpty()) throw new SecurityException("No trusted build relay host is enrolled");
        URI uri=new URI(text==null?"":text.trim());
        if(!"https".equalsIgnoreCase(uri.getScheme())) throw new SecurityException("Trusted relay requires HTTPS");
        if(uri.getUserInfo()!=null) throw new SecurityException("Relay URL user-info is not allowed");
        String host=uri.getHost(); if(host==null || !host.equalsIgnoreCase(trusted)) throw new SecurityException("Relay URL host is not the enrolled trusted host");
        int port=uri.getPort(); if(port!=-1 && port!=443) throw new SecurityException("Trusted relay only allows the default HTTPS port");
        return uri.toURL();
    }

    private static String normalizeHost(String input)throws Exception{
        String h=input==null?"":input.trim().toLowerCase(Locale.US);
        if(h.startsWith("https://")){URI u=new URI(h); h=u.getHost()==null?"":u.getHost().toLowerCase(Locale.US);}
        if(!h.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) throw new SecurityException("Invalid relay hostname");
        if(h.contains("..")||h.equals("localhost")||h.endsWith(".local")) throw new SecurityException("Relay host must be a stable HTTPS hostname");
        return h;
    }

    private static String download(URL url,File dest)throws Exception{
        HttpURLConnection c=(HttpURLConnection)url.openConnection(); c.setInstanceFollowRedirects(false); c.setConnectTimeout(CONNECT_TIMEOUT_MS); c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Accept","application/vnd.android.package-archive,application/octet-stream"); c.setRequestProperty("User-Agent","Lumi-Guardian-Trusted-Relay/1");
        try{
            int code=c.getResponseCode(); if(code<200||code>=300) throw new java.io.IOException("Relay HTTP "+code);
            long declared=c.getContentLengthLong(); if(declared>GuardianInstaller.MAX_APK_BYTES) throw new SecurityException("Relay APK exceeds Guardian size limit");
            MessageDigest md=MessageDigest.getInstance("SHA-256"); long total=0;
            try(BufferedInputStream in=new BufferedInputStream(c.getInputStream()); BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
                byte[] buf=new byte[1024*256]; int n; while((n=in.read(buf))>0){total+=n;if(total>GuardianInstaller.MAX_APK_BYTES)throw new SecurityException("Relay APK exceeds Guardian size limit");md.update(buf,0,n);out.write(buf,0,n);} out.flush();
            }
            if(total<4096L) throw new SecurityException("Relay artifact is too small to be a valid APK");
            StringBuilder hs=new StringBuilder();for(byte b:md.digest())hs.append(String.format(Locale.US,"%02x",b));return hs.toString();
        }finally{c.disconnect();}
    }
}
