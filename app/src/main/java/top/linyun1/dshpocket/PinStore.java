package top.linyun1.dshpocket;

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

/**
 * 用 Android Keystore 里的 AES/GCM 密钥加密保存访问密码。
 * 密钥不可导出，密文与 IV 存在私有 SharedPreferences。
 */
public final class PinStore {

    private static final String SP = "dsh_pocket_secure";
    private static final String KEY_ALIAS = "tsugu_pin_key";
    private static final String K_CT = "pin_ct";
    private static final String K_IV = "pin_iv";
    private static final int GCM_TAG_BITS = 128;

    private PinStore() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    public static boolean save(Context c, String pin) {
        if (pin == null || pin.isEmpty()) {
            return false;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = cipher.doFinal(pin.getBytes(StandardCharsets.UTF_8));
            sp(c).edit()
                    .putString(K_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
                    .putString(K_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String get(Context c) {
        String ct = sp(c).getString(K_CT, null);
        String iv = sp(c).getString(K_IV, null);
        if (ct == null || iv == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] pt = cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP));
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean has(Context c) {
        return sp(c).contains(K_CT) && sp(c).contains(K_IV);
    }

    public static void clear(Context c) {
        sp(c).edit().remove(K_CT).remove(K_IV).apply();
    }
}
