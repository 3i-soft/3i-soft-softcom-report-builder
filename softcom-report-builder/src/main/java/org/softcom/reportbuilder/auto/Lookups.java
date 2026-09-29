package org.softcom.reportbuilder.auto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;
import javax.persistence.metamodel.SingularAttribute;

import org.softcom.reportbuilder.engine.JpaIds;
import org.softcom.reportbuilder.engine.ValueConverter;
import org.softcom.reportbuilder.spi.ReportLookup;

/**
 * Finds which records an id field points to and which text attributes name
 * them, so conditions offer "choose the supplier" instead of a number:
 * <ul>
 * <li>the id of a related entity ({@code supplier.id});</li>
 * <li>a plain number column named after an entity ({@code warehouse_id},
 * {@code toWarehouseId}), as generalWarehouse keeps on its invoice lines -
 * such columns also show names in results.</li>
 * </ul>
 * The name is the entity's {@code name}, {@code <entity>Name},
 * {@code firstName} + {@code lastName}, another {@code ...Name}, a title or a
 * description; an entity without one gets no pick list.
 */
final class Lookups {

	/** Prefixes of a second link to the same entity: towarehouse_id, fromWarehouseId, parentItemId... */
	private static final List<String> LINK_PREFIXES = Arrays.asList("to", "from", "source", "target", "src", "dest", "parent",
			"main", "default", "original", "old", "new");
	private static final List<String> NOT_A_NAME = Arrays.asList("user", "file", "image", "path", "class", "table", "column",
			"host", "server");

	private final Map<String, EntityType<?>> byName = new HashMap<>();
	private final Map<Class<?>, ReportLookup> cache = new HashMap<>();
	private final EntityDiscovery.Options options;

	Lookups(Metamodel metamodel, EntityDiscovery.Options options) {
		this.options = options;
		for (EntityType<?> t : metamodel.getEntities())
			if (t.getName() != null)
				byName.put(t.getName().toLowerCase(Locale.ROOT), t);
	}

	/** A pick list of the entity's records, or null (no single id, no name, not offered). */
	ReportLookup of(EntityType<?> type) {
		if (cache.containsKey(type.getJavaType()))
			return cache.get(type.getJavaType());
		ReportLookup l = null;
		String id = JpaIds.singleIdName(type);
		if (id != null && EntityDiscovery.offeredForLookup(type, options)) {
			Map<String, String> texts = textAttributes(type);
			List<String> label = labelAttributes(type.getName(), texts);
			if (!label.isEmpty())
				l = new ReportLookup(type.getJavaType(), id, label, codeAttribute(type.getName(), texts, label), false);
		}
		cache.put(type.getJavaType(), l);
		return l;
	}

	/**
	 * A plain number attribute named after an entity ({@code warehouse_id},
	 * {@code towarehouse_id}, {@code itemId}): a pick list, and names in
	 * results. Null when no entity matches.
	 */
	ReportLookup forColumn(String attribute, Class<?> javaType) {
		Class<?> c = ValueConverter.wrap(javaType);
		if (!Number.class.isAssignableFrom(c))
			return null;
		String lower = attribute.toLowerCase(Locale.ROOT);
		String base;
		if (lower.endsWith("_id"))
			base = lower.substring(0, lower.length() - 3);
		else if (attribute.endsWith("Id") && attribute.length() > 2)
			base = lower.substring(0, lower.length() - 2);
		else
			return null;
		base = base.replace("_", "");
		EntityType<?> t = byName.get(base);
		for (int i = 0; t == null && i < LINK_PREFIXES.size(); i++)
			if (base.startsWith(LINK_PREFIXES.get(i)) && base.length() > LINK_PREFIXES.get(i).length())
				t = byName.get(base.substring(LINK_PREFIXES.get(i).length()));
		if (t == null)
			return null;
		ReportLookup l = of(t);
		return l == null ? null : l.namesInResults(true);
	}

	/** lower-case name -&gt; name of the entity's own text attributes (never password-like ones). */
	private static Map<String, String> textAttributes(EntityType<?> type) {
		Map<String, String> m = new LinkedHashMap<>();
		List<Attribute<?, ?>> attributes = new ArrayList<>(type.getAttributes());
		attributes.sort((a, b) -> a.getName().compareTo(b.getName()));
		for (Attribute<?, ?> a : attributes)
			if (a instanceof SingularAttribute && a.getPersistentAttributeType() == Attribute.PersistentAttributeType.BASIC
					&& a.getJavaType() == String.class && !EntityDiscovery.isSensitive(a.getName())
					&& EntityDiscovery.fieldType(a) != null && EntityDiscovery.codeKindOf(a) == null)
				m.put(a.getName().toLowerCase(Locale.ROOT), a.getName());
		return m;
	}

	static List<String> labelAttributes(String entity, Map<String, String> texts) {
		String e = entity.toLowerCase(Locale.ROOT);
		for (String n : Arrays.asList("name", e + "name", e + "_name", "arabicname", "namear", "arname", "name_ar", "fullname",
				"full_name"))
			if (texts.containsKey(n))
				return Collections.singletonList(texts.get(n));
		if (texts.containsKey("firstname"))
			return texts.containsKey("lastname") ? Arrays.asList(texts.get("firstname"), texts.get("lastname"))
					: Collections.singletonList(texts.get("firstname"));
		for (Map.Entry<String, String> t : texts.entrySet())
			if (t.getKey().endsWith("name") && !containsAny(t.getKey(), NOT_A_NAME))
				return Collections.singletonList(t.getValue());
		for (String n : Arrays.asList("title", "description", "label"))
			if (texts.containsKey(n))
				return Collections.singletonList(texts.get(n));
		return Collections.emptyList();
	}

	static String codeAttribute(String entity, Map<String, String> texts, List<String> label) {
		String e = entity.toLowerCase(Locale.ROOT);
		for (String n : Arrays.asList("code", e + "code", e + "_code", "number", e + "number", e + "_number", "barcode"))
			if (texts.containsKey(n) && !label.contains(texts.get(n)))
				return texts.get(n);
		return null;
	}

	private static boolean containsAny(String s, List<String> parts) {
		for (String p : parts)
			if (s.contains(p))
				return true;
		return false;
	}
}
