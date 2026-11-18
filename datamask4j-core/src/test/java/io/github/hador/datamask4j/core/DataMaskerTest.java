package io.github.hador.datamask4j.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DataMaskerTest {
    private DataMasker masker;

    @BeforeEach
    void setUp() {
        masker = new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.keep())
                .field("name", FieldRule.redact())
                .field("email", FieldRule.constant("masked@example.test"))
                .field("notes", FieldRule.drop())
                .build());
    }

    @Test
    void shouldApplyExplicitRulesWithoutMutatingInput() {
        var input = new HashMap<>(Map.of("id", "C-1", "name", "Example",
                "email", "demo@example.test", "notes", "private"));
        var before = Map.copyOf(input);
        var output = masker.mask(input);

        assertEquals(Map.of("id", "C-1", "name", "[REDACTED]",
                "email", "masked@example.test"), output);
        assertEquals(before, input);
        assertThrows(UnsupportedOperationException.class, () -> output.put("id", "other"));
    }

    @Test
    void shouldPreserveNullAndDropNullFields() {
        Map<String, String> input = new HashMap<>();
        for (String field : new String[]{"id", "name", "email", "notes"}) {
            input.put(field, null);
        }
        var output = masker.mask(input);
        assertEquals(3, output.size());
        assertTrue(output.containsKey("email"));
        assertNull(output.get("email"));
        assertFalse(output.containsKey("notes"));
    }

    @Test
    void shouldRejectUnknownAndMissingFieldsBeforeInvokingStrategies() {
        AtomicInteger calls = new AtomicInteger();
        var strict = new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.transform(value -> { calls.incrementAndGet(); return value; }))
                .build());
        var unknown = assertThrows(MaskingException.class,
                () -> strict.mask(Map.of("id", "1", "privateColumn", "secret-value")));
        assertFalse(unknown.getMessage().contains("secret-value"));
        assertFalse(unknown.getMessage().contains("privateColumn"));
        assertThrows(MaskingException.class, () -> strict.mask(Map.of()));
        assertEquals(0, calls.get());
    }

    @Test
    void shouldRejectNullFieldNameInInput() {
        Map<String, String> input = new HashMap<>();
        input.put(null, "value");
        assertThrows(MaskingException.class, () -> masker.mask(input));
    }

    @Test
    void shouldRejectNullResultAndHideStrategyException() {
        var nullResult = new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.transform(value -> null)).build());
        assertThrows(MaskingException.class, () -> nullResult.mask(Map.of("id", "raw")));
        var failing = new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.transform(value -> { throw new IllegalStateException(value); })).build());
        var failure = assertThrows(MaskingException.class, () -> failing.mask(Map.of("id", "raw-secret")));
        assertFalse(failure.getMessage().contains("raw-secret"));
        assertNull(failure.getCause());
    }

    @Test
    void shouldAllowDroppingEveryFieldAndTreatEmptyStringAsValue() {
        var drop = new DataMasker(MaskingPolicy.builder().field("id", FieldRule.drop()).build());
        assertTrue(drop.mask(Map.of("id", "")).isEmpty());
        var redact = new DataMasker(MaskingPolicy.builder().field("id", FieldRule.redact()).build());
        assertEquals("[REDACTED]", redact.mask(Map.of("id", "")).get("id"));
    }

    @Test
    void shouldRejectInvalidPolicyAndNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> MaskingPolicy.builder().build());
        assertThrows(IllegalArgumentException.class, () -> MaskingPolicy.builder().field(" ", FieldRule.keep()));
        assertThrows(IllegalArgumentException.class, () -> MaskingPolicy.builder()
                .field("id", FieldRule.keep()).field("id", FieldRule.drop()));
        assertThrows(NullPointerException.class, () -> MaskingPolicy.builder().field(null, FieldRule.keep()));
        assertThrows(NullPointerException.class, () -> MaskingPolicy.builder().field("id", null));
        assertThrows(NullPointerException.class, () -> FieldRule.constant(null));
        assertThrows(NullPointerException.class, () -> FieldRule.transform(null));
        assertThrows(NullPointerException.class, () -> new DataMasker(null));
        assertThrows(NullPointerException.class, () -> masker.mask(null));
    }

    @Test
    void shouldReturnRetainedFieldsInCallerOrderWithoutMutation() {
        var fields = new java.util.ArrayList<>(java.util.List.of("notes", "email", "id", "name"));
        var retained = masker.outputFields(fields);
        assertEquals(java.util.List.of("email", "id", "name"), retained);
        assertEquals(java.util.List.of("notes", "email", "id", "name"), fields);
        assertThrows(UnsupportedOperationException.class, () -> retained.add("extra"));
        fields.clear();
        assertEquals(java.util.List.of("email", "id", "name"), retained);
    }

    @Test
    void shouldRejectDuplicateMissingAndUnknownSchemaFields() {
        assertThrows(MaskingException.class, () -> masker.outputFields(java.util.List.of("id", "name", "email", "notes", "id")));
        assertThrows(MaskingException.class, () -> masker.outputFields(java.util.List.of("id", "name", "email")));
        assertThrows(MaskingException.class, () -> masker.outputFields(java.util.List.of("id", "name", "email", "unknown")));
        assertThrows(NullPointerException.class, () -> masker.outputFields(null));
        assertThrows(NullPointerException.class, () -> masker.outputFields(java.util.Arrays.asList("id", null)));
    }

    @Test
    void shouldReturnEmptySchemaWhenAllFieldsDropped() {
        var drop = new DataMasker(MaskingPolicy.builder().field("id", FieldRule.drop()).build());
        assertEquals(java.util.List.of(), drop.outputFields(java.util.List.of("id")));
    }

    @Test
    void shouldFreezePolicyWhenBuilt() {
        var builder = MaskingPolicy.builder().field("id", FieldRule.keep());
        var original = new DataMasker(builder.build());
        builder.field("extra", FieldRule.drop());
        assertEquals(Map.of("id", "1"), original.mask(Map.of("id", "1")));
    }
}
