package io.github.gallileee.lendloop.book;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BookInfoTest {

	@Test
	void describesAuthorsPublisherAndDate() {
		BookInfo book = new BookInfo("Fantastic Mr. Fox", null, List.of("Roald Dahl", "Tony Ross"), "Puffin",
				"October 1, 1988");

		assertThat(book.itemName()).isEqualTo("Fantastic Mr. Fox");
		assertThat(book.itemDescription()).isEqualTo("By Roald Dahl, Tony Ross · Puffin, October 1, 1988");
	}

	@Test
	void leavesOutWhatIsUnknown() {
		assertThat(new BookInfo("A", null, List.of(), null, null).itemDescription()).isEmpty();
		assertThat(new BookInfo("A", null, null, "Puffin", " ").itemDescription()).isEqualTo("Puffin");
		assertThat(new BookInfo("A", null, List.of(), null, "1988").itemDescription()).isEqualTo("1988");
		assertThat(new BookInfo("A", null, List.of("Dahl"), null, null).itemDescription()).isEqualTo("By Dahl");
	}

	@Test
	void addsTheSubtitleToTheName() {
		assertThat(new BookInfo(" Dune ", " Deluxe Edition ", null, null, null).itemName())
			.isEqualTo("Dune: Deluxe Edition");
	}

	@Test
	void cutsLongTextToTheFormLimits() {
		BookInfo book = new BookInfo("T".repeat(150), null, List.of("A".repeat(1200)), null, null);

		assertThat(book.itemName()).hasSize(BookInfo.MAX_NAME).endsWith("…");
		assertThat(book.itemDescription()).hasSize(BookInfo.MAX_DESCRIPTION).endsWith("…");
	}
}
