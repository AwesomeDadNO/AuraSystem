package com.aura.amp;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.widget.FrameLayout;
import androidx.mediarouter.app.MediaRouteButton;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

/** Native Android Google Cast sender. Requires a receiver-accessible HTTPS audio stream. */
final class AuraCast {
    private final Activity activity;
    private final Runnable onTransfer;
    private final java.util.function.Consumer<String> onError;
    private CastContext context;
    private MediaRouteButton routeButton;
    private SessionManager manager;
    private String url="",title="",artist="",cover="",lastLoadedUrl="";
    private SessionManagerListener<CastSession> listener;

    AuraCast(Activity activity, FrameLayout host, Runnable onTransfer, java.util.function.Consumer<String> onError) {
        this.activity=activity; this.onTransfer=onTransfer; this.onError=onError;
        try {
            context=CastContext.getSharedInstance(activity);
            manager=context.getSessionManager();
            routeButton=new MediaRouteButton(activity);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(48,48,Gravity.TOP|Gravity.LEFT);
            routeButton.setVisibility(android.view.View.INVISIBLE);
            routeButton.setContentDescription("Cast to speakers");
            host.addView(routeButton,lp);
            CastButtonFactory.setUpMediaRouteButton(activity,routeButton);
            listener=new SessionManagerListener<CastSession>() {
                @Override public void onSessionStarting(CastSession s) {}
                @Override public void onSessionStarted(CastSession s,String id) { lastLoadedUrl="";loadTo(s); }
                @Override public void onSessionStartFailed(CastSession s,int error) {onError.accept("Cast connection failed: "+error);}
                @Override public void onSessionEnding(CastSession s) {}
                @Override public void onSessionEnded(CastSession s,int error) { lastLoadedUrl=""; }
                @Override public void onSessionResuming(CastSession s,String id) {}
                @Override public void onSessionResumed(CastSession s,boolean wasSuspended) {}
                @Override public void onSessionResumeFailed(CastSession s,int error) {onError.accept("Cast resume failed: "+error);}
                @Override public void onSessionSuspended(CastSession s,int reason) {}
            };
            manager.addSessionManagerListener(listener,CastSession.class);
        } catch(Exception error){context=null;routeButton=null;onError.accept("Google Cast unavailable: "+error.getMessage());}
    }
    void place(float x,float y,float w,float h){
        if(routeButton==null)return;
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(Math.max(42,Math.round(w)),Math.max(42,Math.round(h)),Gravity.TOP|Gravity.LEFT);
        params.leftMargin=Math.max(0,Math.round(x));params.topMargin=Math.max(0,Math.round(y));
        routeButton.setLayoutParams(params);
        routeButton.setVisibility(android.view.View.VISIBLE);
        routeButton.bringToFront();
    }
    void hide(){if(routeButton!=null)routeButton.setVisibility(android.view.View.INVISIBLE);}
    void prepare(String url,String title,String artist,String cover){
        if(url!=null&&url.startsWith("https://")){
            this.url=url;this.title=title;this.artist=artist;this.cover=cover;
            CastSession session=manager==null?null:manager.getCurrentCastSession();
            if(session!=null&&session.isConnected())loadTo(session);
        }else{this.url="";}
    }
    void openPicker(){
        if(routeButton==null){onError.accept("Google Cast route control was not initialized");return;}
        routeButton.setVisibility(android.view.View.VISIBLE);
        routeButton.showDialog();
    }
    void select(String url,String title,String artist,String cover){
        if(!url.startsWith("https://")){onError.accept("Cast requires HTTPS audio reachable by the speaker");return;}
        this.url=url;this.title=title;this.artist=artist;this.cover=cover;
        if(routeButton==null){onError.accept("Google Cast is not available on this device");return;}
        CastSession connected=manager.getCurrentCastSession();
        if(connected!=null&&connected.isConnected()){loadTo(connected);return;}
        routeButton.showDialog();
    }
    private void loadTo(CastSession session){
        if(url.isEmpty()||session==null||url.equals(lastLoadedUrl))return;
        try {
            RemoteMediaClient client=session.getRemoteMediaClient();
            if(client==null){onError.accept("Cast receiver does not offer media playback");return;}
            MediaMetadata meta=new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
            meta.putString(MediaMetadata.KEY_TITLE,title);
            meta.putString(MediaMetadata.KEY_ARTIST,artist);
            if(cover.startsWith("https://"))meta.addImage(new com.google.android.gms.common.images.WebImage(Uri.parse(cover)));
            String mime=url.matches("(?i).*\\.m3u8(?:\\?.*)?$")?"application/x-mpegURL":url.matches("(?i).*\\.flac(?:\\?.*)?$")?"audio/flac":url.matches("(?i).*\\.(m4a|mp4)(?:\\?.*)?$")?"audio/mp4":"audio/mpeg";
            MediaInfo media=new MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(mime).setMetadata(meta).build();
            final String requestedUrl=url;
            lastLoadedUrl=requestedUrl;
            client.load(new MediaLoadRequestData.Builder().setMediaInfo(media).build())
              .setResultCallback(result->{
                  if(result.getStatus().isSuccess()){
                      onTransfer.run();
                  }else{
                      if(requestedUrl.equals(lastLoadedUrl))lastLoadedUrl="";
                      onError.accept("Cast receiver could not load this stream (status "+result.getStatus().getStatusCode()+").");
                  }
              });
        }catch(Exception e){onError.accept("Cast failed: "+e.getMessage());}
    }
    void close(){if(manager!=null&&listener!=null)manager.removeSessionManagerListener(listener,CastSession.class);}
}
