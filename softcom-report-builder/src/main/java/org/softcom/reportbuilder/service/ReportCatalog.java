package org.softcom.reportbuilder.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportDataSourceProvider;

/**
 * All data sources published by the application's
 * {@link ReportDataSourceProvider} beans, built and checked once.
 */
@ApplicationScoped
public class ReportCatalog implements Serializable {

	private static final long serialVersionUID = 1L;

	@Inject
	@Any
	private Instance<ReportDataSourceProvider> providers;

	private transient volatile Map<String, ReportDataSource> dataSources;

	public ReportDataSource get(String key) {
		return key == null ? null : all().get(key);
	}

	public List<ReportDataSource> list() {
		return new ArrayList<>(all().values());
	}

	/** Data sources the user may use: requires run permission and the data source's own role, if any. */
	public List<ReportDataSource> visibleTo(ReportSecurity security) {
		List<ReportDataSource> list = new ArrayList<>();
		if (!security.canRun())
			return list;
		for (ReportDataSource ds : all().values())
			if (isAllowed(ds, security))
				list.add(ds);
		return list;
	}

	public static boolean isAllowed(ReportDataSource ds, ReportSecurity security) {
		// deliberately no admin shortcut: REPORT_BUILDER_ADMIN manages saved reports, it does not grant data access
		return ds != null && (ds.getRequiredRole() == null || security.hasPermission(ds.getRequiredRole()));
	}

	private Map<String, ReportDataSource> all() {
		Map<String, ReportDataSource> map = dataSources;
		if (map == null) {
			synchronized (this) {
				map = dataSources;
				if (map == null) {
					map = new LinkedHashMap<>();
					for (ReportDataSourceProvider provider : providers) {
						List<ReportDataSource> list = provider.getDataSources();
						if (list == null)
							continue;
						for (ReportDataSource ds : list) {
							ds.checkConsistency();
							if (map.put(ds.getKey(), ds) != null)
								throw new IllegalStateException("Duplicate report data source key: " + ds.getKey());
						}
					}
					map = Collections.unmodifiableMap(map);
					dataSources = map;
				}
			}
		}
		return map;
	}
}
