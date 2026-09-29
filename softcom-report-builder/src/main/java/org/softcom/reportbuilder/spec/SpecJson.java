package org.softcom.reportbuilder.spec;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonException;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonWriter;

import org.softcom.reportbuilder.engine.ReportException;

/**
 * Reads/writes {@link ReportSpec} as JSON using JSON-P (javax.json, part of
 * Java EE 7), so the library adds no Jackson/Gson version to the host
 * application.
 */
public final class SpecJson {

	/** Guards against absurd payloads; real definitions are a few KB. */
	public static final int MAX_LENGTH = 256 * 1024;
	public static final int MAX_DEPTH = 6;

	private SpecJson() {
	}

	public static String toJson(ReportSpec spec) {
		JsonObjectBuilder root = Json.createObjectBuilder();
		root.add("version", spec.getVersion());
		addIfNotNull(root, "dataSource", spec.getDataSource());
		JsonArrayBuilder cols = Json.createArrayBuilder();
		for (ColumnSpec c : spec.getColumns()) {
			JsonObjectBuilder o = Json.createObjectBuilder();
			addIfNotNull(o, "field", c.getField());
			if (c.isAggregated())
				o.add("aggregate", c.getAggregate().name());
			addIfNotEmpty(o, "label", c.getLabel());
			if (c.getDatePart() != null)
				o.add("datePart", c.getDatePart().name());
			cols.add(o);
		}
		root.add("columns", cols);
		if (spec.getFilter() != null)
			root.add("filter", node(spec.getFilter()));
		JsonArrayBuilder sort = Json.createArrayBuilder();
		for (SortSpec s : spec.getSort())
			sort.add(Json.createObjectBuilder().add("column", s.getColumn()).add("desc", s.isDescending()));
		root.add("sort", sort);
		if (spec.isTotals())
			root.add("totals", true);
		if (spec.isSubtotals())
			root.add("subtotals", true);
		StringWriter sw = new StringWriter();
		try (JsonWriter w = Json.createWriter(sw)) {
			w.writeObject(root.build());
		}
		return sw.toString();
	}

	private static JsonObjectBuilder node(FilterNode n) {
		JsonObjectBuilder o = Json.createObjectBuilder();
		if (n.isGroup()) {
			o.add("logic", n.getLogic().name());
			JsonArrayBuilder children = Json.createArrayBuilder();
			if (n.getChildren() != null)
				for (FilterNode c : n.getChildren())
					children.add(node(c));
			o.add("children", children);
		} else {
			addIfNotNull(o, "id", n.getId());
			addIfNotNull(o, "field", n.getField());
			if (n.getOperator() != null)
				o.add("operator", n.getOperator().name());
			addIfNotNull(o, "value", n.getValue());
			addIfNotNull(o, "value2", n.getValue2());
			if (n.getValues() != null) {
				JsonArrayBuilder vals = Json.createArrayBuilder();
				for (String v : n.getValues())
					if (v != null)
						vals.add(v);
				o.add("values", vals);
			}
			if (n.isAskAtRun())
				o.add("askAtRun", true);
			addIfNotEmpty(o, "label", n.getLabel());
		}
		return o;
	}

