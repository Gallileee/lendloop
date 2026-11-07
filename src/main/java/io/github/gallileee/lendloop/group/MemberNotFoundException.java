package io.github.gallileee.lendloop.group;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// The owner named someone who isn't (or is no longer) in the group, e.g. a stale page in another tab.
@ResponseStatus(HttpStatus.NOT_FOUND)
public class MemberNotFoundException extends RuntimeException {

	public MemberNotFoundException(Long userId) {
		super("Not a member of this group: " + userId);
	}
}
