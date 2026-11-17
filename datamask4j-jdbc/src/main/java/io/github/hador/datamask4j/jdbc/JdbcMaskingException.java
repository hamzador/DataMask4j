package io.github.hador.datamask4j.jdbc;

/** Sanitised failure without underlying driver messages or raw records. */
public final class JdbcMaskingException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    JdbcMaskingException(String message) {
        super(message);
    }
}
