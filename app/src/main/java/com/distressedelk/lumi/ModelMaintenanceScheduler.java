package com.distressedelk.lumi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.util.Calendar;

final class ModelMaintenanceScheduler {
    private static final int REQ_WEEKLY = 2201;
    private ModelMaintenanceScheduler() {}

    static void schedule(Context context) {
        try {
            Calendar next = Calendar.getInstance();
            next.set(Calendar.DAY_OF_WEEK, Calendar.THURSDAY);
            next.set(Calendar.HOUR_OF_DAY, 2);
            next.set(Calendar.MINUTE, 15);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            if (next.getTimeInMillis() <= System.currentTimeMillis()) next.add(Calendar.WEEK_OF_YEAR, 1);
            Intent i = new Intent(context, ModelMaintenanceReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(context, REQ_WEEKLY, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.setInexactRepeating(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY * 7L, pi);
        } catch (Exception ignored) {}
    }

    static void retryInOneHour(Context context) {
        try {
            Intent i = new Intent(context, ModelMaintenanceReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(context, 2202, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60L * 60L * 1000L, pi);
        } catch (Exception ignored) {}
    }
}
