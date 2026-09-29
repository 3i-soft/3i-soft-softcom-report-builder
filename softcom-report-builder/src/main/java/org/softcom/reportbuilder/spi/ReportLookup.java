package org.softcom.reportbuilder.spi;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the values of an id field come from: the records of an entity, shown
 * by name. Conditions on the field offer a searchable pick list ("choose the
 * supplier") instead of a number to type, and a column of a plain id
 * ({@code warehouse_id}) shows names.
 */
public final class ReportLookup implements Serializable {

	private static final long serialVersionUID = 1L;

	private final Class<?> entity;
	private final String idAttribute;
	private final List<String> labelAttributes;
	private final String codeAttribute;
	private final boolean namesInResults;

	/**
	 * @param entity          the looked-up entity
	 * @param idAttribute     its id attribute (the field's values)
	 * @param labelAttributes one or two text attributes shown as the name
	 *                        (e.g. firstName, lastName)
	 * @param codeAttribute   a text attribute also searched and shown after the
	 *                        name (e.g. itemCode), or null
	 * @param namesInResults  columns of the field show names instead of ids
	 */
	public ReportLookup(Class<?> entity, String idAttribute, List<String> labelAttributes, String codeAttribute,
			boolean namesInResults) {
		if (entity == null || idAttribute == null || labelAttributes == null || labelAttributes.isEmpty())
			throw new IllegalArgumentException("entity, id and a label attribute are required");
		this.entity = entity;
		this.idAttribute = idAttribute;
		this.labelAttributes = Collections.unmodifiableList(new ArrayList<>(labelAttributes));
		this.codeAttribute = codeAttribute;
		this.namesInResults = namesInResults;
	}

	/** The same lookup with names shown in result columns (or not). */
	public ReportLookup namesInResults(boolean names) {
		return names == namesInResults ? this : new ReportLookup(entity, idAttribute, labelAttributes, codeAttribute, names);
	}

	public Class<?> getEntity() {
		return entity;
	}

	public String getIdAttribute() {
		return idAttribute;
	}

	public List<String> getLabelAttributes() {
		return labelAttributes;
	}

	public String getCodeAttribute() {
		return codeAttribute;
	}

	public boolean isNamesInResults() {
		return namesInResults;
	}
}
