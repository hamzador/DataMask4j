package io.github.hador.datamask4j.batch;

import io.github.hador.datamask4j.core.DataMasker;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import java.util.Map;
import java.util.Objects;

/** Explicit, stateless item transformation. Does not own a transaction, reader or writer. */
public final class MapMaskingItemProcessor implements ItemProcessor<Map<String, String>, Map<String, String>> {
    private final DataMasker masker;

    public MapMaskingItemProcessor(DataMasker masker) {
        this.masker = Objects.requireNonNull(masker, "masker");
    }

    @Override
    public Map<String, String> process(Map<String, String> item) {
        try {
            return Objects.requireNonNull(masker.mask(Objects.requireNonNull(item)));
        } catch (RuntimeException ignored) {
            // Never leak a custom strategy, map implementation or input value in job exceptions.
            throw new BatchMaskingException();
        }
    }
}
