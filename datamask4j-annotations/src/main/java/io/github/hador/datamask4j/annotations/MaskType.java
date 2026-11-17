package io.github.hador.datamask4j.annotations;

/** Explicit record-component transformations; record shape cannot change. */
public enum MaskType {
    KEEP, REDACT, CONSTANT, NULLIFY, HMAC, FAKE_FIRST_NAME, FAKE_LAST_NAME, FAKE_NAME, FAKE_EMAIL
}
