package org.softcom.reportbuilder.spec;

import org.softcom.reportbuilder.spi.FieldType;

public enum Operator {
	EQ(1), NE(1), GT(1), GE(1), LT(1), LE(1), BETWEEN(2), IN(-1), NOT_IN(-1),
	CONTAINS(1), NOT_CONTAINS(1), STARTS_WITH(1), ENDS_WITH(1),
	IS_NULL(0), IS_NOT_NULL(0), IS_TRUE(0), IS_FALSE(0);

	/** Number of values: 0, 1, 2 or -1 for a list. */
	private final int arity;

	Operator(int arity) {
		this.arity = arity;
	}

	public int getArity() {
		return arity;
	}

	public boolean supports(FieldType type) {
		switch (this) {
		case IS_NULL:
		case IS_NOT_NULL:
			return true;
		case EQ:
		case NE:
		case IN:
		case NOT_IN:
			return type != FieldType.BOOLEAN;
		case GT:
		case GE:
		case LT:
		case LE:
		case BETWEEN:
			return type.isOrdered();
		case CONTAINS:
		case NOT_CONTAINS:
		case STARTS_WITH:
		case ENDS_WITH:
			return type == FieldType.STRING;
		case IS_TRUE:
		case IS_FALSE:
			return type == FieldType.BOOLEAN;
		default:
			return false;
		}
	}

	/** Operators that bound the value from below (used for the "required filter" rule). */
	public boolean isLowerBound() {
		return this == EQ || this == GE || this == GT || this == BETWEEN;
	}
}
