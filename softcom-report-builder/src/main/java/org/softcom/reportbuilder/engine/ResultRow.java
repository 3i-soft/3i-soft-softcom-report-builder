package org.softcom.reportbuilder.engine;

import java.io.Serializable;

/**
 * A row of the result table: a data row, a subtotal row (after the last row of
 * each value of the first grouping column) or the grand total row.
 */
public class ResultRow implements Serializable {

	private static final long serialVersionUID = 1L;

	public enum Kind {
		DATA, SUBTOTAL, TOTAL
	}

	private final Object[] values;
	private final Kind kind;
	private final int labelColumn;

	public ResultRow(Object[] values, Kind kind, int labelColumn) {
		this.values = values;
		this.kind = kind;
		this.labelColumn = labelColumn;
	}

	public static ResultRow data(Object[] values) {
		return new ResultRow(values, Kind.DATA, -1);
	}

	/** One value per result column (null where a subtotal/total has nothing to show). */
	public Object[] getValues() {
		return values;
	}

	public Kind getKind() {
		return kind;
	}

	/** Column showing the words "subtotal" / "total", or -1. */
	public int getLabelColumn() {
		return labelColumn;
	}

	/** Bundle key of the words shown in {@link #getLabelColumn()}. */
	public String getLabelKey() {
		return kind == Kind.SUBTOTAL ? "rb.subtotal" : kind == Kind.TOTAL ? "rb.total" : null;
	}

	public String getStyleClass() {
		return kind == Kind.SUBTOTAL ? "rb-row-subtotal" : kind == Kind.TOTAL ? "rb-row-total" : null;
	}
}
