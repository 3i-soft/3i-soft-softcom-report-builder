package org.softcom.reportbuilder.service;

import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

import org.softcom.persistence.IPersistenceHelper;
import org.softcom.reportbuilder.model.ReportRunLog;

/**
 * Writes one row per report execution in its own transaction, so logging can
 * never break (or be rolled back with) the report itself. Use
 * {@code rb_report_run_log} to find slow reports.
 */
@Stateless
public class ReportRunLogger {

	private static final Logger LOG = Logger.getLogger(ReportRunLogger.class.getName());

	@EJB
	private IPersistenceHelper persistenceHelper;

	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void log(ReportRunLog.Kind kind, Long definitionId, String dataSourceKey, String user, Date startedAt,
			long durationMs, int rows, Throwable error) {
		try {
			ReportRunLog log = new ReportRunLog();
			log.setKind(kind.name());
			log.setDefinitionId(definitionId);
			log.setDataSourceKey(dataSourceKey);
			log.setRunBy(user == null || user.length() <= 100 ? user : user.substring(0, 100));
			log.setStartedAt(startedAt);
			log.setDurationMs(durationMs);
			log.setRowCount(rows);
			log.setSuccess(error == null);
			log.setErrorMessage(error == null ? null : String.valueOf(error.getMessage()));
			persistenceHelper.getEntityManager().persist(log);
			// write now, so a database problem is caught here and not at commit (outside this try)
			persistenceHelper.getEntityManager().flush();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Could not write report run log", e);
		}
	}
}
