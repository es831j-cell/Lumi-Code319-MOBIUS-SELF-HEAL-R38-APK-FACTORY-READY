package com.distressedelk.lumi;

import android.app.Activity;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Lumi 1.0 OpenAI Responses client with a bounded local tool loop.
 * Secrets are used only in the HTTPS Authorization header and are never placed in prompts,
 * Memory Vault entries, function outputs, diagnostics, or Technical Ledger records.
 */
final class OpenAIReasoningClient {
    interface Callback { void onSuccess(String reply,String responseId); void onFailure(String error); }
    private OpenAIReasoningClient(){}

    static void request(Activity activity, SharedPreferences prefs, String apiKey, String model,
                        String instructions, String presence, String recentTranscript,
                        String userText, String previousResponseId, Callback callback) {
        new Thread(()->{
            try{
                boolean maintenance=maintenanceIntent(userText,prefs);
                String memory=LumiMemoryVault.get(activity).contextPacket(userText,false,maintenance?6500:2600);
                String recent=recentTranscript==null?"":recentTranscript;
                if(recent.length()>(maintenance?4200:1600))recent=recent.substring(recent.length()-(maintenance?4200:1600));
                String input=presence+"\n"+memory+"\n"+(recent.trim().isEmpty()?"":"Recent active-session transcript:\n"+recent+"\n")+"Current user message: "+userText;
                // Code302: bridge-status questions perform a deterministic local Guardian probe
                // before OpenAI is asked to explain the result. This removes reliance on the model
                // choosing the check_maintenance_bridge tool merely because the tool is available.
                if(maintenance && bridgeStatusIntent(userText)){
                    String bridgeProbe=LumiMaintenanceTools.execute(activity,prefs,"check_maintenance_bridge",new JSONObject(),userText);
                    input += "\nLive same-phone maintenance bridge probe (authoritative): "+bridgeProbe;
                }
                JSONObject first=new JSONObject();first.put("model",model);first.put("instructions",instructions);first.put("input",input);first.put("max_output_tokens",maintenance?650:280);
                if(maintenance)first.put("tools",LumiMaintenanceTools.definitions());
                if(previousResponseId!=null&&!previousResponseId.trim().isEmpty())first.put("previous_response_id",previousResponseId);
                JSONObject response=post(apiKey,first);String responseId=response.optString("id",previousResponseId==null?"":previousResponseId);

                // Bound tool recursion. Tools themselves have strict scopes and independent write gates.
                if(!maintenance){String text=outputText(response);if(text.trim().isEmpty())text="I got a response, but there wasn't any readable text in it.";callback.onSuccess(text.trim(),responseId);return;}
                for(int round=0;round<4;round++){
                    JSONArray calls=functionCalls(response);if(calls.length()==0){String text=outputText(response);if(text.trim().isEmpty())text="I got a response, but there wasn't any readable text in it.";callback.onSuccess(text.trim(),responseId);return;}
                    JSONArray outputs=new JSONArray();
                    for(int i=0;i<calls.length();i++){
                        JSONObject call=calls.getJSONObject(i);String callId=call.optString("call_id","");String name=call.optString("name","");String rawArgs=call.optString("arguments","{}");JSONObject args;try{args=new JSONObject(rawArgs);}catch(Exception e){args=new JSONObject();}
                        String result=LumiMaintenanceTools.execute(activity,prefs,name,args,userText);
                        outputs.put(new JSONObject().put("type","function_call_output").put("call_id",callId).put("output",result));
                    }
                    JSONObject follow=new JSONObject();follow.put("model",model);follow.put("instructions",instructions);follow.put("input",outputs);follow.put("max_output_tokens",650);follow.put("tools",LumiMaintenanceTools.definitions());if(responseId!=null&&!responseId.isEmpty())follow.put("previous_response_id",responseId);
                    response=post(apiKey,follow);responseId=response.optString("id",responseId);
                }
                callback.onFailure("OpenAI maintenance tool loop exceeded Lumi's four-round safety limit.");
            }catch(Exception e){callback.onFailure(e.getClass().getSimpleName()+": "+safe(e.getMessage()));}
        },"LumiOpenAIReasoning").start();
    }

