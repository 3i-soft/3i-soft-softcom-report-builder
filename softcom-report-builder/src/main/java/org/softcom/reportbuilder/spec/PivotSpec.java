package org.softcom.reportbuilder.spec;

import java.io.Serializable;

/**
 * Shows a report as a pivot table: each value of {@link #getField()} becomes a
 * column (e.g. one column per supplier), and each cell holds
 * {@link #getAggregate()} of {@link #getValue()} (e.g. the highest price) for
 * the row's values of the report's columns (e.g. the item). The report's
 * columns are then the rows' keys: plain fields, without totals. Only rows
 * with data appear. Optionally a total column per row; the report's grand
 * total ({@link ReportSpec#isTotals()}) adds a total row.
 */
public class PivotSpec implements Serializable {

	private static final long serialVersionUID = 1L;

	private String field;
	private String value;
	private Aggregate aggregate = Aggregate.SUM;
	private boolean rowTotal;

	public PivotSpec() {
	}

	public PivotSpec(String field, String value, Aggregate aggregate) {
		this.field = field;
		this.value = value;
		this.aggregate = aggregate;
	}

	public PivotSpec copy() {
		PivotSpec p = new PivotSpec(field, value, aggregate);
		p.rowTotal = rowTotal;
		return p;
	}

	/**
	 * Whether a total over several cells means something: a sum of sums or of
	 * counts, the least of minimums, the greatest of maximums. Not for averages
	 * nor distinct counts.
	 */
	public static boolean hasTotals(Aggregate a) {
		return a == Aggregate.SUM || a == Aggregate.COUNT || a == Aggregate.MIN || a == Aggregate.MAX;
	}

	/** The field whose values become the columns. */
	public String getField() {
		return field;
	}

	public void setField(String field) {
		this.field = field;
	}

	/** The field in the cells ({@code *} with COUNT: the number of rows). */
	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public Aggregate getAggregate() {
		return aggregate;
	}

	public void setAggregate(Aggregate aggregate) {
		this.aggregate = aggregate;
	}

	/** A last column with the total of each row (see {@link #hasTotals}). */
	public boolean isRowTotal() {
		return rowTotal;
	}

	public void setRowTotal(boolean rowTotal) {
		this.rowTotal = rowTotal;
	}
}
