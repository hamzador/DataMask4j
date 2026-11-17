package io.github.hador.datamask4j.spring;

import io.github.hador.datamask4j.annotations.Mask;
import io.github.hador.datamask4j.annotations.RecordMasker;
import io.github.hador.datamask4j.annotations.RecordMaskingException;
import io.github.hador.datamask4j.crypto.HmacPseudonymizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.hador.datamask4j.annotations.MaskType.*;
import static org.junit.jupiter.api.Assertions.*;

public class DataMaskAutoConfigurationTest {
    private byte[] key;

    @BeforeEach
    void setUp() {
        key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class ConsumerApplication {}

    public record Customer(@Mask(strategy = HMAC, scope = "customer:v1") String id,
                           @Mask(strategy = REDACT) String notes) {}

    public record Plain(@Mask(strategy = REDACT) String notes) {}

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(ConsumerApplication.class);
        return context;
    }

    private static AnnotationConfigApplicationContext enabled() {
        return context(Map.of("datamask4j.enabled", "true"));
    }

    @Test
    void shouldBeDisabledByDefaultAndNotLoadKey() {
        AtomicInteger calls = new AtomicInteger();
        try (var context = context(Map.of())) {
            context.registerBean(MaskingKeyProvider.class, () -> () -> {
                calls.incrementAndGet();
                return key.clone();
            });
            context.refresh();
            assertTrue(context.getBeansOfType(RecordMasker.class).isEmpty());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void shouldRemainDisabledWhenExplicitlyFalse() {
        try (var context = context(Map.of("datamask4j.enabled", "false"))) {
            context.refresh();
            assertTrue(context.getBeansOfType(RecordMasker.class).isEmpty());
        }
    }

    @Test
    void shouldDiscoverAutoConfigurationAndMaskWithExternalKey() {
        String expected = new HmacPseudonymizer(key, "customer:v1").transform("C-42");
        AtomicInteger calls = new AtomicInteger();
        byte[] transferred = key.clone();
        try (var context = enabled()) {
            context.registerBean(MaskingKeyProvider.class, () -> () -> {
                calls.incrementAndGet();
                return transferred;
            });
            context.refresh();
            var masker = context.getBean(RecordMasker.class);
            var original = new Customer("C-42", "raw");
            assertEquals(new Customer(expected, "[REDACTED]"), masker.mask(original));
            assertEquals("raw", original.notes());
            assertEquals(1, calls.get());
            assertArrayEquals(new byte[32], transferred);
            assertSame(masker, context.getBean(RecordMasker.class));
        }
    }

    @Test
    void shouldFailStartupWhenRequiredProviderIsMissing() {
        try (var context = enabled()) {
            var failure = assertThrows(RuntimeException.class, context::refresh);
            assertSanitized(failure);
        }
    }

    @Test
    void shouldAllowExplicitUnkeyedModeButRejectKeyedRecord() {
        try (var context = context(Map.of("datamask4j.enabled", "true", "datamask4j.key-required", "false"))) {
            context.refresh();
            var masker = context.getBean(RecordMasker.class);
            assertEquals(new Plain("[REDACTED]"), masker.mask(new Plain("raw")));
            assertThrows(RecordMaskingException.class, () -> masker.mask(new Customer("C-42", "raw")));
        }
    }

    @Test
    void shouldUseProviderWhenPresentEvenIfKeyIsOptional() {
        try (var context = context(Map.of("datamask4j.enabled", "true", "datamask4j.key-required", "false"))) {
            context.registerBean(MaskingKeyProvider.class, () -> () -> key.clone());
            context.refresh();
            assertEquals(new HmacPseudonymizer(key, "customer:v1").transform("C-42"),
                    context.getBean(RecordMasker.class).mask(new Customer("C-42", "raw")).id());
        }
    }

    @Test
    void shouldRejectShortOrNullKeysAndClearTransferredArray() {
        byte[] shortKey = new byte[31];
        Arrays.fill(shortKey, (byte) 42);
        try (var context = enabled()) {
            context.registerBean(MaskingKeyProvider.class, () -> () -> shortKey);
            assertSanitized(assertThrows(RuntimeException.class, context::refresh));
            assertArrayEquals(new byte[31], shortKey);
        }
        try (var context = enabled()) {
            context.registerBean(MaskingKeyProvider.class, () -> () -> null);
            assertSanitized(assertThrows(RuntimeException.class, context::refresh));
        }
    }

    @Test
    void shouldSanitizeProviderFailures() {
        try (var context = enabled()) {
            context.registerBean(MaskingKeyProvider.class, () -> () -> {
                throw new IllegalArgumentException("synthetic-sensitive-provider-detail");
            });
            var failure = assertThrows(RuntimeException.class, context::refresh);
            assertSanitized(failure);
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                assertFalse(String.valueOf(cause.getMessage()).contains("synthetic-sensitive-provider-detail"));
            }
        }
    }

    @Test
    void shouldRejectAmbiguousProviders() {
        try (var context = enabled()) {
            context.registerBean("firstProvider", MaskingKeyProvider.class, () -> () -> key.clone());
            context.registerBean("secondProvider", MaskingKeyProvider.class, () -> () -> key.clone());
            assertSanitized(assertThrows(RuntimeException.class, context::refresh));
        }
    }

    @Test
    void shouldBackOffForApplicationDefinedMasker() {
        var custom = new RecordMasker();
        AtomicInteger calls = new AtomicInteger();
        try (var context = enabled()) {
            context.registerBean("customMasker", RecordMasker.class, () -> custom);
            context.registerBean(MaskingKeyProvider.class, () -> () -> {
                calls.incrementAndGet();
                return key.clone();
            });
            context.refresh();
            assertEquals(1, context.getBeansOfType(RecordMasker.class).size());
            assertSame(custom, context.getBean(RecordMasker.class));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void shouldRejectInvalidKeyRequiredConfiguration() {
        try (var context = context(Map.of("datamask4j.enabled", "true", "datamask4j.key-required", "invalid"))) {
            assertSanitized(assertThrows(RuntimeException.class, context::refresh));
        }
    }

    private static void assertSanitized(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertEquals("DataMask4j key configuration failed", root.getMessage());
    }
}
