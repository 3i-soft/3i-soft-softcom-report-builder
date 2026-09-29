package org.softcom.reportbuilder.engine;

import javax.persistence.metamodel.Attribute;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;

/** Id attribute lookup that works with primitive ids. */
public final class JpaIds {

	private JpaIds() {
	}

	/**
	 * Name of the entity's single, simple id attribute (inherited ones
	 * included), or null for a composite or embedded id. Does not use
	 * {@code getId(getIdType().getJavaType())}: EclipseLink 2.6 reports a
	 * primitive id ({@code double id}, as in generalWarehouse's MainEntity) as
	 * Double and then refuses that type.
	 */
	public static String singleIdName(EntityType<?> type) {
		String found = null;
		try {
			for (SingularAttribute<?, ?> a : type.getSingularAttributes()) {
				if (!a.isId())
					continue;
				if (found != null || a.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC)
					return null;
				found = a.getName();
			}
		} catch (RuntimeException e) {
			return null;
		}
		return found;
	}
}
