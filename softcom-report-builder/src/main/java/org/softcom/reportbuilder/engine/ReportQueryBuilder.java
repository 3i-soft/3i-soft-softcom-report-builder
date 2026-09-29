package org.softcom.reportbuilder.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Selection;
import javax.persistence.Tuple;

import org.eclipse.persistence.jpa.JpaCriteriaBuilder;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.DatePart;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SortSpec;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportBuilderConfig;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Builds one JPA Criteria {@code Tuple} query from a validated definition:
 * only the selected columns are fetched (no entities are loaded), totals are
 * computed by the database (GROUP BY), and the forced filter is ANDed around
 * the user's conditions. The grand total and subtotal queries share the same
 * joins, restrictions and conditions.
 */
public final class ReportQueryBuilder {

	/** SQL of a month / year column; {@code ?} is the date. Literal text, so GROUP BY and SELECT stay identical. */
	static final String DEFAULT_MONTH_SQL = "CAST(DATE_TRUNC('month', ?) AS DATE)";
	static final String DEFAULT_YEAR_SQL = "CAST(DATE_TRUNC('year', ?) AS DATE)";

	/** The built query plus the metadata of its columns. */
	public static final class Built {
		private final CriteriaQuery<Tuple> query;
		private final List<ResultColumn> columns;
		private final int[] positions;

		Built(CriteriaQuery<Tuple> query, List<ResultColumn> columns, int[] positions) {
			this.query = query;
			this.columns = columns;
			this.positions = positions;
		}

		public CriteriaQuery<Tuple> getQuery() {
			return query;
		}

		public List<ResultColumn> getColumns() {
			return columns;
		}

		/**
		 * Totals and subtotal queries select only some columns: for each report
		 * column, its position in the query's tuple, or -1. Null = all, in order.
		 */
		int[] getPositions() {
			return positions;
		}
	}

	private ReportQueryBuilder() {
	}

	/** The report's rows. */
	public static Built build(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run) {
		Base b = new Base(em, ds, spec, run);
		boolean grouped = spec.isGrouped();
		int subtotalColumn = spec.subtotalColumn();
		List<Selection<?>> selections = new ArrayList<>();
		List<Expression<?>> columnExpressions = new ArrayList<>();
		List<Expression<?>> groupBy = new ArrayList<>();
		List<ResultColumn> columns = new ArrayList<>();

		for (int i = 0; i < spec.getColumns().size(); i++) {
			ColumnSpec c = spec.getColumns().get(i);
			Aggregate agg = aggregate(c);
			ReportField f = b.field(c);
			Expression<?> e;
			if (agg != Aggregate.NONE) {
				e = b.aggregate(f, agg);
			} else {
				e = b.value(c, f, grouped);
				if (grouped)
					groupBy.add(e);
			}
			// no alias: Tuple values are read by position, and a field may be selected twice
			selections.add(e);
			columnExpressions.add(e);
			FieldType type = SpecValidator.resultType(c, f);
			columns.add(new ResultColumn(i, c.getField(), agg, type, c.getLabel(),
					f == null ? "عدد السجلات" : f.getLabelAr(),
					f == null ? "Row count" : f.getLabelEn(), f == null || !keepsFormat(agg) ? null : f.getFormat(),
					agg == Aggregate.NONE ? c.getDatePart() : null));
		}
		b.cq.multiselect(selections);
		b.where(null);

		if (grouped && !groupBy.isEmpty())
			b.cq.groupBy(groupBy);

		// Stable order is required for correct paging: user sort first, then a unique tie-breaker.
		CriteriaBuilder cb = b.cb;
		List<Order> orders = new ArrayList<>();
		Set<Integer> sorted = new HashSet<>();
		if (subtotalColumn >= 0) {
			// a subtotal row follows the last row of each value: the rows are ordered by that column first
			boolean desc = false;
			for (SortSpec s : spec.getSort())
				if (s.getColumn() == subtotalColumn) {
					desc = s.isDescending();
					break;
				}
			Expression<?> e = columnExpressions.get(subtotalColumn);
			orders.add(desc ? cb.desc(e) : cb.asc(e));
			sorted.add(subtotalColumn);
		}
		for (SortSpec s : spec.getSort()) {
			if (!sorted.add(s.getColumn()))
				continue;
			Expression<?> e = columnExpressions.get(s.getColumn());
			orders.add(s.isDescending() ? cb.desc(e) : cb.asc(e));
		}
		if (grouped) {
			for (int i = 0; i < spec.getColumns().size(); i++)
				if (!spec.getColumns().get(i).isAggregated() && !sorted.contains(i))
					orders.add(cb.asc(columnExpressions.get(i)));
		} else if (ds.getGrainPath() != null) {
			orders.add(cb.asc(b.ctx.join(ds.getGrainPath()).get(ds.getGrainIdAttribute())));
		} else {
			String id = idAttribute(em, ds.getRootEntity());
			if (id != null)
				orders.add(cb.asc(b.root.get(id)));
		}
		if (!orders.isEmpty())
			b.cq.orderBy(orders);
		return new Built(b.cq, columns, null);
	}

