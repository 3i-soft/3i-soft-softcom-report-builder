package org.softcom.reportbuilder.engine;

import java.util.ArrayList;
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

import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SortSpec;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Builds one JPA Criteria {@code Tuple} query from a validated definition:
 * only the selected columns are fetched (no entities are loaded), totals are
 * computed by the database (GROUP BY), and the forced filter is ANDed around
 * the user's conditions.
 */
public final class ReportQueryBuilder {

	/** The built query plus the metadata of its columns. */
	public static final class Built {
		private final CriteriaQuery<Tuple> query;
		private final List<ResultColumn> columns;

		Built(CriteriaQuery<Tuple> query, List<ResultColumn> columns) {
			this.query = query;
			this.columns = columns;
		}

		public CriteriaQuery<Tuple> getQuery() {
			return query;
		}

		public List<ResultColumn> getColumns() {
			return columns;
		}
	}

	private ReportQueryBuilder() {
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Built build(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Tuple> cq = cb.createTupleQuery();
		Root<?> root = cq.from(ds.getRootEntity());
		QueryContextImpl ctx = new QueryContextImpl(cb, cq, root, ds, run);
		// The grain join defines what one row is; create it whatever columns/conditions are used, otherwise
		// "row count" and totals of root fields would change meaning with the selected columns.
		if (ds.getGrainPath() != null)
			ctx.join(ds.getGrainPath());

		boolean grouped = spec.isGrouped();
		List<Selection<?>> selections = new ArrayList<>();
		List<Expression<?>> columnExpressions = new ArrayList<>();
		List<Expression<?>> groupBy = new ArrayList<>();
		List<ResultColumn> columns = new ArrayList<>();

		for (int i = 0; i < spec.getColumns().size(); i++) {
			ColumnSpec c = spec.getColumns().get(i);
			Aggregate agg = c.getAggregate() == null ? Aggregate.NONE : c.getAggregate();
			Expression<?> e;
			ReportField f = null;
			if (ReportDataSource.ROW_COUNT_FIELD.equals(c.getField())) {
				e = cb.count(root);
			} else {
				f = ds.getField(c.getField());
				Expression<?> p = ctx.field(f);
				// A DATE field may be stored as a timestamp: group (and count distinct) by its day.
				Expression<?> day = f.getType() == FieldType.DATE ? cb.function(ds.getDayFunction(), java.util.Date.class, p) : p;
				switch (agg) {
				case SUM:
					e = cb.sum((Expression<Number>) (Expression) p);
					break;
				case AVG:
					e = cb.avg((Expression<Number>) (Expression) p);
					break;
				case MIN:
					e = f.getType().isNumeric() ? cb.min((Expression<Number>) (Expression) p)
							: cb.least((Expression<Comparable>) (Expression) p);
					break;
				case MAX:
					e = f.getType().isNumeric() ? cb.max((Expression<Number>) (Expression) p)
							: cb.greatest((Expression<Comparable>) (Expression) p);
					break;
				case COUNT:
					e = cb.count(p);
					break;
				case COUNT_DISTINCT:
					e = cb.countDistinct(day);
					break;
				default:
					e = grouped ? day : p;
					if (grouped)
						groupBy.add(e);
				}
			}
			// no alias: Tuple values are read by position, and a field may be selected twice
			selections.add(e);
			columnExpressions.add(e);
			FieldType type = SpecValidator.resultType(c, f);
			columns.add(new ResultColumn(i, c.getField(), agg, type, c.getLabel(),
					f == null ? "عدد السجلات" : f.getLabelAr(),
					f == null ? "Row count" : f.getLabelEn(), f == null || !keepsFormat(agg) ? null : f.getFormat()));
		}
		cq.multiselect(selections);

		List<Predicate> where = new ArrayList<>();
		if (ds.getForcedFilter() != null) {
			List<Predicate> forced = ds.getForcedFilter().build(ctx);
			if (forced != null)
				for (Predicate p : forced)
					if (p != null)
						where.add(p);
		}
		Predicate user = new PredicateBuilder(cb, ctx, ds).build(spec.getFilter());
		if (user != null)
			where.add(user);
		if (!where.isEmpty())
			cq.where(where.toArray(new Predicate[where.size()]));

		if (grouped && !groupBy.isEmpty())
			cq.groupBy(groupBy);

		// Stable order is required for correct paging: user sort first, then a unique tie-breaker.
		List<Order> orders = new ArrayList<>();
		Set<Integer> sorted = new HashSet<>();
		for (SortSpec s : spec.getSort()) {
			Expression<?> e = columnExpressions.get(s.getColumn());
			orders.add(s.isDescending() ? cb.desc(e) : cb.asc(e));
			sorted.add(s.getColumn());
		}
		if (grouped) {
			for (int i = 0; i < spec.getColumns().size(); i++)
				if (!spec.getColumns().get(i).isAggregated() && !sorted.contains(i))
					orders.add(cb.asc(columnExpressions.get(i)));
		} else if (ds.getGrainPath() != null) {
			orders.add(cb.asc(ctx.join(ds.getGrainPath()).get(ds.getGrainIdAttribute())));
		} else {
			String id = idAttribute(em, ds.getRootEntity());
			if (id != null)
				orders.add(cb.asc(root.get(id)));
		}
		if (!orders.isEmpty())
			cq.orderBy(orders);
		return new Built(cq, columns);
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
