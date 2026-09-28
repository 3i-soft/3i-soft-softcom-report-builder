package org.softcom.reportbuilder.spi;

/**
 * Logical type of a reportable field. It drives which operators and aggregates
 * the designer offers; the actual conversion of user input always uses the Java
 * type of the mapped attribute.
 */
public enum FieldType {
	STRING, INTEGER, LONG, DECIMAL, DOUBLE, DATE, DATETIME, BOOLEAN, ENUM;

	public boolean isNumeric() {
		return this == INTEGER || this == LONG || this == DECIMAL || this == DOUBLE;
	}

	public boolean isTemporal() {
		return this == DATE || this == DATETIME;
	}

	/** Types that support &lt;, &gt;, BETWEEN, MIN and MAX. */
	public boolean isOrdered() {
		return isNumeric() || isTemporal();
	}
}
