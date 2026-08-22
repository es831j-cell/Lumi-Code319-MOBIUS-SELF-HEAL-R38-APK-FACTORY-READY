package com.distressedelk.lumi;

import android.app.*;
import android.content.*;
import android.os.*;

public class LumiCoreService extends Service {
    static final String CHANNEL="lumi_core_silent_v2";
    @Override public void onCreate(){
        super.onCreate();
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(CHANNEL,"Lumi continuity",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps Lumi's logical session available for device handoff.");
            ch.setSound(null,null);
            ch.enableVibration(false);
            ch.setVibrationPattern(null);
            NotificationManager nm=getSystemService(NotificationManager.class); if(nm!=null)nm.createNotificationChannel(ch);
        }
        Intent open=new Intent(this,MainActivity.class);
        open.putExtra(MainActivity.EXTRA_AUTO_LISTEN,true);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setContentTitle("Lumi is available")
         .setContentText("Tap to talk • hands-free listening")
         .setSmallIcon(android.R.drawable.ic_btn_speak_now)
         .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi);
        startForeground(1401,b.build());
        getSharedPreferences("lumi",MODE_PRIVATE).edit().putLong("core_last_started",System.currentTimeMillis()).apply();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_STICKY;}
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