    private static JSONObject post(String apiKey,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("https://api.openai.com/v1/responses").openConnection();
        try{
            c.setRequestMethod("POST");c.setConnectTimeout(9000);c.setReadTimeout(35000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer "+apiKey);
            try(OutputStream os=c.getOutputStream()){os.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            int code=c.getResponseCode();InputStream is=(code>=200&&code<300)?c.getInputStream():c.getErrorStream();String raw=readAll(is);
            if(code<200||code>=300)throw new java.io.IOException("OpenAI returned HTTP "+code+": "+friendlyApiError(raw));
            return new JSONObject(raw);
        }finally{c.disconnect();}
    }




    private static boolean bridgeStatusIntent(String userText){
        String s=userText==null?"":userText.toLowerCase(java.util.Locale.US).trim();
        boolean bridge=s.contains("maintenance bridge")||s.contains("guardian bridge")||s.contains("bridge connection")||s.contains("guardian connection")||s.contains("make the connection")||s.contains("connect the bridge")||s.contains("connect guardian");
        boolean status=s.contains("working")||s.contains("connected")||s.contains("connection")||s.contains("status")||s.contains("check")||s.contains("verify")||s.contains("test")||s.startsWith("is ")||s.startsWith("are ")||s.startsWith("can ");
        return bridge && status;
    }

    private static boolean maintenanceIntent(String userText, SharedPreferences prefs){
        String s=userText==null?"":userText.toLowerCase(java.util.Locale.US).trim();
        String[] keys={"diagnostic","update lumi","update yourself","repair","fix lumi","maintenance","maintenance bridge","bridge","guardian","install update","rollback","certification","health check","technical ledger","permission","configure lumi","change setting","developer issue","connect guardian","connect the guardian","make the connection","make connection","connect the bridge","bridge connection"};
        for(String k:keys)if(s.contains(k))return true;

        // Code301: maintenance conversations are often elliptical follow-ups. If Lumi just
        // discussed Guardian/the bridge, phrases such as "make the connection" or "do it"
        // must keep the bounded maintenance tool set attached to the OpenAI request.
        String last=prefs==null?"":prefs.getString("last_lumi_reply","").toLowerCase(java.util.Locale.US);
        boolean maintenanceContext=last.contains("maintenance")||last.contains("guardian")||last.contains("bridge")||last.contains("update")||last.contains("repair")||last.contains("fix");
        if(maintenanceContext){
            if(s.matches(".*\\b(do it|go ahead|proceed|approved|approve it|fix it|apply it|make the change|make the connection|connect it|connect them|build it)\\b.*")) return true;
            if(s.contains("connection")||s.contains("connect")) return true;
        }
        return false;
    }

    private static JSONArray functionCalls(JSONObject response)throws Exception{JSONArray out=new JSONArray();JSONArray items=response.optJSONArray("output");if(items==null)return out;for(int i=0;i<items.length();i++){JSONObject x=items.optJSONObject(i);if(x!=null&&"function_call".equals(x.optString("type")))out.put(x);}return out;}
    private static String outputText(JSONObject response){StringBuilder out=new StringBuilder();JSONArray arr=response.optJSONArray("output");if(arr==null)return "";for(int i=0;i<arr.length();i++){JSONObject item=arr.optJSONObject(i);if(item==null)continue;JSONArray c=item.optJSONArray("content");if(c==null)continue;for(int j=0;j<c.length();j++){JSONObject p=c.optJSONObject(j);if(p!=null&&"output_text".equals(p.optString("type"))){String t=p.optString("text","");if(!t.isEmpty()){if(out.length()>0)out.append('\n');out.append(t);}}}}return out.toString();}
    private static String friendlyApiError(String raw){try{JSONObject j=new JSONObject(raw);JSONObject e=j.optJSONObject("error");if(e!=null)return safe(e.optString("message",raw));}catch(Exception ignored){}return raw==null?"":(raw.length()>400?raw.substring(0,400):raw);}
    private static String readAll(InputStream is)throws Exception{if(is==null)return "";try(BufferedReader r=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){StringBuilder s=new StringBuilder();String line;while((line=r.readLine())!=null)s.append(line);return s.toString();}}
    private static String safe(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();}
}
