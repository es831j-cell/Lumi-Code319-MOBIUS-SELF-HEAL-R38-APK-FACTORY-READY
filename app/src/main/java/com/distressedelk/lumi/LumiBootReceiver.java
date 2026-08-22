package com.distressedelk.lumi;

import android.content.*;
import android.os.Build;

public class LumiBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent){
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())){
            Intent s=new Intent(context,LumiCoreService.class);
            try{ if(Build.VERSION.SDK_INT>=26)context.startForegroundService(s); else context.startService(s); }catch(Exception ignored){}
        }
    }
}
