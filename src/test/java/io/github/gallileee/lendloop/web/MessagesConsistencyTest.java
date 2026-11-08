package io.github.gallileee.lendloop.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Properties;
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
