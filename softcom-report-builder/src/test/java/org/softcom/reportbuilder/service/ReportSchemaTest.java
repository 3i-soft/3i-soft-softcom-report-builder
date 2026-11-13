package org.softcom.reportbuilder.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

/**
 * The library creates and updates its own tables. The PostgreSQL tests run with
 * {@code -Drb.test.pg.url=...}, each on a database of its own created next to
 * the test database (the user must be allowed to create databases).
 */
public class ReportSchemaTest {

	private static String pgUrl() {
		String url = System.getProperty("rb.test.pg.url");
		Assume.assumeTrue("PostgreSQL only", url != null && !url.isEmpty());
		return url;
	}

	private static Connection connect(String url) throws SQLException {
		return DriverManager.getConnection(url, System.getProperty("rb.test.pg.user", "postgres"),
				System.getProperty("rb.test.pg.password", ""));
	}

	/** A new empty database next to the test one; returns its URL. */
	private static String freshDatabase(String name) throws SQLException {
		String url = pgUrl();
		String base = url.substring(0, url.lastIndexOf('/') + 1);
		try (Connection c = connect(url); Statement st = c.createStatement()) {
			st.execute("DROP DATABASE IF EXISTS " + name);
			st.execute("CREATE DATABASE " + name);
		}
		return base + name;
	}

	private static void dropDatabase(String name) throws SQLException {
		try (Connection c = connect(pgUrl()); Statement st = c.createStatement()) {
			st.execute("DROP DATABASE IF EXISTS " + name);
		}
	}

	private static List<String> strings(Connection c, String sql) throws SQLException {
		List<String> out = new ArrayList<>();
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next())
				out.add(rs.getString(1));
		}
		return out;
	}

	private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
		return !strings(c, "SELECT column_name FROM information_schema.columns WHERE table_name = '" + table
				+ "' AND column_name = '" + column + "'").isEmpty();
	}

	@Test
	public void everyScriptIsInTheJar() {
		for (String v : ReportSchema.SCRIPTS)
			assertTrue(v, ReportSchema.script(v).contains("DO $SCRIPT$"));
	}

	@Test
	public void otherDatabasesAreLeftAlone() throws SQLException {
		try (Connection c = DriverManager.getConnection("jdbc:h2:mem:rbschema", "sa", "")) {
			assertTrue(ReportSchema.update(c).isEmpty());
			assertTrue("nothing created", strings(c,
					"SELECT table_name FROM information_schema.tables WHERE lower(table_name) LIKE 'rb_%'").isEmpty());
		}
	}

	@Test
	public void anEmptyDatabaseGetsEveryTableOnceAndForAll() throws SQLException {
		String url = freshDatabase("rbschema_fresh");
		try (Connection c = connect(url)) {
			assertEquals(ReportSchema.SCRIPTS, ReportSchema.update(c));
			for (String t : new String[] { "rb_report_definition", "rb_report_run_log", "rb_label" })
				assertFalse(t, strings(c, "SELECT to_regclass('" + t + "')").contains(null));
			assertTrue(hasColumn(c, "rb_report_definition", "folder"));
			assertTrue(hasColumn(c, "rb_label", "hidden"));
			assertEquals(ReportSchema.SCRIPTS, strings(c, "SELECT version FROM rb_schema_version ORDER BY version"));
			assertTrue("the next start-up runs nothing", ReportSchema.update(c).isEmpty());
			assertTrue("auto-commit restored", c.getAutoCommit());
		} finally {
			dropDatabase("rbschema_fresh");
		}
	}

	/**
	 * A database whose tables were made by the application's own migrations
	 * (generalWarehouse's former V5_7_7 / V5_7_8, the same 1.0.0 / 1.1.0 scripts),
	 * with saved data: the library runs every script again, harmlessly, and adds
	 * what is new.
	 */
	@Test
	public void tablesMadeByTheApplicationAreTakenOver() throws SQLException {
		String url = freshDatabase("rbschema_host");
		try (Connection c = connect(url)) {
			try (Statement st = c.createStatement()) {
				st.execute(ReportSchema.script("1.0.0"));
				st.execute(ReportSchema.script("1.1.0"));
				st.execute("INSERT INTO rb_label (label_key, label_ar) VALUES ('Invoice', 'فاتورة')");
			}
			assertFalse(hasColumn(c, "rb_label", "hidden"));
			assertEquals(ReportSchema.SCRIPTS, ReportSchema.update(c));
			assertTrue(hasColumn(c, "rb_label", "hidden"));
			assertEquals("saved data kept", "فاتورة", strings(c, "SELECT label_ar FROM rb_label").get(0));
			assertEquals("false", strings(c, "SELECT hidden::text FROM rb_label").get(0));
		} finally {
			dropDatabase("rbschema_host");
		}
	}

	@Test
	public void aFailingScriptChangesNothing() throws SQLException {
		String url = freshDatabase("rbschema_fail");
		try (Connection c = connect(url)) {
			try (Statement st = c.createStatement()) {
				// a table in the way of 1.0.0's sequence name makes its script fail half way
				st.execute("CREATE TABLE rb_report_run_log_seq (x int)");
			}
			try {
				ReportSchema.update(c);
			} catch (SQLException expected) {
				// the whole update is one transaction
			}
			assertTrue(strings(c, "SELECT to_regclass('rb_report_definition')").contains(null));
			assertTrue(strings(c, "SELECT to_regclass('rb_schema_version')").contains(null));
			assertTrue(c.getAutoCommit());
		} finally {
			dropDatabase("rbschema_fail");
		}
	}
}
