package org.softcom.reportbuilder.spec;

import org.softcom.reportbuilder.spi.FieldType;

public enum Aggregate {
	NONE, SUM, AVG, MIN, MAX, COUNT, COUNT_DISTINCT;

	public boolean supports(FieldType type, boolean aggregatableField) {
		switch (this) {
		case NONE:
		case COUNT:
		case COUNT_DISTINCT:
			return true;
		case SUM:
		case AVG:
			return type.isNumeric() && aggregatableField;
		case MIN:
		case MAX:
			return type.isOrdered();
		default:
			return false;
		}
	}
}
