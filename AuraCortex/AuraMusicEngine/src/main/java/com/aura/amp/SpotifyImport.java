package com.aura.amp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import java.net.URLEncoder;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Spotify playlist metadata import via Authorization Code + PKCE. Never uses a client secret. */
final class SpotifyImport {
    static final String CALLBACK="http://127.0.0.1:8765/callback";
    private final Activity activity;
    private final java.util.function.BiConsumer<String,String> callback;
    private final SecureRandom random=new SecureRandom();
    private volatile String accessToken="";
    private volatile long tokenExpiresAt=0;
    private volatile ServerSocket listener;
    private final java.util.concurrent.atomic.AtomicBoolean authenticating=new java.util.concurrent.atomic.AtomicBoolean(false);
    SpotifyImport(Activity activity, java.util.function.BiConsumer<String,String> callback) {
        this.activity=activity;this.callback=callback;
    }
    private static String enc(String text){try{return URLEncoder.encode(text,"UTF-8");}catch(Exception e){throw new IllegalArgumentException(e);}}
    private String nonce(int length){byte[] bytes=new byte[length];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String challenge(String text)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
    static final String APP_CALLBACK="auraamp://spotify/callback";
    private final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Uri>> pendingRedirect=new java.util.concurrent.atomic.AtomicReference<>();
    private volatile String pendingState="";

    void onRedirect(Uri uri){
        if(uri==null||!APP_CALLBACK.equals(uri.getScheme()+"://"+uri.getHost()+uri.getPath()))return;
        java.util.concurrent.CompletableFuture<Uri> pending=pendingRedirect.get();
        if(pending!=null)pending.complete(uri);
    }
    void login(String clientId){
        if(clientId==null||!clientId.matches("[a-fA-F0-9]{32}")){sendError("Spotify Client ID is missing or invalid in this Android build");return;}
        if(!authenticating.compareAndSet(false,true)){sendError("Spotify sign-in is already in progress");return;}
        new Thread(()->{
            java.util.concurrent.CompletableFuture<Uri> pending=new java.util.concurrent.CompletableFuture<>();
            pendingRedirect.set(pending);
            try{
                String state=nonce(24),verifier=nonce(64);
                pendingState=state;
                String authorization="https://accounts.spotify.com/authorize?response_type=code&client_id="+enc(clientId)
                        +"&redirect_uri="+enc(APP_CALLBACK)+"&scope="+enc("playlist-read-private playlist-read-collaborative")
                        +"&code_challenge_method=S256&code_challenge="+enc(challenge(verifier))+"&state="+enc(state);
                activity.runOnUiThread(()->activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(authorization))));
                Uri uri;
                try {uri=pending.get(120,java.util.concurrent.TimeUnit.SECONDS);}
                catch(java.util.concurrent.TimeoutException e){throw new Exception("Spotify redirect not received. Register auraamp://spotify/callback in Spotify Developer Dashboard, then retry.");}
                if(!state.equals(uri.getQueryParameter("state")))throw new Exception("Spotify login state mismatch");
                if(uri.getQueryParameter("error")!=null)throw new Exception("Spotify authorization declined");
                String code=uri.getQueryParameter("code");
                if(code==null||code.isEmpty())throw new Exception("Spotify did not return an authorization code");
                JSONObject token=postToken("grant_type=authorization_code&code="+enc(code)+"&redirect_uri="+enc(APP_CALLBACK)+"&client_id="+enc(clientId)+"&code_verifier="+enc(verifier));
                accessToken=token.optString("access_token","");
                if(accessToken.isEmpty())throw new Exception("Spotify did not return an access token");
                tokenExpiresAt=System.currentTimeMillis()+Math.max(60,token.optInt("expires_in",3600)-60)*1000L;
                JSONObject playlists=new JSONObject();JSONArray items=new JSONArray();
                for(int offset=0;offset<1000;offset+=50){
                    JSONObject page=get("https://api.spotify.com/v1/me/playlists?limit=50&offset="+offset);
                    JSONArray arr=page.optJSONArray("items");if(arr==null||arr.length()==0)break;
                    for(int j=0;j<arr.length();j++){JSONObject playlist=arr.optJSONObject(j);if(playlist!=null&&!playlist.optString("id","").isEmpty())items.put(playlist);}
                    if(arr.length()<50)break;
                }
                playlists.put("items",items);callback.accept("AuraSpotifyPlaylists",playlists.toString());
            }catch(Exception e){sendError(e.getMessage());}
            finally{pendingRedirect.compareAndSet(pending,null);pendingState="";authenticating.set(false);}
        },"AuraSpotifyPKCE").start();
    }
    void importPlaylist(String id){
        if(!id.matches("[A-Za-z0-9]{1,64}")){sendError("Invalid playlist ID");return;}
        new Thread(()->{
            try{
                if(accessToken.isEmpty()||System.currentTimeMillis()>tokenExpiresAt)throw new Exception("Spotify authorization expired; reconnect");
                JSONArray items=new JSONArray();
                for(int offset=0;offset<2000;offset+=50){
                    JSONObject page=get("https://api.spotify.com/v1/playlists/"+id+"/items?limit=50&offset="+offset);
                    JSONArray tracks=page.optJSONArray("items");if(tracks==null||tracks.length()==0)break;
                    for(int i=0;i<tracks.length();i++){
                        JSONObject entry=tracks.optJSONObject(i);
                        if(entry==null)continue;
                        JSONObject track=entry.optJSONObject("item");
                        if(track==null)track=entry.optJSONObject("track");
                        if(track==null||track.optString("id","").isEmpty()||!"track".equals(track.optString("type","track")))continue;
                        items.put(entry);
                    }
                    if(tracks.length()<50)break;
                }
                JSONObject result=new JSONObject().put("playlistId",id).put("items",items);
                callback.accept("AuraSpotifyTracks",result.toString());
            }catch(Exception e){sendPlaylistError(id,e.getMessage());}
        },"AuraSpotifyPlaylistImport").start();
    }
    private JSONObject postToken(String body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("https://accounts.spotify.com/api/token").openConnection();
        try{c.setRequestMethod("POST");c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
            try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}
            return readJson(c);
        }finally{c.disconnect();}
    }
    private JSONObject get(String address)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        try{c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Authorization","Bearer "+accessToken);return readJson(c);}finally{c.disconnect();}
    }
    private JSONObject readJson(HttpURLConnection conn)throws Exception{
        int code=conn.getResponseCode();
        if(code<200||code>299){
            String reason="";
            try(java.io.InputStream failure=conn.getErrorStream()){
                if(failure!=null){byte[] buf=new byte[4096];int length=failure.read(buf);if(length>0){String raw=new String(buf,0,length,StandardCharsets.UTF_8);JSONObject detail=new JSONObject(raw);reason=detail.optString("error_description","");JSONObject nested=detail.optJSONObject("error");if(nested!=null)reason=nested.optString("message",reason);else if(reason.isEmpty())reason=detail.optString("error","");}}
            }catch(Exception ignored){}
            String message;
            if(code==401)message="Spotify authorization expired or was rejected. Connect Spotify again.";
            else if(code==403)message="Spotify denied playlist access (HTTP 403).";
            else if(code==429)message="Spotify rate limit reached (HTTP 429). Please retry later.";
            else message="Spotify API HTTP "+code;
            if(!reason.isEmpty()&&reason.length()<250)message+=": "+reason;
            throw new Exception(message);
        }
        try(java.io.InputStream stream=conn.getInputStream()){
            byte[] b=new byte[8192];int count,total=0;
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            while((count=stream.read(b))!=-1){total+=count;if(total>4_000_000)throw new Exception("Spotify response too large");out.write(b,0,count);}
            return new JSONObject(out.toString("UTF-8"));
        }
    }
    private void sendPlaylistError(String id,String error){try{callback.accept("AuraSpotifyTracks",new JSONObject().put("playlistId",id).put("error",error==null?"Spotify playlist import failed":error).toString());}catch(Exception ignored){}}
    private void sendError(String error){try{callback.accept("AuraSpotifyPlaylists",new JSONObject().put("error",error==null?"Spotify import failed":error).toString());}catch(Exception ignored){}}
    void close(){java.util.concurrent.CompletableFuture<Uri> waiting=pendingRedirect.getAndSet(null);if(waiting!=null)waiting.cancel(true);}
}
