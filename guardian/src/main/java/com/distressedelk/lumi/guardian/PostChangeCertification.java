package com.distressedelk.lumi.guardian;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * Guardian-owned post-change certification runner.
 *
 * The important trust rule is that Guardian asks Lumi's signature-protected bridge for the
 * actual health/certification result. A model, relay, or update package cannot declare itself
 * healthy. Guardian persists the result so Lumi can report it conversationally later.
 */
public final class PostChangeCertification {
    private PostChangeCertification() {}

    public static Bundle run(Context context, String reason, long targetVersion) {
        SharedPreferences p=context.getSharedPreferences("guardian", Context.MODE_PRIVATE);
        long started=System.currentTimeMillis();
        p.edit().putBoolean("certification_pending", true)
                .putString("certification_reason", safe(reason))
                .putLong("certification_target", targetVersion)
                .putLong("certification_started_at", started).apply();
        GuardianLedger.append(context,"POST_CHANGE_CERT_START","reason="+safe(reason)+" target="+targetVersion);

        Bundle last=new Bundle();
        for(int attempt=1;attempt<=3;attempt++) {
            last=GuardianBridgeClient.call(context,"certify");
            boolean pass=last.getBoolean("ok",false)
                    && last.getBoolean("certified",false)
                    && last.getBoolean("probe_passed", last.getBoolean("certified",false));
            GuardianLedger.append(context, pass?"POST_CHANGE_CERT_ATTEMPT_PASS":"POST_CHANGE_CERT_ATTEMPT_FAIL",
                    "attempt="+attempt+" target="+targetVersion+" "+last.toString());
            if(pass) {
                long now=System.currentTimeMillis();
                p.edit().putBoolean("certification_pending",false)
                        .putBoolean("last_certification_pass",true)
                        .putLong("last_certification_at",now)
                        .putLong("last_certified_version",last.getLong("version_code",targetVersion))
                        .putInt("last_certification_attempts",attempt)
                        .putString("last_certification_summary",last.getString("summary","Certification passed"))
                        .putString("last_certification_probe",last.getString("probe_result",""))
                        .putString("pending_request_state","CERTIFIED")
                        .apply();
                Bundle out=new Bundle(last);out.putBoolean("ok",true);out.putBoolean("certified",true);out.putInt("attempts",attempt);out.putLong("completed_at",now);return out;
            }
            if(attempt<3) try { Thread.sleep(attempt==1?1200L:2500L); } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        long now=System.currentTimeMillis();
        String summary=last.getString("summary",last.getString("error","Certification failed"));
        p.edit().putBoolean("certification_pending",true)
                .putBoolean("last_certification_pass",false)
                .putLong("last_certification_at",now)
                .putInt("last_certification_attempts",3)
                .putString("last_certification_summary",summary)
                .putString("last_certification_probe",last.getString("probe_result",""))
                .putString("pending_request_state","CERTIFICATION_FAILED")
                .apply();
        GuardianLedger.append(context,"POST_CHANGE_CERT_FAIL","target="+targetVersion+" summary="+summary);
        Bundle out=new Bundle(last);out.putBoolean("ok",false);out.putBoolean("certified",false);out.putInt("attempts",3);out.putLong("completed_at",now);return out;
    }

    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}
}
