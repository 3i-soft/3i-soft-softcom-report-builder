package org.softcom.reportbuilder.spec;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The saved definition of a user report (serialized to JSON by
 * {@link SpecJson}). Grouping is implicit: when any column is aggregated, all
 * non-aggregated columns become the GROUP BY.
 */
public class ReportSpec implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final int CURRENT_VERSION = 1;

	private int version = CURRENT_VERSION;
	private String dataSource;
	private List<ColumnSpec> columns = new ArrayList<>();
	private FilterNode filter;
	private List<SortSpec> sort = new ArrayList<>();

	public ReportSpec copy() {
		ReportSpec s = new ReportSpec();
		s.version = version;
		s.dataSource = dataSource;
		for (ColumnSpec c : columns)
			s.columns.add(c.copy());
		s.filter = filter == null ? null : filter.copy();
		for (SortSpec o : sort)
			s.sort.add(o.copy());
		return s;
	}

	public boolean isGrouped() {
		for (ColumnSpec c : columns)
			if (c.isAggregated())
				return true;
		return false;
	}

	/** Rules whose value is asked for at run time, in definition order. */
	public List<FilterNode> getParameters() {
		if (filter == null)
			return Collections.emptyList();
		List<FilterNode> list = new ArrayList<>();
		for (FilterNode r : filter.rules())
			if (r.isAskAtRun())
				list.add(r);
		return list;
	}

	public FilterNode findRule(String id) {
		if (filter == null || id == null)
			return null;
		for (FilterNode r : filter.rules())
			if (id.equals(r.getId()))
				return r;
		return null;
	}

	public int getVersion() {
		return version;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public String getDataSource() {
		return dataSource;
	}

	public void setDataSource(String dataSource) {
		this.dataSource = dataSource;
	}

	public List<ColumnSpec> getColumns() {
		return columns;
	}

	public void setColumns(List<ColumnSpec> columns) {
		this.columns = columns == null ? new ArrayList<ColumnSpec>() : columns;
	}

	public FilterNode getFilter() {
		return filter;
	}

	public void setFilter(FilterNode filter) {
		this.filter = filter;
	}

	public List<SortSpec> getSort() {
		return sort;
	}

	public void setSort(List<SortSpec> sort) {
		this.sort = sort == null ? new ArrayList<SortSpec>() : sort;
	}
}
