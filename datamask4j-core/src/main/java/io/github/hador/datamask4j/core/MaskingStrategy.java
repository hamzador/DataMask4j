package io.github.hador.datamask4j.core;

/** Trusted transformation of a non-null string. Must not return null or disclose inputs. */
@FunctionalInterface
public interface MaskingStrategy {
    String transform(String value);
}
