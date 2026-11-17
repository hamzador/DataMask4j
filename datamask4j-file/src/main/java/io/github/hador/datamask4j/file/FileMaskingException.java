package io.github.hador.datamask4j.file;

/** Sanitised failure: input values, field names and underlying exception causes are excluded. */
public final class FileMaskingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    FileMaskingException(String message) {
        super(message);
    }
}
