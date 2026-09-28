package org.softcom.reportbuilder.engine;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SortSpec;
import org.softcom.reportbuilder.spec.SpecJson;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Checks a definition against the whitelist of its data source. Nothing that is
 * not declared by the data source can reach the query builder.
 */
public final class SpecValidator {

	public static final int MAX_COLUMNS = 40;
	public static final int MAX_RULES = 60;
	public static final int MAX_LIST_VALUES = 500;
	public static final int MAX_SORTS = 5;

	private SpecValidator() {
	}

	/**
	 * @param requireValues false while designing (ask-at-run rules may still be
	 *                      empty); true before running.
	 */
	public static void validate(ReportSpec spec, ReportDataSource ds, boolean requireValues) {
		if (spec == null)
			throw new ReportException("rb.error.invalidDefinition", "null");
		if (ds == null || !ds.getKey().equals(spec.getDataSource()))
			throw new ReportException("rb.error.unknownDataSource", spec.getDataSource());
		List<ColumnSpec> columns = spec.getColumns();
		if (columns.isEmpty())
			throw new ReportException("rb.error.noColumns");
		if (columns.size() > MAX_COLUMNS)
			throw new ReportException("rb.error.tooManyColumns", MAX_COLUMNS);
		boolean grouped = spec.isGrouped();
		for (ColumnSpec c : columns) {
			checkLabel(c.getLabel());
			Aggregate agg = c.getAggregate() == null ? Aggregate.NONE : c.getAggregate();
			if (ReportDataSource.ROW_COUNT_FIELD.equals(c.getField())) {
				if (agg != Aggregate.COUNT)
					throw new ReportException("rb.error.rowCountNeedsCount");
				continue;
			}
			ReportField f = field(ds, c.getField());
			if (!agg.supports(f.getType(), f.isAggregatable()))
				throw new ReportException("rb.error.aggregateNotAllowed", agg, f.getPath());
			if (grouped && agg == Aggregate.NONE && !f.isGroupable())
				throw new ReportException("rb.error.notGroupable", f.getPath());
		}
		if (spec.getFilter() != null)
			validateFilter(spec.getFilter(), ds, requireValues);
		if (spec.getSort().size() > MAX_SORTS)
			throw new ReportException("rb.error.tooManySorts", MAX_SORTS);
		for (SortSpec s : spec.getSort())
			if (s.getColumn() < 0 || s.getColumn() >= columns.size())
				throw new ReportException("rb.error.invalidSort", s.getColumn());
		if (requireValues)
			checkRequiredFilter(spec, ds);
	}

	private static void validateFilter(FilterNode root, ReportDataSource ds, boolean requireValues) {
		List<FilterNode> rules = new ArrayList<>();
		walk(root, 1, rules);
		if (rules.size() > MAX_RULES)
			throw new ReportException("rb.error.tooManyRules", MAX_RULES);
		Set<String> ids = new HashSet<>();
		for (FilterNode r : rules) {
			ReportField f = field(ds, r.getField());
			if (!f.isFilterable())
				throw new ReportException("rb.error.notFilterable", f.getPath());
			Operator op = r.getOperator();
			if (op == null || !op.supports(f.getType()))
				throw new ReportException("rb.error.operatorNotAllowed", op, f.getPath());
			checkLabel(r.getLabel());
			if (r.isAskAtRun()) {
				if (r.getId() == null || r.getId().isEmpty() || !ids.add(r.getId()))
					throw new ReportException("rb.error.invalidParameterId", r.getId());
			}
			if (requireValues || !r.isAskAtRun())
				checkValues(r, f);
		}
	}

	private static void walk(FilterNode node, int depth, List<FilterNode> rules) {
		if (depth > SpecJson.MAX_DEPTH)
			throw new ReportException("rb.error.filterTooDeep", SpecJson.MAX_DEPTH);
		if (node.isGroup()) {
			for (FilterNode c : node.getChildren())
				walk(c, depth + 1, rules);
		} else {
			rules.add(node);
		}
	}

	private static void checkValues(FilterNode r, ReportField f) {
		int arity = r.getOperator().getArity();
		if (arity >= 1 && isBlank(r.getValue()))
			throw new ReportException("rb.error.valueRequired", f.getPath());
		if (arity == 2 && isBlank(r.getValue2()))
			throw new ReportException("rb.error.valueRequired", f.getPath());
		if (arity < 0) {
			int n = 0;
			if (r.getValues() != null)
				for (String v : r.getValues())
					if (!isBlank(v))
						n++;
			if (n == 0)
				throw new ReportException("rb.error.valueRequired", f.getPath());
			if (n > MAX_LIST_VALUES)
				throw new ReportException("rb.error.tooManyValues", f.getPath(), MAX_LIST_VALUES);
		}
	}

	/**
	 * The required field must be restricted by a rule that is reached from the
	 * root through AND groups only (a rule inside an OR group does not restrict
	 * the whole result).
	 */
	private static void checkRequiredFilter(ReportSpec spec, ReportDataSource ds) {
		String required = ds.getRequiredFilterField();
		if (required == null)
			return;
		ReportField field = ds.getField(required);
		List<FilterNode> anded = new ArrayList<>();
		collectAnded(spec.getFilter(), anded);
		for (FilterNode r : anded) {
			if (!required.equals(r.getField()) || r.getOperator() == null || !r.getOperator().isLowerBound())
				continue;
			if (ds.getMaxDateRangeDays() <= 0 || !field.getType().isTemporal())
				return;
			if (r.getOperator() == Operator.BETWEEN) {
				LocalDateTime from = parse(r.getValue(), field);
				LocalDateTime to = parse(r.getValue2(), field);
				if (ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()) + 1 <= ds.getMaxDateRangeDays())
					return;
			}
		}
		if (ds.getMaxDateRangeDays() > 0 && field.getType().isTemporal())
			throw new ReportException("rb.error.requiredDateRange", field.getPath(), ds.getMaxDateRangeDays());
		throw new ReportException("rb.error.requiredFilter", field.getPath());
	}

	private static LocalDateTime parse(String raw, ReportField f) {
		try {
			return ValueConverter.parseDateTime(raw);
		} catch (RuntimeException e) {
			throw new ReportException(e, "rb.error.invalidValue", f.getPath(), raw);
		}
	}

	private static void collectAnded(FilterNode node, List<FilterNode> out) {
		if (node == null)
			return;
		if (!node.isGroup()) {
			out.add(node);
			return;
		}
		if (node.getLogic() == FilterNode.Logic.OR && node.getChildren().size() > 1)
			return;
		for (FilterNode c : node.getChildren())
			collectAnded(c, out);
	}

	private static ReportField field(ReportDataSource ds, String path) {
		ReportField f = path == null ? null : ds.getField(path);
		if (f == null)
			throw new ReportException("rb.error.unknownField", path);
		return f;
	}

	public static final int MAX_LABEL_LENGTH = 100;

	private static void checkLabel(String label) {
		if (label != null && label.length() > MAX_LABEL_LENGTH)
			throw new ReportException("rb.error.labelTooLong", MAX_LABEL_LENGTH);
	}

	static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	/** Field type of a column's values after aggregation. */
	public static FieldType resultType(ColumnSpec c, ReportField f) {
		Aggregate agg = c.getAggregate() == null ? Aggregate.NONE : c.getAggregate();
		switch (agg) {
		case COUNT:
		case COUNT_DISTINCT:
			return FieldType.LONG;
		case AVG:
			return FieldType.DOUBLE;
		default:
			return f == null ? FieldType.LONG : f.getType();
		}
	}
}
