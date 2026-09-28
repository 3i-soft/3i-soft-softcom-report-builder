package org.softcom.reportbuilder.spec;

import java.io.Serializable;

/** One output column: a whitelisted field, optionally aggregated. */
public class ColumnSpec implements Serializable {

	private static final long serialVersionUID = 1L;

	private String field;
	private Aggregate aggregate = Aggregate.NONE;
	/** Optional custom header; the field label is used when empty. */
	private String label;

	public ColumnSpec() {
	}

	public ColumnSpec(String field, Aggregate aggregate) {
		this.field = field;
		this.aggregate = aggregate == null ? Aggregate.NONE : aggregate;
	}

	public ColumnSpec copy() {
		ColumnSpec c = new ColumnSpec(field, aggregate);
		c.label = label;
		return c;
	}

	public boolean isAggregated() {
		return aggregate != null && aggregate != Aggregate.NONE;
	}

	public String getField() {
		return field;
	}

	public void setField(String field) {
		this.field = field;
	}

	public Aggregate getAggregate() {
		return aggregate;
	}

	public void setAggregate(Aggregate aggregate) {
		this.aggregate = aggregate == null ? Aggregate.NONE : aggregate;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}
}
