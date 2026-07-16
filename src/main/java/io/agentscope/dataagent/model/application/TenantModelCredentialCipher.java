package io.agentscope.dataagent.model.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-GCM envelope for tenant API keys. Plaintext keys never leave this component. */
@Component
public class TenantModelCredentialCipher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public TenantModelCredentialCipher(
            @Value("${dataagent.credentials.encryption-key:dataagent-dev-credential-key-change-in-production}")
                    String configuredSecret) {
        try {
            byte[] material = MessageDigest.getInstance("SHA-256")
                    .digest(configuredSecret.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(material, "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot initialise tenant credential encryption", exception);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(encrypted, 0, envelope, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encrypt tenant model credential", exception);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            byte[] envelope = Base64.getDecoder().decode(ciphertext);
            if (envelope.length <= 12) throw new IllegalArgumentException("Invalid credential envelope");
            byte[] iv = java.util.Arrays.copyOfRange(envelope, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(envelope, 12, envelope.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot decrypt tenant model credential", exception);
        }
    }
}
