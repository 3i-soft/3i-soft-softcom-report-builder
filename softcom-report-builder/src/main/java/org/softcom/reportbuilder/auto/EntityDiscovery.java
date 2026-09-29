package org.softcom.reportbuilder.auto;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.persistence.Lob;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.Version;
import javax.persistence.criteria.JoinType;
import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.IdentifiableType;
import javax.persistence.metamodel.ManagedType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.PluralAttribute;
import javax.persistence.metamodel.SingularAttribute;
import javax.persistence.metamodel.Type;

import org.softcom.reportbuilder.engine.JpaIds;
import org.softcom.reportbuilder.engine.ValueConverter;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportBuilderConfig;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Turns every JPA entity of the application into a data source, so a project
 * gets reports without writing one per table:
 * <ul>
 * <li>{@code auto.Entity}: one row per entity; its simple attributes plus
 * those of its many-to-one / one-to-one relations (joined only when a report
 * uses them) up to {@link Options#depth} levels;</li>
 * <li>{@code auto.Entity.collection}: one row per element of a one-to-many
 * collection whose element has no relation back to the entity (such lines
 * would otherwise be unreachable, e.g. Invoice.invoiceLines).</li>
 * </ul>
 * Binary/large (@Lob), version and password-like attributes are never offered.
 * Speed on large tables is enforced at run time (see
 * {@link org.softcom.reportbuilder.engine.SpeedRules}).
 */
public final class EntityDiscovery {

	private static final Logger LOG = Logger.getLogger(EntityDiscovery.class.getName());

	public static final String KEY_PREFIX = "auto.";
	public static final int MAX_FIELDS = 400;

	/** Never offered when contained in an attribute name (any case). */
	private static final String[] SENSITIVE_PARTS = { "password", "passwd", "secret", "token", "credential", "apikey",
			"privatekey" };
	/** Never offered when one of the words of an attribute name (userPin, pin_code, otp). */
	private static final Set<String> SENSITIVE_WORDS = new HashSet<>(
			Arrays.asList("pwd", "pass", "salt", "hash", "pin", "otp", "cvv"));
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	/** the library's own tables (saved reports, run log) */
	private static final String OWN_PACKAGE = "org.softcom.reportbuilder.model.";

	/** What to discover; {@link #fromConfig()} reads {@link ReportBuilderConfig}. */
	public static final class Options {
		private int depth = 2;
		private final Set<String> excluded = new HashSet<>();
		private String requiredRole;
		private int queryTimeoutSeconds = 30;

		public static Options fromConfig() {
			Options o = new Options();
			o.depth(ReportBuilderConfig.getInt(ReportBuilderConfig.AUTO_DEPTH, 2, 0, 3));
			o.requiredRole(ReportBuilderConfig.get(ReportBuilderConfig.AUTO_REQUIRED_ROLE, null));
			o.queryTimeoutSeconds(ReportBuilderConfig.getInt(ReportBuilderConfig.QUERY_TIMEOUT_SECONDS, 30, 1, 3600));
			String excluded = ReportBuilderConfig.get(ReportBuilderConfig.AUTO_EXCLUDE, "");
			for (String name : excluded.split("[,;\\s]+"))
				o.exclude(name);
			return o;
		}

		public Options depth(int depth) {
			this.depth = Math.max(0, depth);
			return this;
		}

		public Options exclude(String entityName) {
			if (entityName != null && !entityName.trim().isEmpty())
				excluded.add(entityName.trim().toLowerCase(Locale.ROOT));
			return this;
		}

		public Options requiredRole(String role) {
			this.requiredRole = role == null || role.trim().isEmpty() ? null : role.trim();
			return this;
		}

		public Options queryTimeoutSeconds(int seconds) {
			this.queryTimeoutSeconds = Math.max(1, seconds);
			return this;
		}
	}

	private EntityDiscovery() {
	}

	public static List<ReportDataSource> discover(Metamodel metamodel, LabelResolver labels, Options options) {
		List<EntityType<?>> types = new ArrayList<>(metamodel.getEntities());
		types.sort(Comparator.comparing(EntityType::getName));
		List<ReportDataSource> out = new ArrayList<>();
		for (EntityType<?> t : types) {
			if (!offered(t, options) || idName(t) == null)
				continue;
			try {
				ReportDataSource ds = entitySource(t, labels, options);
				if (ds != null)
					out.add(ds);
				for (Attribute<?, ?> a : sorted(t.getAttributes())) {
					if (a.getPersistentAttributeType() != Attribute.PersistentAttributeType.ONE_TO_MANY)
						continue;
					ReportDataSource g = collectionSource(t, (PluralAttribute<?, ?, ?>) a, labels, options);
					if (g != null)
						out.add(g);
				}
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Report builder: entity " + t.getName() + " is not offered", e);
			}
		}
		return out;
	}

	private static ReportDataSource entitySource(EntityType<?> t, LabelResolver labels, Options options) {
		ReportDataSource ds = new ReportDataSource(KEY_PREFIX + t.getName(), t.getJavaType())
				.labels(labels.entityAr(t.getName(), t.getJavaType()), labels.entityEn(t.getName(), t.getJavaType()));
		Builder b = new Builder(ds, labels, options, null);
		b.addFields(t, "", 0, null, null);
		return finish(ds, options);
	}

	/** One row per element of a one-to-many collection the element cannot navigate back from. */
	private static ReportDataSource collectionSource(EntityType<?> owner, PluralAttribute<?, ?, ?> c, LabelResolver labels,
			Options options) {
		if (!(c.getElementType() instanceof EntityType))
			return null;
		EntityType<?> element = (EntityType<?>) c.getElementType();
		String elementId = idName(element);
		if (elementId == null || !offered(element, options) || refersTo(element, owner.getJavaType()) || options.depth < 1)
			return null;
		String name = c.getName();
		if (!IDENTIFIER.matcher(name).matches())
			return null;
		String ownerAr = labels.entityAr(owner.getName(), owner.getJavaType());
		String ownerEn = labels.entityEn(owner.getName(), owner.getJavaType());
		String relAr = labels.attributeAr(owner.getName(), name, member(c));
		String relEn = labels.attributeEn(owner.getName(), name, member(c));
		ReportDataSource ds = new ReportDataSource(KEY_PREFIX + owner.getName() + "." + name, owner.getJavaType())
				.labels(combineAr(ownerAr, ownerEn, relAr, relEn), ownerEn + " - " + relEn)
				.join(name, JoinType.INNER).grain(name, elementId).joinLabels(name, relAr, relEn);
		Builder b = new Builder(ds, labels, options, name + ".");
		b.addFields(owner, "", 0, null, null);
		b.addFields(element, name + ".", 1, relAr, relEn);
		return finish(ds, options);
	}

	private static ReportDataSource finish(ReportDataSource ds, Options options) {
		if (ds.getFields().isEmpty())
			return null;
		return ds.automatic(true).requiredRole(options.requiredRole).queryTimeoutSeconds(options.queryTimeoutSeconds);
	}

	/** Adds the fields of one entity (and of its to-one relations) under a path prefix. */
	private static final class Builder {
		private final ReportDataSource ds;
		private final LabelResolver labels;
		private final Options options;
		/** In a collection data source: prefix of the element's fields; other numbers repeat per element. */
		private final String elementPrefix;

		Builder(ReportDataSource ds, LabelResolver labels, Options options, String elementPrefix) {
			this.ds = ds;
			this.labels = labels;
			this.options = options;
			this.elementPrefix = elementPrefix;
		}

		void addFields(ManagedType<?> type, String prefix, int depth, String labelPrefixAr, String labelPrefixEn) {
			String entity = type instanceof EntityType ? ((EntityType<?>) type).getName() : type.getJavaType().getSimpleName();
			for (Attribute<?, ?> a : sorted(type.getAttributes())) {
				if (ds.getFields().size() >= MAX_FIELDS)
					return;
				String name = a.getName();
				if (!IDENTIFIER.matcher(name).matches() || isSensitive(name))
					continue;
				try {
					addAttribute(a, entity, name, prefix, depth, labelPrefixAr, labelPrefixEn);
				} catch (RuntimeException e) {
					// one odd attribute (e.g. a virtual accessor) must not cost the whole entity or those pointing to it
					LOG.log(Level.FINE, "Report builder: attribute " + entity + "." + name + " is not offered", e);
				}
			}
		}

		private void addAttribute(Attribute<?, ?> a, String entity, String name, String prefix, int depth,
				String labelPrefixAr, String labelPrefixEn) {
			AnnotatedElement member = member(a);
			String ar = labels.attributeAr(entity, name, member);
			String en = labels.attributeEn(entity, name, member);
			String fullAr = labelPrefixEn == null ? ar : combineAr(labelPrefixAr, labelPrefixEn, ar, en);
			String fullEn = labelPrefixEn == null ? en : labelPrefixEn + " - " + en;
			switch (a.getPersistentAttributeType()) {
			case BASIC:
				addBasic(a, prefix + name, fullAr, fullEn);
				break;
			case MANY_TO_ONE:
			case ONE_TO_ONE:
				if (depth >= options.depth || !(a instanceof SingularAttribute))
					break;
				Type<?> target = ((SingularAttribute<?, ?>) a).getType();
				if (!(target instanceof EntityType) || !offered((EntityType<?>) target, options))
					break;
				String joinPath = prefix + name;
				ds.join(joinPath, JoinType.LEFT);
				ds.joinLabels(joinPath, fullAr, fullEn);
				addFields((EntityType<?>) target, joinPath + ".", depth + 1, fullAr, fullEn);
				break;
			default:
				// collections, embeddables and element collections are not offered as fields
			}
		}

		private void addBasic(Attribute<?, ?> a, String path, String labelAr, String labelEn) {
			FieldType type = fieldType(a);
			if (type == null)
				return;
			ReportField f = ReportField.of(path, type, labelAr, labelEn);
			boolean id = a instanceof SingularAttribute && ((SingularAttribute<?, ?>) a).isId();
			// Only the row's own numbers may be summed: a related entity's number (invoice.total on an invoice line)
			// or, in a collection data source, the owner's (repeated on each element) would be counted many times.
			boolean own = elementPrefix == null ? path.indexOf('.') < 0
					: path.startsWith(elementPrefix) && path.indexOf('.', elementPrefix.length()) < 0;
			if (id || !own)
				f.aggregatable(false);
			if (id && type.isNumeric())
				f.format("0"); // an id reads 12345, not 12,345
			if (type == FieldType.ENUM)
				f.enumValues(enumLabels(a.getJavaType(), labels));
			ds.add(f);
		}
	}

	/** The attribute's field or getter (for its annotations), or null when the provider cannot give it. */
	private static AnnotatedElement member(Attribute<?, ?> a) {
		try {
			Member m = a.getJavaMember();
			return m instanceof AnnotatedElement ? (AnnotatedElement) m : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Logical type of a simple attribute, or null when it cannot be reported on. */
	static FieldType fieldType(Attribute<?, ?> a) {
		Class<?> c = ValueConverter.wrap(a.getJavaType());
		AnnotatedElement annotated = member(a);
		if (annotated != null && (annotated.isAnnotationPresent(Lob.class) || annotated.isAnnotationPresent(Version.class)))
			return null;
		if (c == String.class)
			return FieldType.STRING;
		if (c == Integer.class || c == Short.class || c == Byte.class)
			return FieldType.INTEGER;
		if (c == Long.class || c == BigInteger.class)
			return FieldType.LONG;
		if (c == BigDecimal.class)
			return FieldType.DECIMAL;
		if (c == Double.class || c == Float.class)
			return FieldType.DOUBLE;
		if (c == Boolean.class)
			return FieldType.BOOLEAN;
		if (c.isEnum())
			return FieldType.ENUM;
		if (c == LocalDate.class || c == java.sql.Date.class)
			return FieldType.DATE;
		if (c == LocalDateTime.class || c == Timestamp.class)
			return FieldType.DATETIME;
		if (c == java.sql.Time.class)
			return null;
		if (Date.class.isAssignableFrom(c) || Calendar.class.isAssignableFrom(c)) {
			Temporal t = annotated == null ? null : annotated.getAnnotation(Temporal.class);
			if (t != null && t.value() == TemporalType.TIME)
				return null;
			return t != null && t.value() == TemporalType.DATE ? FieldType.DATE : FieldType.DATETIME;
		}
		return null;
	}

	private static Map<String, String> enumLabels(Class<?> enumType, LabelResolver labels) {
		Map<String, String> m = new LinkedHashMap<>();
		for (Object constant : enumType.getEnumConstants()) {
			String name = ((Enum<?>) constant).name();
			String ar = labels.enumAr(enumType, name);
			m.put(name, ar != null ? ar : labels.enumEn(enumType, name));
		}
		return m;
	}

	/** Arabic label of "relation - field": null when neither part is known in Arabic. */
	static String combineAr(String prefixAr, String prefixEn, String ar, String en) {
		if (prefixAr == null && ar == null)
			return null;
		return (prefixAr != null ? prefixAr : prefixEn) + " - " + (ar != null ? ar : en);
	}

	/** Not excluded - nor is any entity it inherits from - and not one of the library's own. */
	private static boolean offered(EntityType<?> t, Options options) {
		String name = t.getName();
		if (name == null || !IDENTIFIER.matcher(name).matches() || t.getJavaType().getName().startsWith(OWN_PACKAGE))
			return false;
		for (IdentifiableType<?> type = t; type != null; type = type.getSupertype())
			if (type instanceof EntityType && options.excluded.contains(((EntityType<?>) type).getName().toLowerCase(Locale.ROOT)))
				return false;
		return true;
	}

	/** Password-like attribute names, never offered. */
	public static boolean isSensitive(String attribute) {
		String lower = attribute.toLowerCase(Locale.ROOT);
		for (String part : SENSITIVE_PARTS)
			if (lower.contains(part))
				return true;
		for (String word : LabelResolver.snake(attribute).split("_"))
			if (SENSITIVE_WORDS.contains(word))
				return true;
		return false;
	}

	/** Whether the entity has a to-one relation to {@code owner} (or one of its super classes). */
	private static boolean refersTo(EntityType<?> element, Class<?> owner) {
		for (Attribute<?, ?> a : element.getAttributes()) {
			Attribute.PersistentAttributeType pt = a.getPersistentAttributeType();
			if ((pt == Attribute.PersistentAttributeType.MANY_TO_ONE || pt == Attribute.PersistentAttributeType.ONE_TO_ONE)
					&& a.getJavaType().isAssignableFrom(owner))
				return true;
		}
		return false;
	}

	/** The single, simple id attribute (needed for stable paging), or null (composite / embedded id). */
	private static String idName(EntityType<?> type) {
		return JpaIds.singleIdName(type);
	}

	/** Id first, then simple attributes, then relations; each group by name - stable field lists. */
	private static List<Attribute<?, ?>> sorted(Set<? extends Attribute<?, ?>> attributes) {
		List<Attribute<?, ?>> list = new ArrayList<>(attributes);
		list.sort(Comparator.comparingInt(EntityDiscovery::rank).thenComparing(Attribute::getName));
		return list;
	}

	private static int rank(Attribute<?, ?> a) {
		if (a instanceof SingularAttribute && ((SingularAttribute<?, ?>) a).isId())
			return 0;
		return a.getPersistentAttributeType() == Attribute.PersistentAttributeType.BASIC ? 1 : 2;
	}
}
