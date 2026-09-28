package org.softcom.reportbuilder.spec;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A node of the condition tree: either a group ({@link #getLogic()} != null)
 * combining its children with AND / OR, or a rule on one field.
 *
 * <p>
 * Rule values are kept as canonical strings so the definition is stable JSON:
 * numbers with '.' as decimal separator, dates as {@code yyyy-MM-dd},
 * date-times as {@code yyyy-MM-dd'T'HH:mm[:ss]}, enums as the constant name.
 * </p>
 */
public class FilterNode implements Serializable {

	private static final long serialVersionUID = 1L;

	public enum Logic {
		AND, OR
	}

	// group
	private Logic logic;
	private List<FilterNode> children;

	// rule
	private String id;
	private String field;
	private Operator operator;
	private String value;
	private String value2;
	private List<String> values;
	/** When true the viewer asks for the value(s) each time the report is run. */
	private boolean askAtRun;
	/** Prompt shown for an ask-at-run rule. */
	private String label;

	public static FilterNode group(Logic logic, FilterNode... children) {
		FilterNode g = new FilterNode();
		g.logic = logic == null ? Logic.AND : logic;
		g.children = new ArrayList<>(Arrays.asList(children));
		return g;
	}

	public static FilterNode rule(String id, String field, Operator operator, String... values) {
		FilterNode r = new FilterNode();
		r.id = id;
		r.field = field;
		r.operator = operator;
		if (operator != null && operator.getArity() < 0) {
			r.values = new ArrayList<>(Arrays.asList(values));
		} else {
			if (values.length > 0)
				r.value = values[0];
			if (values.length > 1)
				r.value2 = values[1];
		}
		return r;
	}

	public boolean isGroup() {
		return logic != null;
	}

	public FilterNode copy() {
		FilterNode n = new FilterNode();
		n.logic = logic;
		if (children != null) {
			n.children = new ArrayList<>();
			for (FilterNode c : children)
				n.children.add(c.copy());
		}
		n.id = id;
		n.field = field;
		n.operator = operator;
		n.value = value;
		n.value2 = value2;
		n.values = values == null ? null : new ArrayList<>(values);
		n.askAtRun = askAtRun;
		n.label = label;
		return n;
	}

	/** All rules of this subtree, depth first. */
	public List<FilterNode> rules() {
		List<FilterNode> list = new ArrayList<>();
		collect(this, list);
		return list;
	}

	private static void collect(FilterNode node, List<FilterNode> out) {
		if (node == null)
			return;
		if (node.isGroup()) {
			if (node.children != null)
				for (FilterNode c : node.children)
					collect(c, out);
		} else {
			out.add(node);
		}
	}

	public Logic getLogic() {
		return logic;
	}

	public void setLogic(Logic logic) {
		this.logic = logic;
	}

	public List<FilterNode> getChildren() {
		if (children == null && logic != null)
			children = new ArrayList<>();
		return children;
	}

	public void setChildren(List<FilterNode> children) {
		this.children = children;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getField() {
		return field;
	}

	public void setField(String field) {
		this.field = field;
	}

	public Operator getOperator() {
		return operator;
	}

	public void setOperator(Operator operator) {
		this.operator = operator;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public String getValue2() {
		return value2;
	}

	public void setValue2(String value2) {
		this.value2 = value2;
	}

	public List<String> getValues() {
		return values;
	}

	public void setValues(List<String> values) {
		this.values = values;
	}

	public boolean isAskAtRun() {
		return askAtRun;
	}

	public void setAskAtRun(boolean askAtRun) {
		this.askAtRun = askAtRun;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}
}
