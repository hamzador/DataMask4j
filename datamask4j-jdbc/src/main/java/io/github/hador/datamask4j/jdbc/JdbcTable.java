package io.github.hador.datamask4j.jdbc;

import java.util.Objects;
import java.util.regex.Pattern;

/** Conservative PostgreSQL identifier subset; no arbitrary SQL fragments. */
public record JdbcTable(String database, String schema, String table) {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public JdbcTable {
        identifier(database);
        identifier(schema);
        identifier(table);
        if (schema.equals("information_schema") || schema.startsWith("pg_")) {
            throw new IllegalArgumentException("System schemas are not supported");
        }
    }

    static String identifier(String value) {
        if (!IDENTIFIER.matcher(Objects.requireNonNull(value, "identifier")).matches()) {
            throw new IllegalArgumentException("Unsupported SQL identifier");
        }
        return value;
    }

    String qualifiedName() {
        return "\"" + schema + "\".\"" + table + "\"";
    }
}
