package example.datamask4j;

import io.github.hador.datamask4j.annotations.Mask;
import io.github.hador.datamask4j.annotations.RecordMasker;
import io.github.hador.datamask4j.spring.MaskingKeyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static io.github.hador.datamask4j.annotations.MaskType.*;
import static org.junit.jupiter.api.Assertions.*;

public class BootConsumerTest {
    public record Customer(@Mask(strategy = HMAC, scope = "fixture:v1") String id,
                           @Mask(strategy = REDACT) String email) {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class Application {
        @Bean
        MaskingKeyProvider fixtureKeyProvider() {
            return () -> new byte[32]; // synthetic test key only
        }
    }

    @Test
    void shouldDiscoverStarterFromInstalledJarAndMaskExplicitly() {
        try (var context = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).properties("datamask4j.enabled=true",
                        "spring.main.banner-mode=off").run()) {
            var original = new Customer("C-42", "fixture@example.test");
            var masker = context.getBean(RecordMasker.class);
            var masked = masker.mask(original);
            assertEquals("[REDACTED]", masked.email());
            assertTrue(masked.id().matches("dm1_[A-Za-z0-9_-]{43}"));
            assertEquals(masked, masker.mask(original));
            assertEquals("fixture@example.test", original.email());
        }
    }

    @Test
    void shouldNotEnableStarterByDefault() {
        try (var context = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE).properties("spring.main.banner-mode=off").run()) {
            assertTrue(context.getBeansOfType(RecordMasker.class).isEmpty());
        }
    }
}
