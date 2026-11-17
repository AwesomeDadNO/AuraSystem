package no.aura.os.v2.connect.v1;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;

/**
 * Versioned, metadata-only local sync journal.
 *
 * Records local changes while devices are offline. It NEVER starts network
 * connections and NEVER stores or exports ARL, PINs, credentials, audio bytes,
 * secret source URLs or a permission to pair with another device.
 *
 * Change IDs and tombstones survive process restarts. Encrypted, profile-bound,
 * peer-authenticated transport and acknowledgment is a separate required gate.
 */
public final class AuraSyncJournal {
    private static final String PREFS="aura_amp_sync_journal_v1";
    private static final int MAX_OPERATIONS=1200;
    private static final int MAX_JSON_BYTES=330_000;
    private final SharedPreferences prefs;
    private final String deviceId;

    public AuraSyncJournal(Context context) {
        prefs=context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String candidate=prefs.getString("device_id",null);
        if(candidate==null||!candidate.matches("[0-9a-fA-F-]{36}")){
            candidate=UUID.randomUUID().toString();
            prefs.edit().putString("device_id",candidate).apply();
        }
        deviceId=candidate;
    }

    private static String encodeId(String id){
        return id.replaceAll("[^a-zA-Z0-9_:.-]","_");
    }

    private static String trackId(Object raw) {
        if(raw instanceof JSONObject)raw=((JSONObject)raw).optString("id","");
        String value=String.valueOf(raw==null?"":raw);
        return value.length()>180?"":value;
    }

    private static JSONArray trackIds(JSONArray tracks){
        JSONArray result=new JSONArray();
        if(tracks==null)return result;
        HashSet<String> visited=new HashSet<>();
        for(int i=0;i<tracks.length()&&result.length()<600;i++){
            String id=trackId(tracks.opt(i));
            if(!id.isEmpty()&&visited.add(id))result.put(id);
        }
        return result;
    }

    private static JSONObject normalized(JSONObject snapshot) throws Exception {
        if(snapshot==null)throw new IllegalArgumentException("Missing metadata snapshot");
        JSONObject values=new JSONObject();
        JSONArray playlists=snapshot.optJSONArray("playlists");
        if(playlists!=null)for(int i=0;i<playlists.length()&&i<250;i++){
            JSONObject p=playlists.optJSONObject(i);
            if(p==null)continue;
            String id=p.optString("id","");
            if(id.isEmpty()||id.length()>140)continue;
            JSONObject item=new JSONObject().put("id",id)
                .put("title",p.optString("title",p.optString("name","Playlist")).substring(
                    0,Math.min(120,p.optString("title",p.optString("name","Playlist")).length())))
                .put("tracks",trackIds(p.optJSONArray("tracks")!=null?p.optJSONArray("tracks"):
                                      p.optJSONArray("items")!=null?p.optJSONArray("items"):
                                      p.optJSONArray("ids")));
            values.put("amp.playlists/"+encodeId(id),item);
        }
        JSONArray favorites=snapshot.optJSONArray("favorites");
        if(favorites!=null)for(int i=0;i<favorites.length()&&i<700;i++){
            String id=trackId(favorites.opt(i));
            if(id.isEmpty())continue;
            values.put("amp.favorites/"+encodeId(id),new JSONObject().put("id",id));
        }
        return values;
    }

    private static JSONObject object(String json) {
        try{return new JSONObject(json==null?"{}":json);}
        catch(Exception ignored){return new JSONObject();}
    }

    private synchronized JSONObject state(){return object(prefs.getString("state","{}"));}
    private synchronized JSONArray journal(){
        try{return new JSONArray(prefs.getString("changes","[]"));}
        catch(Exception ignored){return new JSONArray();}
    }

    public synchronized String status(){
        JSONObject out=new JSONObject();
        try{
            out.put("protocol",1);
            out.put("deviceId",deviceId);
            out.put("pending",journal().length());
            out.put("lastChangeMs",prefs.getLong("last_changed",0));
            out.put("lastSyncMs",prefs.getLong("last_sync",0));
            out.put("transport","unpaired");
            out.put("credentialsIncluded",false);
        }catch(Exception ignored){}
        return out.toString();
    }