	/**
	 * The grand total over the whole result (not the page): each aggregated
	 * column over all rows (an average of all rows, not of the groups), and in
	 * a plain list the sum of the columns that can be summed.
	 *
	 * @return null when no column has a total
	 */
	static Built totals(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run) {
		Base b = new Base(em, ds, spec, run);
		boolean grouped = spec.isGrouped();
		int[] positions = new int[spec.getColumns().size()];
		List<Selection<?>> selections = new ArrayList<>();
		for (int i = 0; i < spec.getColumns().size(); i++) {
			ColumnSpec c = spec.getColumns().get(i);
			ReportField f = b.field(c);
			Aggregate agg = aggregate(c);
			Expression<?> e = null;
			if (agg != Aggregate.NONE)
				e = b.aggregate(f, agg);
			else if (!grouped && summable(f) && c.getDatePart() == null)
				e = b.aggregate(f, Aggregate.SUM);
			positions[i] = e == null ? -1 : selections.size();
			if (e != null)
				selections.add(e);
		}
		if (selections.isEmpty())
			return null;
		b.cq.multiselect(selections);
		b.where(null);
		return new Built(b.cq, null, positions);
	}

	/** In a plain list, the grand total sums the report's own numbers (not ids or codes). */
	static boolean summable(ReportField f) {
		return f != null && f.getType().isNumeric() && f.isAggregatable() && !f.hasChoices();
	}

	/**
	 * Subtotals of the given values of the {@link ReportSpec#subtotalColumn()}:
	 * that column plus every aggregated column, grouped by it.
	 */
	static Built subtotals(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run,
			Collection<Object> keys) {
		int by = spec.subtotalColumn();
		Base b = new Base(em, ds, spec, run);
		ColumnSpec byColumn = spec.getColumns().get(by);
		Expression<?> key = b.value(byColumn, b.field(byColumn), true);
		int[] positions = new int[spec.getColumns().size()];
		List<Selection<?>> selections = new ArrayList<>();
		for (int i = 0; i < spec.getColumns().size(); i++) {
			ColumnSpec c = spec.getColumns().get(i);
			Aggregate agg = aggregate(c);
			Expression<?> e = i == by ? key : agg == Aggregate.NONE ? null : b.aggregate(b.field(c), agg);
			positions[i] = e == null ? -1 : selections.size();
			if (e != null)
				selections.add(e);
		}
		b.cq.multiselect(selections);
		// only the values of this page / chunk
		List<Object> values = new ArrayList<>();
		boolean withNull = false;
		for (Object k : keys) {
			if (k == null)
				withNull = true;
			else
				values.add(k);
		}
		Predicate in = values.isEmpty() ? null : key.in(values);
		b.where(!withNull ? in : in == null ? key.isNull() : b.cb.or(in, key.isNull()));
		b.cq.groupBy(Collections.<Expression<?>>singletonList(key));
		return new Built(b.cq, null, positions);
	}

	/** Root, grain join and conditions shared by the report's queries. */
	private static final class Base {
		final EntityManager em;
		final ReportDataSource ds;
		final ReportSpec spec;
		final ReportRunContext run;
		final CriteriaBuilder cb;
		final CriteriaQuery<Tuple> cq;
		final Root<?> root;
		final QueryContextImpl ctx;

		Base(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run) {
			this.em = em;
			this.ds = ds;
			this.spec = spec;
			this.run = run;
			this.cb = em.getCriteriaBuilder();
			this.cq = cb.createTupleQuery();
			this.root = cq.from(ds.getRootEntity());
			this.ctx = new QueryContextImpl(cb, cq, root, ds, run);
			// The grain join defines what one row is; create it whatever columns/conditions are used, otherwise
			// "row count" and totals of root fields would change meaning with the selected columns.
			if (ds.getGrainPath() != null)
				ctx.join(ds.getGrainPath());
		}

