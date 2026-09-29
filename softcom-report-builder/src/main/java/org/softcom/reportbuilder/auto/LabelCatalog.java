package org.softcom.reportbuilder.auto;

import java.io.Serializable;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.PluralAttribute;
import javax.persistence.metamodel.SingularAttribute;

/**
 * Everything the automatic data sources name: tables, fields (once per
 * declaring class: a field of a shared base class is named once for all its
 * tables) and enum values, with the label each one gets without the labels
 * page. Used by the labels page.
 */
public final class LabelCatalog {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	public enum Kind {
		TABLE, FIELD, VALUE
	}

	/** One name. {@code autoAr} is null when no Arabic label was found. */
	public static final class Entry implements Serializable {
		private static final long serialVersionUID = 1L;
		private final String key;
		private final Kind kind;
		private final String owner;
		private final String autoAr;
		private final String autoEn;

		Entry(String key, Kind kind, String owner, String autoAr, String autoEn) {
			this.key = key;
			this.kind = kind;
			this.owner = owner;
			this.autoAr = autoAr;
			this.autoEn = autoEn;
		}

		public String getKey() {
			return key;
		}

		public Kind getKind() {
			return kind;
		}

		/** The table (or shared base class, or enum type) the name belongs to. */
		public String getOwner() {
			return owner;
		}

		public String getAutoAr() {
			return autoAr;
		}

		public String getAutoEn() {
			return autoEn;
		}
	}

	private LabelCatalog() {
	}

	/** @param labels the labels without the labels page (to show what is missing) */
	public static List<Entry> entries(Metamodel metamodel, LabelResolver labels, EntityDiscovery.Options options) {
		List<EntityType<?>> types = new ArrayList<>(metamodel.getEntities());
		types.sort(Comparator.comparing(EntityType::getName));
		Map<String, Entry> out = new LinkedHashMap<>();
		for (EntityType<?> t : types) {
			if (!EntityDiscovery.isOffered(t, options))
				continue;
			String entity = t.getName();
			out.put(entity, new Entry(entity, Kind.TABLE, entity, labels.entityAr(entity, t.getJavaType()),
					labels.entityEn(entity, t.getJavaType())));
			List<Attribute<?, ?>> attributes = new ArrayList<>(t.getAttributes());
			attributes.sort(Comparator.comparing(Attribute::getName));
			for (Attribute<?, ?> a : attributes) {
				String name = a.getName();
				if (!IDENTIFIER.matcher(name).matches() || EntityDiscovery.isSensitive(name) || !named(a))
					continue;
				AnnotatedElement member = member(a);
				String key = LabelResolver.attributeKey(entity, name, member);
				if (!out.containsKey(key))
					out.put(key, new Entry(key, Kind.FIELD, key.substring(0, key.lastIndexOf('.')),
							labels.attributeAr(entity, name, member), labels.attributeEn(entity, name, member)));
				Class<?> type = a.getJavaType();
				if (a.getPersistentAttributeType() == Attribute.PersistentAttributeType.BASIC && type.isEnum())
					for (Object constant : type.getEnumConstants()) {
						String c = ((Enum<?>) constant).name();
						String enumKey = type.getSimpleName() + "." + c;
						if (!out.containsKey(enumKey))
							out.put(enumKey, new Entry(enumKey, Kind.VALUE, type.getSimpleName(), labels.enumAr(type, c),
									labels.enumEn(type, c)));
					}
			}
		}
		return new ArrayList<>(out.values());
	}

	/** Attributes that show up in reports: simple values, to-one relations and collections of entities. */
	private static boolean named(Attribute<?, ?> a) {
		switch (a.getPersistentAttributeType()) {
		case BASIC:
			return a instanceof SingularAttribute && EntityDiscovery.fieldType(a) != null;
		case MANY_TO_ONE:
		case ONE_TO_ONE:
			return true;
		case ONE_TO_MANY:
		case MANY_TO_MANY:
			return a instanceof PluralAttribute && ((PluralAttribute<?, ?, ?>) a).getElementType() instanceof EntityType;
		default:
			return false;
		}
	}

	private static AnnotatedElement member(Attribute<?, ?> a) {
		try {
			Member m = a.getJavaMember();
			return m instanceof AnnotatedElement ? (AnnotatedElement) m : null;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
