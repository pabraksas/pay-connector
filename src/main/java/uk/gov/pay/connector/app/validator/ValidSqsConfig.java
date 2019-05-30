package uk.gov.pay.connector.app.validator;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({TYPE, ANNOTATION_TYPE})
@Retention(RUNTIME)
@Constraint(validatedBy = {SqsConfigValidator.class})
@Documented
public @interface ValidSqsConfig {
    String message() default "{uk.gov.pay.connector.app.validator." +
            "sqsConfig.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
