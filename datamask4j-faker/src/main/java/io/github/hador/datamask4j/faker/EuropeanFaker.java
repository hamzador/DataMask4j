package io.github.hador.datamask4j.faker;

import io.github.hador.datamask4j.core.MaskingStrategy;
import io.github.hador.datamask4j.crypto.HmacPseudonymizer;

import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Stateless, thread-safe deterministic fixture substitution. The input is an opaque
 * seed, not a name to parse. Generated names can coincide with real people.
 */
public final class EuropeanFaker {
    private final EuropeanLocale locale;
    private final HmacPseudonymizer nameTokens;
    private final HmacPseudonymizer emailTokens;

    public EuropeanFaker(byte[] key, String scope, EuropeanLocale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
        if (Objects.requireNonNull(scope, "scope").isBlank()) {
            throw new IllegalArgumentException("Scope must not be blank");
        }
        // Reserve these scope prefixes for faker v1; fixed kind and locale separate domains.
        String prefix = "faker:v1:" + locale.languageTag() + ":";
        this.nameTokens = new HmacPseudonymizer(key, prefix + "name:" + scope);
        this.emailTokens = new HmacPseudonymizer(key, prefix + "email:" + scope);
    }

    public MaskingStrategy firstName() {
        return value -> name(value).first();
    }

    public MaskingStrategy lastName() {
        return value -> name(value).last();
    }

    public MaskingStrategy fullName() {
        return value -> {
            Name name = name(value);
            return name.first() + " " + name.last();
        };
    }

    /**
     * 64 lowercase hex characters plus @example.test. No user-controlled host or
     * source substring. The local part uses the full HMAC digest.
     */
    public MaskingStrategy email() {
        return value -> {
            byte[] digest = digest(emailTokens, value);
            try {
                return HexFormat.of().formatHex(digest) + "@example.test";
            } finally {
                Arrays.fill(digest, (byte) 0);
            }
        };
    }

    private Name name(String value) {
        byte[] digest = digest(nameTokens, value);
        try {
            return new Name(locale.firstName(Byte.toUnsignedInt(digest[0])),
                    locale.lastName(Byte.toUnsignedInt(digest[1])));
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    private static byte[] digest(HmacPseudonymizer tokens, String value) {
        return Base64.getUrlDecoder().decode(tokens.transform(value).substring(4));
    }

    private record Name(String first, String last) {}
}
