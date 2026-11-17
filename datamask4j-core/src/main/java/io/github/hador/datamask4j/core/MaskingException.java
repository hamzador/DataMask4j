package io.github.hador.datamask4j.core;

/** Failure without raw input values or strategy exception causes. */
public final class MaskingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MaskingException(String message) {
        super(message);
    }
}
