package no.aura.os.v2.connect.v1;

import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embedded AURA Launcher LAN receiver.
 * _aura-connect._tcp. discovery exposes only a name and socket port.
 * A fresh random six-digit code, SRP-6a mutual evidence, explicit TV acceptance,
 * AES-256-GCM and a 5-minute pairing window protect media transport.
 * No unauthenticated remote command or playback endpoint exists.
 */
public final class AuraConnectHub {
    public static final String SERVICE_TYPE="_aura-connect._tcp.";
    public static final String SERVICE_NAME="AURA AMP TV";
    private static final SecureRandom RNG=new SecureRandom();
    private static final long PIN_TTL_MS=5*60_000L;
    private static final long MAX_AUDIO=100L*1024L*1024L;
    private static final Object GUARD=new Object();
    private static volatile ServerSocket server;
    private static volatile String pin;
    private static volatile long expires;
    private static volatile String pendingDevice;
    private static volatile boolean pendingEclipseCredential;
    private static volatile CountDownLatch pendingLatch;
    private static volatile boolean pendingApproved;
    private static volatile String message="AURA Connect is ready";
    private static volatile String activeProfile;
    private static final AtomicInteger tries=new AtomicInteger();
    private static final java.util.concurrent.Semaphore peers=new java.util.concurrent.Semaphore(4);
    private static NsdManager nsd;
    private static NsdManager.RegistrationListener registration;
    private static Context app;

    public interface Playback { void play(File file,String title,String artist,String artwork) throws IOException; }
    public interface MetadataSink { void apply(String json,String profile) throws IOException; }
    public interface CredentialSink { boolean saveEclipseArl(String value,String profile); }
    public interface RemoteController {
        JSONObject status(String profile) throws Exception;
        void command(String action,String profile) throws Exception;
        void queueLocal(File file,String title,String artist,String artwork,String profile) throws Exception;
        void queueCatalog(JSONObject track,String profile) throws Exception;
    }
    private static volatile Playback playback;
    private static volatile MetadataSink metadataSink;
    private static volatile CredentialSink credentialSink;
    private static volatile RemoteController remoteController;
    public static void setRemoteController(RemoteController callback){remoteController=callback;}
    public static void setCredentialSink(CredentialSink callback){credentialSink=callback;}
    public static void setMetadataSink(MetadataSink callback){metadataSink=callback;}
    public static void setPlayback(Playback handler){playback=handler;}
    public static void stop(){
        synchronized(GUARD){
            closePairing();
            ServerSocket existing=server;server=null;
            if(existing!=null)try{existing.close();}catch(Exception ignored){}
            if(nsd!=null&&registration!=null)try{nsd.unregisterService(registration);}catch(Exception ignored){}
            registration=null;
            playback=null;metadataSink=null;credentialSink=null;remoteController=null;
        }
    }
    private AuraConnectHub(){}
    public static String getMessage(){return message;}
    public static String getPendingDevice(){return pendingDevice;}
    public static boolean isEclipseCredentialRequested(){return pendingEclipseCredential;}
    public static boolean isRunning(){return server!=null&&!server.isClosed();}
    public static int getPort(){ServerSocket s=server;return s==null?-1:s.getLocalPort();}
    public static String pairingCode(){return System.currentTimeMillis()<expires?pin:null;}
    public static void setProfile(String userId){
        synchronized(GUARD){
            if(userId==null||!userId.equals(activeProfile)){
                activeProfile=userId;
                pin=null;
                expires=0;
                if(pendingLatch!=null){pendingApproved=false;pendingLatch.countDown();}
            }
        }
    }
    public static String openPairing(Context context,String profileId){
        setProfile(profileId);
        start(context);
        synchronized(GUARD) {
            if(!isRunning()) return null;
            pin=String.format(java.util.Locale.US,"%06d",RNG.nextInt(1_000_000));
            expires=System.currentTimeMillis()+PIN_TTL_MS;
            tries.set(0);
            message="Enter this code in AURA Amp. Approve the device on this TV.";
            return pin;
        }
    }
    /** Hide/Back invalidates the PIN immediately; a previously started transfer
     * can finish, but no new pairing can be approved without the visible dialog.
     */
    public static void closePairing() {
        synchronized(GUARD) {
            pin=null;
            expires=0;
            pendingApproved=false;
            if(pendingLatch!=null)pendingLatch.countDown();
            pendingLatch=null;
            pendingDevice=null;pendingEclipseCredential=false;
            message="AURA Connect pairing closed";
        }
    }

