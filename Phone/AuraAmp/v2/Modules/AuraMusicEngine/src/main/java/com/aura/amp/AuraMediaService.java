package com.aura.amp;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.InputStream;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import org.json.JSONArray;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Single playback authority shared by Android system controls, Web UI and home widget. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class AuraMediaService extends MediaLibraryService {
    private MediaLibrarySession session;
    private ExoPlayer player;
    private volatile String artworkLast="";

    private volatile String lastCarBrowseParent=""; // AURA_AMP_QUEUE_CAST_FIX_20261106
    private static final String CAR_ROOT="aura:root";
    private static final String CAR_OFFLINE="aura:offline";
    private static final String CAR_PLAYLISTS="aura:playlists";
    private static final String CAR_PLAYLIST_PREFIX="aura:playlist:";
    private static final String CAR_OFFLINE_PREFIX="aura:track:";

    private MediaItem folder(String id,String name,int mediaType){
        return new MediaItem.Builder().setMediaId(id).setMediaMetadata(new MediaMetadata.Builder()
                .setTitle(name).setIsPlayable(false).setIsBrowsable(true)
                .setMediaType(mediaType).build()).build();
    }

    private JSONObject offlineIndex(){
        try{return new JSONObject(getSharedPreferences("aura_offline",MODE_PRIVATE).getString("index","{}"));}
        catch(Exception e){return new JSONObject();}
    }
    private JSONArray playlistIndex(){
        try{return new JSONArray(getSharedPreferences("aura_car_library",MODE_PRIVATE).getString("playlists","[]"));}
        catch(Exception e){return new JSONArray();}
    }
    private File offlineFile(String id){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();
            for(byte b:digest)hex.append(String.format(java.util.Locale.US,"%02x",b&255));
            return new File(new File(getFilesDir(),"offline_audio"),hex+".audio");
        }catch(Exception e){return new File(getFilesDir(),"invalid-offline-file");}
    }
    private MediaItem offlineItem(String id,JSONObject item){
        if(item==null)return null;
        File file=offlineFile(id);
        if(!file.isFile()||!file.canRead())return null;
        MediaMetadata.Builder info=new MediaMetadata.Builder()
                .setTitle(item.optString("title","Unknown song"))
                .setArtist(item.optString("artist","Unknown artist"))
                .setAlbumTitle(item.optString("album",""))
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC);
        String cover=item.optString("cover","");
        if(cover.startsWith("https://"))info.setArtworkUri(Uri.parse(cover));
        return new MediaItem.Builder().setMediaId(CAR_OFFLINE_PREFIX+id)
                .setUri(Uri.fromFile(file)).setMediaMetadata(info.build()).build();
    }
    private List<MediaItem> carChildren(String parent){
        List<MediaItem> all=new ArrayList<>();
        if(CAR_ROOT.equals(parent)){
            all.add(folder(CAR_OFFLINE,"Offline downloads",MediaMetadata.MEDIA_TYPE_FOLDER_MIXED));
            all.add(folder(CAR_PLAYLISTS,"Downloaded playlists",MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS));
        }else if(CAR_OFFLINE.equals(parent)){
            JSONObject index=offlineIndex();
            java.util.Iterator<String> keys=index.keys();
            while(keys.hasNext()){
                String id=keys.next();
                MediaItem item=offlineItem(id,index.optJSONObject(id));
                if(item!=null)all.add(item);
            }
        }else if(CAR_PLAYLISTS.equals(parent)){
            JSONArray playlists=playlistIndex();
            for(int i=0;i<playlists.length();i++){
                JSONObject list=playlists.optJSONObject(i);if(list==null)continue;
                String id=list.optString("id","");
                if(!id.isEmpty())all.add(folder(CAR_PLAYLIST_PREFIX+id,
                    list.optString("name","Playlist"),MediaMetadata.MEDIA_TYPE_PLAYLIST));
            }
        }else if(parent.startsWith(CAR_PLAYLIST_PREFIX)){
            String playlistId=parent.substring(CAR_PLAYLIST_PREFIX.length());
            JSONArray playlists=playlistIndex();JSONObject index=offlineIndex();
            for(int i=0;i<playlists.length();i++){
                JSONObject list=playlists.optJSONObject(i);
                if(list==null||!playlistId.equals(list.optString("id")))continue;
                JSONArray songs=list.optJSONArray("tracks");if(songs==null)break;
                for(int j=0;j<songs.length();j++){
                    String id=songs.optString(j,"");
                    MediaItem item=offlineItem(id,index.optJSONObject(id));
                    if(item!=null)all.add(item);
                }
                break;
            }
        }
        return all;
    }
    private final MediaLibrarySession.Callback carCallback=new MediaLibrarySession.Callback(){
        @Override public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
            MediaLibrarySession session,MediaSession.ControllerInfo browser,
            @Nullable LibraryParams params){
            return Futures.immediateFuture(LibraryResult.ofItem(
                folder(CAR_ROOT,"AURA AMP",MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),params));
        }
        @Override public ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> onGetChildren(
            MediaLibrarySession session,MediaSession.ControllerInfo browser,String parentId,
            int page,int pageSize,@Nullable LibraryParams params){
            List<MediaItem> children=carChildren(parentId);
            if(parentId.startsWith(CAR_PLAYLIST_PREFIX))lastCarBrowseParent=parentId;
            else if(CAR_OFFLINE.equals(parentId))lastCarBrowseParent=CAR_OFFLINE;
            int from=(int)Math.min(children.size(),(long)page*pageSize);
            int to=Math.min(children.size(),from+pageSize);
            return Futures.immediateFuture(LibraryResult.ofItemList(children.subList(from,to),params));
        }
        @Override public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
            MediaLibrarySession session,MediaSession.ControllerInfo browser,String mediaId){
            MediaItem target=null;
            if(CAR_ROOT.equals(mediaId))target=folder(CAR_ROOT,"AURA AMP",MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
            else if(CAR_OFFLINE.equals(mediaId))target=folder(CAR_OFFLINE,"Offline downloads",MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
            else if(CAR_PLAYLISTS.equals(mediaId))target=folder(CAR_PLAYLISTS,"Downloaded playlists",MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS);
            else if(mediaId.startsWith(CAR_OFFLINE_PREFIX)){
                String id=mediaId.substring(CAR_OFFLINE_PREFIX.length());
                target=offlineItem(id,offlineIndex().optJSONObject(id));
            }else if(mediaId.startsWith(CAR_PLAYLIST_PREFIX)){
                target=folder(mediaId,"Playlist",MediaMetadata.MEDIA_TYPE_PLAYLIST);
            }
            if(target==null)target=folder(CAR_ROOT,"AURA AMP",MediaMetadata.MEDIA_TYPE_FOLDER_MIXED);
            return Futures.immediateFuture(LibraryResult.ofItem(target,null));
        }
        @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(
            MediaSession session,MediaSession.ControllerInfo controller,List<MediaItem> items){
            List<MediaItem> resolved=new ArrayList<>();
            JSONObject index=offlineIndex();
            for(MediaItem item:items){
                if(item.localConfiguration!=null){resolved.add(item);continue;}
                if(item.mediaId.startsWith(CAR_OFFLINE_PREFIX)){
                    String id=item.mediaId.substring(CAR_OFFLINE_PREFIX.length());
                    MediaItem media=offlineItem(id,index.optJSONObject(id));
                    if(media!=null)resolved.add(media);
                }
            }
            if(items.size()==1&&resolved.size()==1&&lastCarBrowseParent.startsWith(CAR_PLAYLIST_PREFIX)
                    &&items.get(0).localConfiguration==null){
                List<MediaItem> siblings=carChildren(lastCarBrowseParent);
                String selected=resolved.get(0).mediaId;
                int at=-1;
                for(int i=0;i<siblings.size();i++)if(selected.equals(siblings.get(i).mediaId)){at=i;break;}
                if(at>=0){
                    for(int i=at+1;i<siblings.size()&&resolved.size()<200;i++)resolved.add(siblings.get(i));
                }
            }
            return Futures.immediateFuture(resolved);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(12_000,25_000,1_000,2_000)
                .setTargetBufferBytes(8 * 1024 * 1024)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        player.setPreloadConfiguration(new ExoPlayer.PreloadConfiguration(2_000_000L));
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        Intent open;
        try {
            open = new Intent(this, Class.forName("com.aura.amp.MainActivity"));
        } catch (ClassNotFoundException e) {
            open = new Intent();
        }
        PendingIntent pi = PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        session = new MediaLibrarySession.Builder(this, player, carCallback).setSessionActivity(pi).build();
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean state) {storeAndRefresh();}
            @Override public void onMediaItemTransition(@Nullable MediaItem item,int reason){storeAndRefresh();}
            @Override public void onPlaybackStateChanged(int state){storeAndRefresh();}
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error){
                android.util.Log.e("AuraAmpPlayback","Media3 failed to play the selected source",error);
                storeAndRefresh();
            }
        });
    }
    private void storeAndRefresh(){
        MediaItem item=player.getCurrentMediaItem();
        String title=item==null?"AURA AMP":String.valueOf(item.mediaMetadata.title==null?"AURA AMP":item.mediaMetadata.title);
        String artist=item==null?"Ready to play":String.valueOf(item.mediaMetadata.artist==null?"":item.mediaMetadata.artist);
        getSharedPreferences("aura_widget",MODE_PRIVATE).edit().putString("title",title).putString("artist",artist).putBoolean("playing",player.isPlaying()).apply();
        AuraWidget.refresh(this);
        Uri artwork=item==null?null:item.mediaMetadata.artworkUri;
        if(artwork!=null&&!artwork.toString().equals(artworkLast)){
            artworkLast=artwork.toString();
            String url=artworkLast;
            new Thread(()->{
                HttpURLConnection conn=null;
                try{
                    if(!url.startsWith("https://"))return;
                    conn=(HttpURLConnection)new URL(url).openConnection();
                    conn.setConnectTimeout(6000);conn.setReadTimeout(6000);conn.setInstanceFollowRedirects(false);
                    if(conn.getResponseCode()!=200||conn.getContentLengthLong()>2_000_000)return;
                    try(InputStream in=conn.getInputStream()){
                        Bitmap decoded=BitmapFactory.decodeStream(in);
                        if(decoded==null)return;
                        Bitmap thumb=Bitmap.createScaledBitmap(decoded,128,128,true);
                        try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"aura_widget_art.png"))){thumb.compress(Bitmap.CompressFormat.PNG,95,out);}
                        AuraWidget.refresh(this);
                    }
                }catch(Exception ignored){}finally{if(conn!=null)conn.disconnect();}
            },"AuraWidgetArtwork").start();
        }
    }
    @Override public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {return session;}
    @Override public void onDestroy() {
        if(session!=null)session.release();
        if(player!=null)player.release();
        session=null;player=null;
        super.onDestroy();
    }
}
