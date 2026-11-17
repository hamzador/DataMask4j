package io.github.hador.datamask4j.jdbc;

/** Counts only; values and database names are deliberately excluded. */
public record JdbcMaskingReport(long rowsProcessed, int sourceColumns, int outputColumns) {}
