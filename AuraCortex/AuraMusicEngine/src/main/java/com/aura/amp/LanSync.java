package com.aura.amp;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AURA Local Sync v2: 6-digit one-time pairing with ephemeral ECDH transport,
 * AES-256-GCM, private LAN restriction, and short-lived sender authorization.
 */
public final class LanSync {
    public interface Listener { void onEvent(String json); void onReceived(String json); }
    private static final SecureRandom RNG=new SecureRandom();
    private static final int MAX=2_000_000;
    private static final long EXPIRE_MS=10*60_000L;
    private final ExecutorService tasks=Executors.newCachedThreadPool();
    private final Listener listener;
    private final Object lock=new Object();
    private volatile ServerSocket server;
    private volatile long expiry;
    private volatile String pin;
    private volatile String snapshot;
    private volatile KeyPair senderKey;
    private final AtomicInteger attempts=new AtomicInteger();
    private volatile byte[] expectedAck;
    public LanSync(Context context,Listener listener){this.listener=listener;}

    public void start(String value){tasks.execute(()->{
        try {
            if(value==null||value.length()>MAX)throw new IOException("Sync data too large");
            JSONObject data=new JSONObject(value);
            if(data.optInt("protocol")!=2||!data.has("profiles"))throw new IOException("Unsupported sync snapshot");
            stop();
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"),RNG);
            KeyPair pair=generator.generateKeyPair();
            String secret=String.format(java.util.Locale.US,"%06d",RNG.nextInt(1_000_000));
            ServerSocket socket=new ServerSocket(0,3);
            String ip=lanAddress();
            if(ip==null){socket.close();throw new IOException("Connect both devices to the same private network");}
            synchronized(lock){server=socket;pin=secret;snapshot=value;senderKey=pair;expiry=System.currentTimeMillis()+EXPIRE_MS;attempts.set(0);}
            emit(new JSONObject().put("event","hosting").put("port",socket.getLocalPort())
                .put("code",secret).put("expiresMinutes",10));
            tasks.execute(()->serve(socket));
            tasks.execute(()->{try{Thread.sleep(EXPIRE_MS);}catch(InterruptedException ignored){}
                synchronized(lock){if(server==socket){stop();try{emit(new JSONObject().put("event","expired").put("message","Paringskode utløpt"));}catch(Exception ignored){}}}});
        }catch(Exception e){error(e);}
    });}

    public void receive(String address,int port,String code){tasks.execute(()->{
        try {
            if(code==null||!code.matches("[0-9]{6}"))throw new IOException("Enter the 6-digit code");
            InetAddress host=InetAddress.getByName(address);
            if(!(host instanceof Inet4Address)||!host.isSiteLocalAddress())throw new IOException("Device is not on a private local network");
            if(port<1024||port>65535)throw new IOException("Invalid service port");
            emit(new JSONObject().put("event","receiving").put("message","Connecting securely to sender…"));
            JSONObject info=request(host,port,"GET",null);
            if(info.optInt("v")!=2)throw new IOException("Update the other AURA AMP device to Local Sync v2");
            byte[] senderPub=unb64(info.getString("publicKey"));
            KeyFactory factory=KeyFactory.getInstance("EC");
            PublicKey pub=factory.generatePublic(new X509EncodedKeySpec(senderPub));
            KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"),RNG);
            KeyPair receiver=generator.generateKeyPair();
            KeyAgreement agreement=KeyAgreement.getInstance("ECDH");agreement.init(receiver.getPrivate());agreement.doPhase(pub,true);
            byte[] shared=agreement.generateSecret();
            JSONObject request=new JSONObject().put("publicKey",b64(receiver.getPublic().getEncoded()));
            JSONObject reply=request(host,port,"POST",request.toString());
            if(reply.optInt("v")!=2)throw new IOException("Sender rejected pairing");
            byte[] clear=decrypt(shared,code,reply);
            JSONObject data=new JSONObject(new String(clear,StandardCharsets.UTF_8));
            if(data.optInt("protocol")!=2||!data.has("profiles"))throw new IOException("Invalid sync data");
            emit(new JSONObject().put("event","receiving").put("message","Mottar brukerdata…"));
            listener.onReceived(data.toString());
            JSONObject ack=request(host,port,"POST",new JSONObject().put("ack",b64(ackProof(shared,code))).toString(),"/aura-sync/v2/ack");
            if(!ack.optBoolean("acknowledged"))throw new IOException("Sender did not confirm transfer");
            emit(new JSONObject().put("event","received").put("message","Brukerdata mottatt"));
        }catch(Exception e){error(e);}
    });}

