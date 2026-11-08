package io.github.gallileee.lendloop.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

// The V12 Java migration on a database that already has items: migrate to V11, add items the old
// way (no search_text), then run V12. Plain Flyway on its own in-memory H2, no Spring context.
class SearchTextMigrationTest {

	private static final String URL = "jdbc:h2:mem:v12-backfill;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

	@Test
	void existingItemsGetTheirSearchTextAndTheColumnBecomesRequired() throws SQLException {
		flywayUpTo("11").migrate();
		try (Connection db = DriverManager.getConnection(URL, "sa", ""); Statement sql = db.createStatement()) {
			sql.execute("insert into app_user (id, email, display_name, password_hash, created_at)"
					+ " values (1, 'old@example.com', 'Old', '{noop}x', current_timestamp)");
			sql.execute("insert into item (owner_id, name, description, category, available, created_at)"
					+ " values (1, 'Vrtačka', 'Příklepová', 'TOOL', true, current_timestamp)");
			sql.execute("insert into item (owner_id, name, description, category, available, created_at)"
					+ " values (1, 'Stan', null, 'OUTDOOR', true, current_timestamp)");

			flywayUpTo("12").migrate();

			List<String> searchTexts = new ArrayList<>();
			try (ResultSet rows = sql.executeQuery("select search_text from item order by id")) {
				while (rows.next()) {
					searchTexts.add(rows.getString(1));
				}
			}
			assertThat(searchTexts).containsExactly("vrtacka\npriklepova", "stan");
			assertThatThrownBy(() -> sql.execute("insert into item (owner_id, name, category, available, created_at)"
					+ " values (1, 'No search text', 'OTHER', true, current_timestamp)"))
				.isInstanceOf(SQLException.class);
		}
	}

	private static Flyway flywayUpTo(String version) {
		return Flyway.configure().dataSource(URL, "sa", "").target(version).load();
	}
}
