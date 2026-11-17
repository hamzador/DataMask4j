package io.github.hador.datamask4j.crypto;

import io.github.hador.datamask4j.core.MaskingStrategy;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Deterministic pseudonymisation, not encryption or anonymisation. Thread-safe. */
public final class HmacPseudonymizer implements MaskingStrategy {
    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] DOMAIN = "DataMask4j/HMAC-SHA256/v1\0".getBytes(StandardCharsets.US_ASCII);
    private final SecretKeySpec key;
    private final byte[] scope;

    public HmacPseudonymizer(byte[] key, String scope) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32) {
            throw new IllegalArgumentException("HMAC key must contain at least 32 bytes");
        }
        if (Objects.requireNonNull(scope, "scope").isBlank()) {
            throw new IllegalArgumentException("Scope must not be blank");
        }
        this.scope = utf8(scope);
        this.key = new SecretKeySpec(key, ALGORITHM);
    }

    @Override
    public String transform(String value) {
        byte[] input = utf8(Objects.requireNonNull(value, "value"));
        byte[] digest = null;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(DOMAIN);
            updateFramed(mac, scope);
            updateFramed(mac, input);
            digest = mac.doFinal();
            return "dm1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException ignored) {
            throw new IllegalStateException("HMAC operation failed");
        } finally {
            Arrays.fill(input, (byte) 0);
            if (digest != null) {
                Arrays.fill(digest, (byte) 0);
            }
        }
    }

    private static void updateFramed(Mac mac, byte[] bytes) {
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
    }

    private static byte[] utf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
            try {
                byte[] bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
                return bytes;
            } finally {
                if (encoded.hasArray()) {
                    Arrays.fill(encoded.array(), (byte) 0);
                }
            }
        } catch (CharacterCodingException ignored) {
            throw new IllegalArgumentException("Invalid Unicode input");
        }
    }
}
