package io.github.hador.datamask4j.spring.autoconfigure;

import io.github.hador.datamask4j.annotations.RecordMasker;
import io.github.hador.datamask4j.spring.MaskingKeyProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/** Explicitly enabled, with no web, database, logging or scheduling interception. */
@AutoConfiguration
@ConditionalOnClass(RecordMasker.class)
@ConditionalOnProperty(prefix = "datamask4j", name = "enabled", havingValue = "true")
public class DataMaskAutoConfiguration {
    @Bean
    @Lazy(false)
    @ConditionalOnMissingBean(RecordMasker.class)
    public RecordMasker recordMasker(ObjectProvider<MaskingKeyProvider> providers, Environment environment) {
        byte[] key = null;
        try {
            boolean keyRequired = environment.getProperty("datamask4j.key-required", Boolean.class, true);
            MaskingKeyProvider provider = providers.getIfAvailable();
            if (provider == null) {
                if (keyRequired) {
                    throw new IllegalStateException("Key provider required");
                }
                return new RecordMasker();
            }
            key = provider.loadKey();
            if (key == null || key.length < 32) {
                throw new IllegalStateException("Invalid masking key");
            }
            return new RecordMasker(key);
        } catch (RuntimeException ignored) {
            // Provider failures can contain secret-manager details or credentials.
            throw new IllegalStateException("DataMask4j key configuration failed");
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }
}
