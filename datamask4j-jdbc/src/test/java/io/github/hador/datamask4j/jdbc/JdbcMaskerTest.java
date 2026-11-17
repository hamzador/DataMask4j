package io.github.hador.datamask4j.jdbc;

import io.github.hador.datamask4j.core.DataMasker;
import io.github.hador.datamask4j.core.FieldRule;
import io.github.hador.datamask4j.core.MaskingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JdbcMaskerTest {
    @Mock private DataSource source;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData databaseMetaData;
    @Mock private Statement session;
    @Mock private Statement select;
    @Mock private PreparedStatement catalog;
    @Mock private ResultSet settings;
    @Mock private ResultSet tableInfo;
    @Mock private ResultSet rows;
    @Mock private ResultSetMetaData columns;
    private JdbcMasker jdbcMasker;
    private final JdbcTable table = new JdbcTable("demo", "public", "customer");

    @BeforeEach
    void setUp() {
        jdbcMasker = new JdbcMasker(new DataMasker(MaskingPolicy.builder()
                .field("email", FieldRule.redact()).field("notes", FieldRule.drop()).build()));
    }

    private void givenPostgres() throws SQLException {
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getAutoCommit()).thenReturn(true);
    }

    private void givenReadOnlySession() throws SQLException {
        givenPostgres();
        when(connection.createStatement()).thenReturn(session, select);
        when(session.executeQuery(anyString())).thenReturn(settings);
        when(settings.next()).thenReturn(true);
        when(settings.getString(1)).thenReturn("demo");
        when(settings.getString(2)).thenReturn("on");
        when(connection.prepareStatement(anyString())).thenReturn(catalog);
        when(catalog.executeQuery()).thenReturn(tableInfo);
        when(tableInfo.next()).thenReturn(true);
        when(tableInfo.getString(1)).thenReturn("r");
    }

    private void givenTextColumns() throws SQLException {
        givenReadOnlySession();
        when(select.executeQuery(anyString())).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(columns);
        when(columns.getColumnCount()).thenReturn(2);
        when(columns.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(columns.getColumnType(2)).thenReturn(Types.VARCHAR);
        when(columns.getColumnTypeName(1)).thenReturn("text");
        when(columns.getColumnTypeName(2)).thenReturn("text");
        when(columns.getColumnName(1)).thenReturn("email");
        when(columns.getColumnName(2)).thenReturn("notes");
    }

    @Test
    void shouldReturnOnlyCountsAndRollbackSource() throws SQLException {
        givenTextColumns();
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("private@example.test");
        when(rows.getString(2)).thenReturn("private");
        assertEquals(new JdbcMaskingReport(1, 2, 1), jdbcMasker.dryRun(source, table));
        verify(connection).setReadOnly(true);
        verify(session).execute("SET TRANSACTION READ ONLY");
        verify(select).executeQuery("SELECT * FROM ONLY \"public\".\"customer\"");
        verify(connection).rollback();
        verify(connection).close();
        verify(connection, never()).commit();
    }

    @Test
    void shouldRejectWritePrivilegesBeforeReadingRecords() throws SQLException {
        givenReadOnlySession();
        when(tableInfo.getBoolean(4)).thenReturn(true);
        assertThrows(JdbcMaskingException.class, () -> jdbcMasker.dryRun(source, table));
        verifyNoInteractions(select);
        verify(connection).rollback();
    }

    @Test
    void shouldRejectNonPostgresBeforeStartingTransaction() throws SQLException {
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getDatabaseProductName()).thenReturn("Other");
        assertThrows(JdbcMaskingException.class, () -> jdbcMasker.dryRun(source, table));
        verify(connection, never()).setAutoCommit(false);
        verify(connection).close();
    }

    @Test
    void shouldRejectUnknownFieldBeforeReadingRows() throws SQLException {
        givenTextColumns();
        when(columns.getColumnName(2)).thenReturn("unknown");
        assertThrows(JdbcMaskingException.class, () -> jdbcMasker.dryRun(source, table));
        verify(rows, never()).next();
        verify(connection).rollback();
    }

    @Test
    void shouldSanitiseConnectionErrors() throws SQLException {
        when(source.getConnection()).thenThrow(new SQLException("raw-secret"));
        var failure = assertThrows(JdbcMaskingException.class, () -> jdbcMasker.dryRun(source, table));
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("raw-secret"));
    }

    @Test
    void shouldRejectWrongDatabaseBeforeReadingCatalog() throws SQLException {
        givenPostgres();
        when(connection.createStatement()).thenReturn(session);
        when(session.executeQuery(anyString())).thenReturn(settings);
        when(settings.next()).thenReturn(true);
        when(settings.getString(1)).thenReturn("wrong");
        assertThrows(JdbcMaskingException.class, () -> jdbcMasker.dryRun(source, table));
        verify(connection, never()).prepareStatement(anyString());
        verify(connection).rollback();
    }
}
