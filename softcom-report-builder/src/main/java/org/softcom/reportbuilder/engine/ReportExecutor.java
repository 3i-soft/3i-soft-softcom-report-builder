package org.softcom.reportbuilder.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.QueryTimeoutException;
import javax.persistence.TypedQuery;
import javax.persistence.Tuple;

import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spi.ReportBuilderConfig;
import org.softcom.reportbuilder.spi.ReportDataSource;

/**
 * Validates and runs a definition with a plain {@link EntityManager} (no
 * container needed, which is what the tests use). The EJB layer adds security
 * and run logging on top.
 */
public class ReportExecutor {

	private static final Logger LOG = Logger.getLogger(ReportExecutor.class.getName());

	public static final int MAX_PAGE_SIZE = 500;
	public static final int EXPORT_CHUNK = 5000;
	/** Deepest row offset a page may start at (a larger value would overflow EclipseLink's first+max). */
	public static final int MAX_FIRST_ROW = 10000000;

	/** EclipseLink 2.6 hint names (verified against eclipselink-2.6.0.jar QueryHints/HintValues). */
	static final String HINT_BIND_PARAMETERS = "eclipselink.jdbc.bind-parameters";
	static final String HINT_JDBC_TIMEOUT_SECONDS = "eclipselink.jdbc.timeout";
	static final String HINT_FETCH_SIZE = "eclipselink.jdbc.fetch-size";
	static final String HINT_TRUE = "True";

	/** Receives exported rows chunk by chunk. */
	public interface RowSink {
		void begin(List<ResultColumn> columns);

		void rows(List<Object[]> rows);

		/** A subtotal row, written after the last row of its value; {@code labelColumn} shows the word "subtotal". */
		default void subtotal(Object[] values, int labelColumn) {
		}

		/** The grand total row, written after the last row. */
		default void total(Object[] values, int labelColumn) {
		}

		/** Called after the last row; {@code truncated} = more rows existed than the export limit. */
		default void end(boolean truncated, int limit) {
		}
	}

	/**
	 * Fills ask-at-run rules from {@code parameters} (rule id -&gt; values: one
	 * value, two for BETWEEN, a list for IN) and returns a new spec.
	 */
	public static ReportSpec applyParameters(ReportSpec spec, Map<String, List<String>> parameters) {
		ReportSpec copy = spec.copy();
		if (parameters == null || parameters.isEmpty())
			return copy;
		for (FilterNode r : copy.getParameters()) {
			List<String> values = parameters.get(r.getId());
			if (values == null)
				continue;
			Operator op = r.getOperator();
			if (op != null && op.getArity() < 0) {
				r.setValues(new ArrayList<>(values));
			} else {
				r.setValue(values.size() > 0 ? values.get(0) : null);
				r.setValue2(values.size() > 1 ? values.get(1) : null);
			}
		}
		return copy;
	}

	public ReportResult run(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run, int first,
			int pageSize) {
		return run(em, ds, spec, run, first, pageSize, null);
	}

	/**
	 * @param speed what the database says about the data source's table (see
	 *              {@link TableStats}); null = no size rule
	 */
	public ReportResult run(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run, int first,
			int pageSize, SpeedInfo speed) {
		SpecValidator.validate(spec, ds, true);
		SpeedRules.check(spec, ds, speed, maxDateRangeDays());
		int size = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
		int start = Math.max(0, Math.min(first, MAX_FIRST_ROW));
		long t0 = System.currentTimeMillis();
		ReportQueryBuilder.Built built = ReportQueryBuilder.build(em, ds, spec, run);
		List<Object[]> fetched = fetch(em, ds, built, start, size + 1, false);
		boolean hasMore = fetched.size() > size;
		List<Object[]> rows = hasMore ? new ArrayList<>(fetched.subList(0, size)) : fetched;
		List<ResultRow> display = null;
		int by = spec.subtotalColumn();
		if (by >= 0 && !rows.isEmpty()) {
			Map<Object, Object[]> subtotals = subtotals(em, ds, spec, run, rows, by);
			int label = labelColumn(spec, by);
			display = new ArrayList<>(rows.size() + 16);
			for (int i = 0; i < rows.size(); i++) {
				Object key = rows.get(i)[by];
				display.add(ResultRow.data(rows.get(i)));
				// the extra row fetched tells whether the last value goes on over the next page
				if (i + 1 >= fetched.size() || !Objects.equals(key, fetched.get(i + 1)[by]))
					display.add(new ResultRow(subtotals.get(key), ResultRow.Kind.SUBTOTAL, label));
			}
		}
		ResultRow totals = spec.isTotals() && start == 0 ? totals(em, ds, spec, run) : null;
		return new ReportResult(built.getColumns(), rows, display, totals, start, hasMore, System.currentTimeMillis() - t0);
	}

