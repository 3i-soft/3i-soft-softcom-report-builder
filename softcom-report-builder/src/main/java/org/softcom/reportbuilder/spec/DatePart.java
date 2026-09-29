package org.softcom.reportbuilder.spec;

/** How a date column is shown and grouped: by day, month (first day of the month) or year. */
public enum DatePart {
	DAY, MONTH, YEAR;

	/** Display pattern of the values (java.text.SimpleDateFormat). */
	public String pattern() {
		switch (this) {
		case MONTH:
			return "yyyy-MM";
		case YEAR:
			return "yyyy";
		default:
			return "yyyy-MM-dd";
		}
	}
}
