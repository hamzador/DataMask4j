package io.github.hador.datamask4j.jdbc;

import io.github.hador.datamask4j.core.DataMasker;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** PostgreSQL-only counts preview. No target connection, DDL or DML. */
public final class JdbcMasker {
    private final DataMasker masker;
    private final JdbcMaskingLimits limits;

    public JdbcMasker(DataMasker masker) {
        this(masker, JdbcMaskingLimits.defaults());
    }

    public JdbcMasker(DataMasker masker, JdbcMaskingLimits limits) {
        this.masker = Objects.requireNonNull(masker, "masker");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public JdbcMaskingReport dryRun(DataSource source, JdbcTable table) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(table, "table");
        try (Connection connection = source.getConnection()) {
            require("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()));
            require(connection.getAutoCommit());
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                verifySource(connection, table);
                return preview(connection, table);
            } finally {
                connection.rollback();
            }
        } catch (SQLException | RuntimeException ignored) {
            throw new JdbcMaskingException("JDBC dry run failed");
        }
    }

    private void verifySource(Connection connection, JdbcTable table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(limits.queryTimeoutSeconds());
            statement.execute("SET TRANSACTION READ ONLY");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT pg_catalog.current_database(), pg_catalog.current_setting('transaction_read_only')")) {
                require(rows.next() && table.database().equals(rows.getString(1)) && "on".equals(rows.getString(2)));
            }
        }
        String metadata = "SELECT c.relkind, c.relispartition, c.relrowsecurity, "
                + "pg_catalog.has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER') "
                + "OR pg_catalog.has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE') "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname=? AND c.relname=?";
        try (PreparedStatement statement = connection.prepareStatement(metadata)) {
            statement.setQueryTimeout(limits.queryTimeoutSeconds());
            statement.setString(1, table.schema());
            statement.setString(2, table.table());
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next() && "r".equals(rows.getString(1))
                        && !rows.getBoolean(2) && !rows.getBoolean(3) && !rows.getBoolean(4));
            }
        }
    }

    private JdbcMaskingReport preview(Connection connection, JdbcTable table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(limits.queryTimeoutSeconds());
            statement.setFetchSize(limits.fetchSize());
            try (ResultSet rows = statement.executeQuery("SELECT * FROM ONLY " + table.qualifiedName())) {
                var fields = columns(rows.getMetaData());
                var output = masker.outputFields(fields);
                long count = 0;
                while (rows.next()) {
                    require(count < limits.maxRows());
                    var record = new LinkedHashMap<String, String>();
                    for (int i = 0; i < fields.size(); i++) {
                        String value = rows.getString(i + 1);
                        checkValue(value);
                        record.put(fields.get(i), value);
                    }
                    masker.mask(record).values().forEach(this::checkValue);
                    count++;
                }
                return new JdbcMaskingReport(count, fields.size(), output.size());
            }
        }
    }

    private List<String> columns(ResultSetMetaData metadata) throws SQLException {
        int count = metadata.getColumnCount();
        require(count > 0 && count <= limits.maxColumns());
        var fields = new ArrayList<String>(count);
        for (int index = 1; index <= count; index++) {
            require(metadata.getColumnType(index) == Types.VARCHAR
                    || metadata.getColumnType(index) == Types.LONGVARCHAR);
            String type = metadata.getColumnTypeName(index);
            require("text".equals(type) || "varchar".equals(type));
            fields.add(JdbcTable.identifier(metadata.getColumnName(index)));
        }
        return fields;
    }

    private void checkValue(String value) {
        require(value == null || value.length() <= limits.maxValueChars());
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new JdbcMaskingException("JDBC preview safety check failed");
        }
    }
}
