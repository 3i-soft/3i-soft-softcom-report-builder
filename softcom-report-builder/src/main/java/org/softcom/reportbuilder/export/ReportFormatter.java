package org.softcom.reportbuilder.export;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import org.softcom.reportbuilder.engine.LookupQueries;
import org.softcom.reportbuilder.engine.ResultColumn;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/** Formats result values for display (screen). Numbers use Latin digits for consistency with the rest of the UI. */
public final class ReportFormatter {

	private ReportFormatter() {
	}

	/** Like {@link #format(Object, ResultColumn, ReportDataSource, Locale)}; an id found in {@code names} shows its name. */
	public static String format(Object value, ResultColumn column, ReportDataSource ds, Locale locale, Map<String, String> names) {
		if (value != null && names != null) {
			String name = names.get(LookupQueries.key(value));
			if (name != null)
				return name;
		}
		return format(value, column, ds, locale);
	}

	public static String format(Object value, ResultColumn column, ReportDataSource ds, Locale locale) {
		if (value == null)
			return "";
		String pattern = column.getFormat();
		FieldType type = column.getType();
		if (value instanceof Date) {
			String p = pattern != null ? pattern : type == FieldType.DATETIME ? "yyyy-MM-dd HH:mm" : "yyyy-MM-dd";
			return new SimpleDateFormat(p, Locale.ENGLISH).format((Date) value);
		}
		if (value instanceof Boolean) {
			boolean arabic = locale == null || "ar".equals(locale.getLanguage());
			return Boolean.TRUE.equals(value) ? (arabic ? "نعم" : "Yes") : (arabic ? "لا" : "No");
		}
		if (value instanceof Number) {
			String p = pattern != null ? pattern : defaultNumberPattern(type, value);
			return new DecimalFormat(p, DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(value);
		}
		String key = value instanceof Enum ? ((Enum<?>) value).name() : value.toString();
		ReportField f = ds == null ? null : ds.getField(column.getField());
		return f == null ? key : f.choiceLabel(key);
	}

	static String defaultNumberPattern(FieldType type, Object value) {
		if (type == FieldType.INTEGER || type == FieldType.LONG)
			return "#,##0";
		if (value instanceof BigDecimal || value instanceof Double || value instanceof Float)
			return "#,##0.##";
		return "#,##0";
	}
}
