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

// Adds item.search_stems (the Czech stems of the name and description, so "vrtačky" finds
// "Vrtačka") and fills it for the items that already exist. A Java migration for the same reason
// as V12: the stemming (CzechStemmer) lives in Java and is unit-tested there.
public class V16__item_search_stems extends BaseJavaMigration {

	private static final int BATCH_SIZE = 500;

	@Override
	public void migrate(Context context) throws SQLException {
		Connection connection = context.getConnection();
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("alter table item add column search_stems varchar(" + Item.SEARCH_STEMS_LENGTH + ")");
		}
		try (Statement select = connection.createStatement();
				ResultSet rows = select.executeQuery("select id, name, description from item");
				PreparedStatement update = connection.prepareStatement("update item set search_stems = ? where id = ?")) {
			int pending = 0;
			while (rows.next()) {
				update.setString(1, SearchText.stemsForItem(rows.getString("name"), rows.getString("description")));
				update.setLong(2, rows.getLong("id"));
				update.addBatch();
				if (++pending == BATCH_SIZE) {
					update.executeBatch();
					pending = 0;
				}
			}
			if (pending > 0) {
				update.executeBatch();
			}
		}
		try (Statement ddl = connection.createStatement()) {
			ddl.execute("alter table item alter column search_stems set not null");
		}
	}
}
