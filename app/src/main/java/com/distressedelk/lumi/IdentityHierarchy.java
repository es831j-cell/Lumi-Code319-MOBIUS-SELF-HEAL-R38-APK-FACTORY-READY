package com.distressedelk.lumi;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.UUID;

/** Code316 identity hierarchy and provisional-contact registry.
 * Root authority is singular. New people begin with zero privileged permissions.
 * Relationship/permission review is intentionally deferred until the administrator is alone.
 */
final class IdentityHierarchy {
    static final long ADMIN_SESSION_MS = 10L * 60L * 1000L;
    private static final String ADMIN_PHRASE = "there can be only one";
    private IdentityHierarchy(){}

    static boolean isAdminPhrase(String raw){
        String s=raw==null?"":raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]"," ").replaceAll("\\s+"," ").trim();
        return s.equals(ADMIN_PHRASE) || s.equals("lumi "+ADMIN_PHRASE);
    }

    static boolean openAdminSession(SharedPreferences prefs){
        if(!prefs.getBoolean("admin_enrollment_complete",false)) return false;
        long until=System.currentTimeMillis()+ADMIN_SESSION_MS;
        prefs.edit().putLong("root_admin_session_until",until)
                .putLong("root_admin_last_verified_at",System.currentTimeMillis())
                .putString("root_admin_authority","SOLE_ROOT_ADMIN")
                .apply();
        return true;
    }

    static boolean adminSessionActive(SharedPreferences prefs){
        return prefs.getBoolean("admin_enrollment_complete",false)
                && System.currentTimeMillis()<=prefs.getLong("root_admin_session_until",0L);
    }

    static String createProvisionalContact(SharedPreferences prefs,String displayName,String source){
        String name=displayName==null?"":displayName.trim();
        if(name.isEmpty()) name="New person";
        try{
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            JSONObject c=new JSONObject();
            c.put("id",UUID.randomUUID().toString());
            c.put("displayName",name);
            c.put("state","PROVISIONAL");
            c.put("relationship","UNREVIEWED");
            c.put("permissionLevel","NONE");
            c.put("privileged",false);
            c.put("source",source==null?"conversation":source);
            c.put("firstMetAt",System.currentTimeMillis());
            c.put("needsPrivateReview",true);
            a.put(c);
            prefs.edit().putString("identity_contacts_json",a.toString())
                    .putBoolean("identity_private_review_pending",true)
                    .putString("identity_private_review_contact_id",c.getString("id"))
                    .putString("identity_private_review_name",name)
                    .apply();
            return c.getString("id");
        }catch(Exception e){ return ""; }
    }

    static String pendingPrivateReviewPrompt(SharedPreferences prefs){
        if(!prefs.getBoolean("identity_private_review_pending",false)) return null;
        if(!adminSessionActive(prefs)) return null;
        String n=prefs.getString("identity_private_review_name","that person");
        return "When we're alone, I still need to privately review "+n+" with you: relationship, trust level, and permissions. Until then, they have no privileged access.";
    }

    static boolean updatePendingReview(SharedPreferences prefs,String relationship,String permission){
        String id=prefs.getString("identity_private_review_contact_id","");
        if(id.isEmpty() || !adminSessionActive(prefs)) return false;
        try{
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            for(int i=0;i<a.length();i++){
                JSONObject c=a.optJSONObject(i); if(c==null || !id.equals(c.optString("id"))) continue;
                if(relationship!=null && !relationship.trim().isEmpty()) c.put("relationship",relationship.trim());
                if(permission!=null && !permission.trim().isEmpty()){
                    String level=permission.trim().toUpperCase(Locale.US).replace(' ','_');
                    if(level.equals("ROOT") || level.equals("ADMIN") || level.equals("ROOT_ADMIN")) level="NONE";
                    c.put("permissionLevel",level);
                    c.put("privileged",!level.equals("NONE"));
                }
                if(!"UNREVIEWED".equals(c.optString("relationship")) && permission!=null){
                    c.put("state","CONFIRMED"); c.put("needsPrivateReview",false);
                    prefs.edit().putBoolean("identity_private_review_pending",false)
                            .remove("identity_private_review_contact_id").remove("identity_private_review_name").apply();
                }
                prefs.edit().putString("identity_contacts_json",a.toString()).apply();
                return true;
            }
        }catch(Exception ignored){}
        return false;
    }

    static String contactSummary(SharedPreferences prefs){
        try{
            JSONArray a=new JSONArray(prefs.getString("identity_contacts_json","[]"));
            int provisional=0,privileged=0;
            for(int i=0;i<a.length();i++){
                JSONObject c=a.optJSONObject(i); if(c==null)continue;
                if("PROVISIONAL".equals(c.optString("state"))) provisional++;
                if(c.optBoolean("privileged",false)) privileged++;
            }
            return "Identity hierarchy: one root administrator, "+a.length()+" contact card"+(a.length()==1?"":"s")+", "+provisional+" awaiting private review, "+privileged+" non-root privileged contacts.";
        }catch(Exception e){ return "Identity hierarchy is initialized with one root administrator."; }
    }
}
