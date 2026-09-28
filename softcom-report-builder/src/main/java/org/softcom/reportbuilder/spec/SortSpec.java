package org.softcom.reportbuilder.spec;

import java.io.Serializable;

/** Sort by one of the report columns (by its zero-based index). */
public class SortSpec implements Serializable {

	private static final long serialVersionUID = 1L;

	private int column;
	private boolean descending;

	public SortSpec() {
	}

	public SortSpec(int column, boolean descending) {
		this.column = column;
		this.descending = descending;
	}

	public SortSpec copy() {
		return new SortSpec(column, descending);
	}

	public int getColumn() {
		return column;
	}

	public void setColumn(int column) {
		this.column = column;
	}

	public boolean isDescending() {
		return descending;
	}

	public void setDescending(boolean descending) {
		this.descending = descending;
	}
}
