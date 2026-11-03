package io.github.gallileee.lendloop.loan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// Also thrown for a loan the user isn't part of, so its existence isn't revealed.
@ResponseStatus(HttpStatus.NOT_FOUND)
public class LoanNotFoundException extends RuntimeException {

	public LoanNotFoundException(Long id) {
		super("Loan not found: " + id);
	}
}
