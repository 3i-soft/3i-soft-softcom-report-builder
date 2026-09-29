package org.softcom.reportbuilder.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Selection;

import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportLookup;

/**
 * The records behind an id field: a search for the pick list (limited by the
 * application's row restrictions, e.g. only the user's warehouses) and the
 * names of given ids. Only the attributes chosen by the discovery are read.
 */
public final class LookupQueries {

	/** Longest list a search returns. */
	public static final int MAX_RESULTS = 30;
	/** Most ids whose names are read in one query. */
	static final int MAX_IDS = 1000;

	private LookupQueries() {
	}

	/** One record: its id (as the rules store it) and its name. */
	public static final class Item implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		private final String id;
		private final String label;

		Item(String id, String label) {
			this.id = id;
			this.label = label;
		}

		public String getId() {
			return id;
		}

		public String getLabel() {
			return label;
		}
	}

	/**
	 * Records whose name or code contains {@code text} (all when empty), sorted
	 * by name.
	 */
	public static List<Item> search(EntityManager em, ReportLookup l, String text, int max, ReportRunContext run,
			int timeoutSeconds) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Tuple> cq = cb.createTupleQuery();
		Root<?> root = cq.from(l.getEntity());
		cq.multiselect(selections(root, l));
		List<Predicate> where = new ArrayList<>();
		String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		if (!t.isEmpty()) {
			String pattern = "%" + PredicateBuilder.escape(t) + "%";
			List<Predicate> any = new ArrayList<>();
			for (String a : searched(l))
				any.add(cb.like(cb.lower(root.<String>get(a)), pattern, PredicateBuilder.LIKE_ESCAPE));
			where.add(cb.or(any.toArray(new Predicate[any.size()])));
		}
		ReportDataSource asSource = new ReportDataSource("lookup." + l.getEntity().getSimpleName(), l.getEntity());
		QueryContextImpl ctx = new QueryContextImpl(cb, cq, root, asSource, run);
		where.addAll(RowRestrictionApplier.predicates(em, ctx, asSource, run == null ? null : run.getRestrictions()));
		if (!where.isEmpty())
			cq.where(where.toArray(new Predicate[where.size()]));
		cq.orderBy(cb.asc(root.get(l.getLabelAttributes().get(0))), cb.asc(root.get(l.getIdAttribute())));
		return items(query(em, cq, Math.max(1, Math.min(max, MAX_RESULTS)), timeoutSeconds), l);
	}

	/**
	 * Names of the given ids (values of a result column, or ids stored in a
	 * rule), keyed by {@link #key(Object)}. Ids that do not exist are left out.
	 */
	public static Map<String, String> names(EntityManager em, ReportLookup l, Collection<?> ids, int timeoutSeconds) {
		Map<String, String> out = new LinkedHashMap<>();
		Class<?> idType = idType(em, l);
		Set<Object> values = new LinkedHashSet<>();
		for (Object id : ids) {
			Object v = convert(id, idType);
			if (v != null)
				values.add(v);
		}
		List<Object> list = new ArrayList<>(values);
		for (int from = 0; from < list.size(); from += MAX_IDS) {
			List<Object> part = list.subList(from, Math.min(list.size(), from + MAX_IDS));
			CriteriaBuilder cb = em.getCriteriaBuilder();
			CriteriaQuery<Tuple> cq = cb.createTupleQuery();
			Root<?> root = cq.from(l.getEntity());
			cq.multiselect(selections(root, l));
			cq.where(root.get(l.getIdAttribute()).in(part));
			for (Item i : items(query(em, cq, part.size(), timeoutSeconds), l))
				out.put(i.getId(), i.getLabel());
		}
		return out;
	}

	/** The same key for 5, 5L, 5.0 and "5": ids from rules, result columns and the entity compare equal. */
	public static String key(Object id) {
		if (id == null)
			return null;
		if (id instanceof Number || id instanceof String) {
			String s = id.toString().trim();
			try {
				return new BigDecimal(s).stripTrailingZeros().toPlainString();
			} catch (NumberFormatException e) {
				return s;
			}
		}
		return id.toString();
	}

	private static List<Selection<?>> selections(Root<?> root, ReportLookup l) {
		List<Selection<?>> s = new ArrayList<>();
		s.add(root.get(l.getIdAttribute()));
		for (String a : l.getLabelAttributes())
			s.add(root.get(a));
		if (l.getCodeAttribute() != null)
			s.add(root.get(l.getCodeAttribute()));
		return s;
	}

	private static List<String> searched(ReportLookup l) {
		List<String> a = new ArrayList<>(l.getLabelAttributes());
		if (l.getCodeAttribute() != null)
			a.add(l.getCodeAttribute());
		return a;
	}

	private static List<Tuple> query(EntityManager em, CriteriaQuery<Tuple> cq, int max, int timeoutSeconds) {
		TypedQuery<Tuple> q = em.createQuery(cq);
		q.setMaxResults(max);
		q.setHint(ReportExecutor.HINT_BIND_PARAMETERS, ReportExecutor.HINT_TRUE);
		q.setHint(ReportExecutor.HINT_JDBC_TIMEOUT_SECONDS, timeoutSeconds);
		try {
			return q.getResultList();
		} catch (PersistenceException e) {
			throw new ReportException(e, "rb.error.queryFailed");
		}
	}

	/** "name lastName (code)". */
	private static List<Item> items(List<Tuple> tuples, ReportLookup l) {
		List<Item> out = new ArrayList<>(tuples.size());
		int names = l.getLabelAttributes().size();
		for (Tuple t : tuples) {
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i <= names; i++) {
				Object v = t.get(i);
				if (v != null && !v.toString().trim().isEmpty())
					sb.append(sb.length() == 0 ? "" : " ").append(v.toString().trim());
			}
			Object code = l.getCodeAttribute() == null ? null : t.get(names + 1);
			if (code != null && !code.toString().trim().isEmpty())
				sb.append(sb.length() == 0 ? code.toString().trim() : " (" + code.toString().trim() + ")");
			String id = key(t.get(0));
			out.add(new Item(id, sb.length() == 0 ? id : sb.toString()));
		}
		return out;
	}

	private static Class<?> idType(EntityManager em, ReportLookup l) {
		return ValueConverter.wrap(em.getMetamodel().entity(l.getEntity()).getAttribute(l.getIdAttribute()).getJavaType());
	}

	/** An id of any number type or text, in the entity's id type; null when it cannot be one. */
	static Object convert(Object id, Class<?> type) {
		if (id == null)
			return null;
		String s = id.toString().trim();
		if (s.isEmpty())
			return null;
		try {
			if (type == String.class)
				return id instanceof Number ? key(id) : s;
			BigDecimal n = id instanceof BigDecimal ? (BigDecimal) id : new BigDecimal(s);
			if (type == Long.class)
				return n.longValueExact();
			if (type == Integer.class)
				return n.intValueExact();
			if (type == Short.class)
				return n.shortValueExact();
			if (type == Double.class)
				return n.doubleValue();
			if (type == Float.class)
				return n.floatValue();
			if (type == BigDecimal.class)
				return n;
			if (type == BigInteger.class)
				return n.toBigIntegerExact();
		} catch (ArithmeticException | NumberFormatException e) {
			return null;
		}
		return null;
	}
}
