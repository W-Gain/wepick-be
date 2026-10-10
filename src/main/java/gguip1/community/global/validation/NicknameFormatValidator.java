package gguip1.community.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class NicknameFormatValidator implements ConstraintValidator<NicknameValidation, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return NicknamePolicy.isValid(value);
    }
}
