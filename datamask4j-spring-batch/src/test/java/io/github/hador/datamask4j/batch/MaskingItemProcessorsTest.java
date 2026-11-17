package io.github.hador.datamask4j.batch;

import io.github.hador.datamask4j.annotations.Mask;
import io.github.hador.datamask4j.annotations.RecordMasker;
import io.github.hador.datamask4j.core.DataMasker;
import io.github.hador.datamask4j.core.FieldRule;
import io.github.hador.datamask4j.core.MaskingPolicy;
import io.github.hador.datamask4j.crypto.HmacPseudonymizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static io.github.hador.datamask4j.annotations.MaskType.*;
import static org.junit.jupiter.api.Assertions.*;

public class MaskingItemProcessorsTest {
    private byte[] key;
    private MapMaskingItemProcessor mapProcessor;
    private RecordMaskingItemProcessor<Customer> recordProcessor;

    public record Customer(@Mask(strategy = HMAC, scope = "customer:v1") String id,
                           @Mask(strategy = REDACT) String email) {}
    public record Unclassified(@Mask(strategy = REDACT) String email, String notes) {}

    @BeforeEach
    void setUp() {
        key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        mapProcessor = new MapMaskingItemProcessor(new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.transform(new HmacPseudonymizer(key, "customer:v1")))
                .field("email", FieldRule.redact())
                .field("notes", FieldRule.drop()).build()));
        recordProcessor = new RecordMaskingItemProcessor<>(new RecordMasker(key));
    }

    @Test
    void shouldReturnImmutableMaskedMapAndPreserveSource() {
        var input = new HashMap<>(Map.of("id", "C-1", "email", "private@example.test", "notes", "secret"));
        var original = Map.copyOf(input);
        var result = mapProcessor.process(input);
        assertEquals(Map.of("id", new HmacPseudonymizer(key, "customer:v1").transform("C-1"),
                "email", "[REDACTED]"), result);
        assertEquals(original, input);
        assertThrows(UnsupportedOperationException.class, () -> result.put("email", "raw"));
    }

    @Test
    void shouldCreateNewRecordAndMatchMapPseudonym() {
        var original = new Customer("C-1", "private@example.test");
        var masked = recordProcessor.process(original);
        assertNotSame(original, masked);
        assertEquals("[REDACTED]", masked.email());
        assertEquals("private@example.test", original.email());
        assertEquals(mapProcessor.process(Map.of("id", "C-1", "email", "raw", "notes", "raw")).get("id"), masked.id());
    }

    @Test
    void shouldPreserveNullFieldsAndNeverFilterItems() {
        var input = new HashMap<String, String>();
        input.put("id", "C-1");
        input.put("email", null);
        input.put("notes", null);
        var result = mapProcessor.process(input);
        assertTrue(result.containsKey("email"));
        assertNull(result.get("email"));
        assertFalse(result.containsKey("notes"));
        assertNull(recordProcessor.process(new Customer("C-1", null)).email());
        var allDropped = new MapMaskingItemProcessor(new DataMasker(MaskingPolicy.builder()
                .field("secret", FieldRule.drop()).build()));
        assertEquals(Map.of(), allDropped.process(Map.of("secret", "raw")));
    }

    @Test
    void shouldRejectUnknownMissingAndUnclassifiedFields() {
        assertSanitized(assertThrows(BatchMaskingException.class, () ->
                mapProcessor.process(Map.of("id", "C-1", "email", "raw", "notes", "raw", "extra", "secret"))));
        assertSanitized(assertThrows(BatchMaskingException.class, () ->
                mapProcessor.process(Map.of("id", "C-1"))));
        var strict = new RecordMaskingItemProcessor<Unclassified>(new RecordMasker());
        assertSanitized(assertThrows(BatchMaskingException.class, () ->
                strict.process(new Unclassified("raw", "secret"))));
    }

    @Test
    void shouldSanitizeTransformationFailuresInsteadOfReturningSource() {
        AtomicInteger calls = new AtomicInteger();
        var failing = new MapMaskingItemProcessor(new DataMasker(MaskingPolicy.builder()
                .field("secret", FieldRule.transform(value -> {
                    calls.incrementAndGet();
                    throw new IllegalArgumentException(value);
                })).build()));
        assertSanitized(assertThrows(BatchMaskingException.class, () -> failing.process(Map.of("secret", "raw-secret"))));
        assertEquals(1, calls.get());
    }

    @Test
    void shouldRejectNullItemsAndDependencies() {
        assertThrows(NullPointerException.class, () -> new MapMaskingItemProcessor(null));
        assertThrows(NullPointerException.class, () -> new RecordMaskingItemProcessor<>(null));
        assertSanitized(assertThrows(BatchMaskingException.class, () -> mapProcessor.process(null)));
        assertSanitized(assertThrows(BatchMaskingException.class, () -> recordProcessor.process(null)));
    }

    @Test
    void shouldProduceSameResultsForReprocessingAndRecreatedProcessor() {
        var source = new Customer("C-1", "raw");
        var expected = recordProcessor.process(source);
        assertEquals(expected, recordProcessor.process(source));
        assertEquals(expected, new RecordMaskingItemProcessor<Customer>(new RecordMasker(key)).process(source));
        assertTrue(IntStream.range(0, 100).parallel().mapToObj(i -> recordProcessor.process(source))
                .allMatch(expected::equals));
    }

    @Test
    void shouldRejectKeyedRecordWithoutSecret() {
        var unkeyed = new RecordMaskingItemProcessor<Customer>(new RecordMasker());
        assertSanitized(assertThrows(BatchMaskingException.class, () -> unkeyed.process(new Customer("C-1", "raw"))));
    }

    private static void assertSanitized(BatchMaskingException exception) {
        assertEquals("Batch item masking failed", exception.getMessage());
        assertNull(exception.getCause());
    }
}
