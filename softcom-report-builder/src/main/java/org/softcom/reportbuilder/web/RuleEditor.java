package org.softcom.reportbuilder.web;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.softcom.reportbuilder.engine.ValueConverter;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spi.FieldType;

/** Editable form of one condition, with typed accessors for the JSF inputs. */
public class RuleEditor implements Serializable {

	private static final long serialVersionUID = 1L;

	private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

	private String id;
	private String field;
	private FieldType type;
	private Operator operator;
	private String value;
	private String value2;
	private String listText;
	private List<String> selected = new ArrayList<>();
	private boolean askAtRun;
	private String label;

	public RuleEditor() {
	}

	public RuleEditor(String id) {
		this.id = id;
	}

	/**
	 * @param pickList whether the field has a choice list: list values then live
	 *                 in {@link #getSelected()} (checkbox menu), else in
	 *                 {@link #getListText()} (comma separated text) - the same
	 *                 test the page uses to pick the input.
	 */
	public static RuleEditor from(FilterNode n, FieldType type, boolean pickList) {
		RuleEditor r = new RuleEditor(n.getId());
		r.field = n.getField();
		r.type = type;
		r.operator = n.getOperator();
		r.value = n.getValue();
		r.value2 = n.getValue2();
		if (n.getValues() != null) {
			if (pickList)
				r.selected = new ArrayList<>(n.getValues());
			else
				r.listText = String.join(", ", n.getValues());
		}
		r.askAtRun = n.isAskAtRun();
		r.label = n.getLabel();
		return r;
	}

	public FilterNode toNode() {
		FilterNode n = FilterNode.rule(id, field, operator);
		if (operator != null && operator.getArity() < 0) {
			List<String> values = new ArrayList<>();
			if (selected != null && !selected.isEmpty()) {
				values.addAll(selected);
			} else if (listText != null) {
				for (String v : listText.split("[,،\n]"))
					if (!v.trim().isEmpty())
						values.add(v.trim());
			}
			n.setValues(values);
		} else if (operator != null) {
			if (operator.getArity() >= 1)
				n.setValue(value);
			if (operator.getArity() == 2)
				n.setValue2(value2);
		}
		n.setAskAtRun(askAtRun);
		n.setLabel(label);
		return n;
	}

	/** Values as the executor expects them for an ask-at-run parameter. */
	public List<String> parameterValues() {
		return toNode().getValues() != null ? toNode().getValues() : pair();
	}

	private List<String> pair() {
		List<String> list = new ArrayList<>();
		list.add(value);
		if (operator != null && operator.getArity() == 2)
			list.add(value2);
		return list;
	}

	/** Resets the operator, values and prompt after the field changed. */
	public void resetFor(FieldType newType, Operator defaultOperator) {
		this.type = newType;
		this.operator = defaultOperator;
		clearValues();
		this.askAtRun = false;
		this.label = null;
	}

	/** After an operator change: values of the old operator no longer fit; no prompt without a value. */
	public void onOperatorChanged() {
		clearValues();
		if (operator == null || operator.getArity() == 0)
			askAtRun = false;
	}

	private void clearValues() {
		this.value = null;
		this.value2 = null;
		this.listText = null;
		this.selected = new ArrayList<>();
	}

	// -------------------------- java.util.Date bindings, for custom pages with a Date input
	// (the bundled pages bind value/value2 directly to <input type="date">, which submits yyyy-MM-dd)

	public Date getDateValue() {
		return toDate(value);
	}

	public void setDateValue(Date d) {
		value = fromDate(d);
	}

	public Date getDateValue2() {
		return toDate(value2);
	}

	public void setDateValue2(Date d) {
		value2 = fromDate(d);
	}

	private static Date toDate(String iso) {
		if (iso == null || iso.trim().isEmpty())
			return null;
		try {
			return Date.from(ValueConverter.parseDateTime(iso).atZone(ZoneId.systemDefault()).toInstant());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private String fromDate(Date d) {
		if (d == null)
			return null;
		LocalDateTime ldt = LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
		return type == FieldType.DATETIME ? ldt.format(ISO_DATE_TIME) : ldt.format(ISO_DATE);
	}

	// ------------------------------------------------------------ accessors

	public String getId() {
		return id;
	}

	public String getField() {
		return field;
	}

	public void setField(String field) {
		this.field = field;
	}

	public FieldType getType() {
		return type;
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

	public String getListText() {
		return listText;
	}

	public void setListText(String listText) {
		this.listText = listText;
	}

	public List<String> getSelected() {
		return selected;
	}

	public void setSelected(List<String> selected) {
		this.selected = selected == null ? new ArrayList<String>() : selected;
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
