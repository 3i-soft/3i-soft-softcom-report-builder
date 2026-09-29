package org.softcom.reportbuilder.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.softcom.persistence.IPersistenceHelper;
import org.softcom.reportbuilder.engine.ReportException;
import org.softcom.reportbuilder.engine.ReportExecutor;
import org.softcom.reportbuilder.engine.ReportResult;
import org.softcom.reportbuilder.engine.ReportRunContext;
import org.softcom.reportbuilder.engine.SpeedInfo;
import org.softcom.reportbuilder.export.ExcelReportExporter;
import org.softcom.reportbuilder.model.ReportDefinition;
import org.softcom.reportbuilder.model.ReportRunLog;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spi.ReportAttributesProvider;
import org.softcom.reportbuilder.spi.ReportDataSource;

/**
 * Entry point used by the JSF beans: permission checks, execution on the host
 * application's EntityManager, and run logging. The values handed to forced
 * filters ({@link ReportAttributesProvider}) are resolved here on the server,
 * never taken from the caller.
 */
@Stateless
public class ReportService {

	private static final Logger LOG = Logger.getLogger(ReportService.class.getName());

	@EJB
	private IPersistenceHelper persistenceHelper;

	@EJB
	private ReportRunLogger runLogger;

	@EJB
	private ReportDefinitionFacade definitions;

	@EJB
	private ReportSpeedService speedService;

	@Inject
	private ReportCatalog catalog;

	@Inject
	private ReportSecurity security;

	@Inject
	@Any
	private Instance<ReportAttributesProvider> attributeProviders;

	private final ReportExecutor executor = new ReportExecutor();

	/** Runs an unsaved definition from the designer. */
	public ReportResult preview(ReportSpec spec, Map<String, List<String>> parameters, int first, int pageSize) {
		if (!security.canDesign())
			throw new ReportException("rb.error.accessDenied");
		QueryGate.enter();
		try {
			return execute(ReportRunLog.Kind.PREVIEW, null, spec, parameters, first, pageSize);
		} finally {
			QueryGate.leave();
		}
	}

	/** Runs a saved report the current user can see. */
	public ReportResult run(Long definitionId, Map<String, List<String>> parameters, int first, int pageSize) {
		// the slot is taken before any database access: waiting must not hold a pooled connection
		QueryGate.enter();
		try {
			ReportDefinition d = definitions.findVisible(definitionId);
			return execute(ReportRunLog.Kind.RUN, d.getId(), definitions.spec(d), parameters, first, pageSize);
		} finally {
			QueryGate.leave();
		}
	}

	/**
	 * Writes a saved report (or, for designers, an unsaved spec when
	 * definitionId is null) as .xlsx. Runs without a JTA transaction: it only
	 * reads, and a long export must not hit the transaction timeout.
	 *
	 * @return the export outcome (rows written, whether the export limit cut it)
	 */
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public ExportOutcome exportExcel(Long definitionId, ReportSpec unsaved, Map<String, List<String>> parameters, String title,
			Locale locale, OutputStream out) throws IOException {
		QueryGate.enter();
		try {
			return export(definitionId, unsaved, parameters, title, locale, out);
		} finally {
			QueryGate.leave();
		}
	}

	private ExportOutcome export(Long definitionId, ReportSpec unsaved, Map<String, List<String>> parameters, String title,
			Locale locale, OutputStream out) throws IOException {
		ReportSpec spec;
		if (definitionId != null) {
			spec = definitions.spec(definitions.findVisible(definitionId));
		} else {
			if (!security.canDesign())
				throw new ReportException("rb.error.accessDenied");
			spec = unsaved;
		}
		ReportDataSource ds = dataSource(spec);
		ReportSpec filled = ReportExecutor.applyParameters(spec, parameters);
		Date started = new Date();
		long t0 = System.currentTimeMillis();
		int rows = 0;
		Throwable error = null;
		ExcelReportExporter exporter = new ExcelReportExporter(ds, title, locale);
		SpeedInfo speed = speedOf(ds);
		try {
			rows = executor.export(persistenceHelper.getEntityManager(), ds, filled, context(), 0, exporter, speed);
			exporter.write(out);
			return new ExportOutcome(rows, exporter.isTruncated(), ds.getMaxExportRows());
		} catch (RuntimeException | IOException e) {
			error = e;
			throw e;
		} finally {
			exporter.dispose();
			log(ReportRunLog.Kind.EXPORT, definitionId, ds.getKey(), started, System.currentTimeMillis() - t0, rows, error);
		}
	}

