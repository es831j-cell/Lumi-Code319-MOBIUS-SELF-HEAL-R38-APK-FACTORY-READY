package com.distressedelk.lumi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Code318: battery-gated continuous self-optimization loop. */
public final class EvolutionEngine {
    private static final long CYCLE_MS = 15L * 60L * 1000L;
    private static final String ACTION_CYCLE = "com.distressedelk.lumi.EVOLUTION_CYCLE";
    private EvolutionEngine() {}

    public static String manualOptimize(Context c, SharedPreferences p, String target) {
        p.edit().putBoolean("evolution_manual_active", true).apply();
        return runCycle(c,p,target == null ? "everything" : target,true);
    }

    public static void bootstrap(Context c, SharedPreferences p) {
        if (!p.contains("overnight_maintenance")) p.edit().putBoolean("overnight_maintenance",true).apply();
        if (p.getBoolean("overnight_maintenance",true) && isChargingAndFull(c)) scheduleNext(c, 5000L);
    }

    public static void onPowerStateChanged(Context c) {
        SharedPreferences p=c.getSharedPreferences("lumi",Context.MODE_PRIVATE);
        if (p.getBoolean("overnight_maintenance",true) && isChargingAndFull(c)) {
            p.edit().putBoolean("evolution_overnight_active",true).putLong("evolution_overnight_started_at",System.currentTimeMillis()).apply();
            runCycle(c,p,"everything",false);
        } else {
            p.edit().putBoolean("evolution_overnight_active",false).apply();
            cancel(c);
        }
    }

    public static String runCycle(Context c, SharedPreferences p, String target, boolean manual) {
        final long now=System.currentTimeMillis();
        boolean overnight=p.getBoolean("overnight_maintenance",true) && isChargingAndFull(c);
        if (!manual && !overnight) {
            p.edit().putBoolean("evolution_overnight_active",false).apply();
            cancel(c);
            return "Overnight optimization is paused until Lumi is charging at 100%.";
        }

        long cycle=p.getLong("evolution_cycle_count",0L)+1L;
        long latency=p.getLong("last_response_latency_ms",-1L);
        int speechRebuilds=p.getInt("speech_recognizer_rebuilds",0);
        int ttsRecoveries=p.getInt("tts_watchdog_recoveries",0);
        long animAge=p.getLong("mobius_last_frame_age_ms",-1L);
        float animFps=p.getFloat("mobius_last_fps",-1f);
        String focus=chooseFocus(target,latency,speechRebuilds,ttsRecoveries,animAge,animFps,cycle);

        String change="No runtime setting needed; baseline retained.";
        SharedPreferences.Editor e=p.edit();
        if ("conversation".equals(focus) && latency>4200L) {
            e.putBoolean("speed_priority",true).putString("reply_style","brief");
            change="Enabled the speed-first conversation profile because response latency was high.";
        } else if ("speech".equals(focus) && speechRebuilds>=2) {
            e.putBoolean("evolution_speech_recovery_priority",true);
            change="Raised speech recovery to the top of the next optimization pass; core recognizer changes remain Guardian-gated.";
        } else if ("animation".equals(focus) && (animAge>750L || (animFps>=0f && animFps<24f))) {
            e.putBoolean("evolution_animation_recovery_priority",true);
            change="Flagged the Möbius renderer for recovery-first tuning on the next core patch.";
        } else if ("battery".equals(focus)) {
            e.putBoolean("evolution_full_charge_gate",true);
            change="Kept heavy optimization gated to plugged-in, fully charged operation.";
        }

        String stamp=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US).format(new Date(now));
        String report="Cycle "+cycle+" • "+stamp+" • focus="+focus+"\n"
                +"Response latency: "+latency+" ms • speech rebuilds: "+speechRebuilds+" • TTS recoveries: "+ttsRecoveries+"\n"
                +"Animation: "+(animFps<0?"not sampled":String.format(Locale.US,"%.1f fps",animFps))+" • frame age: "+animAge+" ms\n"
                +change;
        String log=p.getString("evolution_night_log","");
        if (log.length()>12000) log=log.substring(log.length()-9000);
        e.putLong("evolution_cycle_count",cycle)
                .putLong("evolution_last_cycle_at",now)
                .putString("evolution_last_focus",focus)
                .putString("evolution_last_report",report)
                .putString("evolution_night_log",log+(log.isEmpty()?"":"\n\n")+report)
                .putBoolean("evolution_overnight_active",overnight)
                .apply();

        if (overnight) scheduleNext(c,CYCLE_MS);
        return report;
    }

    private static String chooseFocus(String target,long latency,int speech,int tts,long age,float fps,long cycle){
        String t=target==null?"everything":target.toLowerCase(Locale.US);
        if (!t.equals("everything") && !t.equals("system") && !t.equals("yourself")) return t;
        if (age>750L || (fps>=0f && fps<24f)) return "animation";
        if (speech>=2 || tts>=3) return "speech";
        if (latency>4200L) return "conversation";
        String[] rotation={"conversation","speech","animation","battery","updates"};
        return rotation[(int)(cycle%rotation.length)];
    }

    public static boolean isChargingAndFull(Context c){
        Intent i=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(i==null) return false;
        int status=i.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
        int plugged=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0);
        int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
        int scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
        int pct=scale>0 ? Math.round(level*100f/scale) : level;
        boolean charging=plugged!=0 || status==BatteryManager.BATTERY_STATUS_CHARGING || status==BatteryManager.BATTERY_STATUS_FULL;
        return charging && pct>=100;
    }

    private static PendingIntent alarmIntent(Context c){
        Intent i=new Intent(c,EvolutionReceiver.class).setAction(ACTION_CYCLE);
        return PendingIntent.getBroadcast(c,318,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static boolean isCycleAction(Intent i){ return i!=null && ACTION_CYCLE.equals(i.getAction()); }
    static void scheduleNext(Context c,long delay){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(am==null) return;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+Math.max(5000L,delay),alarmIntent(c));
    }
    static void cancel(Context c){
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        if(am!=null) am.cancel(alarmIntent(c));
    }
}
