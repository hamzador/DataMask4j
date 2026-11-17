package io.github.hador.datamask4j.jdbc;

import io.github.hador.datamask4j.core.DataMasker;
import java.util.Objects;

/** One source table, explicit policy and isolated target. */
public record JdbcExportStep(JdbcTable source, JdbcExportTarget target, DataMasker masker) {
    public JdbcExportStep {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(masker, "masker");
    }
}
