package org.softcom.reportbuilder.service;

import java.sql.Connection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;

import org.softcom.persistence.IPersistenceHelper;
import org.softcom.reportbuilder.spi.ReportBuilderConfig;

/**
 * Brings the library's tables up to date when the application starts
 * ({@link ReportSchema}), on the application's own data source. Switch it off
 * with the system property {@value ReportBuilderConfig#SCHEMA_UPDATE} = false
 * (e.g. when the database user may not change tables; then run the scripts of
 * {@code META-INF/softcom-report-builder/sql/} by hand). A failure is logged
 * and does not stop the application: only the report pages need these tables.
 */
@Singleton
@Startup
@TransactionManagement(TransactionManagementType.BEAN)
public class ReportSchemaUpdater {

	private static final Logger LOG = Logger.getLogger(ReportSchemaUpdater.class.getName());

	@EJB
	private IPersistenceHelper persistenceHelper;

	@PostConstruct
	public void update() {
		if (!ReportBuilderConfig.getBoolean(ReportBuilderConfig.SCHEMA_UPDATE, true)) {
			LOG.info("Report builder: table update switched off (" + ReportBuilderConfig.SCHEMA_UPDATE + ")");
			return;
		}
		try (Connection c = persistenceHelper.getDataSource().getConnection()) {
			List<String> run = ReportSchema.update(c);
			if (!run.isEmpty())
				LOG.info("Report builder: tables updated with scripts " + run);
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Report builder: could not create/update its tables (rb_*); the report pages will fail"
					+ " until the scripts of META-INF/softcom-report-builder/sql/ are run", e);
		}
	}
}
