package io.github.gallileee.lendloop.group;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// Also thrown for a group the user isn't in: answering 404 rather than 403 doesn't reveal that it exists.
@ResponseStatus(HttpStatus.NOT_FOUND)
public class GroupNotFoundException extends RuntimeException {

	public GroupNotFoundException(Long id) {
		super("Group not found: " + id);
	}
}