    public synchronized String recordSnapshot(String profileId,String raw){
        JSONObject summary=new JSONObject();
        try{
            if(profileId==null||!profileId.matches("[a-zA-Z0-9_ -]{1,100}"))
                throw new IllegalArgumentException("Invalid local profile");
            if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES)
                throw new IllegalArgumentException("Metadata too large");
            JSONObject latest=normalized(new JSONObject(raw));
            JSONObject all=state(), previous=all.optJSONObject(profileId);
            if(previous==null)previous=new JSONObject();
            JSONArray changes=journal();
            int added=0;
            HashSet<String> keys=new HashSet<>();
            Iterator<String> fresh=latest.keys();
            while(fresh.hasNext())keys.add(fresh.next());
            Iterator<String> previousKeys=previous.keys();
            while(previousKeys.hasNext())keys.add(previousKeys.next());
            long seq=prefs.getLong("sequence",0);
            long changedAt=System.currentTimeMillis();
            for(String key:keys) {
                Object now=latest.opt(key),before=previous.opt(key);
                String a=now==null?"":now.toString(),c=before==null?"":before.toString();
                if(a.equals(c))continue;
                if(changes.length()>=MAX_OPERATIONS)
                    throw new IllegalStateException("Sync log full; no changes discarded");
                String kind=key.startsWith("amp.playlists/")?"amp.playlists":"amp.favorites";
                String id=key.substring(kind.length()+1);
                JSONObject operation=new JSONObject()
                    .put("changeId",UUID.randomUUID().toString())
                    .put("originDeviceId",deviceId)
                    .put("profileId",profileId)
                    .put("sequence",++seq)
                    .put("timestampMs",changedAt)
                    .put("collection",kind)
                    .put("id",id)
                    .put("deleted",now==null);
                if(now!=null)operation.put("data",now);
                changes.put(operation);added++;
            }
            all.put(profileId,latest);
            prefs.edit().putString("state",all.toString())
                .putString("changes",changes.toString())
                .putLong("sequence",seq)
                .putLong("last_changed",added==0?prefs.getLong("last_changed",0):changedAt)
                .apply();
            summary.put("ok",true).put("newChanges",added).put("pending",changes.length());
        }catch(Exception e){
            summary=new JSONObject();
            try{summary.put("ok",false).put("error",e.getMessage());}
            catch(Exception ignored){}
        }
        return summary.toString();
    }

    /**
     * This deliberately exports only metadata from an already-sanitized,
     * profile-scoped native state snapshot. No history, user data, ARL or media.
     * Used only AFTER SRP authentication and explicit TV approval.
     */
    synchronized String exportApprovedMetadata(String profileId) {
        JSONObject envelope=new JSONObject();
        try{
            JSONObject profileState=state().optJSONObject(profileId);
            if(profileId==null||profileState==null)return "";
            JSONArray favorites=new JSONArray(),playlists=new JSONArray();
            Iterator<String> it=profileState.keys();
            while(it.hasNext()){
                String key=it.next();
                JSONObject item=profileState.optJSONObject(key);
                if(item==null)continue;
                if(key.startsWith("amp.favorites/"))favorites.put(item.optString("id",""));
                else if(key.startsWith("amp.playlists/"))playlists.put(item);
            }
            JSONObject profile=new JSONObject().put("name",profileId)
                .put("favorites",favorites).put("playlists",playlists);
            envelope.put("protocol",2).put("activeUser",profileId)
                .put("profiles",new JSONObject().put(profileId,profile));
            String value=envelope.toString();
            if(value.getBytes(StandardCharsets.UTF_8).length>100_000)return "";
            return value;
        }catch(Exception ignored){return "";}
    }

    /**
     * Validate and re-serialize approved peer metadata to avoid smuggling ARL,
     * session tokens, arbitrary profile IDs or media URLs into WebView state.
     * Scope incoming data to the active TV profile selected at approval.
     */
    static String sanitizeApprovedIncoming(String json,String targetProfile)throws Exception{
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>100_000)
            throw new IllegalArgumentException("Metadata snapshot too large");
        if(targetProfile==null||!targetProfile.matches("[a-zA-Z0-9_ -]{1,100}"))
            throw new IllegalArgumentException("Invalid TV profile");
        JSONObject data=new JSONObject(json);
        if(data.optInt("protocol")!=2)throw new IllegalArgumentException("Unsupported metadata version");
        JSONObject profiles=data.optJSONObject("profiles");
        String sender=data.optString("activeUser","");
        if(profiles==null||sender.isEmpty()||!profiles.has(sender)||profiles.length()!=1)
            throw new IllegalArgumentException("Ambiguous profile selection");
        JSONObject values=normalized(profiles.optJSONObject(sender));
        JSONArray lists=new JSONArray(),favorites=new JSONArray();
        Iterator<String> it=values.keys();
        while(it.hasNext()){
            String key=it.next();
            JSONObject item=values.optJSONObject(key);
            if(item==null)continue;
            if(key.startsWith("amp.playlists/"))lists.put(item);
            else if(key.startsWith("amp.favorites/"))favorites.put(item.optString("id",""));
        }
        JSONObject incoming=new JSONObject().put("name",targetProfile)
            .put("playlists",lists).put("favorites",favorites);
        return new JSONObject().put("protocol",2).put("activeUser",targetProfile)
            .put("profiles",new JSONObject().put(targetProfile,incoming)).toString();
    }

    // ---- amp.playlists.v1 (AURA Connect remote session) -------------------------------------------------------
    // The phone pushes one playlist (or a part of one) / a batch of liked songs per encrypted frame. Only bounded
    // song metadata passes: id, title, artist, album and an https cover. Never stream URLs, ARL, tokens or files.

    private static final int PUSH_MAX_TRACKS=100;

    private static String clip(String v,int max){v=v==null?"":v.replaceAll("[\\p{Cntrl}]","");return v.length()>max?v.substring(0,max):v;}

    private static JSONObject safeTrack(JSONObject t){
        if(t==null)return null;
        String id=t.optString("id","");
        if(!id.matches("[a-zA-Z0-9_:.|\\-]{1,180}"))return null;
        String title=clip(t.optString("title",""),180);
        if(title.isEmpty())return null;
        String cover=t.optString("cover","");
        if(!(cover.startsWith("https://")&&cover.length()<=500))cover="";
        try{
            return new JSONObject().put("id",id).put("title",title)
                .put("artist",clip(t.optString("artist",""),180)).put("album",clip(t.optString("album",""),180))
                .put("cover",cover).put("source","eclipse");
        }catch(Exception e){return null;}
    }

    private static JSONArray safeTracks(JSONArray in,JSONArray ids)throws Exception{
        JSONArray out=new JSONArray();
        if(in==null)throw new IllegalArgumentException("Missing tracks");
        if(in.length()>PUSH_MAX_TRACKS)throw new IllegalArgumentException("Too many tracks in one part");
        HashSet<String> seen=new HashSet<>();
        for(int i=0;i<in.length();i++){
            JSONObject t=safeTrack(in.optJSONObject(i));
            if(t!=null&&seen.add(t.getString("id"))){out.put(t);ids.put(t.getString("id"));}
        }
        return out;
    }

    private static String snapshotFor(String profile,JSONArray playlists,JSONArray favorites,JSONArray tracks)throws Exception{
        if(profile==null||!profile.matches("[a-zA-Z0-9_ -]{1,100}"))throw new IllegalArgumentException("Invalid TV profile");
        JSONObject incoming=new JSONObject().put("name",profile).put("playlists",playlists).put("favorites",favorites);
        return new JSONObject().put("protocol",2).put("activeUser",profile)
            .put("profiles",new JSONObject().put(profile,incoming)).put("tracks",tracks).toString();
    }

    /** {op:"playlist-push", playlist:{id,title,tracks:[{id,title,artist,album,cover}]}} -> merge-ready snapshot. */
    static String sanitizePlaylistPush(JSONObject frame,String targetProfile)throws Exception{
        JSONObject p=frame==null?null:frame.optJSONObject("playlist");
        if(p==null)throw new IllegalArgumentException("Missing playlist");
        String id=p.optString("id","");
        if(!id.matches("[a-zA-Z0-9_:.|\\-]{1,140}"))throw new IllegalArgumentException("Invalid playlist id");
        String title=clip(p.optString("title","Playlist"),120);
        JSONArray ids=new JSONArray();
        JSONArray tracks=safeTracks(p.optJSONArray("tracks"),ids);
        JSONObject list=new JSONObject().put("id",id).put("title",title.isEmpty()?"Playlist":title).put("tracks",ids);
        return snapshotFor(targetProfile,new JSONArray().put(list),new JSONArray(),tracks);
    }

    /** {op:"favorites-push", tracks:[{id,title,artist,album,cover}]} -> merge-ready snapshot (liked songs). */
    static String sanitizeFavoritesPush(JSONObject frame,String targetProfile)throws Exception{
        JSONArray ids=new JSONArray();
        JSONArray tracks=safeTracks(frame==null?null:frame.optJSONArray("tracks"),ids);
        return snapshotFor(targetProfile,new JSONArray(),ids,tracks);
    }

    /**
     * Native transport use ONLY after verified peer identity and granted
     * profile scope. Do not expose through JavaScript bridges or mDNS.
     */
    synchronized JSONArray pendingForAuthorizedPeer(String profileId,int limit){
        JSONArray result=new JSONArray();
        if(profileId==null||limit<=0)return result;
        JSONArray all=journal();
        for(int i=0;i<all.length()&&result.length()<Math.min(limit,100);i++){
            JSONObject change=all.optJSONObject(i);
            if(change!=null&&profileId.equals(change.optString("profileId")))result.put(change);
        }
        return result;
    }

    /** Future encrypted peer ACK path; never invoke from untrusted WebView. */
    synchronized void acknowledgeAuthorizedChanges(HashSet<String> ids){
        if(ids==null||ids.isEmpty())return;
        JSONArray all=journal(),remaining=new JSONArray();
        for(int i=0;i<all.length();i++){
            JSONObject item=all.optJSONObject(i);
            if(item!=null&&!ids.contains(item.optString("changeId")))remaining.put(item);
        }
        prefs.edit().putString("changes",remaining.toString())
            .putLong("last_sync",System.currentTimeMillis()).apply();
    }
}
