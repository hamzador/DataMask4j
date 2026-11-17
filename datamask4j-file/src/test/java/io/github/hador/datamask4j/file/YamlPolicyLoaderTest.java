package io.github.hador.datamask4j.file;

import io.github.hador.datamask4j.core.DataMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YamlPolicyLoaderTest {
    private YamlPolicyLoader loader;

    @BeforeEach
    void setUp() {
        loader = new YamlPolicyLoader();
    }

    @Test
    void shouldLoadExplicitStrategiesAndExternalHmacKey() {
        String policy = """
                fields:
                  id:
                    strategy: hmac
                    scope: customer-id
                  email:
                    strategy: constant
                    value: 'masked@example.test'
                  name:
                    strategy: redact
                  country:
                    strategy: keep
                  notes:
                    strategy: drop
                """;
        byte[] key = new byte[32]; // Synthetic test key only.
        var masker = new DataMasker(loader.load(new StringReader(policy), key));
        var input = Map.of("id", "C-1", "email", "private@example.test", "name", "Example", "country", "FR", "notes", "private");
        var output = masker.mask(input);
        assertTrue(output.get("id").matches("dm1_[A-Za-z0-9_-]{43}"));
        assertEquals("masked@example.test", output.get("email"));
        assertEquals("[REDACTED]", output.get("name"));
        assertEquals("FR", output.get("country"));
        assertFalse(output.containsKey("notes"));
        assertEquals(output, masker.mask(input));
    }

    @Test
    void shouldRejectMissingKeyAndInlineSecrets() {
        for (String policy : List.of(
                "fields: {id: {strategy: hmac, scope: test}}",
                "fields: {id: {strategy: hmac, key: raw-secret}}",
                "fields: {id: {strategy: keep, secret: raw-secret}}")) {
            var failure = assertThrows(FileMaskingException.class, () -> loader.load(new StringReader(policy)));
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains("raw-secret"));
        }
    }

    @Test
    void shouldRejectDuplicatesAliasesTagsMergesAndMultipleDocuments() {
        for (String policy : List.of(
                "fields: {id: {strategy: keep}, id: {strategy: redact}}",
                "fields: {id: {strategy: keep, strategy: drop}}",
                "fields: {id: &rule {strategy: keep}, other: *rule}",
                "fields: {id: {strategy: &s keep}, other: {strategy: *s}}",
                "fields: {id: {strategy: !!java.lang.String keep}}",
                "fields: {id: {<<: {strategy: keep}}}",
                "fields: {id: {strategy: keep}}\n---\nfields: {id: {strategy: drop}}")) {
            assertThrows(FileMaskingException.class, () -> loader.load(new StringReader(policy)), policy);
        }
    }

    @Test
    void shouldRejectUnsupportedSchemaAndImplicitNonStringScalars() {
        for (String policy : List.of("", "[]", "fields: {}", "fields: {id: {strategy: invalid}}",
                "fields: {id: {strategy: constant}}",
                "fields: {id: {strategy: constant, value: 123}}",
                "fields: {id: {strategy: constant, value: null}}",
                "fields: {id: {strategy: keep}}\nextra: true",
                "fields: {id: {strategy: keep, extra: ignored}}",
                "fields: {id: [keep]}")) {
            assertThrows(FileMaskingException.class, () -> loader.load(new StringReader(policy)), policy);
        }
    }

    @Test
    void shouldRejectOversizedPoliciesAndScalars() {
        assertThrows(FileMaskingException.class, () -> loader.load(new StringReader("#" + "x".repeat(262_145))));
        assertThrows(FileMaskingException.class, () -> loader.load(new StringReader(
                "fields: {id: {strategy: constant, value: '" + "x".repeat(65_537) + "'}}")));
    }
}
