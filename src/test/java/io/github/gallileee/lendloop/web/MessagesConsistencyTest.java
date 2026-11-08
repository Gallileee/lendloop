package io.github.gallileee.lendloop.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.loan.LoanStatus;
import io.github.gallileee.lendloop.user.EmailList;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// The English and Czech texts must have the same keys, so no Czech page silently shows an
// English text (or a "??key_cs??" marker), and every text with arguments must be a valid
// MessageFormat with the same number of arguments in both languages.
class MessagesConsistencyTest {

	private static Properties load(String name) throws IOException {
		Properties p = new Properties();
		try (InputStream in = MessagesConsistencyTest.class.getResourceAsStream("/" + name)) {
			assertThat(in).as(name).isNotNull();
			p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		return p;
	}

	@Test
	void bothLanguagesHaveTheSameKeys() throws IOException {
		Properties en = load("messages.properties");
		Properties cs = load("messages_cs.properties");
		assertThat(cs.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(en.stringPropertyNames());
	}

	// Enum values are shown through keys built from their names, which no compiler checks: a new
	// category without texts would show "??category.NEW_cs??".
	@Test
	void everyCategoryLoanStatusAndEmailListHasATextInBothLanguages() throws IOException {
		Properties en = load("messages.properties");
		Properties cs = load("messages_cs.properties");
		List<String> keys = new ArrayList<>();
		for (Category c : Category.values()) {
			keys.add(c.getMessageCode());
		}
		for (LoanStatus s : LoanStatus.values()) {
			keys.add("loan.status." + s.name());
		}
		for (EmailList list : EmailList.values()) {
			keys.add(list.getMessageCode());
		}
		assertThat(en.stringPropertyNames()).containsAll(keys);
		assertThat(cs.stringPropertyNames()).containsAll(keys);
	}

	// Every page says which language it's in (screen readers and hyphenation depend on it).
	@Test
	void everyPageTemplateDeclaresThePageLanguage() throws IOException {
		Path templates = Path.of("src/main/resources/templates");
		try (Stream<Path> files = Files.walk(templates)) {
			List<Path> pages = files.filter(p -> p.toString().endsWith(".html"))
				.filter(p -> !p.getFileName().toString().equals("fragments.html"))
				.toList();
			assertThat(pages).isNotEmpty();
			for (Path page : pages) {
				assertThat(Files.readString(page)).as(page.toString()).contains("th:lang=\"${#locale.language}\"");
			}
		}
	}

	@Test
	void textsWithArgumentsUseTheSameArgumentsInBothLanguages() throws IOException {
		Properties en = load("messages.properties");
		Properties cs = load("messages_cs.properties");
		for (String key : en.stringPropertyNames()) {
			String english = en.getProperty(key);
			if (!english.contains("{0}")) {
				continue;
			}
			int args = new MessageFormat(english).getFormatsByArgumentIndex().length;
			assertThat(new MessageFormat(cs.getProperty(key)).getFormatsByArgumentIndex()).as(key).hasSize(args);
			// An apostrophe written once would swallow the {0} after it.
			assertThat(new MessageFormat(english).format(new Object[] {"X"})).as(key).contains("X");
			assertThat(new MessageFormat(cs.getProperty(key)).format(new Object[] {"X"})).as(key).contains("X");
		}
	}
}
