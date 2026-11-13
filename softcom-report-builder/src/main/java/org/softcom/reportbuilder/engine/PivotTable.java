package org.softcom.reportbuilder.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.softcom.reportbuilder.export.ReportFormatter;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.PivotSpec;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spi.ReportDataSource;

/**
 * A report shown as a pivot table ({@link PivotSpec}), built in memory from
 * one grouped query: GROUP BY the report's columns and the pivot field, with
 * the cells' aggregate. Rows are the report's columns' values, then one column
 * per value of the pivot field (in ascending order), then the optional row
 * total.
 */
final class PivotTable {

	/** Most groups (row x column cells with data) the query may return: a pivot is read whole, then paged. */
	static final int MAX_CELLS = 100000;
	/** Most columns (distinct values of the pivot field). */
	static final int MAX_COLUMNS = 100;
	/** Most rows x columns held in memory (the whole table is kept by the page for paging). */
	static final int MAX_SLOTS = 1000000;

	final List<ResultColumn> columns;
	final List<Object[]> rows;
	/** The total row (null when not asked for or meaningless, see {@link PivotSpec#hasTotals}). */
	final Object[] totals;

	private PivotTable(List<ResultColumn> columns, List<Object[]> rows, Object[] totals) {
		this.columns = columns;
		this.rows = rows;
		this.totals = totals;
	}

	/** The grouped query behind the pivot: the report's columns, the pivot field, the cells' aggregate. */
	static ReportSpec groupedSpec(ReportSpec spec) {
		ReportSpec g = spec.copy();
		PivotSpec p = g.getPivot();
		g.setPivot(null);
		g.setTotals(false);
		g.setSubtotals(false);
		g.getColumns().add(new ColumnSpec(p.getField(), Aggregate.NONE));
		g.getColumns().add(new ColumnSpec(p.getValue(), p.getAggregate()));
		return g;
	}

	/**
	 * @param built       the columns of {@link #groupedSpec}'s query
	 * @param tuples      its rows, ordered by the report's columns
	 * @param headerNames names of the pivot field's ids (supplier id -&gt; name), or null
	 */
	static PivotTable of(List<ResultColumn> built, List<Object[]> tuples, ReportSpec spec, ReportDataSource ds,
			Map<String, String> headerNames) {
		PivotSpec p = spec.getPivot();
		int keys = spec.getColumns().size();
		ResultColumn fieldColumn = built.get(keys);
		ResultColumn valueColumn = built.get(keys + 1);

		// the columns: the pivot field's values, ascending, empty last
		TreeMap<Object, Integer> headers = new TreeMap<>(PivotTable::compareValues);
		boolean hasNull = false;
		for (Object[] t : tuples) {
			if (t[keys] == null)
				hasNull = true;
			else
				headers.put(normalize(t[keys]), 0);
		}
		int columnCount = headers.size() + (hasNull ? 1 : 0);
		if (columnCount > MAX_COLUMNS)
			throw new ReportException("rb.error.pivotTooManyColumns", MAX_COLUMNS, p.getField());
		List<Object> headerValues = new ArrayList<>(headers.keySet());
		if (hasNull)
			headerValues.add(null);
		Map<Object, Integer> position = new LinkedHashMap<>();
		for (int i = 0; i < headerValues.size(); i++)
			position.put(headerValues.get(i), keys + i);

		boolean totalsMeaningful = PivotSpec.hasTotals(p.getAggregate());
		boolean rowTotal = p.isRowTotal() && totalsMeaningful;
		int width = keys + headerValues.size() + (rowTotal ? 1 : 0);

		List<ResultColumn> columns = new ArrayList<>(built.subList(0, keys));
		for (int i = 0; i < headerValues.size(); i++) {
			Object v = headerValues.get(i);
			String label = v == null ? "-" : ReportFormatter.format(v, fieldColumn, ds, null, headerNames);
			columns.add(new ResultColumn(keys + i, valueColumn.getField(), p.getAggregate(), valueColumn.getType(), label,
					null, null, valueColumn.getFormat()));
		}
		if (rowTotal)
			columns.add(new ResultColumn(width - 1, valueColumn.getField(), p.getAggregate(), valueColumn.getType(), null,
					"الإجمالي", "Total", valueColumn.getFormat()));

		// one row per value of the report's columns (the query returns them together, in the report's order)
		Map<List<Object>, Object[]> byKey = new LinkedHashMap<>();
		for (Object[] t : tuples) {
			List<Object> key = Arrays.asList(Arrays.copyOf(t, keys));
			Object[] row = byKey.get(key);
			if (row == null) {
				row = new Object[width];
				System.arraycopy(t, 0, row, 0, keys);
				byKey.put(key, row);
			}
			Object v = t[keys];
			row[position.get(v == null ? null : normalize(v))] = t[keys + 1];
		}
		List<Object[]> rows = new ArrayList<>(byKey.values());
		if ((long) rows.size() * width > MAX_SLOTS)
			throw new ReportException("rb.error.pivotTooLarge", MAX_SLOTS);
		if (rowTotal)
			for (Object[] row : rows)
				for (int c = keys; c < width - 1; c++)
					row[width - 1] = combine(row[width - 1], row[c], p.getAggregate());

		Object[] totals = null;
		if (spec.isTotals() && totalsMeaningful) {
			totals = new Object[width];
			for (Object[] row : rows)
				for (int c = keys; c < width; c++)
					totals[c] = combine(totals[c], row[c], p.getAggregate());
		}
		return new PivotTable(columns, rows, totals);
	}

	/** 10.50 and 10.5 are the same column. */
	private static Object normalize(Object v) {
		return v instanceof BigDecimal ? ((BigDecimal) v).stripTrailingZeros() : v;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static int compareValues(Object a, Object b) {
		if (a instanceof Number && b instanceof Number)
			return decimal((Number) a).compareTo(decimal((Number) b));
		if (a instanceof Comparable && a.getClass() == b.getClass())
			return ((Comparable) a).compareTo(b);
		return Comparator.<String>naturalOrder().compare(String.valueOf(a), String.valueOf(b));
	}

	/** The total of two cells: sum (of sums or counts), least or greatest; empty cells are skipped. */
	static Object combine(Object a, Object b, Aggregate agg) {
		if (a == null)
			return b;
		if (b == null)
			return a;
		switch (agg) {
		case SUM:
		case COUNT:
			if (a instanceof BigDecimal || b instanceof BigDecimal)
				return decimal((Number) a).add(decimal((Number) b));
			if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float)
				return ((Number) a).doubleValue() + ((Number) b).doubleValue();
			return ((Number) a).longValue() + ((Number) b).longValue();
		case MIN:
			return compareValues(a, b) <= 0 ? a : b;
		case MAX:
			return compareValues(a, b) >= 0 ? a : b;
		default:
			return null;
		}
	}

	private static BigDecimal decimal(Number n) {
		return n instanceof BigDecimal ? (BigDecimal) n : new BigDecimal(n.toString());
	}
}
