package io.github.hador.datamask4j.annotations;

import io.github.hador.datamask4j.faker.EuropeanLocale;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Every component must declare a rule. Constants must not contain secrets. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Mask {
    MaskType strategy();
    String value() default "";
    String scope() default "";
    EuropeanLocale locale() default EuropeanLocale.FR_FR;
}
