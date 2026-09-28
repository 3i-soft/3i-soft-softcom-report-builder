package org.softcom.reportbuilder.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Converts the canonical string values stored in a report definition to the
 * Java type of the mapped attribute. Values are never concatenated into SQL:
 * they are handed to the Criteria API as typed objects.
 */
public final class ValueConverter {

	public static final int MAX_TEXT_LENGTH = 500;

	private ValueConverter() {
	}

	public static Object convert(String raw, ReportField field, Class<?> javaType) {
		String value = raw == null ? null : raw.trim();
		if (value == null || value.isEmpty())
			throw new ReportException("rb.error.valueRequired", field.getPath());
		FieldType type = field.getType();
		try {
			if (type.isNumeric())
				return toNumber(parseNumber(value), wrap(javaType), type);
			if (type.isTemporal())
				return toTemporal(parseDateTime(value), wrap(javaType));
			switch (type) {
			case BOOLEAN:
				if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
					throw new IllegalArgumentException(value);
				return Boolean.valueOf(value);
			case ENUM:
				return toEnum(value, javaType);
			default:
				if (value.length() > MAX_TEXT_LENGTH)
					throw new ReportException("rb.error.valueTooLong", field.getPath(), MAX_TEXT_LENGTH);
				return value;
			}
		} catch (ReportException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new ReportException(e, "rb.error.invalidValue", field.getPath(), raw);
		}
	}

	/** Numbers with more integer digits than this are rejected (guards against 1e30000000-style inputs). */
	public static final int MAX_INTEGER_DIGITS = 40;

	/**
	 * Accepts ASCII, Arabic-Indic and Eastern Arabic-Indic digits, the Arabic
	 * decimal separator, and thousands separators (',' or the Arabic one) only
	 * in proper groups of three: "2,5" is rejected rather than read as 25.
	 */
	public static BigDecimal parseNumber(String value) {
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c >= '٠' && c <= '٩')
				sb.append((char) ('0' + (c - '٠')));
			else if (c >= '۰' && c <= '۹')
				sb.append((char) ('0' + (c - '۰')));
			else if (c == '٫')
				sb.append('.');
			else if (c == '٬')
				sb.append(',');
			else if (!Character.isWhitespace(c))
				sb.append(c);
		}
		String n = sb.toString();
		if (n.indexOf(',') >= 0) {
			if (!n.matches("[-+]?\\d{1,3}(,\\d{3})+(\\.\\d+)?"))
				throw new IllegalArgumentException("Invalid number: " + value);
			n = n.replace(",", "");
		}
		BigDecimal d = new BigDecimal(n);
		if (d.precision() - d.scale() > MAX_INTEGER_DIGITS)
			throw new IllegalArgumentException("Number too large: " + value);
		return d;
	}

	/** Parses yyyy-MM-dd, yyyy-MM-ddTHH:mm[:ss] or "yyyy-MM-dd HH:mm[:ss]". */
	public static LocalDateTime parseDateTime(String value) {
		String v = value.trim().replace(' ', 'T');
		try {
			if (v.length() <= 10)
				return LocalDate.parse(v).atStartOfDay();
			return LocalDateTime.parse(v);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("Invalid date: " + value, e);
		}
	}

	public static Object toTemporal(LocalDateTime ldt, Class<?> javaType) {
		if (javaType == LocalDateTime.class)
			return ldt;
		if (javaType == LocalDate.class)
			return ldt.toLocalDate();
		if (javaType == java.sql.Date.class)
			return java.sql.Date.valueOf(ldt.toLocalDate());
		if (javaType == Timestamp.class)
			return Timestamp.valueOf(ldt);
		if (javaType == java.sql.Time.class)
			return java.sql.Time.valueOf(ldt.toLocalTime());
		Date date = Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
		if (Calendar.class.isAssignableFrom(javaType)) {
			Calendar cal = new GregorianCalendar();
			cal.setTime(date);
			return cal;
		}
		return date;
	}

	public static LocalDateTime startOfDay(LocalDate d) {
		return d.atTime(LocalTime.MIDNIGHT);
	}

	private static Object toNumber(BigDecimal n, Class<?> javaType, FieldType type) {
		if (javaType == Integer.class)
			return n.intValueExact();
		if (javaType == Long.class)
			return n.longValueExact();
		if (javaType == Short.class)
			return n.shortValueExact();
		if (javaType == Byte.class)
			return n.byteValueExact();
		if (javaType == Double.class)
			return n.doubleValue();
		if (javaType == Float.class)
			return n.floatValue();
		if (javaType == BigInteger.class)
			return n.toBigIntegerExact();
		if (javaType == BigDecimal.class)
			return n;
		// attribute type unknown/unusual: fall back to the declared logical type
		switch (type) {
		case INTEGER:
			return n.intValueExact();
		case LONG:
			return n.longValueExact();
		case DOUBLE:
			return n.doubleValue();
		default:
			return n;
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object toEnum(String value, Class<?> javaType) {
		if (javaType != null && javaType.isEnum())
			return Enum.valueOf((Class) javaType, value);
		return value;
	}

	public static Class<?> wrap(Class<?> c) {
		if (c == null || !c.isPrimitive())
			return c == null ? Object.class : c;
		if (c == int.class)
			return Integer.class;
		if (c == long.class)
			return Long.class;
		if (c == double.class)
			return Double.class;
		if (c == float.class)
			return Float.class;
		if (c == short.class)
			return Short.class;
		if (c == byte.class)
			return Byte.class;
		if (c == boolean.class)
			return Boolean.class;
		if (c == char.class)
			return Character.class;
		return c;
	}
}
