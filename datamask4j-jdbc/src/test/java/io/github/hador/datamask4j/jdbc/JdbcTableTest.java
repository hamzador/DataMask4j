package io.github.hador.datamask4j.jdbc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcTableTest {
    @Test
    void shouldQuoteSupportedIdentifiers() {
        assertEquals("\"public\".\"customer\"", new JdbcTable("demo", "public", "customer").qualifiedName());
    }

    @Test
    void shouldRejectSqlFragmentsAndSystemSchemas() {
        for (String value : new String[]{"customer; drop table customer", "Customer", "a.b", "a b", "a".repeat(64), ""}) {
            assertThrows(IllegalArgumentException.class, () -> new JdbcTable("demo", "public", value));
        }
        assertThrows(IllegalArgumentException.class, () -> new JdbcTable("demo", "pg_catalog", "customer"));
        assertThrows(IllegalArgumentException.class, () -> new JdbcTable("demo", "information_schema", "customer"));
    }

    @Test
    void shouldRejectInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcMaskingLimits(0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new JdbcMaskingLimits(1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new JdbcMaskingLimits(1, 1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new JdbcMaskingLimits(1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new JdbcMaskingLimits(1, 1, 1, 1, 0));
    }
}