	private ReportResult execute(ReportRunLog.Kind kind, Long definitionId, ReportSpec spec,
			Map<String, List<String>> parameters, int first, int pageSize) {
		ReportDataSource ds = dataSource(spec);
		Date started = new Date();
		long t0 = System.currentTimeMillis();
		ReportResult result = null;
		Throwable error = null;
		SpeedInfo speed = speedOf(ds);
		try {
			result = executor.run(persistenceHelper.getEntityManager(), ds, ReportExecutor.applyParameters(spec, parameters),
					context(), first, pageSize, speed);
			return result;
		} catch (RuntimeException e) {
			error = e;
			throw e;
		} finally {
			log(kind, definitionId, ds.getKey(), started, System.currentTimeMillis() - t0,
					result == null ? 0 : result.getRows().size(), error);
		}
	}

	/** Size and fast fields of a data source the user may use (for the designer's hints). */
	public SpeedInfo speed(String dataSourceKey) {
		if (!security.canRun())
			return SpeedInfo.UNKNOWN;
		ReportDataSource ds = catalog.get(dataSourceKey);
		return ReportCatalog.isAllowed(ds, security) ? speedOf(ds) : SpeedInfo.UNKNOWN;
	}

	/** Table statistics are a hint: whatever goes wrong reading them (another provider, a class missing...), the report runs. */
	private SpeedInfo speedOf(ReportDataSource ds) {
		try {
			SpeedInfo s = speedService.speed(ds);
			return s == null ? SpeedInfo.UNKNOWN : s;
		} catch (RuntimeException | LinkageError e) {
			LOG.log(Level.FINE, "No table statistics for " + ds.getKey(), e);
			return SpeedInfo.UNKNOWN;
		}
	}

	/** Logging must never replace the report result or its real error. */
	private void log(ReportRunLog.Kind kind, Long definitionId, String dataSourceKey, Date started, long durationMs, int rows,
			Throwable error) {
		try {
			runLogger.log(kind, definitionId, dataSourceKey, security.getCurrentUser(), started, durationMs, rows, error);
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Could not write report run log", e);
		}
	}

	private ReportDataSource dataSource(ReportSpec spec) {
		if (!security.canRun())
			throw new ReportException("rb.error.accessDenied");
		ReportDataSource ds = catalog.get(spec.getDataSource());
		if (!ReportCatalog.isAllowed(ds, security))
			throw new ReportException("rb.error.unknownDataSource", spec.getDataSource());
		return ds;
	}

	private ReportRunContext context() {
		Map<String, Object> attributes = new HashMap<>();
		for (ReportAttributesProvider p : attributeProviders) {
			Map<String, Object> a = p.getAttributes();
			if (a != null)
				attributes.putAll(a);
		}
		final ReportSecurity sec = security;
		return new ReportRunContext(sec.getCurrentUser(), sec::hasPermission, attributes);
	}

	/** Result of an Excel export. */
	public static final class ExportOutcome implements java.io.Serializable {
		private static final long serialVersionUID = 1L;
		private final int rows;
		private final boolean truncated;
		private final int limit;

		public ExportOutcome(int rows, boolean truncated, int limit) {
			this.rows = rows;
			this.truncated = truncated;
			this.limit = limit;
		}

		public int getRows() {
			return rows;
		}

		public boolean isTruncated() {
			return truncated;
		}

		public int getLimit() {
			return limit;
		}
	}
}
