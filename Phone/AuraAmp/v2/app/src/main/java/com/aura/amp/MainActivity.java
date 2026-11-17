package com.aura.amp;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import java.security.MessageDigest;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceError;
import android.util.Log;
import android.content.pm.ActivityInfo;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.webkit.ValueCallback;
import android.view.View;
import android.graphics.Color;
import android.content.ComponentName;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.SessionToken;
import androidx.media3.session.MediaController;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import android.media.AudioManager;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.barcode.common.Barcode;

/** Aura Amp Flat UIX host. HTTPS-only restricted native JSON transport bridges WebView CORS. */
public final class MainActivity extends Activity {
    private AuraUpdateAgent auraUpdater;
    private AuraCredentialVault credentialVault;
    private AuraConnectClient auraConnect;   // AuraConnect controller (pair with a TV, send the saved ARL). Lazily created.
    private final android.os.Handler updateHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable updatePulse=new Runnable(){@Override public void run(){if(auraUpdater!=null)auraUpdater.checkIfDue();updateHandler.postDelayed(this,30L*60*1000);}};
    private static final int FILE_REQUEST = 1003;
    private WebView webView;
    private LanSync lanSync;
    private android.net.nsd.NsdManager syncNsd;
    private android.net.nsd.NsdManager.RegistrationListener syncRegistration;
    private android.net.nsd.NsdManager.DiscoveryListener syncDiscovery;
    private android.net.wifi.WifiManager.MulticastLock syncMulticastLock;
    private void stopLocalSyncAdvert(){
        if(syncNsd!=null&&syncRegistration!=null)try{syncNsd.unregisterService(syncRegistration);}catch(Exception ignored){}
        syncRegistration=null;
    }
    private void advertiseLocalSync(int port){
        runOnUiThread(()->{
            stopLocalSyncAdvert();
            if(syncNsd==null)syncNsd=(android.net.nsd.NsdManager)getSystemService(NSD_SERVICE);
            if(syncNsd==null)return;
            android.net.nsd.NsdServiceInfo info=new android.net.nsd.NsdServiceInfo();
            info.setServiceName("AURA AMP Mobile");info.setServiceType("_auraamp._tcp.");info.setPort(port);
            syncRegistration=new android.net.nsd.NsdManager.RegistrationListener(){
                public void onServiceRegistered(android.net.nsd.NsdServiceInfo x){}
                public void onRegistrationFailed(android.net.nsd.NsdServiceInfo x,int err){sendSyncEvent("{\"event\":\"error\",\"message\":\"Device advertising failed\"}");}
                public void onServiceUnregistered(android.net.nsd.NsdServiceInfo x){}
                public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo x,int err){}
            };
            try{syncNsd.registerService(info,android.net.nsd.NsdManager.PROTOCOL_DNS_SD,syncRegistration);}catch(Exception ignored){}
        });
    }
    private void discoverLocalSync(){
        runOnUiThread(()->{
            if(syncNsd==null)syncNsd=(android.net.nsd.NsdManager)getSystemService(NSD_SERVICE);
            if(syncNsd==null)return;
            if(syncDiscovery!=null)try{syncNsd.stopServiceDiscovery(syncDiscovery);}catch(Exception ignored){}
            android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wifi!=null&&syncMulticastLock==null){syncMulticastLock=wifi.createMulticastLock("aura_amp_local_discovery");syncMulticastLock.setReferenceCounted(false);syncMulticastLock.acquire();}
            java.util.Map<String,org.json.JSONObject> devices=new java.util.concurrent.ConcurrentHashMap<>();
            syncDiscovery=new android.net.nsd.NsdManager.DiscoveryListener(){
                private void publish(){org.json.JSONArray arr=new org.json.JSONArray();for(org.json.JSONObject v:devices.values())arr.put(v);sendNativeData("AuraSyncNearby",arr.toString());}
                public void onDiscoveryStarted(String type){sendSyncEvent("{\"event\":\"scan-started\",\"message\":\"Searching for nearby AURA AMP devices\"}");}
                public void onServiceFound(android.net.nsd.NsdServiceInfo info){
                    if(!info.getServiceType().toLowerCase(java.util.Locale.ROOT).contains("_auraamp._tcp"))return;
                    syncNsd.resolveService(info,new android.net.nsd.NsdManager.ResolveListener(){
                        public void onResolveFailed(android.net.nsd.NsdServiceInfo x,int err){}
                        public void onServiceResolved(android.net.nsd.NsdServiceInfo x){
                            try{devices.put(x.getServiceName(),new org.json.JSONObject().put("name",x.getServiceName()).put("host",x.getHost().getHostAddress()).put("port",x.getPort()));publish();
                                sendSyncEvent(new org.json.JSONObject().put("event","scan-found").put("count",devices.size()).put("message","Found "+devices.size()+" AURA AMP device(s)").toString());
                            }catch(Exception ignored){}
                        }
                    });
                }
                public void onServiceLost(android.net.nsd.NsdServiceInfo info){devices.remove(info.getServiceName());publish();}
                public void onDiscoveryStopped(String type){}
                public void onStartDiscoveryFailed(String type,int err){sendSyncEvent("{\"event\":\"error\",\"message\":\"Local scan unavailable\"}");}
                public void onStopDiscoveryFailed(String type,int err){}
            };
            try{
                final android.net.nsd.NsdManager.DiscoveryListener session=syncDiscovery;
                syncNsd.discoverServices("_auraamp._tcp.",android.net.nsd.NsdManager.PROTOCOL_DNS_SD,session);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
                    if(syncDiscovery!=session)return;
                    sendSyncEvent("{\"event\":\"scan-complete\",\"message\":\"Scan complete - choose a device or scan again\"}");
                    try{syncNsd.stopServiceDiscovery(session);}catch(Exception ignored){}
                    syncDiscovery=null;
                    if(syncMulticastLock!=null&&syncMulticastLock.isHeld())syncMulticastLock.release();
                    syncMulticastLock=null;
                },20000);
            }catch(Exception ex){sendSyncEvent("{\"event\":\"error\",\"message\":\"Unable to start local discovery\"}");}
        });
    }
    private AuraCast castSender;
    private FrameLayout castHost;
    private String pendingCastUrl="",pendingCastTitle="",pendingCastArtist="",pendingCastCover="";
    private void initCastAfterUiReady(){
        if(castSender!=null||castHost==null||isFinishing())return;
        try{
            castSender=new AuraCast(this,castHost,()->withController(c->c.pause()),error->Log.w("AuraCast",error));
            if(!pendingCastUrl.isEmpty())castSender.prepare(pendingCastUrl,pendingCastTitle,pendingCastArtist,pendingCastCover);
            if(webView!=null)webView.evaluateJavascript("syncNativeCastButton()",null);
        }catch(Throwable error){Log.w("AuraAmp","Cast SDK not ready",error);}
    }
    private SpotifyImport spotifyImporter;
    private ListenableFuture<MediaController> controllerFuture;
    private volatile boolean mediaInitAttempted = false;
    private final java.util.List<java.util.function.Consumer<MediaController>> pendingControllerActions = new java.util.ArrayList<>();
    private void initMediaSafely(){
        if(mediaInitAttempted)return;
        mediaInitAttempted=true;
        try{
            controllerFuture=new MediaController.Builder(this,
                    new SessionToken(this,new ComponentName(this,AuraMediaService.class))).buildAsync();
        }catch(Throwable error){Log.e("AuraAmp","Media session startup failed; UI remains available",error);}
    }
    private synchronized LanSync getLanSync(){
        if(lanSync==null){
            lanSync=new LanSync(this,new LanSync.Listener(){
                @Override public void onEvent(String json){
                    try{org.json.JSONObject d=new org.json.JSONObject(json);
                        if("hosting".equals(d.optString("event")))advertiseLocalSync(d.optInt("port"));
                        else if("error".equals(d.optString("event"))||"sent".equals(d.optString("event")))stopLocalSyncAdvert();
                    }catch(Exception ignored){}
                    sendSyncEvent(json);
                }
                @Override public void onReceived(String json){
                    try{
                        org.json.JSONObject data=new org.json.JSONObject(json),users=data.optJSONObject("profiles");
                        if(users!=null){
                            org.json.JSONObject imported=new org.json.JSONObject();
                            java.util.Iterator<String> it=users.keys();
                            while(it.hasNext()){
                                String key=it.next();org.json.JSONObject profile=users.optJSONObject(key);if(profile==null)continue;
                                String account=profile.optString("name",key).replaceAll("[^a-zA-Z0-9_ -]","").trim();
                                if(account.isEmpty())account="AURA user";
                                account=account+" (synced "+Long.toString(System.currentTimeMillis(),36)+")";
                                String arl=profile.optString("arl","");
                                if(credentialVault!=null&&arl.matches("[a-fA-F0-9]{100,256}"))credentialVault.saveArlForProfile(account,arl);
                                profile.remove("arl");imported.put(account,profile);
                            }
                            data.put("profiles",imported);
                        }
                        sendSyncPayload(data.toString());
                    }catch(Exception ignored){sendSyncEvent("{\"event\":\"error\",\"message\":\"Invalid sync data\"}");}
                }
            });
        }
        return lanSync;
    }
    private void withController(java.util.function.Consumer<MediaController> action){
        initMediaSafely();
        if(controllerFuture==null){
            synchronized(pendingControllerActions){ pendingControllerActions.add(action); }
            return;
        }
        controllerFuture.addListener(()->{
            try {
                MediaController controller=controllerFuture.get();
                runOnUiThread(()->action.accept(controller));
                synchronized(pendingControllerActions){
                    if(!pendingControllerActions.isEmpty()){
                        java.util.List<java.util.function.Consumer<MediaController>> copy=new java.util.ArrayList<>(pendingControllerActions);
                        pendingControllerActions.clear();
                        for(java.util.function.Consumer<MediaController> queued: copy){
                            try{ queued.accept(controller); }catch(Exception ignored){}
                        }
                    }
                }
            } catch(Exception ignored){
                synchronized(pendingControllerActions){ pendingControllerActions.add(action); }
            }
        }, MoreExecutors.directExecutor());
    }
    private void playbackErrorToWeb(String error){
        String safe=error==null?"Unable to play this audio source":error;
        Log.e("AuraAmpPlayback",safe);
        runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript("window.AuraPlaybackError("+JSONObject.quote(safe)+")",null);});
    }
    private void stateToWeb(MediaController c){
        if(webView==null)return;
        JSONObject obj=new JSONObject();
        try {MediaItem item=c.getCurrentMediaItem(); if(item!=null){obj.put("title",String.valueOf(item.mediaMetadata.title==null?"":item.mediaMetadata.title));obj.put("artist",String.valueOf(item.mediaMetadata.artist==null?"":item.mediaMetadata.artist));}obj.put("mediaId",item==null?"":item.mediaId);obj.put("playing",c.isPlaying());obj.put("hasNext",c.hasNextMediaItem());obj.put("mediaCount",c.getMediaItemCount());obj.put("playbackState",c.getPlaybackState());if(c.getPlayerError()!=null)obj.put("error",c.getPlayerError().getMessage()==null?"Media decoder error":c.getPlayerError().getMessage());obj.put("position",Math.max(0,c.getCurrentPosition()));obj.put("duration",Math.max(0,c.getDuration()));obj.put("shuffle",c.getShuffleModeEnabled());obj.put("repeatMode",c.getRepeatMode());}
        catch(Exception ignored){}
        webView.evaluateJavascript("window.AuraPlaybackState("+JSONObject.quote(obj.toString())+")",null);
        MediaItem item=c.getCurrentMediaItem();
        if(item!=null){
            String title=String.valueOf(item.mediaMetadata.title==null?"AURA AMP":item.mediaMetadata.title);
            String artist=String.valueOf(item.mediaMetadata.artist==null?"":item.mediaMetadata.artist);

        }
    }
    private void sendNativeData(String callbackName,String json){
        runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript("window."+callbackName+"?.("+JSONObject.quote(json)+")",null);});
    }
    private void nativeToast(String message){runOnUiThread(()->android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show());}
    private ValueCallback<Uri[]> pendingFile;
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private synchronized AuraConnectClient auraConnect(){ if(auraConnect==null) auraConnect=new AuraConnectClient(this); return auraConnect; }
    private void auraConnectResult(String fn,String json){ runOnUiThread(()->{ if(webView!=null) webView.evaluateJavascript(fn+"("+JSONObject.quote(json)+")",null); }); }
    private void sendSyncEvent(String json){runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript("window.AuraSyncStatus("+JSONObject.quote(json)+")",null);});}
    private void sendSyncPayload(String json){runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript("window.AuraSyncReceived("+JSONObject.quote(json)+")",null);});}
    private File offlineFile(String id){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(id.getBytes("UTF-8"));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.US,"%02x",b&255));
            File folder=new File(getFilesDir(),"offline_audio");if(!folder.exists())folder.mkdirs();return new File(folder,hex.toString()+".audio");}
        catch(Exception ex){throw new IllegalArgumentException("Invalid offline id",ex);}
    }
    private synchronized JSONObject readOfflineIndex(){try{return new JSONObject(getSharedPreferences("aura_offline",MODE_PRIVATE).getString("index","{}"));}catch(Exception e){return new JSONObject();}}
    private synchronized void writeOfflineIndex(JSONObject all){getSharedPreferences("aura_offline",MODE_PRIVATE).edit().putString("index",all.toString()).apply();}
    private void emitOfflineIndex(){JSONObject all=readOfflineIndex();JSONArray valid=new JSONArray();java.util.Iterator<String> keys=all.keys();while(keys.hasNext()){
        String k=keys.next();if(offlineFile(k).isFile())valid.put(all.optJSONObject(k));
    }String payload=valid.toString();runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript("window.AuraOfflineIndex("+JSONObject.quote(payload)+")",null);});}
    private void downloadEvent(String id,boolean ok,String message){runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript("window.AuraDownloadEvent("+JSONObject.quote(id)+","+ok+","+JSONObject.quote(message)+")",null);});}
    private static boolean privateNetworkHost(String host){
        if(host==null||host.isEmpty())return false;
        try{
            java.net.InetAddress[] addresses=java.net.InetAddress.getAllByName(host);
            if(addresses.length==0)return false;
            for(java.net.InetAddress ip:addresses){
                if(!(ip.isSiteLocalAddress()||ip.isLinkLocalAddress()||ip.isLoopbackAddress()))return false;
            }
            return true;
        }catch(Exception ex){return false;}
    }
    private static boolean playableAddress(Uri uri){
        String scheme=uri.getScheme();
        return "https".equalsIgnoreCase(scheme)||("http".equalsIgnoreCase(scheme)&&privateNetworkHost(uri.getHost()));
    }
    private void serverResult(String id, boolean ok, String message){
        runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript("window.AuraServerResponse("+JSONObject.quote(id)+","+ok+","+JSONObject.quote(message)+")",null);});
    }
    private final class NativeApi {
        @JavascriptInterface public void spotifyLogin(){
            if(spotifyImporter==null)spotifyImporter=new SpotifyImport(MainActivity.this,MainActivity.this::sendNativeData);
            // Public OAuth Client ID: never a secret. Resource fallback avoids
            // missing/overridden BuildConfig fields in local Android builds.
            String configuredId="";
            try { configuredId=BuildConfig.SPOTIFY_CLIENT_ID; }
            catch(Throwable error) { Log.w("AuraSpotify", "BuildConfig Client ID unavailable", error); }
            if(configuredId==null||configuredId.trim().isEmpty()){
                configuredId=getString(R.string.spotify_public_client_id);
            }
            final String clientId=configuredId==null?"":configuredId.trim();
            if(!clientId.matches("[a-fA-F0-9]{32}")){
                Log.e("AuraSpotify","Spotify Client ID missing/invalid in packaged build");
                sendNativeData("AuraSpotifyPlaylists", "{\"error\":\"Spotify Client ID not configured in this Android build. Please reinstall the latest AURA AMP build.\"}");
                return;
            }
            spotifyImporter.login(clientId);
        }
        @JavascriptInterface public void spotifyImport(String playlistId){
            if(spotifyImporter==null){nativeToast("Connect Spotify in Settings first");return;}
            spotifyImporter.importPlaylist(playlistId);
        }
        @JavascriptInterface public void positionCastButton(float left,float top,float width,float height){
            runOnUiThread(()->{if(castSender!=null)castSender.place(left,top,width,height);});
        }
        @JavascriptInterface public void prepareCastAudio(String url,String title,String artist,String cover){
            runOnUiThread(()->{
                pendingCastUrl=url==null?"":url;pendingCastTitle=title;pendingCastArtist=artist;pendingCastCover=cover;
                if(castSender!=null)castSender.prepare(url,title,artist,cover);
            });
        }
        @JavascriptInterface public void hideCastButton(){
            runOnUiThread(()->{if(castSender!=null)castSender.hide();});
        }
        /**
         * Kite hand-off integration. Kite currently exposes a public launcher Activity,
         * not a public receiver-selection/capture SDK. We deliberately do not invoke
         * its non-exported activities, bypass MediaProjection consent, or bundle code
         * without resolving its LGPL/ported-file attribution obligations.
         */
        @JavascriptInterface public void openAirPlayKite(){
            runOnUiThread(()->{
                if(Build.VERSION.SDK_INT<29){
                    nativeToast("AirPlay via Kite requires Android 10 or later");return;
                }
                try{
                    Intent launch=getPackageManager().getLaunchIntentForPackage("com.henry.kite");
                    if(launch!=null){
                        nativeToast("In Kite, select your receiver and Sound only. Approve audio capture if asked.");
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                    }else{
                        nativeToast("Kite isn't installed. Opening its official GitHub project.");
                        Intent site=new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/itsHenry35/Kite"));
                        startActivity(site);
                    }
                }catch(Exception ex){
                    Log.e("AuraAmp","Unable to open Kite AirPlay sender",ex);
                    nativeToast("Unable to launch AirPlay sender: "+ex.getMessage());
                }
            });
        }
        @JavascriptInterface public void openCastPicker(){
            runOnUiThread(()->{
                if(castSender==null)initCastAfterUiReady();
                if(castSender==null){nativeToast("Google Cast is not ready. Please try again.");return;}
                try{castSender.openPicker();}catch(Exception ex){nativeToast("Cast discovery error: "+ex.getMessage());}
            });
        }
        @JavascriptInterface public void castAudio(String url,String title,String artist,String cover){
            runOnUiThread(()->{
                if(castSender==null){nativeToast("Google Cast is unavailable");return;}
                try{castSender.select(url,title,artist,cover);}catch(Exception ex){nativeToast("Cast failed: "+ex.getMessage());}
            });
        }
        @JavascriptInterface public void setTheme(String theme){
            runOnUiThread(()->{
                boolean dark=!"light".equals(theme);
                int color=Color.parseColor(dark?"#191C25":"#FFFFFF");
                getWindow().setStatusBarColor(color);
                getWindow().setNavigationBarColor(color);
                getWindow().getDecorView().setSystemUiVisibility(dark?0:(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
            });
        }

        @JavascriptInterface public void playAudio(String url,String title,String artist,String cover,String mediaId){
            runOnUiThread(()->withController(c->{
                try {
                    Uri u=Uri.parse(url);
                    if(url.startsWith("aura-offline://")){String id=Uri.decode(url.substring("aura-offline://".length()));File file=offlineFile(id);if(!file.isFile()){playbackErrorToWeb("Offline file is missing");return;}u=Uri.fromFile(file);}
                    else if(!playableAddress(u)&&!"content".equalsIgnoreCase(u.getScheme()))return;
                    MediaMetadata.Builder metadata=new MediaMetadata.Builder().setTitle(title).setArtist(artist);
                    if(cover!=null&&cover.startsWith("https://"))metadata.setArtworkUri(Uri.parse(cover));
                    MediaItem.Builder mediaBuilder=new MediaItem.Builder().setUri(u).setMediaId(mediaId).setMediaMetadata(metadata.build());
                    String lower=url.toLowerCase(java.util.Locale.ROOT);
                    if(lower.contains(".m3u8")||lower.contains("format=hls"))mediaBuilder.setMimeType("application/x-mpegURL");
                    else if(lower.contains(".mpd")||lower.contains("format=dash"))mediaBuilder.setMimeType("application/dash+xml");
                    MediaItem media=mediaBuilder.build();
                    int index=c.getCurrentMediaItemIndex();
                     MediaItem upcoming=(index>=0&&index+1<c.getMediaItemCount())?c.getMediaItemAt(index+1):null;
                     if(upcoming!=null&&mediaId.equals(upcoming.mediaId)){
                         // Preserve the buffered next item rather than restarting its source.
                         c.seekToNextMediaItem();c.play();
                     }else{
                         int existing=-1;
                         for(int i=0;i<c.getMediaItemCount();i++)if(mediaId.equals(c.getMediaItemAt(i).mediaId)){existing=i;break;}
                         if(existing>=0){c.seekToDefaultPosition(existing);c.play();}
                         else{c.setMediaItem(media);c.prepare();c.play();}
                     }
                     stateToWeb(c);
                }catch(Exception ex){playbackErrorToWeb(ex.getMessage());}
            }));
        }
        @JavascriptInterface public void setUpcoming(String url,String title,String artist,String cover,String mediaId){
            // Compatibility with older WebView UI, without discarding the remaining native queue.
            org.json.JSONArray array=new org.json.JSONArray();
            try{array.put(new JSONObject().put("url",url).put("title",title)
                .put("artist",artist).put("cover",cover).put("id",mediaId));}catch(Exception ignored){}
            setUpcomingQueue(array.toString());
        }
        @JavascriptInterface public void setUpcomingQueue(String json){
            runOnUiThread(()->withController(c->{
                try{
                    org.json.JSONArray list=new org.json.JSONArray(json);
                    if(list.length()>50)return;
                    java.util.List<MediaItem> nextItems=new java.util.ArrayList<>();
                    for(int i=0;i<list.length();i++){
                        JSONObject object=list.optJSONObject(i);
                        if(object==null)continue;
                        String stream=object.optString("url",""),id=object.optString("id","");
                        if(stream.length()>4096||id.isEmpty()||id.length()>512)continue;
                        Uri uri;
                        if(stream.startsWith("aura-offline://")){
                            File local=offlineFile(Uri.decode(stream.substring("aura-offline://".length())));
                            if(!local.isFile())continue;
                            uri=Uri.fromFile(local);
                        }else{
                            uri=Uri.parse(stream);
                            if(!playableAddress(uri))continue;
                        }
                        MediaMetadata.Builder meta=new MediaMetadata.Builder()
                            .setTitle(object.optString("title",""))
                            .setArtist(object.optString("artist",""));
                        String cover=object.optString("cover","");
                        if(cover.startsWith("https://"))meta.setArtworkUri(Uri.parse(cover));
                        MediaItem.Builder builder=new MediaItem.Builder()
                            .setUri(uri).setMediaId(id).setMediaMetadata(meta.build());
                        String lower=stream.toLowerCase(java.util.Locale.ROOT);
                        if(lower.contains(".m3u8")||lower.contains("format=hls"))builder.setMimeType("application/x-mpegURL");
                        else if(lower.contains(".mpd")||lower.contains("format=dash"))builder.setMimeType("application/dash+xml");
                        nextItems.add(builder.build());
                    }
                    int index=c.getCurrentMediaItemIndex();
                    if(index<0||c.getMediaItemCount()==0)return;
                    // Keep the active track and replace the upcoming queue atomically.
                    if(c.getMediaItemCount()>index+1)c.removeMediaItems(index+1,c.getMediaItemCount());
                    if(!nextItems.isEmpty())c.addMediaItems(nextItems);
                    stateToWeb(c);
                }catch(Exception ex){Log.w("AuraAmpQueue","Unable to update queued media items",ex);}
            }));
        }
        @JavascriptInterface public void stopAudio(){runOnUiThread(()->withController(c->{c.stop();c.clearMediaItems();stateToWeb(c);}));}
        @JavascriptInterface public void toggleAudio(){runOnUiThread(()->withController(c->{if(c.isPlaying())c.pause();else c.play();stateToWeb(c);}));}
        @JavascriptInterface public void seekAudio(long pos){runOnUiThread(()->withController(c->{c.seekTo(Math.max(0,pos));stateToWeb(c);}));}
        @JavascriptInterface public void requestPlaybackState(){runOnUiThread(()->withController(MainActivity.this::stateToWeb));}
        @JavascriptInterface public void skipAudioNext(){
            runOnUiThread(()->withController(c->{
                if(c.hasNextMediaItem()){c.seekToNextMediaItem();c.play();stateToWeb(c);}
            }));
        }
        @JavascriptInterface public void setShuffle(boolean enabled){runOnUiThread(()->withController(c->{c.setShuffleModeEnabled(enabled);stateToWeb(c);}));}
        @JavascriptInterface public void setRepeatMode(int mode){runOnUiThread(()->withController(c->{c.setRepeatMode(mode==2?2:mode==1?1:0);stateToWeb(c);}));}
        @JavascriptInterface public void checkForUpdates(){runOnUiThread(()->{if(auraUpdater!=null)auraUpdater.checkNow();});}
        @JavascriptInterface public boolean saveDeezerArl(String arl){
            return credentialVault!=null&&credentialVault.saveArl(arl);
        }
        @JavascriptInterface public boolean hasDeezerArl(){
            return credentialVault!=null&&credentialVault.hasArl();
        }
        @JavascriptInterface public void clearDeezerArl(){
            if(credentialVault!=null)credentialVault.clearArl();
        }
        // --- AuraConnect: pair with a TV/desktop and send the ARL the person already saved. Pairing stays off until the TV build enables it. ---
        @JavascriptInterface public void auraConnectPair(String payload){
            worker.execute(()->auraConnectResult("window.AuraConnectPaired",auraConnect().pair(payload)));
        }
        @JavascriptInterface public void auraConnectReceivers(){
            worker.execute(()->auraConnectResult("window.AuraConnectReceivers",auraConnect().receivers()));
        }
        @JavascriptInterface public void auraConnectSendArl(String deviceId){
            worker.execute(()->{ String arl=credentialVault!=null?credentialVault.arlForEclipse():null; auraConnectResult("window.AuraConnectSendResult",auraConnect().sendArl(deviceId,arl)); });
        }
        @JavascriptInterface public void auraConnectSendPlaylists(String deviceId, String playlistsJson){
            worker.execute(()->auraConnectResult("window.AuraConnectSendPlaylistsResult",auraConnect().sendPlaylists(deviceId,playlistsJson)));
        }
        @JavascriptInterface public void auraConnectControl(String deviceId, String op, String argsJson){
            worker.execute(()->auraConnectResult("window.AuraConnectControlResult",auraConnect().control(deviceId, op, argsJson)));
        }
        @JavascriptInterface public void auraConnectSendMediaServerSignIn(String deviceId, String serverJson){
            worker.execute(()->auraConnectResult("window.AuraConnectSendMediaServerResult",auraConnect().sendMediaServerSignIn(deviceId, serverJson)));
        }
        @JavascriptInterface public boolean auraConnectForget(String deviceId){ return auraConnect().forget(deviceId); }
        @JavascriptInterface public void scanQrCode(){
            runOnUiThread(()->{
                try {
                    GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .enableAutoZoom()
                        .build();
                    GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(MainActivity.this, options);
                    scanner.startScan()
                        .addOnSuccessListener(barcode -> {
                            String raw = barcode.getRawValue();
                            if(raw != null && !raw.trim().isEmpty()){
                                auraConnectPair(raw);
                            }
                        })
                        .addOnFailureListener(e -> {
                            auraConnectResult("window.AuraConnectPaired", "{\"ok\":false,\"error\":\"QR scanning failed or cancelled: " + e.getMessage() + "\"}");
                        });
                } catch (Exception e) {
                    auraConnectResult("window.AuraConnectPaired", "{\"ok\":false,\"error\":\"Camera/QR scanner unavailable: " + e.getMessage() + "\"}");
                }
            });
        }

        @JavascriptInterface public void enableSleepMode(final boolean enable){
            runOnUiThread(()->{
                try {
                    if (enable) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    } else {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    }
                } catch (Exception e) {
                    Log.e("AuraAmp", "Error toggling sleep mode: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface public void pinWidget(){
            runOnUiThread(()->{
                try{
                    android.appwidget.AppWidgetManager manager=android.appwidget.AppWidgetManager.getInstance(MainActivity.this);
                    if(android.os.Build.VERSION.SDK_INT>=26&&manager.isRequestPinAppWidgetSupported()){
                        manager.requestPinAppWidget(new android.content.ComponentName(MainActivity.this,AuraWidget.class),null,null);
                    }else{
                        android.widget.Toast.makeText(MainActivity.this,"Long-press Home → Widgets → AURA AMP",android.widget.Toast.LENGTH_LONG).show();
                    }
                }catch(Exception ex){android.widget.Toast.makeText(MainActivity.this,"Open home screen Widgets to add AURA AMP",android.widget.Toast.LENGTH_LONG).show();}
            });
        }
        @JavascriptInterface public void refreshWidget(){
            runOnUiThread(()->AuraWidget.refresh(MainActivity.this));
        }
        @JavascriptInterface public void openExternal(String address){
            runOnUiThread(()->{try{Uri u=Uri.parse(address);if(!"https".equalsIgnoreCase(u.getScheme())||!"www.youtube.com".equalsIgnoreCase(u.getHost()))return;startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(Exception ignored){}});
        }
        @JavascriptInterface public void serverGet(String id,String type,String serverBase,String token,String path){
            worker.execute(()->{
                HttpURLConnection c=null;
                try{
                    if(!("plex".equals(type)||"emby".equals(type)||"jellyfin".equals(type)))throw new Exception("Unsupported server type");
                    if(token==null||token.length()>4096||path==null||path.length()>4000)throw new Exception("Invalid request");
                    Uri base=Uri.parse(serverBase);
                    if(!playableAddress(base)||base.getHost()==null)throw new Exception("Use HTTPS or private LAN server address");
                    // Resolve paths under this user's own server. Redirects are forbidden to protect credentials.
                    String root=serverBase.endsWith("/")?serverBase.substring(0,serverBase.length()-1):serverBase;
                    URL url=new URL(root+(path.startsWith("/")?path:"/"+path));
                    c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(9000);c.setReadTimeout(12000);
                    c.setRequestProperty("Accept","application/json,application/xml,text/xml");
                    c.setRequestProperty("User-Agent","AuraAmp/1.2 Android");
                    if("plex".equals(type))c.setRequestProperty("X-Plex-Token",token);
                    else c.setRequestProperty("X-Emby-Token",token);
                    if(!"plex".equals(type))c.setRequestProperty("X-Emby-Authorization","MediaBrowser Client=\"AuraAmp\", Device=\"Android\", DeviceId=\"auraamp-android\", Version=\"1.2\", Token=\""+token+"\"");
                    int status=c.getResponseCode();
                    if(status<200||status>=300)throw new Exception("Server returned HTTP "+status);
                    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] buf=new byte[8192];int n,total=0;while((n=in.read(buf))!=-1){total+=n;if(total>5_000_000)throw new Exception("Server response too large");out.write(buf,0,n);}
                        serverResult(id,true,out.toString("UTF-8"));
                    }
                }catch(Exception e){serverResult(id,false,e.getMessage()==null?"Unable to connect to home server":e.getMessage());}
                finally{if(c!=null)c.disconnect();}
            });
        }
        @JavascriptInterface public void startSync(String json){
            try{
                org.json.JSONObject data=new org.json.JSONObject(json),users=data.optJSONObject("profiles");
                if(users==null)return;
                java.util.Iterator<String> names=users.keys();
                while(names.hasNext()){
                    String name=names.next(),arl=credentialVault==null?null:credentialVault.arlForProfile(name);
                    if(arl!=null){org.json.JSONObject profile=users.optJSONObject(name);if(profile!=null)profile.put("arl",arl);}
                }
                getLanSync().start(data.toString());
            }catch(Exception e){sendSyncEvent("{\"event\":\"error\",\"message\":\"Unable to start sync\"}");}
        }
        @JavascriptInterface public void receiveSync(String ip,int port,String code){try{getLanSync().receive(ip,port,code);}catch(Exception e){sendSyncEvent("{\"event\":\"error\",\"message\":\"Unable to connect\"}");}}
        @JavascriptInterface public void stopSync(){if(lanSync!=null)lanSync.stop();stopLocalSyncAdvert();sendSyncEvent("{\"event\":\"stopped\"}");}
        @JavascriptInterface public void scanSync(){discoverLocalSync();}
        @JavascriptInterface public void setActiveSyncProfile(String name){if(credentialVault!=null)credentialVault.setActiveProfile(name);}

        @JavascriptInterface public void get(String id, String address) {
            worker.execute(() -> {
                boolean ok = false; String response;
                try {
                    String resolvedAddress=address;
                    Uri uri = Uri.parse(address);
                    if (!"https".equalsIgnoreCase(uri.getScheme())) throw new Exception("Only HTTPS API calls permitted");
                    if ("eclipse.ultramax.vip".equalsIgnoreCase(uri.getHost())) {
                        String path=uri.getEncodedPath();
                        if(path!=null&&path.startsWith("/__aura_arl__/")){
                            String arl=credentialVault==null?null:credentialVault.arlForEclipse();
                            if(arl==null)throw new Exception("Save your Deezer ARL in Settings");
                            resolvedAddress="https://eclipse.ultramax.vip/"+arl+path.substring("/__aura_arl__".length());
                            if(uri.getEncodedQuery()!=null)resolvedAddress+="?"+uri.getEncodedQuery();
                        }
                    }
                    String host = uri.getHost();
                    if (host == null || !(host.equals("itunes.apple.com") || host.equals("api.deezer.com") || host.equals("eclipse.ultramax.vip") || host.equals("rss.applemarketingtools.com") || host.equals("lrclib.net")))
                        throw new Exception("Source host is not allowed");
                    HttpURLConnection conn = (HttpURLConnection) new URL(resolvedAddress).openConnection();
                    conn.setRequestMethod("GET");conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(10000);conn.setReadTimeout(15000);
                    conn.setRequestProperty("Accept","application/json");
                    conn.setRequestProperty("User-Agent","AuraAmp/1.0.7 Android");
                    int code = conn.getResponseCode();
                    if(code<200 || code>=300)throw new Exception("Source HTTP " +code);
                    String type=conn.getContentType();
                    if(type!=null&&!type.toLowerCase(java.util.Locale.ROOT).contains("json")&&!("itunes.apple.com".equals(host)&&type.toLowerCase(java.util.Locale.ROOT).contains("javascript")))throw new Exception("Source response was not JSON");
                    try(InputStream in=conn.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                        byte[] b=new byte[8192];int n;int sum=0;
                        while((n=in.read(b))!=-1){sum+=n;if(sum>3_000_000)throw new Exception("API response too large");out.write(b,0,n);}
                        response=out.toString("UTF-8");
                    } finally {conn.disconnect();}
                    ok=true;
                }catch(Exception ex){response=ex.getMessage()==null?"API request failed":ex.getMessage();}
                final boolean success=ok; final String message=response;
                runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript("window.AuraBridgeResponse("+JSONObject.quote(id)+","+success+","+JSONObject.quote(message)+")",null);});
            });
        }
        @JavascriptInterface public void syncCarPlaylists(String json){
            // Only IDs/titles are stored; no access tokens, URLs or credentials.
            worker.execute(()->{
                try{
                    org.json.JSONArray items=new org.json.JSONArray(json);
                    if(items.length()>300)throw new IllegalArgumentException("Too many playlists");
                    org.json.JSONArray safe=new org.json.JSONArray();
                    for(int i=0;i<items.length();i++){
                        JSONObject obj=items.optJSONObject(i);if(obj==null)continue;
                        String id=obj.optString("id",""),name=obj.optString("name","");
                        if(id.length()>120||name.length()>150||id.isEmpty())continue;
                        org.json.JSONArray tracks=obj.optJSONArray("tracks"),ids=new org.json.JSONArray();
                        if(tracks!=null)for(int j=0;j<Math.min(tracks.length(),1000);j++){
                            String song=tracks.optString(j,"");
                            if(song.length()>0&&song.length()<300)ids.put(song);
                        }
                        safe.put(new JSONObject().put("id",id).put("name",name).put("tracks",ids));
                    }
                    getSharedPreferences("aura_car_library",MODE_PRIVATE).edit().putString("playlists",safe.toString()).apply();
                }catch(Exception error){Log.w("AuraCar","Playlist sync rejected: "+error.getClass().getSimpleName());}
            });
        }
        @JavascriptInterface public void listDownloads(){worker.execute(()->emitOfflineIndex());}
        @JavascriptInterface public void deleteDownload(String id){worker.execute(()->{
            try{File f=offlineFile(id);if(f.exists())f.delete();JSONObject items=readOfflineIndex();items.remove(id);writeOfflineIndex(items);emitOfflineIndex();}
            catch(Exception ex){downloadEvent(id,false,ex.getMessage());}
        });}
        @JavascriptInterface public void downloadTrack(String id,String address,String title,String artist,String album,String cover,boolean wifiOnly){
            worker.execute(()->{
                File temp=null; HttpURLConnection c=null;
                try{
                    if(id==null||id.length()>300)throw new Exception("Invalid track identifier");
                    URL u=new URL(address);if(!"https".equalsIgnoreCase(u.getProtocol()))throw new Exception("HTTPS required");
                    if(u.getHost()==null||u.getHost().isEmpty()||u.getHost().equalsIgnoreCase("api.deezer.com"))throw new Exception("Unavailable audio download");
                    if(wifiOnly){ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
                        NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
                        if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))throw new Exception("Connect to Wi-Fi or disable Wi-Fi only in Settings");}
                    for(int redirect=0;redirect<=4;redirect++){
                        c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","audio/*,application/octet-stream");
                        int code=c.getResponseCode();
                        if(code>=300&&code<400){
                            if(redirect==4)throw new Exception("Too many audio redirects");
                            String location=c.getHeaderField("Location");if(location==null)throw new Exception("Audio redirect has no location");
                            URL next=new URL(u,location);if(!"https".equalsIgnoreCase(next.getProtocol()))throw new Exception("Insecure audio redirect refused");
                            c.disconnect();c=null;u=next;continue;
                        }
                        if(code<200||code>=300)throw new Exception("Audio server returned HTTP "+code);
                        break;
                    }
                    String mime=c.getContentType();if(mime!=null&&(mime.contains("text/html")||mime.contains("application/json")||mime.contains("mpegurl")||mime.contains("dash+xml")))throw new Exception("Music source returned a page, not audio");
                    long size=c.getContentLengthLong();if(size>500L*1024*1024)throw new Exception("File exceeds 500 MB limit");
                    File target=offlineFile(id);temp=new File(target.getParentFile(),target.getName()+".part");
                    long total=0;try(InputStream in=c.getInputStream();FileOutputStream os=new FileOutputStream(temp)){
                        byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){total+=n;if(total>500L*1024*1024)throw new Exception("File too large");os.write(b,0,n);}os.getFD().sync();}
                    if(total<1024)throw new Exception("Downloaded file was empty or invalid");
                    if(target.exists())target.delete();if(!temp.renameTo(target))throw new Exception("Could not save offline file");
                    JSONObject item=new JSONObject();String[] parts=id.split("\\|",2);item.put("id",parts.length==2?parts[1]:id);
                    item.put("origin",parts.length==2?parts[0]:"ultramax");item.put("providerId",parts.length==2?parts[1]:id);
                    item.put("title",title);item.put("artist",artist);item.put("album",album);item.put("cover",cover);item.put("bytes",total);
                    JSONObject all=readOfflineIndex();all.put(id,item);writeOfflineIndex(all);downloadEvent(id,true,"Saved offline");emitOfflineIndex();
                }catch(Exception ex){if(temp!=null)temp.delete();downloadEvent(id,false,ex.getMessage()==null?"Download failed":ex.getMessage());}
                finally{if(c!=null)c.disconnect();}
            });
        }
        @JavascriptInterface public void output(){runOnUiThread(()->{
            try{ startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); }
            catch(Exception ignored){}
        });}
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        credentialVault = new AuraCredentialVault(this);
        auraUpdater = new AuraUpdateAgent(this, BuildConfig.AURA_UPDATE_URL);
        auraUpdater.checkIfDue();
        // Initialize the UI first. Media3/LAN discovery must not block app launch.
        webView=new WebView(this);webView.setBackgroundColor(Color.rgb(20,21,28));webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        WebSettings st=webView.getSettings();st.setJavaScriptEnabled(true);st.setDomStorageEnabled(true);
        st.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);
        st.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        st.setAllowFileAccess(true);st.setAllowContentAccess(true);st.setAllowFileAccessFromFileURLs(false);
        st.setAllowUniversalAccessFromFileURLs(false);st.setMediaPlaybackRequiresUserGesture(true);
        st.setDefaultTextEncodingName("UTF-8");
        webView.addJavascriptInterface(new NativeApi(),"AuraNative");
        webView.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                super.onPageFinished(view,url);
                // Initialize Media3 only after the local UI is visible.
                view.postDelayed(()->initMediaSafely(),500);
                // Cast SDK / discovery initialize after the WebView UI and source are ready.
                view.postDelayed(()->initCastAfterUiReady(),1600);
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,WebResourceError error){
                if(req.isForMainFrame())Log.e("AuraAmp","Main UI load error: "+error.getDescription());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                Uri target=req.getUrl();if("file".equals(target.getScheme())&&"android_asset".equals(target.getHost()))return false;
                if("https".equals(target.getScheme()))try{startActivity(new Intent(Intent.ACTION_VIEW,target));}catch(Exception ignored){}
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> files,FileChooserParams params){
                if(pendingFile!=null)pendingFile.onReceiveValue(null);pendingFile=files;
                try{startActivityForResult(params.createIntent(),FILE_REQUEST);return true;}
                catch(Exception ex){pendingFile=null;return false;}
            }
        });
        try{
            FrameLayout container=new FrameLayout(this);
            container.addView(webView,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(container);
            initMediaSafely();
            castHost=container;
            // Initialize Cast only after the interface has loaded to avoid startup failures.
            webView.loadUrl("file:///android_asset/index.html");
        }catch(Throwable error){
            Log.e("AuraAmp","Unable to start UI",error);
            TextView fallback=new TextView(this);
            fallback.setText("AURA AMP could not start. Please send the Android crash log.\n"+error.getClass().getSimpleName());
            fallback.setTextColor(Color.WHITE);fallback.setBackgroundColor(Color.rgb(25,28,37));
            fallback.setPadding(30,80,30,30);setContentView(fallback);
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==FILE_REQUEST&&pendingFile!=null){pendingFile.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode,data));pendingFile=null;}
    }
    @Override public void onBackPressed(){if(webView!=null)webView.evaluateJavascript("document.querySelector('[data-nav=discover]')?.click()",null);else super.onBackPressed();}
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        if(spotifyImporter!=null)spotifyImporter.onRedirect(intent==null?null:intent.getData());
    }
    @Override protected void onResume(){super.onResume();if(auraUpdater!=null){auraUpdater.onResume();auraUpdater.checkIfDue();}updateHandler.removeCallbacks(updatePulse);updateHandler.postDelayed(updatePulse,30L*60*1000);}
    @Override protected void onPause(){updateHandler.removeCallbacks(updatePulse);super.onPause();}
    @Override protected void onDestroy(){updateHandler.removeCallbacks(updatePulse);if(auraUpdater!=null)auraUpdater.close();if(castSender!=null)castSender.close();if(spotifyImporter!=null)spotifyImporter.close();if(pendingFile!=null)pendingFile.onReceiveValue(null);worker.shutdownNow();if(controllerFuture!=null)MediaController.releaseFuture(controllerFuture);if(lanSync!=null)lanSync.close();stopLocalSyncAdvert();
        if(syncNsd!=null&&syncDiscovery!=null)try{syncNsd.stopServiceDiscovery(syncDiscovery);}catch(Exception ignored){}
        if(syncMulticastLock!=null&&syncMulticastLock.isHeld())syncMulticastLock.release();
        if(webView!=null){webView.destroy();webView=null;}super.onDestroy();}
}
