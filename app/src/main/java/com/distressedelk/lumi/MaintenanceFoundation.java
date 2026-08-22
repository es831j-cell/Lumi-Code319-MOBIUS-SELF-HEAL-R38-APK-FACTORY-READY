package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Installs Lumi 1.0's permanent maintenance policy/config foundation into app-private storage. */
final class MaintenanceFoundation {
    static final String CAPABILITY="directMaintenanceHostV1";
    private static final String[] ASSETS={
            "lumi-maintenance/direct-maintenance.json",
            "lumi-maintenance/guardian-maintenance-contract.json",
            "lumi-maintenance/maintenance-transaction-schema.json",
            "lumi-maintenance/relay-protocol-v1.json",
            "lumi-maintenance/silent-maintenance-policy.json",
            "lumi-maintenance/direct-maintenance-commands.json",
            "lumi-maintenance/direct-maintenance-operator.md",
            "lumi-maintenance/openai-voice-architecture.json"
    };
    private MaintenanceFoundation(){}

    static synchronized void initialize(Context context, SharedPreferences prefs){
        try{
            LumiMemoryVault vault=LumiMemoryVault.get(context);
            vault.initializeFromLegacy(prefs);
            File root=new File(context.getFilesDir(),"lumi_updates/modules/maintenance");
            if(!root.exists())root.mkdirs();
            for(String asset:ASSETS){
                String name=asset.substring(asset.lastIndexOf('/')+1);
                File out=new File(root,name);
                if(!out.isFile() || out.length()<16) copyAsset(context,asset,out);
            }
            prefs.edit()
                    .putBoolean("lumi_1_0_foundation_ready",true)
                    .putBoolean("direct_maintenance_host_ready",true)
                    .putString("direct_maintenance_host_capability",CAPABILITY)
                    .putInt("memory_vault_schema",1)
                    .putLong("lumi_1_0_foundation_initialized_at",System.currentTimeMillis())
                    .apply();
            vault.ledger("bootstrap","Lumi 1.0 maintenance foundation ready","Memory Vault and native maintenance tool host initialized.","");
        }catch(Exception e){
            prefs.edit().putBoolean("lumi_1_0_foundation_ready",false).putString("lumi_1_0_foundation_error",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage())).apply();
        }
    }

    private static void copyAsset(Context c,String asset,File out)throws Exception{
        File parent=out.getParentFile();if(parent!=null&&!parent.exists())parent.mkdirs();
        File tmp=new File(out.getAbsolutePath()+".new");
        try(InputStream in=c.getAssets().open(asset);FileOutputStream os=new FileOutputStream(tmp)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)os.write(b,0,n);os.getFD().sync();}
        if(out.exists()&&!out.delete())throw new Exception("Could not replace maintenance asset");
        if(!tmp.renameTo(out))throw new Exception("Could not activate maintenance asset");
    }
}
