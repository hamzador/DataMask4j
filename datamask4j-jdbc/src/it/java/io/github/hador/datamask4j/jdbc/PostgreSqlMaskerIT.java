package io.github.hador.datamask4j.jdbc;

import io.github.hador.datamask4j.core.DataMasker;
import io.github.hador.datamask4j.core.FieldRule;
import io.github.hador.datamask4j.core.MaskingPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgreSqlMaskerIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres@sha256:f377d590e5474681d4d7838635031a2b50647923b7abfa803ce9b5ff35d5c663"))
            .withDatabaseName("masking_demo");

    private static final String TEST_PASSWORD = "fixture-only-password";
    private static final JdbcTable CUSTOMER = new JdbcTable("masking_demo", "public", "customer");
    private JdbcMasker masker;

    @BeforeAll
    static void createFixtures() throws SQLException {
        try (var connection = admin().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE mask_reader LOGIN PASSWORD '" + TEST_PASSWORD + "'");
            statement.execute("CREATE ROLE mask_writer LOGIN PASSWORD '" + TEST_PASSWORD + "'");
            statement.execute("CREATE ROLE mask_column_writer LOGIN PASSWORD '" + TEST_PASSWORD + "'");
            statement.execute("CREATE TABLE public.customer (email varchar(255), notes text)");
            statement.execute("INSERT INTO public.customer VALUES ('fixture@example.test', 'synthetic'), (NULL, 'other')");
            statement.execute("CREATE TABLE public.empty_customer (email text, notes text)");
            statement.execute("CREATE TABLE public.extra_customer (email text, notes text, secret text)");
            statement.execute("CREATE TABLE public.numeric_customer (email text, notes integer)");
            statement.execute("CREATE VIEW public.customer_view AS SELECT * FROM public.customer");
            statement.execute("CREATE TABLE public.rls_customer (email text, notes text)");
            statement.execute("ALTER TABLE public.rls_customer ENABLE ROW LEVEL SECURITY");
            statement.execute("CREATE TABLE public.partitioned_customer (email text, notes text) PARTITION BY LIST (email)");
            statement.execute("CREATE TABLE public.partition_customer PARTITION OF public.partitioned_customer FOR VALUES IN ('fixture@example.test')");
            statement.execute("GRANT USAGE ON SCHEMA public TO mask_reader, mask_writer, mask_column_writer");
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO mask_reader, mask_writer, mask_column_writer");
            statement.execute("GRANT UPDATE ON public.customer TO mask_writer");
            statement.execute("GRANT UPDATE(email) ON public.customer TO mask_column_writer");
        }
    }

    @BeforeEach
    void setUp() {
        masker = new JdbcMasker(new DataMasker(MaskingPolicy.builder()
                .field("email", FieldRule.redact())
                .field("notes", FieldRule.drop())
                .build()));
    }

    private static DataSource admin() {
        return datasource(POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DataSource reader() {
        return datasource("mask_reader", TEST_PASSWORD);
    }

    private static DataSource datasource(String user, String password) {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(user);
        source.setPassword(password);
        source.setConnectTimeout(10);
        return source;
    }

    private static JdbcTable table(String name) {
        return new JdbcTable("masking_demo", "public", name);
    }

    @Test
    void shouldPreviewRealPostgresAndLeaveSourceUnchanged() throws SQLException {
        assertEquals(new JdbcMaskingReport(2, 2, 1), masker.dryRun(reader(), CUSTOMER));
        try (var connection = admin().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT email, notes FROM public.customer ORDER BY email NULLS LAST")) {
            assertTrue(rows.next());
            assertEquals("fixture@example.test", rows.getString(1));
            assertEquals("synthetic", rows.getString(2));
            assertTrue(rows.next());
            assertNull(rows.getString(1));
            assertEquals("other", rows.getString(2));
            assertFalse(rows.next());
        }
    }

    @Test
    void shouldAcceptEmptyTableWithMatchingSchema() {
        assertEquals(new JdbcMaskingReport(0, 2, 1), masker.dryRun(reader(), table("empty_customer")));
    }

    @Test
    void shouldRejectTableLevelAndColumnLevelWritePrivileges() {
        assertThrows(JdbcMaskingException.class,
                () -> masker.dryRun(datasource("mask_writer", TEST_PASSWORD), CUSTOMER));
        assertThrows(JdbcMaskingException.class,
                () -> masker.dryRun(datasource("mask_column_writer", TEST_PASSWORD), CUSTOMER));
        assertThrows(JdbcMaskingException.class, () -> masker.dryRun(admin(), CUSTOMER));
    }

    @Test
    void shouldRejectWrongDatabaseIdentity() {
        assertThrows(JdbcMaskingException.class,
                () -> masker.dryRun(reader(), new JdbcTable("wrong_db", "public", "customer")));
    }

    @Test
    void shouldRejectUnclassifiedColumnsEvenWhenTableIsEmpty() {
        assertThrows(JdbcMaskingException.class,
                () -> masker.dryRun(reader(), table("extra_customer")));
    }

    @Test
    void shouldRejectUnsupportedColumnTypesAndTableKinds() {
        for (String name : new String[]{"numeric_customer", "customer_view", "rls_customer",
                "partitioned_customer", "partition_customer", "missing_customer"}) {
            assertThrows(JdbcMaskingException.class, () -> masker.dryRun(reader(), table(name)), name);
        }
    }

    @Test
    void shouldFailWhenActualRowsExceedBound() {
        var bounded = new JdbcMasker(new DataMasker(MaskingPolicy.builder()
                .field("email", FieldRule.redact()).field("notes", FieldRule.drop()).build()),
                new JdbcMaskingLimits(1, 2, 100, 1, 5));
        assertThrows(JdbcMaskingException.class, () -> bounded.dryRun(reader(), CUSTOMER));
    }

    @Test
    void shouldFailWhenFieldValueExceedsBound() {
        var bounded = new JdbcMasker(new DataMasker(MaskingPolicy.builder()
                .field("email", FieldRule.keep()).field("notes", FieldRule.drop()).build()),
                new JdbcMaskingLimits(10, 2, 5, 1, 5));
        assertThrows(JdbcMaskingException.class, () -> bounded.dryRun(reader(), CUSTOMER));
    }

    @Test
    void shouldEnforceReadOnlyOnServerEvenForWriteCapableRole() throws SQLException {
        try (var connection = datasource("mask_writer", TEST_PASSWORD).getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY");
                try (var rows = statement.executeQuery("SHOW transaction_read_only")) {
                    assertTrue(rows.next());
                    assertEquals("on", rows.getString(1));
                }
                SQLException error = assertThrows(SQLException.class,
                        () -> statement.executeUpdate("UPDATE public.customer SET notes = 'changed'"));
                assertEquals("25006", error.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void shouldRejectDatabaseWriteBySelectOnlyRole() throws SQLException {
        try (var connection = reader().getConnection(); var statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("UPDATE public.customer SET notes = 'changed'"));
            assertEquals("42501", error.getSQLState());
        }
    }
}
