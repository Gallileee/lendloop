package io.github.gallileee.lendloop.item;

// Stored by name (varchar), so new categories can be added without touching existing rows.
public enum Category {
	BOOK("Book"),
	TOOL("Tool"),
	GAME("Board game"),
	OUTDOOR("Outdoor & sport"),
	ELECTRONICS("Electronics"),
	KITCHEN("Kitchen"),
	OTHER("Other");

	private final String label;

	Category(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
