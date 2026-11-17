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

// The V16 Java migration on a database that already has items: migrate to V15, add items without
// stems, then run V16. Plain Flyway on its own in-memory H2, no Spring context.
class SearchStemsMigrationTest {

	private static final String URL = "jdbc:h2:mem:v16-backfill;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

	@Test
	void existingItemsGetTheirStemsAndTheColumnBecomesRequired() throws SQLException {
		flywayUpTo("15").migrate();
		try (Connection db = DriverManager.getConnection(URL, "sa", ""); Statement sql = db.createStatement()) {
			sql.execute("insert into app_user (id, email, display_name, password_hash, created_at)"
					+ " values (1, 'old@example.com', 'Old', '{noop}x', current_timestamp)");
			sql.execute("insert into item (owner_id, name, description, category, available, created_at, search_text)"
					+ " values (1, 'Vrtačka', 'Příklepová', 'TOOL', true, current_timestamp, 'vrtacka\npriklepova')");
			sql.execute("insert into item (owner_id, name, description, category, available, created_at, search_text)"
					+ " values (1, 'Žebříky', null, 'OUTDOOR', true, current_timestamp, 'zebriky')");

			flywayUpTo("16").migrate();

			List<String> stems = new ArrayList<>();
			try (ResultSet rows = sql.executeQuery("select search_stems from item order by id")) {
				while (rows.next()) {
					stems.add(rows.getString(1));
				}
			}
			assertThat(stems).containsExactly(" vrtack\n priklp", " zebrik");
			assertThatThrownBy(() -> sql.execute("insert into item (owner_id, name, category, available, created_at,"
					+ " search_text) values (1, 'No stems', 'OTHER', true, current_timestamp, 'no stems')"))
				.isInstanceOf(SQLException.class);
		}
	}

	private static Flyway flywayUpTo(String version) {
		return Flyway.configure().dataSource(URL, "sa", "").target(version).load();
	}
}
