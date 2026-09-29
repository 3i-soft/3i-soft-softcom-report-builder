package org.softcom.reportbuilder.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.From;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Predicate;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.PluralAttribute;
import javax.persistence.metamodel.SingularAttribute;

import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.RowRestrictions;
import org.softcom.reportbuilder.spi.RowRestrictions.CustomRule;
import org.softcom.reportbuilder.spi.RowRestrictions.EntityRule;
import org.softcom.reportbuilder.spi.RowRestrictions.ValueRule;

/**
 * Turns the application's {@link RowRestrictions} into predicates for one
 * query. The rules are applied to the root entity; in a collection data
 * source they are applied to the element (the line) only when none concerns
 * the root: an invoice limited to the user's warehouses already limits its
 * lines, and copied columns on the lines must not decide a second time.
 */
final class RowRestrictionApplier {

	private RowRestrictionApplier() {
	}

	static List<Predicate> predicates(EntityManager em, QueryContextImpl ctx, ReportDataSource ds, RowRestrictions r) {
		if (r == null || r.isEmpty())
			return Collections.emptyList();
		CriteriaBuilder cb = ctx.getCriteriaBuilder();
		List<Predicate> out = new ArrayList<>();
		if (r.isDenyAll()) {
			out.add(cb.disjunction());
			return out;
		}
		Metamodel mm = em.getMetamodel();
		EntityType<?> rootType = mm.entity(ds.getRootEntity());
		From<?, ?> root = ctx.getRoot();
		From<?, ?> element = null;
		EntityType<?> elementType = null;
		if (ds.getGrainPath() != null && ds.getGrainPath().indexOf('.') < 0) {
			Attribute<?, ?> a = rootType.getAttribute(ds.getGrainPath());
			if (a instanceof PluralAttribute && ((PluralAttribute<?, ?, ?>) a).getElementType() instanceof EntityType) {
				element = ctx.join(ds.getGrainPath());
				elementType = (EntityType<?>) ((PluralAttribute<?, ?, ?>) a).getElementType();
			}
		}
		List<Predicate> rows = linkPredicates(cb, root, rootType, r);
		if (rows.isEmpty() && element != null)
			rows = linkPredicates(cb, element, elementType, r);
		out.addAll(rows);
		for (CustomRule rule : r.getCustomRules()) {
			if (!rule.getRootEntity().isAssignableFrom(ds.getRootEntity()))
				continue;
			List<Predicate> custom = rule.getFilter().build(ctx);
			if (custom != null)
				for (Predicate p : custom)
					if (p != null)
						out.add(p);
		}
		return out;
	}

	/** The entity and value rules that concern one entity (empty when none does). */
	private static List<Predicate> linkPredicates(CriteriaBuilder cb, From<?, ?> from, EntityType<?> type, RowRestrictions r) {
		List<Predicate> out = new ArrayList<>();
		for (EntityRule rule : r.getEntityRules()) {
			Predicate p = entityPredicate(cb, from, type, rule);
			if (p != null)
				out.add(p);
		}
		for (ValueRule rule : r.getValueRules()) {
			Predicate p = valuePredicate(cb, from, type, rule);
			if (p != null)
				out.add(p);
		}
		return out;
	}

	/** Null when the entity is not the restricted one nor linked to it within MAX_HOPS relations. */
	private static Predicate entityPredicate(CriteriaBuilder cb, From<?, ?> from, EntityType<?> type, EntityRule rule) {
		Class<?> target = rule.getEntity();
		if (target.isAssignableFrom(type.getJavaType()))
			return in(cb, from.get(idOf(type)), rule.getIds());
		List<List<SingularAttribute<?, ?>>> paths = nearestPaths(type, target);
		if (paths.isEmpty())
			return null;
		List<Predicate> any = new ArrayList<>();
		for (List<SingularAttribute<?, ?>> path : paths) {
			From<?, ?> f = from;
			for (SingularAttribute<?, ?> a : path)
				f = f.join(a.getName(), JoinType.LEFT); // LEFT: an empty link must not drop rows of the other links
			any.add(in(cb, f.get(idOf((EntityType<?>) path.get(path.size() - 1).getType())), rule.getIds()));
		}
		return any.size() == 1 ? any.get(0) : cb.or(any.toArray(new Predicate[any.size()]));
	}