    private JSONObject request(InetAddress host,int port,String verb,String payload)throws Exception{
        return request(host,port,verb,payload,"/aura-sync/v2");
    }
    private JSONObject request(InetAddress host,int port,String verb,String payload,String route)throws Exception{
        try(Socket sock=new Socket()){
            sock.connect(new InetSocketAddress(host,port),6000);sock.setSoTimeout(12000);
            byte[] body=payload==null?new byte[0]:payload.getBytes(StandardCharsets.UTF_8);
            OutputStream out=sock.getOutputStream();
            String headers=verb+" "+route+" HTTP/1.1\r\nHost: aura.local\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
            InputStream in=new BufferedInputStream(sock.getInputStream());
            String top=readLine(in,2048);if(!top.startsWith("HTTP/1.1 200"))throw new IOException("Pairing unavailable or expired");
            int length=-1;String line;
            while(!(line=readLine(in,2048)).isEmpty()){
                if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());
            }
            if(length<0||length>MAX+4096)throw new IOException("Invalid sync response size");
            byte[] result=readBytes(in,length);
            return new JSONObject(new String(result,StandardCharsets.UTF_8));
        }
    }
    private void serve(ServerSocket socket){
        while(!socket.isClosed()&&System.currentTimeMillis()<expiry){
            try(Socket peer=socket.accept()){
                peer.setSoTimeout(4500);
                if(!peer.getInetAddress().isSiteLocalAddress())continue;
                InputStream in=new BufferedInputStream(peer.getInputStream());
                String first=readLine(in,2048);
                int size=0;String line;
                while(!(line=readLine(in,2048)).isEmpty()){
                    if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))size=Integer.parseInt(line.substring(15).trim());
                }
                if(size<0||size>8192)continue;
                String payload=new String(readBytes(in,size),StandardCharsets.UTF_8);
                JSONObject output=null;
                if(first.startsWith("POST /aura-sync/v2/ack ")&&expectedAck!=null){
                    byte[] supplied=unb64(new JSONObject(payload).optString("ack",""));
                    if(MessageDigest.isEqual(expectedAck,supplied)){
                        output=new JSONObject().put("v",2).put("acknowledged",true);
                    }
                }else if(first.startsWith("GET /aura-sync/v2 ")&&attempts.get()<3&&senderKey!=null){
                    output=new JSONObject().put("v",2).put("publicKey",b64(senderKey.getPublic().getEncoded()));
                }else if(first.startsWith("POST /aura-sync/v2 ")&&attempts.incrementAndGet()<=3&&senderKey!=null){
                    PublicKey receiver=KeyFactory.getInstance("EC").generatePublic(
                        new X509EncodedKeySpec(unb64(new JSONObject(payload).getString("publicKey"))));
                    KeyAgreement agreement=KeyAgreement.getInstance("ECDH");agreement.init(senderKey.getPrivate());agreement.doPhase(receiver,true);
                    byte[] shared=agreement.generateSecret();
                    expectedAck=ackProof(shared,pin);
                    emit(new JSONObject().put("event","sending").put("message","Sender brukerdata…"));
                    output=encrypt(shared,pin,snapshot);
                }
                byte[] response=(output==null?"{}":output.toString()).getBytes(StandardCharsets.UTF_8);
                String status=output==null?"HTTP/1.1 403 Forbidden":"HTTP/1.1 200 OK";
                OutputStream out=peer.getOutputStream();out.write((status+"\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: "+response.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(response);out.flush();
                if(first.startsWith("POST /aura-sync/v2/ack ")&&output!=null){
                    emit(new JSONObject().put("event","sent").put("message","Brukerdata sendt · synkronisering fullført"));
                    stop();
                }else if(attempts.get()>=3&&output==null)stop();
            }catch(SocketException ex){break;}catch(Exception ignored){}
        }
    }
    private static JSONObject encrypt(byte[] shared,String code,String value)throws Exception{
        byte[] iv=new byte[12];RNG.nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,derive(shared,code),new GCMParameterSpec(128,iv));
        cipher.updateAAD("AURA-AMP-LOCAL-SYNC-2".getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("v",2).put("iv",b64(iv)).put("ciphertext",b64(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))));
    }
    private static byte[] decrypt(byte[] shared,String code,JSONObject envelope)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,derive(shared,code),new GCMParameterSpec(128,unb64(envelope.getString("iv"))));
        cipher.updateAAD("AURA-AMP-LOCAL-SYNC-2".getBytes(StandardCharsets.UTF_8));
        try{return cipher.doFinal(unb64(envelope.getString("ciphertext")));}
        catch(javax.crypto.AEADBadTagException e){throw new IOException("Incorrect pairing code or invalid transfer");}
    }
    private static byte[] ackProof(byte[] shared,String code)throws Exception{
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(derive(shared,code).getEncoded(),"HmacSHA256"));
        return mac.doFinal("AURA-AMP-LOCAL-SYNC-ACK-2".getBytes(StandardCharsets.US_ASCII));
    }
    private static SecretKeySpec derive(byte[] shared,String code)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update("aura-amp-sync-v2".getBytes(StandardCharsets.UTF_8));digest.update(shared);
        digest.update(code.getBytes(StandardCharsets.US_ASCII));return new SecretKeySpec(digest.digest(),"AES");
    }
    private static String readLine(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')b.write(c);if(b.size()>limit)throw new IOException("Invalid HTTP header");}
        if(c==-1)throw new EOFException("Connection closed");return b.toString("US-ASCII");
    }
    private static byte[] readBytes(InputStream in,int length)throws IOException{
        byte[] out=new byte[length];int offset=0;
        while(offset<length){int n=in.read(out,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
        return out;
    }
    public void stop(){synchronized(lock){ServerSocket s=server;server=null;pin=null;snapshot=null;senderKey=null;expectedAck=null;expiry=0;if(s!=null)try{s.close();}catch(IOException ignored){}}}
    public void close(){stop();tasks.shutdownNow();}
    private static String b64(byte[] x){return Base64.encodeToString(x,Base64.NO_WRAP);}
    private static byte[] unb64(String x){return Base64.decode(x,Base64.DEFAULT);}
    private static String lanAddress()throws SocketException{
        Enumeration<NetworkInterface> nets=NetworkInterface.getNetworkInterfaces();
        while(nets!=null&&nets.hasMoreElements()){
            NetworkInterface net=nets.nextElement();if(!net.isUp()||net.isLoopback()||net.isVirtual())continue;
            Enumeration<InetAddress> addresses=net.getInetAddresses();
            while(addresses.hasMoreElements()){InetAddress ip=addresses.nextElement();if(ip instanceof Inet4Address&&ip.isSiteLocalAddress())return ip.getHostAddress();}
        }
        return null;
    }
    private void emit(JSONObject value){listener.onEvent(value.toString());}
    private void error(Exception e){try{emit(new JSONObject().put("event","error").put("message",e.getMessage()==null?"Sync failed":e.getMessage()));}catch(Exception ignored){}}
}
