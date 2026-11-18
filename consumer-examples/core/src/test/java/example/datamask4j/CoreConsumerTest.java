package example.datamask4j;

import io.github.hador.datamask4j.core.DataMasker;
import io.github.hador.datamask4j.core.FieldRule;
import io.github.hador.datamask4j.core.MaskingPolicy;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CoreConsumerTest {
    @Test
    void shouldMaskUsingOnlyInstalledCoreArtifact() {
        var masker = new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.keep())
                .field("email", FieldRule.redact())
                .field("notes", FieldRule.drop()).build());
        assertEquals(Map.of("id", "fixture", "email", "[REDACTED]"),
                masker.mask(Map.of("id", "fixture", "email", "fixture@example.test", "notes", "synthetic")));
    }

    @Test
    void shouldNotPullOptionalRuntimeFrameworks() {
        for (String name : new String[]{"org.springframework.context.ApplicationContext",
                "com.fasterxml.jackson.databind.ObjectMapper", "org.postgresql.Driver",
                "io.github.hador.datamask4j.crypto.HmacPseudonymizer"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name));
        }
    }
}
