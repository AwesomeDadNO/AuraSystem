package com.aura.amp;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AuraCredentialVault {
    private static final String ALIAS = "aura_amp_deezer_arl_v1";
    private static final String STORE = "aura_credentials";
    private static final String VALUE = "encrypted_arl";
    private static final String IV = "arl_iv";
    private final Context context;
    private String activeProfile="Default";
    private String suffix(String profile){
        try{
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(profile.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder();for(int i=0;i<12;i++)b.append(String.format(java.util.Locale.US,"%02x",hash[i]&255));return b.toString();
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private String valueKey(String profile){return VALUE+"_"+suffix(profile);}
    private String ivKey(String profile){return IV+"_"+suffix(profile);}
    public synchronized void setActiveProfile(String name){
        if(name==null||name.isEmpty()||name.length()>100)return;
        SharedPreferences p=context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
        if(p.contains(VALUE)&&p.contains(IV)){
            SharedPreferences.Editor editor=p.edit();
            if(!p.contains(valueKey(name)))editor.putString(valueKey(name),p.getString(VALUE,""))
                .putString(ivKey(name),p.getString(IV,""));
            editor.remove(VALUE).remove(IV).apply();
        }
        activeProfile=name;
    }
    public synchronized String arlForProfile(String name){
        try{
            SharedPreferences prefs=context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
            String data=prefs.getString(valueKey(name),""),iv=prefs.getString(ivKey(name),"");
            if(data.isEmpty()||iv.isEmpty())return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,getOrCreateKey(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
            String result=new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8);
            return result.matches("[a-fA-F0-9]{100,256}")?result:null;
        }catch(Exception e){return null;}
    }
    public synchronized boolean saveArlForProfile(String name,String arl){
        if(name==null||name.isEmpty()||name.length()>100||arl==null||!arl.matches("[a-fA-F0-9]{100,256}"))return false;
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,getOrCreateKey());
            byte[] bytes=cipher.doFinal(arl.getBytes(StandardCharsets.UTF_8));
            context.getSharedPreferences(STORE,Context.MODE_PRIVATE).edit()
                .putString(valueKey(name),Base64.encodeToString(bytes,Base64.NO_WRAP))
                .putString(ivKey(name),Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).apply();
            return true;
        }catch(Exception e){return false;}
    }

    public AuraCredentialVault(Context context) { this.context=context.getApplicationContext(); }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS)) return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }

    public synchronized boolean saveArl(String raw){return saveArlForProfile(activeProfile,raw);}
    public synchronized boolean hasArl(){return arlForProfile(activeProfile)!=null;}
    public synchronized String arlForEclipse(){return arlForProfile(activeProfile);}
    public synchronized void clearArl(){
        context.getSharedPreferences(STORE,Context.MODE_PRIVATE).edit().remove(valueKey(activeProfile)).remove(ivKey(activeProfile)).apply();
    }
}
