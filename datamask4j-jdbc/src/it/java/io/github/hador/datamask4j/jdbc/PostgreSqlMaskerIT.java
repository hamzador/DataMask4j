package io.github.hador.datamask4j.jdbc;

import io.github.hador.datamask4j.core.DataMasker;
import io.github.hador.datamask4j.core.FieldRule;
import io.github.hador.datamask4j.core.MaskingPolicy;
import io.github.hador.datamask4j.crypto.HmacPseudonymizer;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
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
            postgresImage())
            .withDatabaseName("masking_demo");

    private static DockerImageName postgresImage() {
        String image = System.getenv("DATAMASK4J_POSTGRES_IMAGE");
        if (image == null) {
            return DockerImageName.parse("postgres@sha256:f377d590e5474681d4d7838635031a2b50647923b7abfa803ce9b5ff35d5c663");
        }
        if (!"datamask4j-postgres-ci:patched".equals(image)) {
            throw new IllegalStateException("Unexpected CI PostgreSQL image");
        }
        return DockerImageName.parse(image).asCompatibleSubstituteFor("postgres");
    }

    private static final String TEST_PASSWORD = "fixture-only-password";
    private static final JdbcTable CUSTOMER = new JdbcTable("masking_demo", "public", "customer");
    private JdbcMasker masker;

    @BeforeAll
    static void createFixtures() throws SQLException {
        String expectedImageId = System.getenv("DATAMASK4J_POSTGRES_EXPECTED_ID");
        if (System.getenv("DATAMASK4J_POSTGRES_IMAGE") != null) {
            assertNotNull(expectedImageId, "CI image identity required");
            assertEquals(expectedImageId, POSTGRES.getContainerInfo().getImageId(),
                    "Integration tests must use the prepared image");
            String ryukId = System.getenv("DATAMASK4J_RYUK_EXPECTED_ID");
            assertNotNull(ryukId, "CI Ryuk image identity required");
            assertTrue(DockerClientFactory.instance().client().listContainersCmd().withShowAll(true).exec()
                    .stream().anyMatch(container -> ryukId.equals(container.getImageId())
                            && "running".equals(container.getState())),
                    "The pinned Ryuk reaper must be running during the tests");
        }
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
            statement.execute("CREATE ROLE dm_mask_writer LOGIN PASSWORD '" + TEST_PASSWORD + "'");
            statement.execute("CREATE TABLE public.src_parent (id text PRIMARY KEY, name text)");
            statement.execute("INSERT INTO public.src_parent VALUES ('C-42', 'Synthetic name')");
            statement.execute("CREATE TABLE public.src_child (id text PRIMARY KEY, customer_id text REFERENCES public.src_parent(id))");
            statement.execute("INSERT INTO public.src_child VALUES ('O-1', 'C-42')");
            statement.execute("GRANT SELECT ON public.src_parent, public.src_child TO mask_reader");
            statement.execute("CREATE DATABASE dm_test_export");
        }
        createExportFixtures();
    }

    private static void createExportFixtures() throws SQLException {
        try (var connection = targetAdmin().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.export_ok (email text)");
            statement.execute("CREATE TABLE public.export_existing (email text)");
            statement.execute("INSERT INTO public.export_existing VALUES ('already-present')");
            statement.execute("CREATE TABLE public.export_rollback (email text NOT NULL)");
            statement.execute("CREATE TABLE public.export_extra (email text, extra text)");
            statement.execute("CREATE TABLE public.export_default (email text DEFAULT 'unexpected')");
            statement.execute("CREATE TABLE public.export_wrong_type (email integer)");
            statement.execute("CREATE TABLE public.export_excess_privileges (email text)");
            statement.execute("CREATE TABLE public.export_bound (email text)");
            for (String suffix : List.of("ok", "rollback", "wrong_order", "external", "bound", "deferred")) {
                statement.execute("CREATE TABLE public.parent_" + suffix + " (id text PRIMARY KEY, name text)");
                statement.execute("CREATE TABLE public.child_" + suffix + " (id text PRIMARY KEY, customer_id text REFERENCES public.parent_"
                        + suffix + "(id)" + (suffix.equals("deferred") ? " DEFERRABLE INITIALLY DEFERRED" : "") + ")");
            }
            statement.execute("GRANT USAGE ON SCHEMA public TO dm_mask_writer");
            statement.execute("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO dm_mask_writer");
            statement.execute("GRANT DELETE ON public.export_excess_privileges TO dm_mask_writer");
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

    private static DataSource targetAdmin() {
        return targetDatasource(POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static DataSource targetWriter() {
        return targetDatasource("dm_mask_writer", TEST_PASSWORD);
    }

    private static DataSource targetDatasource(String user, String password) {
        var source = (PGSimpleDataSource) datasource(user, password);
        source.setDatabaseName("dm_test_export");
        return source;
    }

    private static JdbcExportTarget target(String name) {
        return new JdbcExportTarget(new JdbcTable("dm_test_export", "public", name), "dm_mask_writer");
    }

    private static JdbcExporter exporter() {
        return new JdbcExporter(new DataMasker(MaskingPolicy.builder()
                .field("email", FieldRule.redact()).field("notes", FieldRule.drop()).build()));
    }

    private static long targetCount(String name) throws SQLException {
        try (var connection = targetAdmin().getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM public." + JdbcTable.identifier(name))) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    @Test
    void shouldExportOnlyMaskedColumnsAndPreserveNull() throws SQLException {
        assertEquals(new JdbcMaskingReport(2, 2, 1),
                exporter().export(reader(), CUSTOMER, targetWriter(), target("export_ok")));
        try (var connection = targetAdmin().getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT email FROM public.export_ok ORDER BY email NULLS LAST")) {
            assertTrue(rows.next());
            assertEquals("[REDACTED]", rows.getString(1));
            assertTrue(rows.next());
            assertNull(rows.getString(1));
            assertFalse(rows.next());
        }
        assertEquals(new JdbcMaskingReport(2, 2, 1), masker.dryRun(reader(), CUSTOMER));
    }

    @Test
    void shouldRejectNonEmptyTargetWithoutOverwriting() throws SQLException {
        assertThrows(JdbcMaskingException.class, () ->
                exporter().export(reader(), CUSTOMER, targetWriter(), target("export_existing")));
        assertEquals(1, targetCount("export_existing"));
    }

    @Test
    void shouldRollbackWhenTargetConstraintFails() throws SQLException {
        assertThrows(JdbcMaskingException.class, () ->
                exporter().export(reader(), CUSTOMER, targetWriter(), target("export_rollback")));
        assertEquals(0, targetCount("export_rollback"));
    }

    @Test
    void shouldRollbackAfterFirstInsertWhenRowBoundIsExceeded() throws SQLException {
        var bounded = new JdbcExporter(new DataMasker(MaskingPolicy.builder()
                .field("email", FieldRule.redact()).field("notes", FieldRule.drop()).build()),
                new JdbcMaskingLimits(1, 2, 100, 1, 5));
        assertThrows(JdbcMaskingException.class, () ->
                bounded.export(reader(), CUSTOMER, targetWriter(), target("export_bound")));
        assertEquals(0, targetCount("export_bound"));
    }

    @Test
    void shouldRejectUnexpectedTargetSchemaAndDefaults() throws SQLException {
        for (String name : new String[]{"export_extra", "export_default", "export_wrong_type"}) {
            assertThrows(JdbcMaskingException.class, () ->
                    exporter().export(reader(), CUSTOMER, targetWriter(), target(name)));
            assertEquals(0, targetCount(name));
        }
    }

    @Test
    void shouldRejectPrivilegedTargetRoleAndWrongDatabase() throws SQLException {
        assertThrows(JdbcMaskingException.class, () ->
                exporter().export(reader(), CUSTOMER, targetWriter(), target("export_excess_privileges")));
        assertThrows(JdbcMaskingException.class, () ->
                exporter().export(reader(), CUSTOMER, targetAdmin(), target("export_bound")));
        assertThrows(JdbcMaskingException.class, () ->
                exporter().export(reader(), CUSTOMER, reader(), target("export_bound")));
        assertEquals(0, targetCount("export_bound"));
        assertEquals(0, targetCount("export_excess_privileges"));
    }

    @Test
    void shouldRejectSameDatabaseAndUnapprovedTargetName() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcExportTarget(CUSTOMER, "dm_mask_writer"));
        assertThrows(JdbcMaskingException.class, () ->
                exporter().export(targetWriter(), target("export_bound").table(),
                        targetWriter(), target("export_bound")));
        var sameDatasource = reader();
        assertThrows(JdbcMaskingException.class, () ->
                exporter().export(sameDatasource, CUSTOMER, sameDatasource, target("export_bound")));
    }

    private static List<JdbcExportStep> plan(String suffix, boolean consistent) {
        byte[] key = new byte[32]; // Synthetic test key only.
        var identity = new HmacPseudonymizer(key, "customer:v1");
        var foreignKey = consistent ? identity : new HmacPseudonymizer(key, "wrong-scope");
        var parentMasker = new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.transform(identity)).field("name", FieldRule.redact()).build());
        var childMasker = new DataMasker(MaskingPolicy.builder()
                .field("id", FieldRule.keep()).field("customer_id", FieldRule.transform(foreignKey)).build());
        return List.of(new JdbcExportStep(table("src_parent"), target("parent_" + suffix), parentMasker),
                new JdbcExportStep(table("src_child"), target("child_" + suffix), childMasker));
    }

    private static List<JdbcMaskingReport> exportPlan(List<JdbcExportStep> plan) {
        return JdbcExporter.exportAll(reader(), targetWriter(), plan, JdbcMaskingLimits.defaults());
    }

    @Test
    void shouldExportRelatedTablesWithSharedPseudonymAndAtomicCommit() throws SQLException {
        assertEquals(List.of(new JdbcMaskingReport(1, 2, 2), new JdbcMaskingReport(1, 2, 2)),
                exportPlan(plan("ok", true)));
        try (var connection = targetAdmin().getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT p.id, p.name, c.customer_id FROM public.parent_ok p JOIN public.child_ok c ON p.id=c.customer_id")) {
            assertTrue(rows.next());
            assertTrue(rows.getString(1).matches("dm1_[A-Za-z0-9_-]{43}"));
            assertEquals("[REDACTED]", rows.getString(2));
            assertEquals(rows.getString(1), rows.getString(3));
            assertFalse(rows.next());
        }
    }

    @Test
    void shouldRollbackAllTablesWhenForeignKeyPseudonymsDoNotMatch() throws SQLException {
        assertThrows(JdbcMaskingException.class, () -> exportPlan(plan("rollback", false)));
        assertEquals(0, targetCount("parent_rollback"));
        assertEquals(0, targetCount("child_rollback"));
    }

    @Test
    void shouldRejectChildBeforeParentAndForeignKeysOutsidePlan() throws SQLException {
        var wrong = plan("wrong_order", true);
        assertThrows(JdbcMaskingException.class, () -> exportPlan(List.of(wrong.getLast(), wrong.getFirst())));
        assertEquals(0, targetCount("child_wrong_order"));
        assertEquals(0, targetCount("parent_wrong_order"));
        var external = plan("external", true);
        assertThrows(JdbcMaskingException.class, () -> exportPlan(List.of(external.getFirst())));
        assertEquals(0, targetCount("parent_external"));
        assertThrows(JdbcMaskingException.class, () -> exportPlan(List.of(external.getLast())));
        assertEquals(0, targetCount("child_external"));
    }

    @Test
    void shouldRollbackAllTablesWhenGlobalRowBudgetIsExceeded() throws SQLException {
        assertThrows(JdbcMaskingException.class, () -> JdbcExporter.exportAll(reader(), targetWriter(),
                plan("bound", true), new JdbcMaskingLimits(1, 2, 100, 1, 5)));
        assertEquals(0, targetCount("parent_bound"));
        assertEquals(0, targetCount("child_bound"));
    }

    @Test
    void shouldRejectDeferredForeignKeys() throws SQLException {
        assertThrows(JdbcMaskingException.class, () -> exportPlan(plan("deferred", true)));
        assertEquals(0, targetCount("parent_deferred"));
        assertEquals(0, targetCount("child_deferred"));
    }

    @Test
    void shouldRejectEmptyDuplicateAndOversizedPlans() {
        var valid = plan("bound", true);
        assertThrows(JdbcMaskingException.class, () -> exportPlan(List.of()));
        assertThrows(JdbcMaskingException.class, () -> exportPlan(List.of(valid.getFirst(), valid.getFirst())));
        assertThrows(JdbcMaskingException.class, () -> exportPlan(java.util.Collections.nCopies(33, valid.getFirst())));
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
