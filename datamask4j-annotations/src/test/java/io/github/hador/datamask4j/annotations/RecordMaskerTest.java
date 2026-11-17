package io.github.hador.datamask4j.annotations;

import io.github.hador.datamask4j.crypto.HmacPseudonymizer;
import io.github.hador.datamask4j.faker.EuropeanFaker;
import io.github.hador.datamask4j.faker.EuropeanLocale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.github.hador.datamask4j.annotations.MaskType.*;
import static org.junit.jupiter.api.Assertions.*;

public class RecordMaskerTest {
    private byte[] key;
    private RecordMasker masker;
    private static final AtomicInteger READS = new AtomicInteger();

    @BeforeEach
    void setUp() {
        key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        masker = new RecordMasker(key);
        READS.set(0);
    }

    public record Customer(@Mask(strategy = KEEP) long id,
                           @Mask(strategy = HMAC, scope = "customer:v1") String reference,
                           @Mask(strategy = FAKE_NAME, scope = "name", locale = EuropeanLocale.DE_DE) String name,
                           @Mask(strategy = FAKE_EMAIL, scope = "email") String email,
                           @Mask(strategy = REDACT) String notes,
                           @Mask(strategy = CONSTANT, value = "FR") String country,
                           @Mask(strategy = NULLIFY) String phone) {}

    public record Plain(@Mask(strategy = KEEP) Long id,
                        @Mask(strategy = REDACT) String note,
                        @Mask(strategy = CONSTANT) String constant) {}

    public record Incomplete(@Mask(strategy = KEEP) String first, String unknown) {
        @Override public String first() {
            READS.incrementAndGet();
            return first;
        }
    }

    public record Nested(@Mask(strategy = KEEP) List<String> values) {}
    public record BadType(@Mask(strategy = REDACT) int number) {}
    public record MissingScope(@Mask(strategy = HMAC) String value) {}
    public record ExtraScope(@Mask(strategy = REDACT, scope = "unused") String value) {}
    public record ExtraValue(@Mask(strategy = KEEP, value = "unused") String value) {}
    public record ExtraLocale(@Mask(strategy = HMAC, scope = "valid", locale = EuropeanLocale.DE_DE) String value) {}
    public record ReservedScope(@Mask(strategy = HMAC, scope = "faker:v1:fr-FR:name:fixture") String value) {}
    public record NameParts(@Mask(strategy = FAKE_FIRST_NAME, scope = "same") String first,
                            @Mask(strategy = FAKE_LAST_NAME, scope = "same") String last,
                            @Mask(strategy = FAKE_NAME, scope = "same") String full) {}
    public record ExplodingAccessor(@Mask(strategy = REDACT) String value) {
        @Override public String value() {
            throw new IllegalStateException("sensitive-accessor-value");
        }
    }
    public record RejectingConstructor(@Mask(strategy = NULLIFY) String value) {
        public RejectingConstructor {
            if (value == null) {
                throw new IllegalArgumentException("sensitive-constructor-value");
            }
        }
    }
    private record PrivateRecord(@Mask(strategy = KEEP) String value) {}

    @Test
    void shouldMaskAllDeclaredStrategiesWithoutChangingSource() {
        var source = new Customer(42L, "C-42", "Raw Name", "raw@example.test", "private", "DE", "private-phone");
        var result = masker.mask(source);
        assertNotSame(source, result);
        assertEquals(42L, result.id());
        assertEquals(new HmacPseudonymizer(key, "customer:v1").transform("C-42"), result.reference());
        assertEquals(new EuropeanFaker(key, "name", EuropeanLocale.DE_DE).fullName().transform("Raw Name"), result.name());
        assertEquals(new EuropeanFaker(key, "email", EuropeanLocale.FR_FR).email().transform("raw@example.test"), result.email());
        assertEquals("[REDACTED]", result.notes());
        assertEquals("FR", result.country());
        assertNull(result.phone());
        assertEquals("private-phone", source.phone());
        assertEquals("Raw Name", source.name());
    }

    @Test
    void shouldPreserveNullsAndSupportUnkeyedRules() {
        var source = new Plain(null, null, "raw");
        var result = new RecordMasker().mask(source);
        assertEquals(new Plain(null, null, ""), result);
        assertEquals(new Plain(1L, "[REDACTED]", null), new RecordMasker().mask(new Plain(1L, "raw", null)));
    }

    @Test
    void shouldRejectUnclassifiedComponentBeforeReadingAnyValues() {
        assertThrows(RecordMaskingException.class, () -> masker.mask(new Incomplete("raw", "unknown")));
        assertEquals(0, READS.get());
    }

    @Test
    void shouldRejectNestedMutableAndUnsupportedTypes() {
        assertThrows(RecordMaskingException.class, () -> masker.mask(new Nested(List.of("raw"))));
        assertThrows(RecordMaskingException.class, () -> masker.mask(new BadType(42)));
        assertThrows(RecordMaskingException.class, () -> masker.mask(new PrivateRecord("raw")));
    }

    @Test
    void shouldRejectMissingKeyScopeAndUnusedOptionsEvenWhenNull() {
        assertThrows(RecordMaskingException.class, () -> new RecordMasker().mask(new Customer(1, null, null, null, null, null, null)));
        assertThrows(RecordMaskingException.class, () -> masker.mask(new MissingScope(null)));
        assertThrows(RecordMaskingException.class, () -> masker.mask(new ExtraScope(null)));
        assertThrows(RecordMaskingException.class, () -> masker.mask(new ExtraValue(null)));
        assertThrows(RecordMaskingException.class, () -> masker.mask(new ExtraLocale(null)));
        assertThrows(RecordMaskingException.class, () -> masker.mask(new ReservedScope(null)));
    }

    @Test
    void shouldGenerateConsistentNameComponentsForSameSeed() {
        var result = masker.mask(new NameParts("C-42", "C-42", "C-42"));
        assertEquals(result.first() + " " + result.last(), result.full());
    }

    @Test
    void shouldSanitizeAccessorAndConstructorFailures() {
        for (Record source : List.of(new ExplodingAccessor("raw"), new RejectingConstructor("raw"))) {
            var error = assertThrows(RecordMaskingException.class, () -> masker.mask(source));
            assertEquals("Record masking failed", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void shouldDefensivelyCopyKeyAndSupportConcurrentCalls() {
        var source = new NameParts("seed", "seed", "seed");
        var expected = masker.mask(source);
        Arrays.fill(key, (byte) 0);
        assertTrue(IntStream.range(0, 100).parallel().mapToObj(i -> masker.mask(source)).allMatch(expected::equals));
    }

    @Test
    void shouldRejectInvalidKeyAndNullSource() {
        assertThrows(IllegalArgumentException.class, () -> new RecordMasker(new byte[31]));
        assertThrows(NullPointerException.class, () -> new RecordMasker(null));
        assertThrows(NullPointerException.class, () -> masker.mask(null));
    }
}
