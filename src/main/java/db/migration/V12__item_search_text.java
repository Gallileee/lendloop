package db.migration;

import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.SearchText;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

// Adds item.search_text (the folded name and description that the search box matches) and fills
// it for the items that already exist.
//
// A Java migration because the folding (SearchText) can't be written in SQL that runs on both
// H2 and PostgreSQL: H2 has no unaccent(), and PostgreSQL's needs an extension. Flyway finds it
// by its package (db.migration), the same location as the .sql files.
public class V12__item_search_text extends BaseJavaMigration {

	@Override
	public void migrate(Context context) throws SQLException {
		Connection connection = context.getConnection();
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("alter table item add column search_text varchar(" + Item.SEARCH_TEXT_LENGTH + ")");
		}
		try (Statement select = connection.createStatement();
				ResultSet rows = select.executeQuery("select id, name, description from item");
				PreparedStatement update = connection.prepareStatement("update item set search_text = ? where id = ?")) {
			int pending = 0;
			while (rows.next()) {
				update.setString(1, SearchText.forItem(rows.getString("name"), rows.getString("description")));
				update.setLong(2, rows.getLong("id"));
				update.addBatch();
				if (++pending == 500) {
					update.executeBatch();
					pending = 0;
				}
			}
			if (pending > 0) {
				update.executeBatch();
			}
		}
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("alter table item alter column search_text set not null");
		}
	}
}
