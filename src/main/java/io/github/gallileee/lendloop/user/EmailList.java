package io.github.gallileee.lendloop.user;

// The kinds of email a user can turn off, one per switch on the Settings page.
public enum EmailList {
	// Due tomorrow / overdue reminders about things I borrowed (AppUser.remindBorrowed).
	BORROWED("reminders about things you borrowed"),
	// Overdue notices about things I lent out, and reminders for loans to people outside the app
	// (AppUser.notifyLentOverdue).
	LENT("notices about things you lent out");

	private final String description;

	EmailList(String description) {
		this.description = description;
	}

	public String getDescription() {
		return description;
	}
}
