package io.github.gallileee.lendloop.user;

// The kinds of email a user can turn off, one per switch on the Settings page.
public enum EmailList {
	// Due tomorrow / overdue reminders about things I borrowed (AppUser.remindBorrowed).
	BORROWED,
	// Overdue notices about things I lent out, and reminders for loans to people outside the app
	// (AppUser.notifyLentOverdue).
	LENT;

	// Its description ("reminders about things you borrowed") on the pages and in the emails'
	// footer, in the page's or the recipient's language.
	public String getMessageCode() {
		return "emailList." + name();
	}
}
