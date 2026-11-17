package io.github.hador.datamask4j.batch;

/** A failed item must not be implicitly retained, skipped or included in an error message. */
public final class BatchMaskingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BatchMaskingException() {
        super("Batch item masking failed");
    }
}
