package io.github.hador.datamask4j.annotations;

/** Generic masking failure that deliberately does not expose underlying exceptions. */
public final class RecordMaskingException extends RuntimeException {
    RecordMaskingException() {
        super("Record masking failed");
    }
}
