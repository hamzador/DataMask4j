package io.github.hador.datamask4j.annotations;

import io.github.hador.datamask4j.core.MaskingStrategy;
import io.github.hador.datamask4j.crypto.HmacPseudonymizer;
import io.github.hador.datamask4j.faker.EuropeanFaker;
import io.github.hador.datamask4j.faker.EuropeanLocale;

import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Creates a new instance of the same public record type. All component rules are checked
 * before reading any values. Accessors and canonical constructors must be trusted and pure.
 */
public final class RecordMasker {
    private static final Set<Class<?>> KEEP_TYPES = Set.of(
            String.class, boolean.class, byte.class, short.class, int.class, long.class,
            float.class, double.class, char.class, Boolean.class, Byte.class, Short.class,
            Integer.class, Long.class, Float.class, Double.class, Character.class);
    private final byte[] key;

    public RecordMasker() {
        this.key = null;
    }

    public RecordMasker(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < 32) {
            throw new IllegalArgumentException("HMAC key must contain at least 32 bytes");
        }
        this.key = key.clone();
    }

    /** No source mutation, implicit KEEP or nested traversal. Null components are preserved. */
    public <T extends Record> T mask(T source) {
        Objects.requireNonNull(source, "source");
        try {
            Class<?> type = source.getClass();
            require(type.isRecord() && Modifier.isPublic(type.getModifiers()));
            RecordComponent[] components = type.getRecordComponents();
            require(components.length <= 128);
            var types = new Class<?>[components.length];
            var rules = new java.util.ArrayList<UnaryOperator<Object>>(components.length);
            for (int index = 0; index < components.length; index++) {
                RecordComponent component = components[index];
                types[index] = component.getType();
                rules.add(rule(component));
            }
            var constructor = type.getConstructor(types);
            var arguments = new Object[components.length];
            for (int index = 0; index < components.length; index++) {
                Object value = components[index].getAccessor().invoke(source);
                arguments[index] = value == null ? null : rules.get(index).apply(value);
            }
            return cast(constructor.newInstance(arguments));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Constructor/accessor failures may contain sensitive data. Never attach causes.
            throw new RecordMaskingException();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Record> T cast(Object value) {
        return (T) value;
    }

    private UnaryOperator<Object> rule(RecordComponent component) {
        Mask annotation = component.getAnnotation(Mask.class);
        require(annotation != null);
        MaskType strategy = annotation.strategy();
        boolean faker = switch (strategy) {
            case FAKE_FIRST_NAME, FAKE_LAST_NAME, FAKE_NAME, FAKE_EMAIL -> true;
            default -> false;
        };
        boolean keyed = faker || strategy == MaskType.HMAC;
        require(keyed ? key != null && !annotation.scope().isBlank() : annotation.scope().isEmpty());
        require(strategy == MaskType.CONSTANT || annotation.value().isEmpty());
        require(faker || annotation.locale() == EuropeanLocale.FR_FR);
        if (strategy == MaskType.KEEP) {
            require(KEEP_TYPES.contains(component.getType()));
            return UnaryOperator.identity();
        }
        require(component.getType() == String.class);
        return switch (strategy) {
            case REDACT -> value -> "[REDACTED]";
            case CONSTANT -> value -> annotation.value();
            case NULLIFY -> value -> null;
            case HMAC -> {
                require(!annotation.scope().startsWith("faker:v1:"));
                yield stringRule(new HmacPseudonymizer(key, annotation.scope()));
            }
            case FAKE_FIRST_NAME, FAKE_LAST_NAME, FAKE_NAME, FAKE_EMAIL -> {
                var generator = new EuropeanFaker(key, annotation.scope(), annotation.locale());
                MaskingStrategy selected = switch (strategy) {
                    case FAKE_FIRST_NAME -> generator.firstName();
                    case FAKE_LAST_NAME -> generator.lastName();
                    case FAKE_NAME -> generator.fullName();
                    case FAKE_EMAIL -> generator.email();
                    default -> throw new RecordMaskingException();
                };
                yield stringRule(selected);
            }
            case KEEP -> throw new RecordMaskingException();
        };
    }

    private static UnaryOperator<Object> stringRule(MaskingStrategy strategy) {
        return value -> strategy.transform((String) value);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new RecordMaskingException();
        }
    }
}
