package io.github.gallileee.lendloop.item;

// Stored by name (varchar), so new categories can be added without touching existing rows.
// The names people see are in messages*.properties under category.<NAME>.
public enum Category {
	BOOK,
	TOOL,
	GAME,
	OUTDOOR,
	ELECTRONICS,
	KITCHEN,
	OTHER;

	// For templates: th:text="#{${c.messageCode}}".
	public String getMessageCode() {
		return "category." + name();
	}
}
