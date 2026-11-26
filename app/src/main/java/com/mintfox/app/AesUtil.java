package com.mintfox.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesUtil {

    private static final byte[] KEY = "de6fab95e3d7a886eb1d8cc6c9e4859f590ac4caed909210573cfc849ccf2be5".getBytes();
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private AesUtil() { }

    public static boolean encryptFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            out.write(iv);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] o = c.update(buf, 0, n);
                if (o != null) out.write(o);
            }
            byte[] tail = c.doFinal();
            if (tail != null) out.write(tail);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
