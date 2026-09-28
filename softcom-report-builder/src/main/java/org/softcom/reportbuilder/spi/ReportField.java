package org.softcom.reportbuilder.spi;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A whitelisted field of a {@link ReportDataSource}.
 * <ul>
 * <li>Attribute field: {@code path} is a JPA attribute path from the data source
 * root, e.g. {@code quantity} or {@code item.name}; every prefix ({@code item})
 * must be a join declared on the data source.</li>
 * <li>Computed field ({@link #computed}): {@code path} is just a key and the
 * value comes from a Criteria expression.</li>
 * </ul>
 */
public class ReportField implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String path;
	private final FieldType type;
	private final FieldExpression expression;
	private String labelAr;
	private String labelEn;
	private String hintAr;
	private String hintEn;
	private boolean filterable = true;
	private boolean groupable = true;
	private boolean aggregatable;
	private boolean caseInsensitive;
	private String format;
	private Map<String, String> enumValues = Collections.emptyMap();
	private ChoiceSource choiceSource;

	public ReportField(String path, FieldType type) {
		this(path, type, null);
	}

	private ReportField(String path, FieldType type, FieldExpression expression) {
		if (path == null || path.trim().isEmpty())
			throw new IllegalArgumentException("path is required");
		if (type == null)
			throw new IllegalArgumentException("type is required for " + path);
		this.path = path.trim();
		this.type = type;
		this.expression = expression;
		this.aggregatable = type.isNumeric();
	}

	public static ReportField of(String path, FieldType type, String labelAr, String labelEn) {
		return new ReportField(path, type).labels(labelAr, labelEn);
	}

	/** A field whose value is computed by the database, e.g. price x quantity. {@code key} must not contain '.'. */
	public static ReportField computed(String key, FieldType type, String labelAr, String labelEn, FieldExpression expression) {
		if (key == null || key.contains("."))
			throw new IllegalArgumentException("Computed field key must be a simple name: " + key);
		if (expression == null)
			throw new IllegalArgumentException("expression is required for " + key);
		// Not groupable by default: a constant inside the expression is bound as a separate JDBC parameter in
		// SELECT and GROUP BY, which PostgreSQL rejects. Call groupable(true) for expressions without constants.
		return new ReportField(key, type, expression).labels(labelAr, labelEn).groupable(false);
	}

	public ReportField labels(String labelAr, String labelEn) {
		this.labelAr = labelAr;
		this.labelEn = labelEn;
		return this;
	}

	/** Short explanation shown as a tooltip in the designer (e.g. "in the invoice currency"). */
	public ReportField hint(String hintAr, String hintEn) {
		this.hintAr = hintAr;
		this.hintEn = hintEn;
		return this;
	}

	/** Whether SUM/AVG are offered (defaults to true for numeric fields). */
	public ReportField aggregatable(boolean aggregatable) {
		this.aggregatable = aggregatable;
		return this;
	}

	public ReportField filterable(boolean filterable) {
		this.filterable = filterable;
		return this;
	}

	public ReportField groupable(boolean groupable) {
		this.groupable = groupable;
		return this;
	}

	/**
	 * Text filters use lower(column) LIKE lower(value). Off by default because
	 * it prevents PostgreSQL from using a plain b-tree index on the column.
	 */
	public ReportField caseInsensitive(boolean caseInsensitive) {
		this.caseInsensitive = caseInsensitive;
		return this;
	}

	/** Display format: a java.text pattern for numbers/dates, e.g. "#,##0.00", "0" or "yyyy-MM-dd". */
	public ReportField format(String format) {
		this.format = format;
		return this;
	}

	/**
	 * Fixed allowed values (for ENUM fields): key = Java enum constant name or
	 * stored value, value = display label.
	 */
	public ReportField enumValues(Map<String, String> values) {
		this.enumValues = values == null ? Collections.<String, String>emptyMap()
				: Collections.unmodifiableMap(new LinkedHashMap<>(values));
		return this;
	}

	/** Allowed values looked up at run time (e.g. a code table). */
	public ReportField choices(ChoiceSource source) {
		this.choiceSource = source;
		return this;
	}

	/** Fixed values if declared, otherwise the choice source, otherwise empty. */
	public Map<String, String> getChoices() {
		if (!enumValues.isEmpty())
			return enumValues;
		if (choiceSource != null) {
			Map<String, String> m = choiceSource.choices();
			if (m != null)
				return m;
		}
		return Collections.emptyMap();
	}

	public boolean hasChoices() {
		return !enumValues.isEmpty() || choiceSource != null;
	}

	/** Display label of a stored value, or the value itself. */
	public String choiceLabel(String storedValue) {
		if (storedValue == null || !hasChoices())
			return storedValue;
		String label = !enumValues.isEmpty() ? enumValues.get(storedValue) : choiceSource.label(storedValue);
		return label == null || label.isEmpty() ? storedValue : label;
	}

	public String getLabel(Locale locale) {
		return pick(locale, labelAr, labelEn, path);
	}

	public String getHint(Locale locale) {
		return pick(locale, hintAr, hintEn, null);
	}

	private static String pick(Locale locale, String ar, String en, String fallback) {
		boolean arabic = locale == null || "ar".equals(locale.getLanguage());
		String s = arabic ? ar : en;
		if (s == null || s.isEmpty())
			s = arabic ? en : ar;
		return s == null || s.isEmpty() ? fallback : s;
	}

	/** The path of the join this field is reached through, or null for a root attribute / computed field. */
	public String getJoinPath() {
		if (expression != null)
			return null;
		int dot = path.lastIndexOf('.');
		return dot < 0 ? null : path.substring(0, dot);
	}

	public boolean isComputed() {
		return expression != null;
	}

	public FieldExpression getExpression() {
		return expression;
	}

	public String getPath() {
		return path;
	}

	public FieldType getType() {
		return type;
	}

	public String getLabelAr() {
		return labelAr;
	}

	public String getLabelEn() {
		return labelEn;
	}

	public boolean isFilterable() {
		return filterable;
	}

	public boolean isGroupable() {
		return groupable;
	}

	public boolean isAggregatable() {
		return aggregatable;
	}

	public boolean isCaseInsensitive() {
		return caseInsensitive;
	}

	public String getFormat() {
		return format;
	}

	@Override
	public String toString() {
		return path + ":" + type;
	}
}
