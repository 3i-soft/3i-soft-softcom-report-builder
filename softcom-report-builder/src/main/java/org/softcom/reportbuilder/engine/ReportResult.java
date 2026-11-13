package org.softcom.reportbuilder.engine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One page of a report result. There is deliberately no row count (it costs a
 * second full query); a grand total is computed only when the report asks for
 * it, and only with the first page.
 */
public class ReportResult implements Serializable {

	private static final long serialVersionUID = 1L;

	private final List<ResultColumn> columns;
	private final List<Object[]> rows;
	private final int first;
	private final boolean hasMore;
	private final long durationMs;
	private final List<ResultRow> displayRows;
	private final ResultRow totals;
	/** Column index -&gt; id key -&gt; name, for columns of ids shown by name (see ReportLookup). */
	private Map<Integer, Map<String, String>> names = Collections.emptyMap();
	/** Every row of a result read whole (a pivot table), paged in memory; null = only this page was read. */
	private List<Object[]> allRows;
	private ResultRow allTotals;

	public ReportResult(List<ResultColumn> columns, List<Object[]> rows, int first, boolean hasMore, long durationMs) {
		this(columns, rows, null, null, first, hasMore, durationMs);
	}

	/**
	 * @param displayRows the data rows with the subtotal rows among them; null =
	 *                    the data rows
	 * @param totals      the grand total row, or null
	 */
	public ReportResult(List<ResultColumn> columns, List<Object[]> rows, List<ResultRow> displayRows, ResultRow totals,
			int first, boolean hasMore, long durationMs) {
		this.columns = columns;
		this.rows = rows;
		this.first = first;
		this.hasMore = hasMore;
		this.durationMs = durationMs;
		if (displayRows == null) {
			displayRows = new ArrayList<>(rows.size());
			for (Object[] r : rows)
				displayRows.add(ResultRow.data(r));
		}
		this.displayRows = displayRows;
		this.totals = totals;
	}

	public List<ResultColumn> getColumns() {
		return columns;
	}

	public List<Object[]> getRows() {
		return rows;
	}

	/** The rows to show: data rows and subtotal rows (the grand total is {@link #getTotals()}). */
	public List<ResultRow> getDisplayRows() {
		return displayRows;
	}

	/** The grand total row (first page only), or null. */
	public ResultRow getTotals() {
		return totals;
	}

	/** Names of the ids of a column (key: {@code LookupQueries.key(value)}), or null. */
	public Map<String, String> getNames(int column) {
		return names.get(column);
	}

	void setNames(Map<Integer, Map<String, String>> names) {
		this.names = names == null ? Collections.<Integer, Map<String, String>>emptyMap() : names;
	}

	public int getFirst() {
		return first;
	}

	public boolean isHasMore() {
		return hasMore;
	}

	public long getDurationMs() {
		return durationMs;
	}

	/**
	 * A result read whole (a pivot table): its pages are cut from memory by
	 * {@link #page(int, int)}, without asking the database again.
	 */
	static ReportResult complete(List<ResultColumn> columns, List<Object[]> allRows, ResultRow totals,
			Map<Integer, Map<String, String>> names, long durationMs, int first, int pageSize) {
		ReportResult whole = new ReportResult(columns, Collections.<Object[]>emptyList(), 0, false, durationMs);
		whole.allRows = allRows;
		whole.allTotals = totals;
		whole.setNames(names);
		return whole.page(first, pageSize);
	}

	/** Whether every row is held (see {@link #page(int, int)}). */
	public boolean isComplete() {
		return allRows != null;
	}

	/** Another page of a complete result, from memory; null when the result was not read whole. */
	public ReportResult page(int first, int pageSize) {
		if (allRows == null)
			return null;
		int from = Math.max(0, Math.min(first, allRows.size()));
		int to = Math.min(allRows.size(), from + Math.max(1, pageSize));
		ReportResult p = new ReportResult(columns, new ArrayList<>(allRows.subList(from, to)), null,
				from == 0 ? allTotals : null, from, to < allRows.size(), durationMs);
		p.allRows = allRows;
		p.allTotals = allTotals;
		p.names = names;
		return p;
	}

	/** The number of rows of a complete result, else -1. */
	public int getTotalRows() {
		return allRows == null ? -1 : allRows.size();
	}
}
