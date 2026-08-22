package com.distressedelk.lumi;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Signature-protected round trip through the separate Guardian application. */
final class GuardianControlClient {
    private static final Uri URI=Uri.parse("content://com.distressedelk.lumi.guardian.control");
    private GuardianControlClient(){}
    static Bundle call(Context context,String method){ return call(context,method,null); }
    static Bundle call(Context context,String method,Bundle extras){
        try{
            Bundle b=context.getContentResolver().call(URI,method,null,extras);
            if(b!=null)return b;
        }catch(Exception e){ Bundle b=new Bundle();b.putBoolean("ok",false);b.putString("error",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));return b; }
        Bundle b=new Bundle();b.putBoolean("ok",false);b.putString("error","Guardian returned no result");return b;
    }
}