		ReportField field(ColumnSpec c) {
			return ReportDataSource.ROW_COUNT_FIELD.equals(c.getField()) ? null : ds.getField(c.getField());
		}

		/** A non-aggregated column: the field, or its day / month / year. */
		Expression<?> value(ColumnSpec c, ReportField f, boolean grouped) {
			Expression<?> p = ctx.field(f);
			DatePart part = c.getDatePart();
			if (part == DatePart.MONTH || part == DatePart.YEAR)
				return truncate(p, part);
			// A DATE field may be stored as a timestamp: group (and count distinct) by its day.
			if (part == DatePart.DAY || (grouped && f.getType() == FieldType.DATE))
				return day(p);
			return p;
		}

		@SuppressWarnings({ "unchecked", "rawtypes" })
		Expression<?> aggregate(ReportField f, Aggregate agg) {
			if (f == null)
				return cb.count(root);
			Expression<?> p = ctx.field(f);
			switch (agg) {
			case SUM:
				return cb.sum((Expression<Number>) (Expression) p);
			case AVG:
				return cb.avg((Expression<Number>) (Expression) p);
			case MIN:
				return f.getType().isNumeric() ? cb.min((Expression<Number>) (Expression) p)
						: cb.least((Expression<Comparable>) (Expression) p);
			case MAX:
				return f.getType().isNumeric() ? cb.max((Expression<Number>) (Expression) p)
						: cb.greatest((Expression<Comparable>) (Expression) p);
			case COUNT:
				return cb.count(p);
			case COUNT_DISTINCT:
				return cb.countDistinct(f.getType() == FieldType.DATE ? day(p) : p);
			default:
				throw new IllegalArgumentException(String.valueOf(agg));
			}
		}

		private Expression<?> day(Expression<?> p) {
			return cb.function(ds.getDayFunction(), java.util.Date.class, p);
		}

		/**
		 * First day of the month / year. The unit is written into the SQL text
		 * (EclipseLink would send a Criteria literal as a bind parameter, and
		 * PostgreSQL does not accept "GROUP BY date_trunc($1, d)" for "SELECT
		 * date_trunc($2, d)"). The SQL comes from configuration, never from the
		 * report definition.
		 */
		private Expression<?> truncate(Expression<?> p, DatePart part) {
			String sql = part == DatePart.MONTH
					? ReportBuilderConfig.get(ReportBuilderConfig.MONTH_SQL, DEFAULT_MONTH_SQL)
					: ReportBuilderConfig.get(ReportBuilderConfig.YEAR_SQL, DEFAULT_YEAR_SQL);
			JpaCriteriaBuilder jcb = (JpaCriteriaBuilder) cb;
			org.eclipse.persistence.expressions.Expression date = jcb.toExpression(p);
			return jcb.fromExpression(date.sql(sql, new ArrayList<>()), java.util.Date.class);
		}

		/** Forced filter, row restrictions and the user's conditions (plus {@code extra}). */
		void where(Predicate extra) {
			List<Predicate> where = new ArrayList<>();
			if (ds.getForcedFilter() != null) {
				List<Predicate> forced = ds.getForcedFilter().build(ctx);
				if (forced != null)
					for (Predicate p : forced)
						if (p != null)
							where.add(p);
			}
			// the application's row restrictions (e.g. the user's warehouses), for every data source
			where.addAll(RowRestrictionApplier.predicates(em, ctx, ds, run == null ? null : run.getRestrictions()));
			Predicate user = new PredicateBuilder(cb, ctx, ds).build(spec.getFilter());
			if (user != null)
				where.add(user);
			if (extra != null)
				where.add(extra);
			if (!where.isEmpty())
				cq.where(where.toArray(new Predicate[where.size()]));
		}
	}

	private static Aggregate aggregate(ColumnSpec c) {
		return c.getAggregate() == null ? Aggregate.NONE : c.getAggregate();
	}

	/** COUNT/AVG results are not in the field's unit/format (e.g. a date pattern on a count). */
	private static boolean keepsFormat(Aggregate agg) {
		return agg == Aggregate.NONE || agg == Aggregate.MIN || agg == Aggregate.MAX || agg == Aggregate.SUM;
	}

	private static String idAttribute(EntityManager em, Class<?> entity) {
		try {
			return JpaIds.singleIdName(em.getMetamodel().entity(entity));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