	/** The shortest to-one paths from {@code type} to the target entity (all of the same, smallest length). */
	static List<List<SingularAttribute<?, ?>>> nearestPaths(EntityType<?> type, Class<?> target) {
		List<List<SingularAttribute<?, ?>>> frontier = new ArrayList<>();
		List<EntityType<?>> frontierTypes = new ArrayList<>();
		frontier.add(new ArrayList<SingularAttribute<?, ?>>());
		frontierTypes.add(type);
		for (int hop = 1; hop <= RowRestrictions.MAX_HOPS; hop++) {
			List<List<SingularAttribute<?, ?>>> found = new ArrayList<>();
			List<List<SingularAttribute<?, ?>>> next = new ArrayList<>();
			List<EntityType<?>> nextTypes = new ArrayList<>();
			for (int i = 0; i < frontier.size(); i++) {
				for (SingularAttribute<?, ?> a : frontierTypes.get(i).getSingularAttributes()) {
					Attribute.PersistentAttributeType pt = a.getPersistentAttributeType();
					if ((pt != Attribute.PersistentAttributeType.MANY_TO_ONE && pt != Attribute.PersistentAttributeType.ONE_TO_ONE)
							|| !(a.getType() instanceof EntityType))
						continue;
					List<SingularAttribute<?, ?>> path = new ArrayList<>(frontier.get(i));
					path.add(a);
					EntityType<?> linked = (EntityType<?>) a.getType();
					if (target.isAssignableFrom(linked.getJavaType())) {
						found.add(path);
					} else {
						next.add(path);
						nextTypes.add(linked);
					}
				}
			}
			if (!found.isEmpty())
				return found;
			frontier = next;
			frontierTypes = nextTypes;
		}
		return Collections.emptyList();
	}

	private static Predicate valuePredicate(CriteriaBuilder cb, From<?, ?> from, EntityType<?> type, ValueRule rule) {
		List<Predicate> any = new ArrayList<>();
		for (SingularAttribute<?, ?> a : type.getSingularAttributes())
			if (a.getPersistentAttributeType() == Attribute.PersistentAttributeType.BASIC && rule.getAttributes().contains(a.getName()))
				any.add(in(cb, from.get(a.getName()), rule.getValues()));
		if (any.isEmpty())
			return null;
		return any.size() == 1 ? any.get(0) : cb.or(any.toArray(new Predicate[any.size()]));
	}

	private static String idOf(EntityType<?> type) {
		String id = JpaIds.singleIdName(type);
		if (id == null)
			throw new IllegalStateException("Row restriction on " + type.getName() + ": it needs a single simple id");
		return id;
	}

	private static Predicate in(CriteriaBuilder cb, Expression<?> path, List<Object> values) {
		if (values.isEmpty())
			return cb.disjunction();
		Class<?> type = ValueConverter.wrap(path.getJavaType());
		List<Object> converted = new ArrayList<>(values.size());
		for (Object v : values)
			converted.add(convert(v, type));
		return path.in(converted);
	}

	/** Ids given as Double (e.g. generalWarehouse) work on Long / Integer / BigDecimal columns too. */
	static Object convert(Object v, Class<?> type) {
		if (!(v instanceof Number) || type.isInstance(v))
			return v;
		Number n = (Number) v;
		if (type == Long.class)
			return n.longValue();
		if (type == Integer.class)
			return n.intValue();
		if (type == Double.class)
			return n.doubleValue();
		if (type == Float.class)
			return n.floatValue();
		if (type == Short.class)
			return n.shortValue();
		if (type == Byte.class)
			return n.byteValue();
		if (type == BigDecimal.class)
			return new BigDecimal(n.toString());
		if (type == BigInteger.class)
			return new BigDecimal(n.toString()).toBigInteger();
		return v;
	}
}
