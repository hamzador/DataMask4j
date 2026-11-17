package io.github.hador.datamask4j.faker;

import io.github.hador.datamask4j.core.DataMasker;
import io.github.hador.datamask4j.core.FieldRule;
import io.github.hador.datamask4j.core.MaskingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class EuropeanFakerTest {
    private byte[] key;
    private EuropeanFaker faker;

    @BeforeEach
    void setUp() {
        key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
        faker = new EuropeanFaker(key, "fixture:v1", EuropeanLocale.FR_FR);
    }

    @Test
    void shouldGenerateStableNamesWithMatchingComponents() {
        String seed = "C-42";
        String first = faker.firstName().transform(seed);
        String last = faker.lastName().transform(seed);
        assertEquals(first + " " + last, faker.fullName().transform(seed));
        assertEquals(faker.fullName().transform(seed),
                new EuropeanFaker(key, "fixture:v1", EuropeanLocale.FR_FR).fullName().transform(seed));
    }

    @Test
    void shouldUseOnlyReservedTestDomainAndLowercaseHex() {
        String result = faker.email().transform("raw@example.invalid");
        assertTrue(result.matches("[0-9a-f]{64}@example\\.test"));
        assertFalse(result.contains("raw"));
        assertEquals(77, result.length());
        assertEquals(result, faker.email().transform("raw@example.invalid"));
    }

    @Test
    void shouldSeparateEmailScopesLocalesKeysAndInputs() {
        String expected = faker.email().transform("C-42");
        byte[] other = key.clone();
        other[0] = 42;
        assertNotEquals(expected, new EuropeanFaker(other, "fixture:v1", EuropeanLocale.FR_FR).email().transform("C-42"));
        assertNotEquals(expected, new EuropeanFaker(key, "another", EuropeanLocale.FR_FR).email().transform("C-42"));
        assertNotEquals(expected, new EuropeanFaker(key, "fixture:v1", EuropeanLocale.DE_DE).email().transform("C-42"));
        assertNotEquals(expected, faker.email().transform("C-43"));
        assertNotEquals(faker.email().transform("é"), faker.email().transform("e\u0301"));
    }

    @Test
    void shouldGenerateDistinctEmailsInSyntheticSample() {
        var values = new HashSet<String>();
        for (int index = 0; index < 1000; index++) {
            assertTrue(values.add(faker.email().transform("fixture-" + index)));
        }
    }

    @Test
    void shouldGenerateNamesForEveryDeclaredLocale() {
        assertEquals(7, EuropeanLocale.values().length);
        for (var locale : EuropeanLocale.values()) {
            var localized = new EuropeanFaker(key, "fixture:v1", locale);
            for (int index = 0; index < 20; index++) {
                String seed = "C-" + index;
                assertEquals(localized.firstName().transform(seed) + " " + localized.lastName().transform(seed),
                        localized.fullName().transform(seed));
                assertFalse(localized.fullName().transform(seed).isBlank());
            }
        }
    }

    @Test
    void shouldComposeWithStrictPoliciesAndPreserveNull() {
        var masker = new DataMasker(MaskingPolicy.builder()
                .field("name", FieldRule.transform(faker.fullName()))
                .field("email", FieldRule.transform(faker.email()))
                .field("phone", FieldRule.drop()).build());
        var input = new HashMap<String, String>();
        input.put("name", "C-42");
        input.put("email", null);
        input.put("phone", "private");
        var output = masker.mask(input);
        assertEquals(faker.fullName().transform("C-42"), output.get("name"));
        assertTrue(output.containsKey("email"));
        assertNull(output.get("email"));
        assertFalse(output.containsKey("phone"));
        assertEquals("private", input.get("phone"));
    }

    @Test
    void shouldDefensivelyCopyKeyAndSupportConcurrentCalls() {
        String expected = faker.email().transform("C-42");
        Arrays.fill(key, (byte) 0);
        assertTrue(IntStream.range(0, 100).parallel()
                .mapToObj(index -> faker.email().transform("C-42"))
                .allMatch(expected::equals));
    }

    @Test
    void shouldRejectInvalidInputsWithoutExposingValues() {
        assertThrows(IllegalArgumentException.class, () -> new EuropeanFaker(new byte[31], "scope", EuropeanLocale.FR_FR));
        assertThrows(IllegalArgumentException.class, () -> new EuropeanFaker(key, " ", EuropeanLocale.FR_FR));
        assertThrows(NullPointerException.class, () -> new EuropeanFaker(key, null, EuropeanLocale.FR_FR));
        assertThrows(NullPointerException.class, () -> new EuropeanFaker(key, "scope", null));
        assertThrows(NullPointerException.class, () -> faker.email().transform(null));
        assertThrows(IllegalArgumentException.class, () -> faker.fullName().transform("\uD800"));
        assertDoesNotThrow(() -> faker.email().transform(""));
    }
}
