package io.github.hador.datamask4j.core;

import java.util.Objects;

/** An explicit decision to retain, transform or remove a field. */
public final class FieldRule {
    private final MaskingStrategy strategy;
    private final boolean drop;

    private FieldRule(MaskingStrategy strategy, boolean drop) {
        this.strategy = strategy;
        this.drop = drop;
    }

    public static FieldRule keep() {
        return transform(value -> value);
    }

    public static FieldRule redact() {
        return constant("[REDACTED]");
    }

    public static FieldRule constant(String replacement) {
        Objects.requireNonNull(replacement, "replacement");
        return transform(value -> replacement);
    }

    public static FieldRule drop() {
        return new FieldRule(null, true);
    }

    public static FieldRule transform(MaskingStrategy strategy) {
        return new FieldRule(Objects.requireNonNull(strategy, "strategy"), false);
    }

    boolean isDrop() {
        return drop;
    }

    String apply(String value) {
        return value == null ? null : strategy.transform(value);
    }
}