	/** The subtotals of the values of column {@code by} in {@code rows}, as full-width rows. */
	private Map<Object, Object[]> subtotals(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run,
			List<Object[]> rows, int by) {
		Set<Object> keys = new LinkedHashSet<>();
		for (Object[] r : rows)
			keys.add(r[by]);
		ReportQueryBuilder.Built q = ReportQueryBuilder.subtotals(em, ds, spec, run, keys);
		Map<Object, Object[]> map = new HashMap<>();
		for (Object[] r : fetch(em, ds, q, 0, keys.size() + 1, false)) {
			Object[] full = spread(r, q.getPositions());
			map.put(full[by], full);
		}
		// a value always has its subtotal row, even if the database returned nothing for it
		for (Object k : keys)
			if (!map.containsKey(k)) {
				Object[] empty = new Object[spec.getColumns().size()];
				empty[by] = k;
				map.put(k, empty);
			}
		return map;
	}

	/** The grand total row, or null when no column has a total. */
	private ResultRow totals(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run) {
		ReportQueryBuilder.Built q = ReportQueryBuilder.totals(em, ds, spec, run);
		if (q == null)
			return null;
		List<Object[]> r = fetch(em, ds, q, 0, 1, false);
		Object[] values = r.isEmpty() ? new Object[spec.getColumns().size()] : spread(r.get(0), q.getPositions());
		// the word "total" goes in the first column without a total
		int label = -1;
		for (int i = 0; i < q.getPositions().length && label < 0; i++)
			if (q.getPositions()[i] < 0)
				label = i;
		return new ResultRow(values, ResultRow.Kind.TOTAL, label);
	}

	private static Object[] spread(Object[] tuple, int[] positions) {
		Object[] full = new Object[positions.length];
		for (int i = 0; i < positions.length; i++)
			if (positions[i] >= 0)
				full[i] = tuple[positions[i]];
		return full;
	}

	/** The word "subtotal" goes in the second grouping column (the first one shows the value). */
	static int labelColumn(ReportSpec spec, int by) {
		for (int i = 0; i < spec.getColumns().size(); i++)
			if (i != by && !spec.getColumns().get(i).isAggregated())
				return i;
		return -1;
	}


	/** Streams up to {@code maxRows} rows (capped by the data source) in chunks. Returns the number of rows. */
	public int export(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run, int maxRows,
			RowSink sink) {
		return export(em, ds, spec, run, maxRows, sink, null);
	}

