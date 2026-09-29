package org.softcom.reportbuilder.engine;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Predicate;

import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Turns the (validated) condition tree into Criteria predicates. Every value is
 * converted to the attribute's Java type and passed to the Criteria API as an
 * object, never as SQL text.
 *
 * <p>
 * DATE fields use whole-day semantics so they also work on timestamp columns:
 * {@code = d} means {@code >= d 00:00 and < d+1 00:00}, {@code BETWEEN d1 and d2}
 * includes all of d2, and so on. This keeps the column un-wrapped, so an index
 * on it can be used.
 * </p>
 */
class PredicateBuilder {

	/** Escape character for LIKE patterns ('!' avoids backslash quoting differences between databases). */
	static final char LIKE_ESCAPE = '!';

	private final CriteriaBuilder cb;
	private final QueryContextImpl ctx;
	private final ReportDataSource ds;

	PredicateBuilder(CriteriaBuilder cb, QueryContextImpl ctx, ReportDataSource ds) {
		this.cb = cb;
		this.ctx = ctx;
		this.ds = ds;
	}

	/** @return the predicate, or null for an empty tree. */
	Predicate build(FilterNode node) {
		if (node == null)
			return null;
		if (!node.isGroup())
			return rule(node);
		List<Predicate> parts = new ArrayList<>();
		for (FilterNode child : node.getChildren()) {
			Predicate p = build(child);
			if (p != null)
				parts.add(p);
		}
		if (parts.isEmpty())
			return null;
		if (parts.size() == 1)
			return parts.get(0);
		Predicate[] array = parts.toArray(new Predicate[parts.size()]);
		return node.getLogic() == FilterNode.Logic.OR ? cb.or(array) : cb.and(array);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Predicate rule(FilterNode r) {
		ReportField f = ds.getField(r.getField());
		Expression<?> path = ctx.field(f);
		Class<?> javaType = ValueConverter.wrap(path.getJavaType());
		Operator op = r.getOperator();
		switch (op) {
		case IS_NULL:
			return path.isNull();
		case IS_NOT_NULL:
			return path.isNotNull();
		case IS_TRUE:
			return cb.equal(path, Boolean.TRUE);
		case IS_FALSE:
			return cb.equal(path, Boolean.FALSE);
		default:
			break;
		}
		if (f.getType() == FieldType.DATE || (f.getType() == FieldType.DATETIME && datesOnly(r)))
			return dateRule(r, f, (Expression) path, javaType);
		switch (op) {
		case EQ:
			return cb.equal(path, value(r.getValue(), f, javaType));
		case NE:
			return cb.notEqual(path, value(r.getValue(), f, javaType));
		case GT:
			return cb.greaterThan((Expression) path, (Comparable) value(r.getValue(), f, javaType));
		case GE:
			return cb.greaterThanOrEqualTo((Expression) path, (Comparable) value(r.getValue(), f, javaType));
		case LT:
			return cb.lessThan((Expression) path, (Comparable) value(r.getValue(), f, javaType));
		case LE:
			return cb.lessThanOrEqualTo((Expression) path, (Comparable) value(r.getValue(), f, javaType));
		case BETWEEN: {
			Comparable a = (Comparable) value(r.getValue(), f, javaType);
			Comparable b = (Comparable) value(r.getValue2(), f, javaType);
			if (a.compareTo(b) > 0)
				throw new ReportException("rb.error.rangeInverted", f.getPath());
			return cb.between((Expression) path, a, b);
		}
		case IN:
			return path.in(values(r, f, javaType));
		case NOT_IN:
			return cb.not(path.in(values(r, f, javaType)));
		case CONTAINS:
			return like(f, (Expression<String>) path, "%" + escape(text(r, f)) + "%", false);
		case NOT_CONTAINS:
			return like(f, (Expression<String>) path, "%" + escape(text(r, f)) + "%", true);
		case STARTS_WITH:
			return like(f, (Expression<String>) path, escape(text(r, f)) + "%", false);
		case ENDS_WITH:
			return like(f, (Expression<String>) path, "%" + escape(text(r, f)), false);
		default:
			throw new ReportException("rb.error.operatorNotAllowed", op, f.getPath());
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Predicate dateRule(FilterNode r, ReportField f, Expression path, Class<?> javaType) {
		switch (r.getOperator()) {
		case EQ: {
			LocalDate d = day(r.getValue(), f);
			return cb.and(ge(path, d, javaType), lt(path, d.plusDays(1), javaType));
		}
		case NE: {
			LocalDate d = day(r.getValue(), f);
			return cb.or(lt(path, d, javaType), ge(path, d.plusDays(1), javaType));
		}
		case GT:
			return ge(path, day(r.getValue(), f).plusDays(1), javaType);
		case GE:
			return ge(path, day(r.getValue(), f), javaType);
		case LT:
			return lt(path, day(r.getValue(), f), javaType);
		case LE:
			return lt(path, day(r.getValue(), f).plusDays(1), javaType);
		case BETWEEN: {
			LocalDate from = day(r.getValue(), f);
			LocalDate to = day(r.getValue2(), f);
			if (from.isAfter(to))
				throw new ReportException("rb.error.rangeInverted", f.getPath());
			return cb.and(ge(path, from, javaType), lt(path, to.plusDays(1), javaType));
		}
		case IN:
		case NOT_IN: {
			List<Predicate> days = new ArrayList<>();
			for (String v : nonBlank(r)) {
				LocalDate d = day(v, f);
				days.add(cb.and(ge(path, d, javaType), lt(path, d.plusDays(1), javaType)));
			}
			Predicate any = cb.or(days.toArray(new Predicate[days.size()]));
			return r.getOperator() == Operator.IN ? any : cb.not(any);
		}
		default:
			throw new ReportException("rb.error.operatorNotAllowed", r.getOperator(), f.getPath());
		}
	}

	/**
	 * A DATETIME rule whose values are all plain days (no time) means whole days,
	 * like a DATE field: "BETWEEN 2026-01-01 AND 2026-01-31" includes the
	 * afternoon of the 31st. Values with a time are compared exactly.
	 */
	static boolean datesOnly(FilterNode r) {
		List<String> values = new ArrayList<>();
		if (r.getOperator().getArity() < 0) {
			values.addAll(nonBlank(r));
		} else {
			values.add(r.getValue());
			if (r.getOperator().getArity() == 2)
				values.add(r.getValue2());
		}
		for (String v : values)
			if (v == null || v.trim().length() > 10)
				return false;
		return !values.isEmpty();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Predicate ge(Expression path, LocalDate day, Class<?> javaType) {
		return cb.greaterThanOrEqualTo(path, (Comparable) ValueConverter.toTemporal(ValueConverter.startOfDay(day), javaType));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Predicate lt(Expression path, LocalDate day, Class<?> javaType) {
		return cb.lessThan(path, (Comparable) ValueConverter.toTemporal(ValueConverter.startOfDay(day), javaType));
	}

	private LocalDate day(String raw, ReportField f) {
		if (SpecValidator.isBlank(raw))
			throw new ReportException("rb.error.valueRequired", f.getPath());
		try {
			LocalDateTime ldt = ValueConverter.parseDateTime(raw);
			return ldt.toLocalDate();
		} catch (RuntimeException e) {
			throw new ReportException(e, "rb.error.invalidValue", f.getPath(), raw);
		}
	}

	private Predicate like(ReportField f, Expression<String> path, String pattern, boolean negate) {
		Expression<String> target = path;
		String p = pattern;
		if (f.isCaseInsensitive()) {
			target = cb.lower(path);
			p = pattern.toLowerCase();
		}
		return negate ? cb.notLike(target, p, LIKE_ESCAPE) : cb.like(target, p, LIKE_ESCAPE);
	}

	private String text(FilterNode r, ReportField f) {
		return (String) ValueConverter.convert(r.getValue(), f, String.class);
	}

	static String escape(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 4);
		for (char c : s.toCharArray()) {
			if (c == LIKE_ESCAPE || c == '%' || c == '_')
				sb.append(LIKE_ESCAPE);
			sb.append(c);
		}
		return sb.toString();
	}

	private Object value(String raw, ReportField f, Class<?> javaType) {
		return ValueConverter.convert(raw, f, javaType);
	}

	private List<Object> values(FilterNode r, ReportField f, Class<?> javaType) {
		List<Object> list = new ArrayList<>();
		for (String v : nonBlank(r))
			list.add(ValueConverter.convert(v, f, javaType));
		if (list.isEmpty())
			throw new ReportException("rb.error.valueRequired", f.getPath());
		return list;
	}

	private static List<String> nonBlank(FilterNode r) {
		List<String> list = new ArrayList<>();
		if (r.getValues() != null)
			for (String v : r.getValues())
				if (!SpecValidator.isBlank(v))
					list.add(v.trim());
		return list;
	}
}
