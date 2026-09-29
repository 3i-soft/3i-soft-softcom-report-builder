package org.softcom.reportbuilder.engine;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Keeps reports on large tables fast without per-table setup: a report on a
 * large table must restrict an indexed ("fast") field with a condition that
 * applies to the whole result (reached through AND groups only):
 * {@code =} or {@code IN}, or {@code BETWEEN} - for dates spanning at most
 * {@code maxRangeDays}. Only automatic data sources are checked: a
 * hand-written one is designed by a developer (its own required filter,
 * forced filters). A large table without a filterable index other than its
 * primary key is left to the query timeout.
 */
public final class SpeedRules {

	/** How many fast fields the error message lists. */
	static final int LISTED_FIELDS = 6;

	private SpeedRules() {
	}

	public static void check(ReportSpec spec, ReportDataSource ds, SpeedInfo speed, int maxRangeDays) {
		// only a primary key indexed: asking for "id = ..." would make the table useless; the timeout protects it
		if (!applies(ds, speed) || usefulFastFields(ds, speed).isEmpty())
			return;
		List<FilterNode> anded = new ArrayList<>();
		SpecValidator.collectAnded(spec.getFilter(), anded);
		for (FilterNode r : anded)
			if (isFastCondition(r, ds, speed, maxRangeDays))
				return;
		throw new ReportException("rb.error.needFastFilter", new FieldList(listed(ds, speed)), speed.getEstimatedRows(),
				maxRangeDays);
	}

	static boolean isFastCondition(FilterNode r, ReportDataSource ds, SpeedInfo speed, int maxRangeDays) {
		if (r.isGroup() || r.getOperator() == null || !speed.isFast(r.getField()))
			return false;
		ReportField f = ds.getField(r.getField());
		if (f == null)
			return false;
		switch (r.getOperator()) {
		case EQ:
		case IN:
			return true;
		case BETWEEN:
			if (!f.getType().isTemporal() || maxRangeDays <= 0)
				return true;
			try {
				LocalDateTime from = ValueConverter.parseDateTime(r.getValue());
				LocalDateTime to = ValueConverter.parseDateTime(r.getValue2());
				return ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()) + 1 <= maxRangeDays;
			} catch (RuntimeException e) {
				return false;
			}
		default:
			return false;
		}
	}

	/** Whether the large-table rule applies: an automatic data source on a large table without its own required filter. */
	public static boolean applies(ReportDataSource ds, SpeedInfo speed) {
		return ds != null && speed != null && ds.isAutomatic() && speed.isLarge() && ds.getRequiredFilterField() == null;
	}

	/** Fast, filterable fields other than primary keys, in the data source's order. */
	public static List<String> usefulFastFields(ReportDataSource ds, SpeedInfo speed) {
		List<String> out = new ArrayList<>();
		for (ReportField f : ds.getFields())
			if (f.isFilterable() && speed.getUsefulFastFields().contains(f.getPath()))
				out.add(f.getPath());
		return out;
	}

	/** Fast fields in the data source's order, dates first: they are what users usually filter on. */
	private static List<String> listed(ReportDataSource ds, SpeedInfo speed) {
		List<String> dates = new ArrayList<>();
		List<String> others = new ArrayList<>();
		for (String path : usefulFastFields(ds, speed))
			(ds.getField(path).getType().isTemporal() ? dates : others).add(path);
		List<String> all = new ArrayList<>(dates);
		all.addAll(others);
		return all.size() > LISTED_FIELDS ? all.subList(0, LISTED_FIELDS) : all;
	}

	/** The fast date/time field a new report on this data source should start with, or null. */
	public static String suggestedDateField(ReportDataSource ds, SpeedInfo speed) {
		if (!applies(ds, speed))
			return null;
		String fallback = null;
		for (ReportField f : ds.getFields()) {
			if (!f.getType().isTemporal() || !f.isFilterable() || !speed.isFast(f.getPath()))
				continue;
			if (f.getPath().indexOf('.') < 0)
				return f.getPath();
			if (fallback == null)
				fallback = f.getPath();
		}
		return fallback;
	}
}
