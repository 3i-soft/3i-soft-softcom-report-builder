package org.softcom.reportbuilder.spi;

import javax.faces.context.FacesContext;
import javax.servlet.ServletContext;

/**
 * Settings of the report builder. Each one is read from the web application's
 * context parameters (web.xml), else from a system property with the same
 * name, else its default - nothing has to be configured.
 *
 * <pre>
 * &lt;context-param&gt;
 *     &lt;param-name&gt;softcom.reportbuilder.AUTO_EXCLUDE&lt;/param-name&gt;
 *     &lt;param-value&gt;AuditEntity, ExceptionLog&lt;/param-value&gt;
 * &lt;/context-param&gt;
 * </pre>
 */
public final class ReportBuilderConfig {

	public static final String PREFIX = "softcom.reportbuilder.";

	/** true (default): every JPA entity of the application is offered as a data source. */
	public static final String AUTO_DATA_SOURCES = PREFIX + "AUTO_DATA_SOURCES";
	/** true (default): new reports start with "deleted / cancelled is not true" on tables having such a flag. */
	public static final String AUTO_DEFAULT_CONDITIONS = PREFIX + "AUTO_DEFAULT_CONDITIONS";
	/** Comma separated entity names never offered (nor reachable through relations). */
	public static final String AUTO_EXCLUDE = PREFIX + "AUTO_EXCLUDE";
	/** Permission needed to see the automatic data sources; empty (default) = every report user. */
	public static final String AUTO_REQUIRED_ROLE = PREFIX + "AUTO_REQUIRED_ROLE";
	/** How many relations deep fields are offered (customer.name = 1, customer.city.name = 2). Default 2. */
	public static final String AUTO_DEPTH = PREFIX + "AUTO_DEPTH";
	/** Tables with at least this many rows need a condition on an indexed field. Default 200000. */
	public static final String LARGE_TABLE_ROWS = PREFIX + "LARGE_TABLE_ROWS";
	/** Longest date range a fast condition may span on a large table. Default 366 days. */
	public static final String MAX_DATE_RANGE_DAYS = PREFIX + "MAX_DATE_RANGE_DAYS";
	/** Query timeout of the automatic data sources. Default 30 seconds. */
	public static final String QUERY_TIMEOUT_SECONDS = PREFIX + "QUERY_TIMEOUT_SECONDS";
	/** Report queries that may run at the same time in this application. Default 6. */
	public static final String MAX_CONCURRENT_QUERIES = PREFIX + "MAX_CONCURRENT_QUERIES";

	/**
	 * SQL of a column grouped by month, {@code ?} being the date. Default (PostgreSQL):
	 * {@code CAST(DATE_TRUNC('month', ?) AS DATE)}.
	 */
	public static final String MONTH_SQL = PREFIX + "MONTH_SQL";
	/** Same for years. Default: {@code CAST(DATE_TRUNC('year', ?) AS DATE)}. */
	public static final String YEAR_SQL = PREFIX + "YEAR_SQL";

	/** Remembered at start-up (see {@code ReportBuilderContextListener}), for threads without a JSF request. */
	private static volatile ServletContext servletContext;

	private ReportBuilderConfig() {
	}

	/** Called when the application starts and stops (null). */
	public static void setServletContext(ServletContext context) {
		servletContext = context;
	}

	public static ServletContext getServletContext() {
		return servletContext;
	}

	public static String get(String name, String defaultValue) {
		String v = null;
		try {
			FacesContext fc = FacesContext.getCurrentInstance();
			if (fc != null)
				v = fc.getExternalContext().getInitParameter(name);
		} catch (RuntimeException | LinkageError e) {
			// no JSF in this thread: the servlet context or system properties below
		}
		ServletContext sc = servletContext;
		if ((v == null || v.trim().isEmpty()) && sc != null)
			v = sc.getInitParameter(name);
		if (v == null || v.trim().isEmpty())
			v = System.getProperty(name);
		return v == null || v.trim().isEmpty() ? defaultValue : v.trim();
	}

	public static int getInt(String name, int defaultValue, int min, int max) {
		String v = get(name, null);
		if (v == null)
			return defaultValue;
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(v)));
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	public static boolean getBoolean(String name, boolean defaultValue) {
		String v = get(name, null);
		return v == null ? defaultValue : Boolean.parseBoolean(v);
	}
}
