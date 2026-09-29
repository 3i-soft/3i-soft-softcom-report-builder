package org.softcom.reportbuilder.spi;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.persistence.criteria.JoinType;

/**
 * A business-level "table" users can report on: a root entity at the most
 * detailed grain, the many-to-one relations that may be joined from it, the
 * whitelisted fields, and the conditions always applied.
 *
 * <p>
 * Only declare joins that go towards the "one" side (many-to-one / one-to-one).
 * Joining a collection would multiply root rows and make SUM/COUNT wrong.
 * </p>
 *
 * <pre>
 * new ReportDataSource("gw.invoiceLines", InvoiceLine.class)
 * 		.labels("بنود الفواتير", "Invoice lines")
 * 		.join("invoice", JoinType.INNER)
 * 		.join("item", JoinType.INNER)
 * 		.add(ReportField.of("item.name", FieldType.STRING, "المادة", "Item"))
 * 		.add(ReportField.of("quantity", FieldType.DECIMAL, "الكمية", "Quantity"))
 * 		.requireFilterOn("invoice.invoiceDate", 366)
 * 		.forcedFilter(ctx -&gt; Arrays.asList(ctx.getCriteriaBuilder().isTrue(ctx.path("invoice.closed"))));
 * </pre>
 */
public class ReportDataSource implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String ROW_COUNT_FIELD = "*";

	private final String key;
	private final Class<?> rootEntity;
	private String labelAr;
	private String labelEn;
	private String requiredRole;
	private final Map<String, JoinType> joins = new LinkedHashMap<>();
	private final Map<String, ReportField> fields = new LinkedHashMap<>();
	private ForcedFilter forcedFilter;
	private String grainPath;
	private String grainIdAttribute;
	private String requiredFilterField;
	private int maxDateRangeDays;
	/** Excel's own limit is 1,048,576 rows per sheet (one is the header). */
	public static final int MAX_EXPORT_ROWS = 1000000;

	private int maxExportRows = 100000;
	private String dayFunction;
	private int queryTimeoutSeconds = 60;
	private boolean automatic;
	private final Map<String, String[]> joinLabels = new LinkedHashMap<>();

	public ReportDataSource(String key, Class<?> rootEntity) {
		if (key == null || !key.matches("[A-Za-z0-9_.\\-]{1,150}"))
			throw new IllegalArgumentException("Invalid data source key: " + key);
		if (rootEntity == null)
			throw new IllegalArgumentException("rootEntity is required");
		this.key = key;
		this.rootEntity = rootEntity;
	}

	public ReportDataSource labels(String labelAr, String labelEn) {
		this.labelAr = labelAr;
		this.labelEn = labelEn;
		return this;
	}

	/**
	 * Declares a relation that may be joined, e.g. {@code invoice} or
	 * {@code invoice.warehouse}. The parent path must be declared first. Use
	 * INNER for mandatory (NOT NULL) relations - it is faster - and LEFT for
	 * optional ones so rows without the relation are not dropped.
	 */
	public ReportDataSource join(String path, JoinType type) {
		if (path == null || !path.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))
			throw new IllegalArgumentException("Invalid join path: " + path);
		int dot = path.lastIndexOf('.');
		if (dot > 0 && !joins.containsKey(path.substring(0, dot)))
			throw new IllegalArgumentException("Declare parent join '" + path.substring(0, dot) + "' before '" + path + "'");
		if (type == JoinType.RIGHT)
			throw new IllegalArgumentException("RIGHT joins are not supported: " + path);
		joins.put(path, type == null ? JoinType.LEFT : type);
		return this;
	}

	public ReportDataSource add(ReportField field) {
		// a field reached through a collection is resolved in its own subquery: no declared join
		String joinPath = field.getJoinPath();
		if (joinPath != null && !joins.containsKey(joinPath))
			throw new IllegalArgumentException("Field '" + field.getPath() + "' needs join '" + joinPath + "' to be declared first");
		if (ROW_COUNT_FIELD.equals(field.getPath()) || fields.containsKey(field.getPath()))
			throw new IllegalArgumentException("Duplicate or reserved field path: " + field.getPath());
		fields.put(field.getPath(), field);
		return this;
	}

	public ReportDataSource forcedFilter(ForcedFilter forcedFilter) {
		this.forcedFilter = forcedFilter;
		return this;
	}

	/**
	 * Use when the most detailed record can only be reached through a
	 * collection of the root (e.g. root {@code Invoice}, rows =
	 * {@code invoiceLines}). The join must be declared (INNER); one result row
	 * is then one element of that collection, and {@code idAttribute} of the
	 * element keeps paging stable. Do not declare any other collection join.
	 */
	public ReportDataSource grain(String joinPath, String idAttribute) {
		this.grainPath = joinPath;
		this.grainIdAttribute = idAttribute;
		return this;
	}

	/**
	 * Every report on this data source must restrict this date/number field with
	 * a top-level condition (=, &gt;=, &gt; or BETWEEN). With
	 * {@code maxRangeDays > 0} a date field must use BETWEEN and span at most
	 * that many days. This keeps queries on large tables fast.
	 */
	public ReportDataSource requireFilterOn(String fieldPath, int maxRangeDays) {
		this.requiredFilterField = fieldPath;
		this.maxDateRangeDays = Math.max(0, maxRangeDays);
		return this;
	}

	/** Role (permission name) the user needs to see and run this data source; null = any report user. */
	public ReportDataSource requiredRole(String role) {
		this.requiredRole = role;
		return this;
	}

	public ReportDataSource maxExportRows(int rows) {
		this.maxExportRows = Math.max(1, Math.min(rows, MAX_EXPORT_ROWS));
		return this;
	}

	/**
	 * SQL function that truncates a timestamp to its day, used when a report
	 * groups by a DATE field (so all rows of one day form one group). Default:
	 * system property {@code org.softcom.reportbuilder.dayFunction}, else
	 * {@code date} (PostgreSQL). H2 uses {@code TRUNC}.
	 */
	public ReportDataSource dayFunction(String sqlFunction) {
		if (sqlFunction != null && !sqlFunction.matches("[A-Za-z_][A-Za-z0-9_]*"))
			throw new IllegalArgumentException("Invalid SQL function name: " + sqlFunction);
		this.dayFunction = sqlFunction;
		return this;
	}

	public String getDayFunction() {
		String f = dayFunction != null ? dayFunction : System.getProperty("org.softcom.reportbuilder.dayFunction", "date");
		return f.matches("[A-Za-z_][A-Za-z0-9_]*") ? f : "date";
	}

	public ReportDataSource queryTimeoutSeconds(int seconds) {
		this.queryTimeoutSeconds = Math.max(1, seconds);
		return this;
	}

	/** Marks a data source discovered from the JPA metamodel (listed apart from the hand-written ones). */
	public ReportDataSource automatic(boolean automatic) {
		this.automatic = automatic;
		return this;
	}

	/** Display name of a declared join, used to group the fields reached through it. */
	public ReportDataSource joinLabels(String path, String labelAr, String labelEn) {
		if (!joins.containsKey(path))
			throw new IllegalArgumentException("Join '" + path + "' is not declared");
		joinLabels.put(path, new String[] { labelAr, labelEn });
		return this;
	}

	/**
	 * Display name of a relation path that is not a declared join (the
	 * collections behind condition-only fields), used to group their fields.
	 */
	public ReportDataSource groupLabels(String path, String labelAr, String labelEn) {
		joinLabels.put(path, new String[] { labelAr, labelEn });
		return this;
	}

	/** Label of a declared join (or of a {@link #groupLabels} path), or null when none was given. */
	public String getJoinLabel(String path, Locale locale) {
		String[] l = joinLabels.get(path);
		if (l == null)
			return null;
		boolean arabic = locale == null || "ar".equals(locale.getLanguage());
		String s = arabic ? l[0] : l[1];
		if (s == null || s.isEmpty())
			s = arabic ? l[1] : l[0];
		return s == null || s.isEmpty() ? null : s;
	}

	public boolean isAutomatic() {
		return automatic;
	}

	/** Fails fast (at application start-up) on an inconsistent definition. */
	public void checkConsistency() {
		if (fields.isEmpty())
			throw new IllegalStateException("Data source " + key + " declares no fields");
		if (grainPath != null && (!joins.containsKey(grainPath) || grainIdAttribute == null))
			throw new IllegalStateException("Data source " + key + ": grain join '" + grainPath
					+ "' must be declared and have an id attribute");
		if (requiredFilterField != null) {
			ReportField f = fields.get(requiredFilterField);
			if (f == null)
				throw new IllegalStateException("Data source " + key + ": required filter field '" + requiredFilterField
						+ "' is not a declared field");
			if (!f.getType().isOrdered())
				throw new IllegalStateException("Data source " + key + ": required filter field must be a date or number");
			if (!f.isFilterable())
				throw new IllegalStateException("Data source " + key + ": required filter field must be filterable");
		}
	}

	public String getLabel(Locale locale) {
		boolean arabic = locale == null || "ar".equals(locale.getLanguage());
		String label = arabic ? labelAr : labelEn;
		if (label == null || label.isEmpty())
			label = arabic ? labelEn : labelAr;
		return label == null || label.isEmpty() ? key : label;
	}

	public ReportField getField(String path) {
		return fields.get(path);
	}

	public Collection<ReportField> getFields() {
		return Collections.unmodifiableCollection(fields.values());
	}

	public List<ReportField> getFields(boolean filterableOnly) {
		List<ReportField> list = new ArrayList<>();
		for (ReportField f : fields.values())
			if (!filterableOnly || f.isFilterable())
				list.add(f);
		return list;
	}

	public Map<String, JoinType> getJoins() {
		return Collections.unmodifiableMap(joins);
	}

	public String getKey() {
		return key;
	}

	public Class<?> getRootEntity() {
		return rootEntity;
	}

	public String getLabelAr() {
		return labelAr;
	}

	public String getLabelEn() {
		return labelEn;
	}

	public String getRequiredRole() {
		return requiredRole;
	}

	public ForcedFilter getForcedFilter() {
		return forcedFilter;
	}

	public String getGrainPath() {
		return grainPath;
	}

	public String getGrainIdAttribute() {
		return grainIdAttribute;
	}

	public String getRequiredFilterField() {
		return requiredFilterField;
	}

	public int getMaxDateRangeDays() {
		return maxDateRangeDays;
	}

	public int getMaxExportRows() {
		return maxExportRows;
	}

	public int getQueryTimeoutSeconds() {
		return queryTimeoutSeconds;
	}
}