    public static void approve(boolean accepted){
        synchronized(GUARD){
            if(pendingLatch!=null){pendingApproved=accepted;pendingLatch.countDown();}
        }
    }
    public static void start(Context context){
        synchronized(GUARD){
            if(isRunning())return;
            app=context.getApplicationContext();
            try{
                ServerSocket socket=new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(0),8);
                server=socket;
                Thread worker=new Thread(()->serve(socket),"AURA-Connect-Receiver");
                worker.setDaemon(true);
                worker.start();
                advertise(socket.getLocalPort());
                message="AURA Connect ready on this network";
            }catch(Exception e){message="AURA Connect unavailable: "+e.getMessage();}
        }
    }
    private static void advertise(int port){
        try{
            nsd=(NsdManager)app.getSystemService(Context.NSD_SERVICE);
            if(nsd==null)return;
            NsdServiceInfo info=new NsdServiceInfo();
            info.setServiceName(SERVICE_NAME);
            info.setServiceType(SERVICE_TYPE);
            info.setPort(port);
            info.setAttribute("protocol","1");
            info.setAttribute("kind","amp-tv");
            registration=new NsdManager.RegistrationListener(){
                public void onServiceRegistered(NsdServiceInfo value){}
                public void onRegistrationFailed(NsdServiceInfo value,int code){
                    message="LAN discovery unavailable (error "+code+")";
                }
                public void onServiceUnregistered(NsdServiceInfo value){}
                public void onUnregistrationFailed(NsdServiceInfo value,int code){}
            };
            nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
        }catch(Exception e){message="LAN discovery unavailable: "+e.getMessage();}
    }
    private static void serve(ServerSocket listening){
        while(!listening.isClosed()){
            try{
                Socket peer=listening.accept();
                if(!peers.tryAcquire()){peer.close();continue;}
                Thread worker=new Thread(()->{
                    try{process(peer);}finally{peers.release();}
                },"AURA-Connect-Peer");
                worker.setDaemon(true);
                worker.start();
            }catch(IOException e){if(!listening.isClosed())message="Receiver error: "+e.getMessage();}
        }
    }
    private static boolean privateLan(InetAddress ip){
        return ip.isSiteLocalAddress()||ip.isLinkLocalAddress()||ip.isLoopbackAddress();
    }
    private static void process(Socket socket){
        try(Socket peer=socket){
            if(!privateLan(peer.getInetAddress()))return;
            peer.setSoTimeout(12_000);
            DataInputStream input=new DataInputStream(new BufferedInputStream(peer.getInputStream()));
            DataOutputStream output=new DataOutputStream(new BufferedOutputStream(peer.getOutputStream()));
            JSONObject hello=AuraConnectWire.read(input);
            if(!"hello".equals(hello.optString("op"))||!"1".equals(hello.optString("protocol")))return;
            final String secret;
            final String sessionProfile;
            synchronized(GUARD){
                if(pin==null||System.currentTimeMillis()>=expires||tries.get()>=5){
                    AuraConnectWire.write(output,new JSONObject().put("error","Pairing closed"));
                    return;
                }
                secret=pin;
                sessionProfile=activeProfile;
            }
            AuraConnectWire.Server srp=new AuraConnectWire.Server(secret);
            AuraConnectWire.write(output,srp.hello().put("capabilities",
                new org.json.JSONArray().put("amp.metadata.v1").put("amp.remote.v1").put("amp.playlists.v1")));
            JSONObject proof=AuraConnectWire.read(input);
            if(tries.incrementAndGet()>5)return;
            srp.verify(proof);
            String serverProof=srp.evidence();
            byte[] aes=srp.sessionKey();
            AuraConnectWire.Channel channel = new AuraConnectWire.Channel(aes);
            AuraConnectWire.write(output,new JSONObject().put("M2",serverProof));
            String device=proof.optString("device","AURA Amp").replaceAll("[^a-zA-Z0-9 _-]","").trim();
            if(device.isEmpty())device="AURA Amp";
            CountDownLatch latch=new CountDownLatch(1);
            synchronized(GUARD) {
                if(System.currentTimeMillis()>=expires||pin==null||
                    !secret.equals(pin)||!sessionProfile.equals(activeProfile)||pendingLatch!=null)return;
                pendingDevice=device;
                pendingEclipseCredential=proof.optBoolean("eclipseCredentialRequested",false);
                pendingApproved=false;
                pendingLatch=latch;
                message="PAIR_REQUEST: "+device+(pendingEclipseCredential?" · Eclipse ARL requested":"")+" · Approve on TV";
            }
            peer.setSoTimeout(75_000);
            boolean acknowledged=latch.await(60,TimeUnit.SECONDS);
            boolean accepted;
            synchronized(GUARD) {
                accepted=acknowledged&&pendingApproved&&secret.equals(pin)&&
                    sessionProfile.equals(activeProfile)&&System.currentTimeMillis()<expires;
                if(pendingLatch==latch){pendingDevice=null;pendingLatch=null;pendingApproved=false;pendingEclipseCredential=false;}
                if(accepted){pin=null;expires=0;}
            }
            AuraConnectWire.write(output,channel.encrypt(
                new JSONObject().put("approved",accepted)));
            if(!accepted){message="PAIR_DENIED: TV declined or approval timed out";return;}
            message="PAIR_APPROVED: "+device+" authenticated";
            receiveAudio(input,output,channel,sessionProfile,
                proof.optBoolean("eclipseCredentialRequested",false));
        }catch(Exception e){
            // No user/token/secret content is logged. Failed pairing does not grant access.
            message=e instanceof java.net.SocketTimeoutException
                ?"PAIR_TIMEOUT: TV receiver timed out while waiting for phone"
                :e instanceof java.io.EOFException
                    ?"PAIR_DISCONNECTED: Phone closed the connection"
                    :e instanceof java.io.IOException&&e.getMessage()!=null&&
                        e.getMessage().toLowerCase(java.util.Locale.US).contains("pair")
                        ?"PAIR_FAILED: Invalid/expired PIN or declined request"
                        :"PAIR_FAILED: Connection failed; check six-digit PIN and TV approval";
        }
    }
    /**
     * The approved SRP-6a/AES-GCM session stays active while the phone is
     * connected. No persistent, reusable PIN or unauthenticated HTTP endpoint.
     * AURA Connect remote control uses sequence-checked encrypted messages.
     */
    private static void receiveRemoteSession(DataInputStream input,DataOutputStream output,
        AuraConnectWire.Channel channel,String sessionProfile)throws Exception{
        while(true){
            JSONObject frame=channel.decrypt(AuraConnectWire.read(input));
            synchronized(GUARD){
                if(!sessionProfile.equals(activeProfile))
                    throw new IOException("REMOTE_PROFILE_CHANGED");
            }
            RemoteController handler=remoteController;
            if(handler==null)throw new IOException("REMOTE_UNAVAILABLE");
            String op=frame.optString("op","");
            if("remote-status".equals(op)){
                JSONObject status=handler.status(sessionProfile);
                AuraConnectWire.write(output,channel.encrypt(new JSONObject()
                    .put("op","remote-state").put("state",status)));
            }else if("remote-control".equals(op)){
                String action=frame.optString("action","");
                if(!("pause".equals(action)||"resume".equals(action)||
                    "next".equals(action)||"previous".equals(action)))
                    throw new IOException("REMOTE_INVALID_ACTION");
                handler.command(action,sessionProfile);
                AuraConnectWire.write(output,channel.encrypt(new JSONObject().put("ok",true)));
            }else if("queue-track".equals(op)){
                String id=frame.optString("id","");
                String title=frame.optString("title",""),artist=frame.optString("artist","");
                if(!id.matches("[a-zA-Z0-9_:.|\\-]{1,180}")||
                    title.isEmpty()||title.length()>180||artist.length()>180)
                    throw new IOException("QUEUE_INVALID_TRACK");
                handler.queueCatalog(new JSONObject().put("id",id)
                    .put("title",title).put("artist",artist),sessionProfile);
                AuraConnectWire.write(output,channel.encrypt(new JSONObject().put("ok",true)));
            }else if("queue-file".equals(op)){
                receiveRemoteQueuedFile(frame,input,output,channel,sessionProfile,handler);
            }else if("playlist-push".equals(op)||"favorites-push".equals(op)){
                // amp.playlists.v1: phone -> TV playlists / liked songs, metadata only, inside the approved
                // session and into the approved profile. Large lists arrive in parts; the TV merges them.
                MetadataSink sink=metadataSink;
                if(sink==null)throw new IOException("SYNC_UNAVAILABLE");
                String snapshot="playlist-push".equals(op)
                    ?AuraSyncJournal.sanitizePlaylistPush(frame,sessionProfile)
                    :AuraSyncJournal.sanitizeFavoritesPush(frame,sessionProfile);
                synchronized(GUARD){
                    if(!sessionProfile.equals(activeProfile))throw new IOException("SYNC_PROFILE_CHANGED");
                }
                sink.apply(snapshot,sessionProfile);
                message="AURA Connect · playlists received from phone";
                AuraConnectWire.write(output,channel.encrypt(new JSONObject().put("ok",true)));
            }else if("disconnect".equals(op)){
                AuraConnectWire.write(output,channel.encrypt(new JSONObject().put("ok",true)));
                message="AURA Connect remote disconnected";
                return;
            }else throw new IOException("REMOTE_UNKNOWN_MESSAGE");
        }
    }

    private static void receiveRemoteQueuedFile(JSONObject meta,DataInputStream input,
        DataOutputStream output,AuraConnectWire.Channel channel,String sessionProfile,
        RemoteController handler)throws Exception{
        long size=meta.optLong("size",0);
        if(size<1||size>MAX_AUDIO)throw new IOException("QUEUE_INVALID_FILE_SIZE");
        String title=meta.optString("title","Local song"),artist=meta.optString("artist","AURA AMP"),
            artwork=meta.optString("artwork","");
        if(title.length()>180||artist.length()>180||artwork.length()>1024)
            throw new IOException("QUEUE_METADATA_TOO_LONG");
        File folder=new File(app.getCacheDir(),"aura-connect");
        if(!folder.exists()&&!folder.mkdirs())throw new IOException("QUEUE_STORAGE_UNAVAILABLE");
        File temp=File.createTempFile("queued_",".audio",folder);
        long received=0;
        try{
            try(FileOutputStream file=new FileOutputStream(temp)){
                while(received<size){
                    JSONObject part=channel.decrypt(AuraConnectWire.read(input));
                    if(!"chunk".equals(part.optString("op")))throw new IOException("QUEUE_UNEXPECTED_FRAME");
                    byte[] bytes=AuraConnectWire.from64(part.getString("data"));
                    if(bytes.length<1||bytes.length>AuraConnectWire.CHUNK_BYTES||
                       received+bytes.length>size)throw new IOException("QUEUE_INVALID_CHUNK");
                    file.write(bytes);received+=bytes.length;
                }
                file.getFD().sync();
            }
            JSONObject last=channel.decrypt(AuraConnectWire.read(input));
            if(!"end".equals(last.optString("op")))throw new IOException("QUEUE_INCOMPLETE");
            synchronized(GUARD){
                if(!sessionProfile.equals(activeProfile))throw new IOException("QUEUE_PROFILE_CHANGED");
            }
            handler.queueLocal(temp,title,artist,artwork,sessionProfile);
            AuraConnectWire.write(output,channel.encrypt(new JSONObject()
                .put("ok",true).put("received",received)));
        }catch(Exception error){temp.delete();throw error;}
    }

    private static void receiveAudio(DataInputStream input,DataOutputStream output,
                                     AuraConnectWire.Channel channel,String sessionProfile,
                                     boolean credentialRequested)throws Exception{
        JSONObject info=channel.decrypt(AuraConnectWire.read(input));
        if("pair-only".equals(info.optString("op"))){
            synchronized(GUARD){
                if(activeProfile==null||!activeProfile.equals(sessionProfile))
                    throw new IOException("PAIR_PROFILE_CHANGED");
            }
            String arl=info.optString("eclipseArl","");
            if(!arl.isEmpty()){
                if(!credentialRequested)throw new IOException("ECLIPSE_NOT_AUTHORIZED");
                if(!arl.matches("[a-fA-F0-9]{100,256}"))
                    throw new IOException("ECLIPSE_INVALID_ARL");
                CredentialSink sink=credentialSink;
                if(sink==null||!sink.saveEclipseArl(arl,sessionProfile))
                    throw new IOException("ECLIPSE_VAULT_ERROR");
            }
            AuraConnectWire.write(output,channel.encrypt(
                new JSONObject().put("paired",true).put("scope","one-time")));
            message=arl.isEmpty()?"PAIR_OK · AURA AMP Mobile verified":
                "PAIR_OK · Eclipse ARL imported securely into current TV profile";
            receiveRemoteSession(input,output,channel,sessionProfile);
            return;
        }
        if("sync-metadata".equals(info.optString("op"))){
            // The TV user just approved this device and current profile.
            // Accept bounded, sanitized, metadata-only profile updates;
            // never accept secrets or ARL from this path.
            String raw=info.optString("snapshot","");
            String sanitized=AuraSyncJournal.sanitizeApprovedIncoming(raw,sessionProfile);
            synchronized(GUARD){
                if(activeProfile==null||!activeProfile.equals(sessionProfile))
                    throw new IOException("Active TV user changed");
            }
            MetadataSink sink=metadataSink;
            if(sink==null)throw new IOException("TV metadata sync unavailable");
            sink.apply(sanitized,sessionProfile);
            info=channel.decrypt(AuraConnectWire.read(input));
        }
        if(!"audio".equals(info.optString("op")))throw new IOException("Unsupported operation");
        long size=info.optLong("size",0);
        if(size<=0||size>MAX_AUDIO)throw new IOException("Audio file too large");
        String title=info.optString("title","Music");
        String artist=info.optString("artist","Aura Amp");
        if(title.length()>180||artist.length()>180)throw new IOException("Metadata too long");
        File folder=new File(app.getCacheDir(),"aura-connect");
        if(!folder.exists()&&!folder.mkdirs())throw new IOException("Storage unavailable");
        File temp=File.createTempFile("incoming_",".audio",folder);
        long received=0;
        try{
            try(FileOutputStream file=new FileOutputStream(temp)){
                while(received<size){
                    JSONObject frame=channel.decrypt(AuraConnectWire.read(input));
                    if(!"chunk".equals(frame.optString("op")))throw new IOException("Unexpected media frame");
                    byte[] bytes=AuraConnectWire.from64(frame.getString("data"));
                    if(bytes.length<1||bytes.length>AuraConnectWire.CHUNK_BYTES||
                        received+bytes.length>size)throw new IOException("Invalid audio chunk");
                    file.write(bytes);
                    received+=bytes.length;
                }
                file.getFD().sync();
            }
            JSONObject end=channel.decrypt(AuraConnectWire.read(input));
            if(!"end".equals(end.optString("op")))throw new IOException("Incomplete transfer");
            // A different Launcher user may have been selected during transfer.
            // Never start previous user's music inside the newly selected profile.
            synchronized(GUARD) {
                if(activeProfile==null||!activeProfile.equals(sessionProfile))
                    throw new IOException("Launcher user changed during transfer");
            }
            Playback callback=playback;
            if(callback==null)throw new IOException("TV player unavailable");
            callback.play(temp,title,artist,info.optString("artwork",""));
            AuraConnectWire.write(output,channel.encrypt(
                new JSONObject().put("ok",true).put("received",received)));
            message="Playing "+title+" · "+artist;
        }catch(Exception e){temp.delete();throw e;}
    }
}
