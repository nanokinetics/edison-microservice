package de.otto.edison.validation.validators;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.lang.annotation.Annotation;
import java.util.regex.Pattern;

public class SafeIdValidator implements ConstraintValidator<Annotation, String> {

    private static final Pattern IdPattern = Pattern.compile("[a-zA-Z0-9\\-_]*");

    @Override
    public void initialize(Annotation annotation) {
        // do nothing
    }

    @Override
    public boolean isValid(String id, ConstraintValidatorContext context) {
        if (id == null) {
            return true;
        }
        return IdPattern.matcher(id).matches();
    }
}
