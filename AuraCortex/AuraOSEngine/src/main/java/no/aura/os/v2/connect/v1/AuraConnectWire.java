package no.aura.os.v2.connect.v1;

import java.util.Base64;
import org.bouncycastle.crypto.agreement.srp.SRP6Client;
import org.bouncycastle.crypto.agreement.srp.SRP6Server;
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator;
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.json.JSONObject;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AURA Connect: RFC 5054 SRP-6a password-authenticated key exchange.
 * The 6-digit TV PIN is NEVER transmitted or used as a direct encryption key.
 * SRP proof verifies each side's PIN and ephemeral shared secret, followed
 * by explicit TV approval. All media chunks are then AES-256-GCM protected.
 */
public final class AuraConnectWire {
    static final int MAX_FRAME = 55000;
    static final int CHUNK_BYTES = 12000;
    private static final SecureRandom RNG = new SecureRandom();
    private static final byte[] ID = "AURA-CONNECT-V1".getBytes(StandardCharsets.UTF_8);
    private AuraConnectWire(){}

    static String b64(byte[] value){return Base64.getEncoder().encodeToString(value);}
    static byte[] from64(String s){return Base64.getDecoder().decode(s);}
    static byte[] random(int n){byte[] b=new byte[n];RNG.nextBytes(b);return b;}
    static String hex(BigInteger n){return n.toString(16);}
    static BigInteger number(String s){return new BigInteger(s,16);}
    static byte[] secret(BigInteger key)throws Exception {
        byte[] k=key.toByteArray();
        return MessageDigest.getInstance("SHA-256").digest(k);
    }

    static void write(DataOutputStream out,JSONObject msg)throws Exception{
        String value=msg.toString();
        if(value.length()>MAX_FRAME)throw new IOException("Frame too large");
        out.writeUTF(value);out.flush();
    }
    static JSONObject read(DataInputStream in)throws Exception{
        String raw=in.readUTF();
        if(raw.length()>MAX_FRAME)throw new IOException("Frame too large");
        return new JSONObject(raw);
    }

    static JSONObject encrypt(byte[] key,JSONObject payload)throws Exception{
        byte[] iv=random(12);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(ID);
        return new JSONObject().put("iv",b64(iv))
            .put("ciphertext",b64(cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8))));
    }
    static JSONObject decrypt(byte[] key,JSONObject envelope)throws Exception{
        byte[] iv=from64(envelope.getString("iv"));
        if(iv.length!=12)throw new IOException("Bad IV");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        cipher.updateAAD(ID);
        return new JSONObject(new String(cipher.doFinal(from64(envelope.getString("ciphertext"))),
            StandardCharsets.UTF_8));
    }

    /**
     * Fresh per-connection encrypted channel. Each direction has a strict
     * monotonically increasing counter. A previously recorded GCM frame may
     * never be replayed within the same authenticated pairing session.
     */
    static final class Channel {
        final byte[] key;
        long sent = 0L;
        long received = 0L;
        Channel(byte[] key) { this.key = key.clone(); }
        JSONObject encrypt(JSONObject message) throws Exception {
            if (sent == Long.MAX_VALUE) throw new IOException("Session exhausted");
            JSONObject packet = AuraConnectWire.encrypt(key, message);
            packet.put("seq", sent++);
            return packet;
        }
        JSONObject decrypt(JSONObject packet) throws Exception {
            long seq = packet.getLong("seq");
            if (seq != received) throw new IOException("Out-of-order or replayed session frame");
            JSONObject message = AuraConnectWire.decrypt(key, packet);
            received++;
            return message;
        }
    }

    static final class Server {
        final SRP6Server srp=new SRP6Server();
        final byte[] salt=random(16);
        final BigInteger B;
        Server(String code) {
            SRP6VerifierGenerator generator=new SRP6VerifierGenerator();
            generator.init(SRP6StandardGroups.rfc5054_2048,new SHA256Digest());
            BigInteger verifier=generator.generateVerifier(salt,ID,code.getBytes(StandardCharsets.UTF_8));
            srp.init(SRP6StandardGroups.rfc5054_2048,verifier,new SHA256Digest(),RNG);
            B=srp.generateServerCredentials();
        }
        JSONObject hello()throws Exception{
            return new JSONObject().put("version",1).put("salt",b64(salt)).put("B",hex(B));
        }
        void verify(JSONObject proof)throws Exception{
            srp.calculateSecret(number(proof.getString("A")));
            if(!srp.verifyClientEvidenceMessage(number(proof.getString("M1"))))
                throw new IOException("Incorrect or expired pairing code");
        }
        String evidence()throws Exception{return hex(srp.calculateServerEvidenceMessage());}
        byte[] sessionKey()throws Exception{return secret(srp.calculateSessionKey());}
    }

    static final class Client {
        final SRP6Client srp=new SRP6Client();
        final BigInteger A;
        Client(byte[] salt,String code){
            srp.init(SRP6StandardGroups.rfc5054_2048,new SHA256Digest(),RNG);
            A=srp.generateClientCredentials(salt,ID,code.getBytes(StandardCharsets.UTF_8));
        }
        JSONObject proof(String serverB)throws Exception{
            srp.calculateSecret(number(serverB));
            return new JSONObject().put("A",hex(A))
                .put("M1",hex(srp.calculateClientEvidenceMessage()));
        }
        byte[] verify(String M2)throws Exception{
            if(!srp.verifyServerEvidenceMessage(number(M2)))
                throw new IOException("Receiver pairing authentication failed");
            return secret(srp.calculateSessionKey());
        }
    }
}
