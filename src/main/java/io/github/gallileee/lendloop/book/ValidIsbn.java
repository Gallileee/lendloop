package io.github.gallileee.lendloop.book;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// A blank value or a valid ISBN-10 / ISBN-13 (see Isbn.parse).
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidIsbn.Validator.class)
public @interface ValidIsbn {

	String message() default "That isn't a valid ISBN. Check the digits on the back of the book.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<ValidIsbn, String> {

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			return value == null || value.isBlank() || Isbn.parse(value).isPresent();
		}
	}
}
