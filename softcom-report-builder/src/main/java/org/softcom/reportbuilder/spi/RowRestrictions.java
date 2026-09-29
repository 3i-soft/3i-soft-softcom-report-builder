package org.softcom.reportbuilder.spi;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The rows the current user may see, filled by the application's
 * {@link ReportRowFilter}s for one report query. Every restriction is ANDed
 * around the report's own conditions, so no user condition can widen it.
 */
public final class RowRestrictions implements Serializable {

	private static final long serialVersionUID = 1L;

	/** How many relations away a row may be linked to a restricted entity (invoice line -&gt; invoice -&gt; warehouse). */
	public static final int MAX_HOPS = 2;

	private final String user;
	private final transient Predicate<String> roleCheck;
	private final Map<String, Object> attributes;
	private final List<EntityRule> entityRules = new ArrayList<>();
	private final List<ValueRule> valueRules = new ArrayList<>();
	private final List<CustomRule> customRules = new ArrayList<>();
	private boolean denyAll;

	public RowRestrictions(String user, Predicate<String> roleCheck, Map<String, Object> attributes) {
		this.user = user;
		this.roleCheck = roleCheck;
		this.attributes = attributes == null ? Collections.<String, Object>emptyMap()
				: Collections.unmodifiableMap(new HashMap<>(attributes));
	}

	/** The user running the report (as given by the application's ReportSecurity). */
	public String getCurrentUser() {
		return user;
	}

	public boolean isUserInRole(String role) {
		return roleCheck != null && role != null && roleCheck.test(role);
	}

	/** Values from the application's ReportAttributesProvider beans. */
	public Map<String, Object> getAttributes() {
		return attributes;
	}

	/**
	 * Rows of {@code entity} (or a subclass) and rows linked to it through
	 * many-to-one / one-to-one relations are limited to these ids. Only the
	 * nearest links count (at most {@link #MAX_HOPS} relations away): an
	 * invoice is allowed when its warehouse <em>or</em> its destination
	 * warehouse is allowed; a row whose links are all empty is not shown.
	 * Rows not linked to the entity at all (e.g. items) are not limited.
	 *
	 * @param ids null = no limit; empty = no linked row at all
	 */
	public RowRestrictions allowOnly(Class<?> entity, Collection<?> ids) {
		if (entity == null)
			throw new IllegalArgumentException("entity is required");
		if (ids != null)
			entityRules.add(new EntityRule(entity, ids));
		return this;
	}

	/**
	 * Rows having one or more of these simple attributes (e.g. a copied
	 * {@code warehouse_id} number column) are limited to those where at least
	 * one of them is in {@code values}. Entities without any of them are not
	 * limited.
	 *
	 * @param values null = no limit; empty = no such row at all
	 */
	public RowRestrictions allowOnlyValues(Collection<?> values, String... attributes) {
		if (attributes == null || attributes.length == 0)
			throw new IllegalArgumentException("attribute names are required");
		if (values != null)
			valueRules.add(new ValueRule(Arrays.asList(attributes), values));
		return this;
	}

	/** Any other condition, for data sources whose root entity is {@code rootEntity} or one of its subclasses. */
	public RowRestrictions where(Class<?> rootEntity, ForcedFilter filter) {
		if (rootEntity == null || filter == null)
			throw new IllegalArgumentException("rootEntity and filter are required");
		customRules.add(new CustomRule(rootEntity, filter));
		return this;
	}

	/** No row at all (e.g. no logged-in user). */
	public RowRestrictions denyAll() {
		this.denyAll = true;
		return this;
	}

	public boolean isDenyAll() {
		return denyAll;
	}

	public List<EntityRule> getEntityRules() {
		return Collections.unmodifiableList(entityRules);
	}

	public List<ValueRule> getValueRules() {
		return Collections.unmodifiableList(valueRules);
	}

	public List<CustomRule> getCustomRules() {
		return Collections.unmodifiableList(customRules);
	}

	public boolean isEmpty() {
		return !denyAll && entityRules.isEmpty() && valueRules.isEmpty() && customRules.isEmpty();
	}

	/** See {@link RowRestrictions#allowOnly}. */
	public static final class EntityRule implements Serializable {
		private static final long serialVersionUID = 1L;
		private final Class<?> entity;
		private final List<Object> ids;

		EntityRule(Class<?> entity, Collection<?> ids) {
			this.entity = entity;
			this.ids = Collections.unmodifiableList(new ArrayList<Object>(ids));
		}

		public Class<?> getEntity() {
			return entity;
		}

		public List<Object> getIds() {
			return ids;
		}
	}

	/** See {@link RowRestrictions#allowOnlyValues}. */
	public static final class ValueRule implements Serializable {
		private static final long serialVersionUID = 1L;
		private final List<String> attributes;
		private final List<Object> values;

		ValueRule(List<String> attributes, Collection<?> values) {
			this.attributes = Collections.unmodifiableList(new ArrayList<>(attributes));
			this.values = Collections.unmodifiableList(new ArrayList<Object>(values));
		}

		public List<String> getAttributes() {
			return attributes;
		}

		public List<Object> getValues() {
			return values;
		}
	}

	/** See {@link RowRestrictions#where}. */
	public static final class CustomRule implements Serializable {
		private static final long serialVersionUID = 1L;
		private final Class<?> rootEntity;
		private final ForcedFilter filter;

		CustomRule(Class<?> rootEntity, ForcedFilter filter) {
			this.rootEntity = rootEntity;
			this.filter = filter;
		}

		public Class<?> getRootEntity() {
			return rootEntity;
		}

		public ForcedFilter getFilter() {
			return filter;
		}
	}
}