	public int export(EntityManager em, ReportDataSource ds, ReportSpec spec, ReportRunContext run, int maxRows,
			RowSink sink, SpeedInfo speed) {
		SpecValidator.validate(spec, ds, true);
		SpeedRules.check(spec, ds, speed, maxDateRangeDays());
		int limit = Math.max(1, Math.min(maxRows <= 0 ? ds.getMaxExportRows() : maxRows, ds.getMaxExportRows()));
		ReportQueryBuilder.Built built = ReportQueryBuilder.build(em, ds, spec, run);
		sink.begin(built.getColumns());
		int by = spec.subtotalColumn();
		int label = by < 0 ? -1 : labelColumn(spec, by);
		// the subtotal of a chunk's last value is written once the next chunk shows the value has ended
		Object[] pendingSubtotal = null;
		int total = 0;
		boolean truncated = false;
		while (total < limit) {
			int chunk = Math.min(EXPORT_CHUNK, limit - total);
			boolean lastChunk = total + chunk >= limit;
			// the last chunk asks for one extra row to know whether the limit cut the result
			List<Object[]> rows = fetch(em, ds, built, total, lastChunk ? chunk + 1 : chunk, true);
			if (lastChunk && rows.size() > chunk) {
				truncated = true;
				rows = new ArrayList<>(rows.subList(0, chunk));
			}
			if (rows.isEmpty())
				break;
			if (by < 0) {
				sink.rows(rows);
			} else {
				if (pendingSubtotal != null && !Objects.equals(pendingSubtotal[by], rows.get(0)[by]))
					sink.subtotal(pendingSubtotal, label);
				Map<Object, Object[]> subtotals = subtotals(em, ds, spec, run, rows, by);
				int from = 0;
				for (int i = 0; i + 1 < rows.size(); i++)
					if (!Objects.equals(rows.get(i)[by], rows.get(i + 1)[by])) {
						sink.rows(rows.subList(from, i + 1));
						sink.subtotal(subtotals.get(rows.get(i)[by]), label);
						from = i + 1;
					}
				sink.rows(rows.subList(from, rows.size()));
				pendingSubtotal = subtotals.get(rows.get(rows.size() - 1)[by]);
			}
			total += rows.size();
			if (rows.size() < chunk || truncated)
				break;
		}
		// a value cut by the export limit gets no subtotal (it would not match its visible rows)
		if (pendingSubtotal != null && !truncated)
			sink.subtotal(pendingSubtotal, label);
		if (spec.isTotals() && total > 0) {
			ResultRow t = totals(em, ds, spec, run);
			if (t != null)
				sink.total(t.getValues(), t.getLabelColumn());
		}
		sink.end(truncated, limit);
		return total;
	}

	private List<Object[]> fetch(EntityManager em, ReportDataSource ds, ReportQueryBuilder.Built built, int first,
			int max, boolean export) {
		TypedQuery<Tuple> q = em.createQuery(built.getQuery());
		q.setFirstResult(first);
		q.setMaxResults(max);
		// Bind values as JDBC parameters even when the persistence unit disables binding globally.
		q.setHint(HINT_BIND_PARAMETERS, HINT_TRUE);
		q.setHint(HINT_JDBC_TIMEOUT_SECONDS, ds.getQueryTimeoutSeconds());
		if (export)
			q.setHint(HINT_FETCH_SIZE, Math.min(max, 500));
		try {
			List<Tuple> tuples = q.getResultList();
			List<Object[]> rows = new ArrayList<>(tuples.size());
			for (Tuple t : tuples)
				rows.add(t.toArray());
			return rows;
		} catch (QueryTimeoutException e) {
			throw new ReportException(e, "rb.error.timeout", ds.getQueryTimeoutSeconds());
		} catch (PersistenceException e) {
			if (isTimeout(e))
				throw new ReportException(e, "rb.error.timeout", ds.getQueryTimeoutSeconds());
			LOG.log(Level.WARNING, "Report query failed on data source " + ds.getKey(), e);
			throw new ReportException(e, "rb.error.queryFailed");
		}
	}

	/** Longest date range of a fast condition on a large table ({@link ReportBuilderConfig#MAX_DATE_RANGE_DAYS}). */
	public static int maxDateRangeDays() {
		return ReportBuilderConfig.getInt(ReportBuilderConfig.MAX_DATE_RANGE_DAYS, 366, 1, 36600);
	}

	/** Row count from which a table needs a fast condition ({@link ReportBuilderConfig#LARGE_TABLE_ROWS}). */
	public static long largeTableRows() {
		return ReportBuilderConfig.getInt(ReportBuilderConfig.LARGE_TABLE_ROWS, 200000, 1, Integer.MAX_VALUE);
	}

	/** PostgreSQL reports a statement timeout / cancel with SQLState 57014. */
	private static boolean isTimeout(Throwable e) {
		for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
			if (t instanceof java.sql.SQLException && "57014".equals(((java.sql.SQLException) t).getSQLState()))
				return true;
			if (t instanceof java.sql.SQLTimeoutException)
				return true;
		}
		return false;
	}
}
