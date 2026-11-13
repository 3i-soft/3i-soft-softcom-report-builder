package org.softcom.reportbuilder.engine;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.LinkSpec;
import org.softcom.reportbuilder.spec.PivotSpec;
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
	public static final int MAX_LINKS = 3;
	public static final int MAX_LINK_PAIRS = 5;

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
		validateLinks(spec, ds);
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
			if (f.isViaCollection())
				throw new ReportException("rb.error.conditionOnly", f.getPath());
			if (!agg.supports(f.getType(), f.isAggregatable()))
				throw new ReportException("rb.error.aggregateNotAllowed", agg, f.getPath());
			if (grouped && agg == Aggregate.NONE && !f.isGroupable())
				throw new ReportException("rb.error.notGroupable", f.getPath());
			if (c.getDatePart() != null && (agg != Aggregate.NONE || !f.getType().isTemporal()))
				throw new ReportException("rb.error.datePartNotAllowed", f.getPath());
		}
		if (spec.getFilter() != null)
			validateFilter(spec.getFilter(), ds, requireValues);
		if (spec.getSort().size() > MAX_SORTS)
			throw new ReportException("rb.error.tooManySorts", MAX_SORTS);
		for (SortSpec s : spec.getSort())
			if (s.getColumn() < 0 || s.getColumn() >= columns.size())
				throw new ReportException("rb.error.invalidSort", s.getColumn());
		if (spec.getPivot() != null)
			validatePivot(spec, ds);
		if (requireValues)
			checkRequiredFilter(spec, ds);
	}

	/**
	 * A pivot table: the report's columns are the rows' keys (plain fields, no
	 * totals nor row count), the column field can be grouped, and the cells'
	 * value supports its aggregate.
	 */
	static void validatePivot(ReportSpec spec, ReportDataSource ds) {
		PivotSpec p = spec.getPivot();
		if (SpecValidator.isBlank(p.getField()))
			throw new ReportException("rb.error.pivotField");
		if (SpecValidator.isBlank(p.getValue()))
			throw new ReportException("rb.error.pivotValue");
		for (ColumnSpec c : spec.getColumns())
			if (c.isAggregated() || ReportDataSource.ROW_COUNT_FIELD.equals(c.getField()))
				throw new ReportException("rb.error.pivotRowColumns");
			else if (!field(ds, c.getField()).isGroupable())
				throw new ReportException("rb.error.notGroupable", c.getField());
		ReportField column = field(ds, p.getField());
		if (column.isViaCollection() || !column.isGroupable())
			throw new ReportException("rb.error.notGroupable", column.getPath());
		Aggregate agg = p.getAggregate();
		if (agg == null || agg == Aggregate.NONE)
			throw new ReportException("rb.error.pivotValue");
		if (ReportDataSource.ROW_COUNT_FIELD.equals(p.getValue())) {
			if (agg != Aggregate.COUNT)
				throw new ReportException("rb.error.rowCountNeedsCount");
			return;
		}
		ReportField value = field(ds, p.getValue());
		if (value.isViaCollection())
			throw new ReportException("rb.error.conditionOnly", value.getPath());
		if (!agg.supports(value.getType(), value.isAggregatable()))
			throw new ReportException("rb.error.aggregateNotAllowed", agg, value.getPath());
	}

	/**
	 * The report's links must be exactly the ones the data source was resolved
	 * with ({@link ReportDataSource#withLinks}), each with 1 to
	 * {@value #MAX_LINK_PAIRS} pairs of comparable fields: a field of the
	 * report or of an earlier link on the left, a field of the linked table on
	 * the right.
	 */
	static void validateLinks(ReportSpec spec, ReportDataSource ds) {
		List<LinkSpec> links = spec.getLinks();
		if (links.size() > MAX_LINKS)
			throw new ReportException("rb.error.tooManyLinks", MAX_LINKS);
		if (links.size() != ds.getLinks().size())
			throw new ReportException("rb.error.invalidLink", links.size());
		Set<String> earlier = new HashSet<>();
		for (LinkSpec l : links) {
			String alias = l.getAlias();
			ReportDataSource.Link resolved = alias == null ? null : ds.getLinks().get(alias);
			if (alias == null || !alias.matches(LinkSpec.ALIAS_PATTERN) || resolved == null || earlier.contains(alias)
					|| !resolved.getTarget().getKey().equals(l.getDataSource()))
				throw new ReportException("rb.error.invalidLink", alias);
			ReportDataSource target = resolved.getTarget();
			if (l.getOn().isEmpty())
				throw new ReportException("rb.error.linkNeedsPair", target.getKey());
			if (l.getOn().size() > MAX_LINK_PAIRS)
				throw new ReportException("rb.error.tooManyLinkPairs", MAX_LINK_PAIRS);
			if (l.getOn().size() != resolved.getPairs().size())
				throw new ReportException("rb.error.invalidLink", alias);
			for (int i = 0; i < l.getOn().size(); i++) {
				LinkSpec.Pair p = l.getOn().get(i);
				String[] r = resolved.getPairs().get(i);
				if (!p.isComplete())
					throw new ReportException("rb.error.linkNeedsPair", target.getKey());
				if (!p.getLeft().equals(r[0]) || !p.getRight().equals(r[1]))
					throw new ReportException("rb.error.invalidLink", alias);
				ReportField left = field(ds, p.getLeft());
				String leftAlias = LinkSpec.aliasOf(p.getLeft());
				if (leftAlias != null && !earlier.contains(leftAlias))
					throw new ReportException("rb.error.unknownField", p.getLeft());
				ReportField right = field(target, p.getRight());
				for (ReportField f : new ReportField[] { left, right })
					if (f.isViaCollection() || !f.isFilterable())
						throw new ReportException("rb.error.notFilterable", f.getPath());
				if (!comparable(left.getType(), right.getType()))
					throw new ReportException("rb.error.linkTypes", left.getPath(), l.fieldPrefix() + right.getPath());
			}
			earlier.add(alias);
		}
	}

	/** Pairs of a link compare numbers with numbers (an id may be a double on one side and a long on the other). */
	static boolean comparable(FieldType a, FieldType b) {
		if (a == b)
			return true;
		if (a.isNumeric() && b.isNumeric())
			return true;
		return (a == FieldType.STRING || a == FieldType.ENUM) && (b == FieldType.STRING || b == FieldType.ENUM)
				|| a.isTemporal() && b.isTemporal();
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

	/** Rules that restrict the whole result: reached from the root through AND groups only. */
	static void collectAnded(FilterNode node, List<FilterNode> out) {
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
			if (c.getDatePart() != null)
				return FieldType.DATE;
			return f == null ? FieldType.LONG : f.getType();
		}
	}
}
