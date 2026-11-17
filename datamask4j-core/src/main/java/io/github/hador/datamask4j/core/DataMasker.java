package io.github.hador.datamask4j.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Strict in-process masker. The caller must not mutate input concurrently. */
public final class DataMasker {
    private final MaskingPolicy policy;

    public DataMasker(MaskingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Validates the complete input schema and returns retained fields in caller order. */
    public java.util.List<String> outputFields(java.util.List<String> fields) {
        var snapshot = java.util.List.copyOf(fields);
        var unique = new java.util.HashSet<>(snapshot);
        if (unique.size() != snapshot.size() || !unique.equals(policy.rules().keySet())) {
            throw new MaskingException("Input fields do not match masking policy");
        }
        return snapshot.stream().filter(field -> !policy.rules().get(field).isDrop()).toList();
    }

    public Map<String, String> mask(Map<String, String> input) {
        var snapshot = new LinkedHashMap<>(Objects.requireNonNull(input, "input"));
        if (!snapshot.keySet().equals(policy.rules().keySet())) {
            throw new MaskingException("Input fields do not match masking policy");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : snapshot.entrySet()) {
            FieldRule rule = policy.rules().get(entry.getKey());
            if (rule.isDrop()) {
                continue;
            }
            String value = entry.getValue();
            String masked;
            try {
                masked = rule.apply(value);
            } catch (RuntimeException ignored) {
                // Custom exceptions can contain raw personal data.
                throw new MaskingException("Field transformation failed");
            }
            if (value != null && masked == null) {
                throw new MaskingException("Field transformation returned null");
            }
            result.put(entry.getKey(), masked);
        }
        return Collections.unmodifiableMap(result);
    }
}
