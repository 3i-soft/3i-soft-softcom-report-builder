package org.softcom.reportbuilder.service;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;

import org.softcom.persistence.IPersistenceHelper;
import org.softcom.reportbuilder.engine.ReportExecutor;
import org.softcom.reportbuilder.engine.SpeedInfo;
import org.softcom.reportbuilder.engine.TableStats;
import org.softcom.reportbuilder.spi.ReportDataSource;

/**
 * Table statistics of a data source (cached). Runs outside the caller's
 * transaction: a failing catalog query must never abort the report's own
 * transaction on PostgreSQL.
 */
@Stateless
public class ReportSpeedService {

	@EJB
	private IPersistenceHelper persistenceHelper;

	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public SpeedInfo speed(ReportDataSource ds) {
		if (ds == null)
			return SpeedInfo.UNKNOWN;
		return TableStats.of(persistenceHelper.getEntityManager(), ds, ReportExecutor.largeTableRows());
	}
}
