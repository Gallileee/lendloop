package io.github.gallileee.lendloop.item;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// Also thrown for someone else's item: answering 404 rather than 403 doesn't reveal that it exists.
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ItemNotFoundException extends RuntimeException {

	public ItemNotFoundException(Long id) {
		super("Item not found: " + id);
	}
}
