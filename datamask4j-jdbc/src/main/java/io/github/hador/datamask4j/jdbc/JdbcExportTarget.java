package io.github.hador.datamask4j.jdbc;

import java.util.Objects;

/** Explicit acknowledgement of a separately provisioned, disposable test database. */
public record JdbcExportTarget(JdbcTable table, String writerRole) {
    public JdbcExportTarget {
        Objects.requireNonNull(table, "table");
        JdbcTable.identifier(writerRole);
        if (!table.database().startsWith("dm_test_")) {
            throw new IllegalArgumentException("Target database must start with dm_test_");
        }
    }
}
