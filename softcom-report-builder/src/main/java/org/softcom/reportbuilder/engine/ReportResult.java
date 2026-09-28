package org.softcom.reportbuilder.engine;

import java.io.Serializable;
import java.util.List;

/** One page of a report result. There is deliberately no total count (it costs a second full query). */
public class ReportResult implements Serializable {

	private static final long serialVersionUID = 1L;

	private final List<ResultColumn> columns;
	private final List<Object[]> rows;
	private final int first;
	private final boolean hasMore;
	private final long durationMs;

	public ReportResult(List<ResultColumn> columns, List<Object[]> rows, int first, boolean hasMore, long durationMs) {
		this.columns = columns;
		this.rows = rows;
		this.first = first;
		this.hasMore = hasMore;
		this.durationMs = durationMs;
	}

	public List<ResultColumn> getColumns() {
		return columns;
	}

	public List<Object[]> getRows() {
		return rows;
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
}
