package com.aura.amp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import android.widget.RemoteViews;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

public final class AuraWidget extends AppWidgetProvider {
    private static final String ACTION = "com.aura.amp.WIDGET_ACTION";
    public static void refresh(Context c){
        AppWidgetManager wm=AppWidgetManager.getInstance(c);
        int[] ids=wm.getAppWidgetIds(new ComponentName(c,AuraWidget.class));
        for(int id:ids)draw(c,wm,id);
    }
    private static PendingIntent control(Context c,String name,int request){
        Intent intent=new Intent(c,AuraWidget.class).setAction(ACTION).putExtra("command",name);
        return PendingIntent.getBroadcast(c,request,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private static void draw(Context c,AppWidgetManager wm,int id){
        boolean dark=c.getSharedPreferences("aura_widget",Context.MODE_PRIVATE).getBoolean("dark",true);
        String title=c.getSharedPreferences("aura_widget",Context.MODE_PRIVATE).getString("title","AURA AMP");
        String artist=c.getSharedPreferences("aura_widget",Context.MODE_PRIVATE).getString("artist","Ready to play");
        boolean playing=c.getSharedPreferences("aura_widget",Context.MODE_PRIVATE).getBoolean("playing",false);
        RemoteViews rv=new RemoteViews(c.getPackageName(),R.layout.aura_widget);
        rv.setInt(R.id.widget_root,"setBackgroundResource",dark?R.drawable.widget_dark:R.drawable.widget_light);
        rv.setTextColor(R.id.widget_title,dark?Color.WHITE:Color.rgb(22,24,32));
        rv.setTextColor(R.id.widget_artist,dark?Color.rgb(162,165,179):Color.rgb(101,105,119));
        rv.setTextViewText(R.id.widget_title,title);
        rv.setTextViewText(R.id.widget_artist,artist);
        File artFile=new File(c.getFilesDir(),"aura_widget_art.png");
        if(artFile.exists()){
            Bitmap cover=BitmapFactory.decodeFile(artFile.getAbsolutePath());
            if(cover!=null)rv.setImageViewBitmap(R.id.widget_art,cover);
        }
        rv.setTextViewText(R.id.widget_play,playing?"Ⅱ":"▶");
        rv.setTextColor(R.id.widget_prev,dark?Color.WHITE:Color.rgb(22,24,32));
        rv.setTextColor(R.id.widget_next,dark?Color.WHITE:Color.rgb(22,24,32));
        rv.setOnClickPendingIntent(R.id.widget_play,control(c,"toggle",201));
        rv.setOnClickPendingIntent(R.id.widget_prev,control(c,"prev",206));
        rv.setOnClickPendingIntent(R.id.widget_next,control(c,"next",202));
        try {
            Intent open=new Intent(c, Class.forName("com.aura.amp.MainActivity"));
            rv.setOnClickPendingIntent(R.id.widget_title,PendingIntent.getActivity(c,204,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
            rv.setOnClickPendingIntent(R.id.widget_artist,PendingIntent.getActivity(c,205,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
        } catch(Exception ignored){}
        rv.setOnClickPendingIntent(R.id.widget_theme,control(c,"theme",203));
        wm.updateAppWidget(id,rv);
    }
    @Override public void onUpdate(Context c,AppWidgetManager wm,int[] ids){for(int id:ids)draw(c,wm,id);}
    @Override public void onEnabled(Context c){super.onEnabled(c);refresh(c);}
    @Override public void onReceive(Context c,Intent intent){
        super.onReceive(c,intent);
        if(!ACTION.equals(intent.getAction()))return;
        String cmd=intent.getStringExtra("command");
        if("theme".equals(cmd)){
            boolean dark=c.getSharedPreferences("aura_widget",Context.MODE_PRIVATE).getBoolean("dark",true);
            c.getSharedPreferences("aura_widget",Context.MODE_PRIVATE).edit().putBoolean("dark",!dark).apply();
            refresh(c);return;
        }
        final PendingResult async=goAsync();
        ListenableFuture<MediaController> future=new MediaController.Builder(c,new SessionToken(c,new ComponentName(c,AuraMediaService.class))).buildAsync();
        future.addListener(()->{
            try{
                MediaController controller=future.get();
                if("toggle".equals(cmd)){if(controller.isPlaying())controller.pause();else controller.play();}
                else if("next".equals(cmd)&&controller.hasNextMediaItem())controller.seekToNextMediaItem();
                else if("prev".equals(cmd)&&controller.hasPreviousMediaItem())controller.seekToPreviousMediaItem();
                c.getSharedPreferences("aura_widget",Context.MODE_PRIVATE).edit()
                    .putBoolean("playing",controller.isPlaying()).apply();
                refresh(c);
                MediaController.releaseFuture(future);
            }catch(Exception ignored){}finally{async.finish();}
        },MoreExecutors.directExecutor());
    }
}
