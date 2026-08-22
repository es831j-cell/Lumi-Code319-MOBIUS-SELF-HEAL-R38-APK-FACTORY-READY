package com.distressedelk.lumi;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;

public class LumiOverlayService extends Service {
    private WindowManager wm; private ImageView avatar;
    @Override public void onCreate(){
        super.onCreate(); wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        avatar=new ImageView(this);
        String profile=getSharedPreferences("lumi",MODE_PRIVATE).getString("profile","Home");
        avatar.setImageResource("Public".equalsIgnoreCase(profile) ? R.drawable.lumi_public : R.drawable.lumi_home);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.argb(235,20,28,38)); bg.setCornerRadius(42); bg.setStroke(2,Color.rgb(127,232,255)); avatar.setBackground(bg); avatar.setClipToOutline(true);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(300,390,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.END; p.x=18; p.y=150; wm.addView(avatar,p);
        avatar.setOnClickListener(v->{ Intent i=new Intent(this,MainActivity.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); stopSelf(); });
    }
    @Override public void onDestroy(){ if(avatar!=null) wm.removeView(avatar); super.onDestroy(); }
    @Override public IBinder onBind(Intent i){ return null; }
}
