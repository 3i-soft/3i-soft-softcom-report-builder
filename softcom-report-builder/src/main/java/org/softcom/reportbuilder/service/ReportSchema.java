package org.softcom.reportbuilder.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The library's own tables (rb_*), created and updated by the library itself:
 * the host application has nothing to copy into its migrations. Each script
 * of {@link #SCRIPTS} runs once, in order, and is recorded in
 * {@value #VERSION_TABLE}; the scripts are idempotent, so a database whose
 * tables were made by the application's own migrations (before this class)
 * is simply brought up to date. PostgreSQL only.
 */
public final class ReportSchema {

	/** The scripts, in order; add the next version at the end (and never change a released one). */
	public static final List<String> SCRIPTS = Collections.unmodifiableList(Arrays.asList("1.0.0", "1.1.0", "1.2.0"));

	public static final String VERSION_TABLE = "rb_schema_version";

	static final String SCRIPT_FOLDER = "META-INF/softcom-report-builder/sql/";

	/** Serializes concurrent start-ups (several servers on one database): a transaction-level advisory lock. */
	private static final long LOCK_KEY = 0x7262736368656dL; // "rbschem"

	private ReportSchema() {
	}

	/**
	 * Runs the scripts not run yet, all in one transaction.
	 *
	 * @return the versions run now (empty when up to date, or not PostgreSQL)
	 */
	public static List<String> update(Connection c) throws SQLException {
		if (!"PostgreSQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName()))
			return Collections.emptyList();
		boolean autoCommit = c.getAutoCommit();
		c.setAutoCommit(false);
		List<String> run = new ArrayList<>();
		try (Statement st = c.createStatement()) {
			st.execute("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")");
			st.execute("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " (version character varying(20) NOT NULL,"
					+ " applied_date timestamp without time zone NOT NULL, CONSTRAINT " + VERSION_TABLE
					+ "_pkey PRIMARY KEY (version))");
			Set<String> applied = new HashSet<>();
			try (ResultSet rs = st.executeQuery("SELECT version FROM " + VERSION_TABLE)) {
				while (rs.next())
					applied.add(rs.getString(1));
			}
			for (String v : SCRIPTS) {
				if (applied.contains(v))
					continue;
				st.execute(script(v));
				try (PreparedStatement ps = c.prepareStatement(
						"INSERT INTO " + VERSION_TABLE + " (version, applied_date) VALUES (?, CURRENT_TIMESTAMP)")) {
					ps.setString(1, v);
					ps.executeUpdate();
				}
				run.add(v);
			}
			c.commit();
			return run;
		} catch (SQLException | RuntimeException e) {
			c.rollback();
			throw e;
		} finally {
			c.setAutoCommit(autoCommit);
		}
	}

	/** The text of a script, from the library's jar. */
	static String script(String version) {
		String path = SCRIPT_FOLDER + "postgresql-" + version + ".sql";
		ClassLoader cl = ReportSchema.class.getClassLoader();
		try (InputStream in = cl.getResourceAsStream(path)) {
			if (in == null)
				throw new IllegalStateException("Missing report builder script " + path);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0;)
				out.write(buf, 0, n);
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot read report builder script " + path, e);
		}
	}
}
