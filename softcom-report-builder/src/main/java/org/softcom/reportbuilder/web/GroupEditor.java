package org.softcom.reportbuilder.web;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.softcom.reportbuilder.spec.FilterNode;

/** A group of conditions combined with AND ("all") or OR ("any"). */
public class GroupEditor implements Serializable {

	private static final long serialVersionUID = 1L;

	private FilterNode.Logic logic = FilterNode.Logic.AND;
	private List<RuleEditor> rules = new ArrayList<>();

	public FilterNode.Logic getLogic() {
		return logic;
	}

	public void setLogic(FilterNode.Logic logic) {
		this.logic = logic == null ? FilterNode.Logic.AND : logic;
	}

	public List<RuleEditor> getRules() {
		return rules;
	}
}
