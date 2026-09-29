package org.softcom.reportbuilder.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
		List<Object[]> rows = fetch(em, ds, built, start, size + 1, false);
		boolean hasMore = rows.size() > size;
		if (hasMore)
			rows = new ArrayList<>(rows.subList(0, size));
		return new ReportResult(built.getColumns(), rows, start, hasMore, System.currentTimeMillis() - t0);
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
			sink.rows(rows);
			total += rows.size();
			if (rows.size() < chunk || truncated)
				break;
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
