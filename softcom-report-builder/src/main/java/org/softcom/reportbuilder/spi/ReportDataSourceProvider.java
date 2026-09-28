package org.softcom.reportbuilder.spi;

import java.util.List;

/**
 * Implemented by each host application (as a CDI bean, e.g.
 * {@code @ApplicationScoped}) to publish the data sources its users may report
 * on. All providers found by CDI are merged; data source keys must be unique.
 */
public interface ReportDataSourceProvider {

	List<ReportDataSource> getDataSources();
}