	public static ReportSpec fromJson(String json) {
		if (json == null || json.trim().isEmpty())
			throw new ReportException("rb.error.invalidDefinition", "empty");
		if (json.length() > MAX_LENGTH)
			throw new ReportException("rb.error.invalidDefinition", "too large");
		JsonObject root;
		try (JsonReader r = Json.createReader(new StringReader(json))) {
			root = r.readObject();
		} catch (JsonException | IllegalStateException e) {
			throw new ReportException(e, "rb.error.invalidDefinition", e.getMessage());
		}
		try {
			ReportSpec spec = new ReportSpec();
			spec.setVersion(root.getInt("version", ReportSpec.CURRENT_VERSION));
			spec.setDataSource(string(root, "dataSource"));
			List<ColumnSpec> columns = new ArrayList<>();
			for (JsonObject c : objects(root, "columns")) {
				ColumnSpec col = new ColumnSpec(string(c, "field"), enumValue(Aggregate.class, string(c, "aggregate"), Aggregate.NONE));
				col.setLabel(string(c, "label"));
				col.setDatePart(enumValue(DatePart.class, string(c, "datePart"), null));
				columns.add(col);
			}
			spec.setColumns(columns);
			if (root.containsKey("filter") && !root.isNull("filter"))
				spec.setFilter(parseNode(root.getJsonObject("filter"), 1));
			List<SortSpec> sort = new ArrayList<>();
			for (JsonObject s : objects(root, "sort"))
				sort.add(new SortSpec(s.getInt("column", 0), s.getBoolean("desc", false)));
			spec.setSort(sort);
			spec.setTotals(root.getBoolean("totals", false));
			spec.setSubtotals(root.getBoolean("subtotals", false));
			return spec;
		} catch (ClassCastException | NullPointerException e) {
			throw new ReportException(e, "rb.error.invalidDefinition", "unexpected structure");
		}
	}

	private static FilterNode parseNode(JsonObject o, int depth) {
		if (depth > MAX_DEPTH)
			throw new ReportException("rb.error.filterTooDeep", MAX_DEPTH);
		FilterNode n = new FilterNode();
		String logic = string(o, "logic");
		if (logic != null) {
			n.setLogic(enumValue(FilterNode.Logic.class, logic, FilterNode.Logic.AND));
			List<FilterNode> children = new ArrayList<>();
			for (JsonObject c : objects(o, "children"))
				children.add(parseNode(c, depth + 1));
			n.setChildren(children);
		} else {
			n.setId(string(o, "id"));
			n.setField(string(o, "field"));
			n.setOperator(enumValue(Operator.class, string(o, "operator"), null));
			n.setValue(string(o, "value"));
			n.setValue2(string(o, "value2"));
			if (o.containsKey("values") && !o.isNull("values")) {
				List<String> values = new ArrayList<>();
				for (JsonValue v : o.getJsonArray("values"))
					values.add(scalar(v));
				n.setValues(values);
			}
			n.setAskAtRun(o.getBoolean("askAtRun", false));
			n.setLabel(string(o, "label"));
		}
		return n;
	}

	private static List<JsonObject> objects(JsonObject o, String name) {
		List<JsonObject> list = new ArrayList<>();
		if (!o.containsKey(name) || o.isNull(name))
			return list;
		JsonArray arr = o.getJsonArray(name);
		for (JsonValue v : arr)
			if (v.getValueType() == JsonValue.ValueType.OBJECT)
				list.add((JsonObject) v);
		return list;
	}

	private static String string(JsonObject o, String name) {
		if (!o.containsKey(name) || o.isNull(name))
			return null;
		return scalar(o.get(name));
	}

	private static String scalar(JsonValue v) {
		switch (v.getValueType()) {
		case STRING:
			return ((JsonString) v).getString();
		case NUMBER:
			return ((JsonNumber) v).bigDecimalValue().toPlainString();
		case TRUE:
			return "true";
		case FALSE:
			return "false";
		case NULL:
			return null;
		default:
			throw new ReportException("rb.error.invalidDefinition", "scalar expected");
		}
	}

	private static <E extends Enum<E>> E enumValue(Class<E> type, String name, E defaultValue) {
		if (name == null || name.isEmpty())
			return defaultValue;
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			throw new ReportException("rb.error.invalidDefinition", type.getSimpleName() + " " + name);
		}
	}

	private static void addIfNotNull(JsonObjectBuilder o, String name, String value) {
		if (value != null)
			o.add(name, value);
	}

	private static void addIfNotEmpty(JsonObjectBuilder o, String name, String value) {
		if (value != null && !value.trim().isEmpty())
			o.add(name, value);
	}
}
