package io.github.hador.datamask4j.jdbc;

import io.github.hador.datamask4j.core.DataMasker;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Single-table PostgreSQL export into an empty, explicitly configured test database.
 * Inserts only; never modifies the source. Caller must provide trusted, isolated data sources.
 */
public final class JdbcExporter {
    private final DataMasker masker;
    private final JdbcMaskingLimits limits;
    private final JdbcMasker sourceChecks;

    public JdbcExporter(DataMasker masker) {
        this(masker, JdbcMaskingLimits.defaults());
    }

    public JdbcExporter(DataMasker masker, JdbcMaskingLimits limits) {
        this.masker = Objects.requireNonNull(masker, "masker");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.sourceChecks = new JdbcMasker(masker, limits);
    }

    /**
     * Returns counts only after the target commit succeeds. On an ambiguous commit/connection
     * failure, inspect the isolated target before retrying; no automatic retry is performed.
     */
    public JdbcMaskingReport export(DataSource source, JdbcTable sourceTable,
                                    DataSource destination, JdbcExportTarget target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceTable, "sourceTable");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(target, "target");
        require(source != destination && !sourceTable.database().equals(target.table().database()));

        try (Connection write = destination.getConnection()) {
            requirePostgres(write);
            require(write.getAutoCommit());
            write.setReadOnly(false);
            write.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            write.setAutoCommit(false);
            try {
                verifyTargetSession(write, target);
                JdbcMaskingReport report;
                try (Connection read = source.getConnection()) {
                    requirePostgres(read);
                    require(read.getAutoCommit());
                    read.setReadOnly(true);
                    read.setAutoCommit(false);
                    try {
                        sourceChecks.verifySource(read, sourceTable);
                        try (var lock = read.createStatement()) {
                            lock.setQueryTimeout(limits.queryTimeoutSeconds());
                            lock.execute("LOCK TABLE ONLY " + sourceTable.qualifiedName() + " IN ACCESS SHARE MODE");
                        }
                        sourceChecks.verifySourceTable(read, sourceTable);
                        report = copy(read, sourceTable, write, target.table());
                    } finally {
                        read.rollback();
                    }
                }
                // The source transaction and connection are closed before committing the target.
                write.commit();
                return report;
            } catch (SQLException | RuntimeException failure) {
                write.rollback();
                throw failure;
            }
        } catch (SQLException | RuntimeException ignored) {
            throw new JdbcMaskingException("JDBC export failed; inspect target before retrying");
        }
    }

    private void verifyTargetSession(Connection connection, JdbcExportTarget target) throws SQLException {
        String sql = """
                SELECT pg_catalog.current_database(), current_user,
                       pg_catalog.current_setting('transaction_read_only'),
                       pg_catalog.current_setting('transaction_isolation'),
                       r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls
                FROM pg_catalog.pg_roles r WHERE r.rolname = current_user
                """;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(limits.queryTimeoutSeconds());
            try (var rows = statement.executeQuery(sql)) {
                require(rows.next() && target.table().database().equals(rows.getString(1))
                        && target.writerRole().equals(rows.getString(2))
                        && "off".equals(rows.getString(3)) && "serializable".equals(rows.getString(4))
                        && !rows.getBoolean(5));
            }
        }
    }

    private JdbcMaskingReport copy(Connection read, JdbcTable sourceTable,
                                   Connection write, JdbcTable target) throws SQLException {
        try (var statement = read.createStatement()) {
            statement.setQueryTimeout(limits.queryTimeoutSeconds());
            statement.setFetchSize(limits.fetchSize());
            try (var rows = statement.executeQuery("SELECT * FROM ONLY " + sourceTable.qualifiedName())) {
                List<String> fields = sourceChecks.columns(rows.getMetaData());
                List<String> retained = masker.outputFields(fields);
                require(!retained.isEmpty());
                verifyTarget(write, target, retained);
                try (var insert = write.prepareStatement(insertSql(target, retained))) {
                    insert.setQueryTimeout(limits.queryTimeoutSeconds());
                    long count = 0;
                    while (rows.next()) {
                        require(count < limits.maxRows());
                        var record = new LinkedHashMap<String, String>();
                        for (int i = 0; i < fields.size(); i++) {
                            String value = rows.getString(i + 1);
                            sourceChecks.checkValue(value);
                            record.put(fields.get(i), value);
                        }
                        var masked = masker.mask(record);
                        for (int i = 0; i < retained.size(); i++) {
                            String value = masked.get(retained.get(i));
                            sourceChecks.checkValue(value);
                            if (value == null) {
                                insert.setNull(i + 1, Types.VARCHAR);
                            } else {
                                insert.setString(i + 1, value);
                            }
                        }
                        require(insert.executeUpdate() == 1);
                        count++;
                    }
                    return new JdbcMaskingReport(count, fields.size(), retained.size());
                }
            }
        }
    }

    private void verifyTarget(Connection connection, JdbcTable target, List<String> retained) throws SQLException {
        verifyTargetCatalog(connection, target);
        // Hold ACCESS SHARE for the relation before the repeated catalog checks.
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(limits.queryTimeoutSeconds());
            try (var rows = statement.executeQuery("SELECT * FROM ONLY " + target.qualifiedName() + " LIMIT 0")) {
                List<String> actual = sourceChecks.columns(rows.getMetaData());
                require(actual.size() == retained.size() && new HashSet<>(actual).equals(new HashSet<>(retained)));
            }
        }
        verifyTargetCatalog(connection, target);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(limits.queryTimeoutSeconds());
            try (var rows = statement.executeQuery("SELECT 1 FROM ONLY " + target.qualifiedName() + " LIMIT 1")) {
                require(!rows.next());
            }
        }
    }

    private void verifyTargetCatalog(Connection connection, JdbcTable target) throws SQLException {
        String sql = """
                SELECT c.relkind = 'r' AND c.relpersistence = 'p'
                       AND NOT c.relispartition AND NOT c.relrowsecurity
                       AND pg_catalog.has_table_privilege(current_user,c.oid,'SELECT')
                       AND pg_catalog.has_table_privilege(current_user,c.oid,'INSERT')
                       AND NOT pg_catalog.has_table_privilege(current_user,c.oid,'UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES')
                       AND NOT pg_catalog.has_any_column_privilege(current_user,c.oid,'UPDATE,REFERENCES')
                       AND NOT pg_catalog.pg_has_role(current_user,c.relowner,'MEMBER')
                       AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid)
                       AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_trigger t WHERE t.tgrelid=c.oid)
                       AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite r WHERE r.ev_class=c.oid)
                       AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_attrdef d WHERE d.adrelid=c.oid)
                       AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a WHERE a.attrelid=c.oid
                           AND a.attnum>0 AND NOT a.attisdropped AND (a.attidentity<>'' OR a.attgenerated<>''))
                       AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.conrelid=c.oid AND k.contype NOT IN ('p','u','n'))
                       AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_index i WHERE i.indrelid=c.oid
                           AND (i.indexprs IS NOT NULL OR i.indpred IS NOT NULL))
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND c.relname=?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(limits.queryTimeoutSeconds());
            statement.setString(1, target.schema());
            statement.setString(2, target.table());
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next() && rows.getBoolean(1));
            }
        }
    }

    private static String insertSql(JdbcTable target, List<String> retained) {
        String fields = retained.stream()
                .map(field -> "\"" + JdbcTable.identifier(field) + "\"")
                .collect(Collectors.joining(","));
        String parameters = String.join(",", Collections.nCopies(retained.size(), "?"));
        return "INSERT INTO " + target.qualifiedName() + " (" + fields + ") VALUES (" + parameters + ")";
    }

    private static void requirePostgres(Connection connection) throws SQLException {
        require("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new JdbcMaskingException("JDBC export safety check failed");
        }
    }
}
