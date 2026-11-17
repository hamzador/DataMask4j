package io.github.hador.datamask4j.batch;

import io.github.hador.datamask4j.annotations.RecordMasker;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import java.util.Objects;

/** Explicit processor for public, flat, fully annotated Java records. */
public final class RecordMaskingItemProcessor<T extends Record> implements ItemProcessor<T, T> {
    private final RecordMasker masker;

    public RecordMaskingItemProcessor(RecordMasker masker) {
        this.masker = Objects.requireNonNull(masker, "masker");
    }

    @Override
    public T process(T item) {
        try {
            return Objects.requireNonNull(masker.mask(Objects.requireNonNull(item)));
        } catch (RuntimeException ignored) {
            throw new BatchMaskingException();
        }
    }
}
