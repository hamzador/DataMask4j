package io.github.hador.datamask4j.jdbc;

/** Bounds are per preview; connection acquisition and server resources remain external. */
public record JdbcMaskingLimits(long maxRows, int maxColumns, int maxValueChars,
                                int fetchSize, int queryTimeoutSeconds) {
    public JdbcMaskingLimits {
        if (maxRows <= 0 || maxColumns <= 0 || maxValueChars <= 0
                || fetchSize <= 0 || queryTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("JDBC limits must be positive");
        }
    }
    public static JdbcMaskingLimits defaults() {
        return new JdbcMaskingLimits(100_000, 128, 65_536, 100, 30);
    }
}
