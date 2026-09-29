package org.softcom.reportbuilder.engine;

import java.io.Serializable;
import java.util.Locale;

import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.DatePart;
import org.softcom.reportbuilder.spi.FieldType;

/** Metadata of one result column. */
public class ResultColumn implements Serializable {

	private static final long serialVersionUID = 1L;

	private final int index;
	private final String field;
	private final Aggregate aggregate;
	private final FieldType type;
	private final String customLabel;
	private final String labelAr;
	private final String labelEn;
	private final String format;
	private final DatePart datePart;

	public ResultColumn(int index, String field, Aggregate aggregate, FieldType type, String customLabel, String labelAr,
			String labelEn, String format) {
		this(index, field, aggregate, type, customLabel, labelAr, labelEn, format, null);
	}

	public ResultColumn(int index, String field, Aggregate aggregate, FieldType type, String customLabel, String labelAr,
			String labelEn, String format, DatePart datePart) {
		this.index = index;
		this.datePart = datePart;
		this.field = field;
		this.aggregate = aggregate;
		this.type = type;
		this.customLabel = customLabel;
		this.labelAr = labelAr;
		this.labelEn = labelEn;
		// a month or year column shows "2026-03" / "2026", whatever the field's own pattern
		this.format = datePart == null ? format : datePart.pattern();
	}

	/** Custom label if set, otherwise the field label, suffixed with the aggregate (e.g. "Quantity (SUM)"). */
	public String getLabel(Locale locale) {
		if (customLabel != null && !customLabel.trim().isEmpty())
			return customLabel;
		boolean arabic = locale == null || "ar".equals(locale.getLanguage());
		String base = arabic ? labelAr : labelEn;
		if (base == null || base.isEmpty())
			base = arabic ? labelEn : labelAr;
		if (base == null || base.isEmpty())
			base = field;
		if (datePart == DatePart.MONTH || datePart == DatePart.YEAR)
			base = base + " (" + datePartLabel(arabic) + ")";
		return aggregate == null || aggregate == Aggregate.NONE ? base : base + " (" + aggregateLabel(arabic) + ")";
	}

	private String datePartLabel(boolean arabic) {
		if (datePart == DatePart.MONTH)
			return arabic ? "شهر" : "month";
		return arabic ? "سنة" : "year";
	}

	private String aggregateLabel(boolean arabic) {
		if (!arabic)
			return aggregate.name();
		switch (aggregate) {
		case SUM:
			return "مجموع";
		case AVG:
			return "متوسط";
		case MIN:
			return "أصغر";
		case MAX:
			return "أكبر";
		case COUNT:
			return "عدد";
		case COUNT_DISTINCT:
			return "عدد مميز";
		default:
			return aggregate.name();
		}
	}

	public int getIndex() {
		return index;
	}

	public String getField() {
		return field;
	}

	public Aggregate getAggregate() {
		return aggregate;
	}

	public FieldType getType() {
		return type;
	}

	public String getFormat() {
		return format;
	}

	/** Day, month or year of a date column; null = as stored. */
	public DatePart getDatePart() {
		return datePart;
	}
}
