package local.boox.openai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class KeyVault {
    private static final String ALIAS = "boox-openai-api-key-v1";
    private final SharedPreferences prefs;
    KeyVault(Context context) { prefs = context.getSharedPreferences("private_config", Context.MODE_PRIVATE); }
    private SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    boolean hasKey() { return prefs.contains("ciphertext"); }
    String model() { return prefs.getString("model", "gpt-4.1"); }
    void save(String key, String model) throws Exception {
        SharedPreferences.Editor edit = prefs.edit().putString("model", model);
        if (!key.isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret());
            edit.putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            edit.putString("ciphertext", Base64.encodeToString(cipher.doFinal(key.getBytes("UTF-8")), Base64.NO_WRAP));
        }
        if (!edit.commit()) throw new java.io.IOException("Settings could not be saved.");
    }
    String read() throws Exception {
        if (!hasKey()) throw new IllegalStateException("Enter and save your API key first.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("ciphertext", ""), Base64.NO_WRAP)), "UTF-8");
    }
    void clear() throws Exception {
        if (!prefs.edit().clear().commit()) throw new java.io.IOException();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        store.deleteEntry(ALIAS);
    }
}
