package io.github.hador.datamask4j.crypto;

import io.github.hador.datamask4j.core.DataMasker;
import io.github.hador.datamask4j.core.FieldRule;
import io.github.hador.datamask4j.core.MaskingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class HmacPseudonymizerTest {
    private byte[] key;
    private HmacPseudonymizer pseudonymizer;

    @BeforeEach
    void setUp() {
        key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        pseudonymizer = new HmacPseudonymizer(key, "customer-id:v1");
    }

    @Test
    void shouldMatchIndependentKnownAnswerVector() {
        assertEquals("dm1_J2tMtrzl72P4aaqZa4wodmOB8bovImiqDke_Eu1_bgA", pseudonymizer.transform("C-42"));
    }

    @Test
    void shouldFrameUtf8ByteLengthsAndEmbeddedNul() {
        var unicodeScope = new HmacPseudonymizer(key, "é/🔑");
        assertEquals("dm1_u-Bs-RqyJXD-mdg4Ee3d5BuvOfDiGAsB33u_2_pS258",
                unicodeScope.transform("a\0😀"));
    }

    @Test
    void shouldMatchKnownAnswerForEmptyValue() {
        assertEquals("dm1_VqUmM58q5qgO_TWhs8rbgcr81uUFjtvIy2R-9nD0aKI",
                pseudonymizer.transform(""));
    }

    @Test
    void shouldBeDeterministicAndIsolateScopesAndKeys() {
        String first = pseudonymizer.transform("C-42");
        assertEquals(first, new HmacPseudonymizer(key, "customer-id:v1").transform("C-42"));
        assertNotEquals(first, new HmacPseudonymizer(key, "order-id:v1").transform("C-42"));
        byte[] other = key.clone();
        other[0] = 42;
        assertNotEquals(first, new HmacPseudonymizer(other, "customer-id:v1").transform("C-42"));
        assertNotEquals(first, pseudonymizer.transform("C-43"));
        assertTrue(first.matches("dm1_[A-Za-z0-9_-]{43}"));
    }

    @Test
    void shouldSeparateAmbiguousScopeAndValueBoundaries() {
        assertNotEquals(new HmacPseudonymizer(key, "ab").transform("c"),
                new HmacPseudonymizer(key, "a").transform("bc"));
        assertNotEquals(pseudonymizer.transform("é"), pseudonymizer.transform("e\u0301"));
        assertNotEquals(pseudonymizer.transform(""), pseudonymizer.transform(" "));
    }

    @Test
    void shouldDefensivelyCopyCallerKey() {
        String before = pseudonymizer.transform("C-42");
        Arrays.fill(key, (byte) 0);
        assertEquals(before, pseudonymizer.transform("C-42"));
    }

    @Test
    void shouldRejectInvalidKeysScopesAndUnicode() {
        assertThrows(IllegalArgumentException.class, () -> new HmacPseudonymizer(new byte[31], "id"));
        assertThrows(NullPointerException.class, () -> new HmacPseudonymizer(null, "id"));
        assertThrows(IllegalArgumentException.class, () -> new HmacPseudonymizer(key, " "));
        assertThrows(NullPointerException.class, () -> new HmacPseudonymizer(key, null));
        assertThrows(IllegalArgumentException.class, () -> new HmacPseudonymizer(key, "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> pseudonymizer.transform("\uD800"));
        assertThrows(NullPointerException.class, () -> pseudonymizer.transform(null));
    }

    @Test
    void shouldPreserveSharedIdentityAcrossIndependentPolicies() {
        var customer = new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.transform(pseudonymizer)).build());
        var order = new DataMasker(MaskingPolicy.builder()
                .field("customerId", FieldRule.transform(pseudonymizer)).build());
        assertEquals(customer.mask(Map.of("id", "C-42")).get("id"),
                order.mask(Map.of("customerId", "C-42")).get("customerId"));
    }

    @Test
    void shouldProduceConsistentResultsDuringConcurrentCalls() {
        String expected = pseudonymizer.transform("C-42");
        assertTrue(IntStream.range(0, 100).parallel()
                .mapToObj(index -> pseudonymizer.transform("C-42"))
                .allMatch(expected::equals));
    }
}
