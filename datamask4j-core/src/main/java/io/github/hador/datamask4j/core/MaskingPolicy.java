package io.github.hador.datamask4j.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable policy with an exact set of classified fields. */
public final class MaskingPolicy {
    private final Map<String, FieldRule> rules;

    private MaskingPolicy(Map<String, FieldRule> rules) {
        this.rules = Map.copyOf(rules);
    }

    public static Builder builder() {
        return new Builder();
    }

    Map<String, FieldRule> rules() {
        return rules;
    }

    public static final class Builder {
        private final Map<String, FieldRule> rules = new LinkedHashMap<>();

        private Builder() {}

        public Builder field(String name, FieldRule rule) {
            if (Objects.requireNonNull(name, "field name").isBlank()) {
                throw new IllegalArgumentException("Field name must not be blank");
            }
            Objects.requireNonNull(rule, "rule");
            if (rules.putIfAbsent(name, rule) != null) {
                throw new IllegalArgumentException("Duplicate field rule");
            }
            return this;
        }

        public MaskingPolicy build() {
            if (rules.isEmpty()) {
                throw new IllegalArgumentException("At least one field rule is required");
            }
            return new MaskingPolicy(rules);
        }
    }
}
