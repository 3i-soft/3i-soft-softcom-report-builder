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

import org.softcom.reportbuilder.engine.ReportException;
import org.softcom.reportbuilder.spec.LinkSpec;
import org.softcom.reportbuilder.spec.ReportSpec;
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

	/** Builds the data sources again on next use (e.g. after labels were changed): no restart needed. */
	public void refresh() {
		synchronized (this) {
			dataSources = null;
		}
	}

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

	/**
	 * The data source of a report as its queries see it: with the tables it
	 * links ({@link ReportDataSource#withLinks}). The user must be allowed to
	 * use the data source and every linked one.
	 *
	 * @throws ReportException rb.error.unknownDataSource otherwise
	 */
	public ReportDataSource resolve(ReportSpec spec, ReportSecurity security) {
		ReportDataSource ds = get(spec.getDataSource());
		if (!isAllowed(ds, security))
			throw new ReportException("rb.error.unknownDataSource", spec.getDataSource());
		for (LinkSpec l : spec.getLinks())
			if (!isAllowed(get(l.getDataSource()), security))
				throw new ReportException("rb.error.unknownDataSource", l.getDataSource());
		return withLinks(ds, spec.getLinks(), this::get, false);
	}

	/**
	 * Adds a report's links to its data source.
	 *
	 * @param targets data sources by key
	 * @param lenient true while designing: links without a (known) table and
	 *                incomplete pairs are left out instead of failing, so the
	 *                designer can list the fields of the links done so far
	 */
	public static ReportDataSource withLinks(ReportDataSource ds, List<LinkSpec> links,
			java.util.function.Function<String, ReportDataSource> targets, boolean lenient) {
		if (ds == null || links == null || links.isEmpty())
			return ds;
		List<ReportDataSource.Link> list = new ArrayList<>();
		for (LinkSpec l : links) {
			ReportDataSource target = l.getDataSource() == null ? null : targets.apply(l.getDataSource());
			if (target == null || l.getAlias() == null || !l.getAlias().matches(LinkSpec.ALIAS_PATTERN)) {
				if (lenient)
					continue;
				throw new ReportException("rb.error.invalidLink", l.getAlias());
			}
			List<String[]> pairs = new ArrayList<>();
			for (LinkSpec.Pair p : l.getOn())
				if (!lenient || p.isComplete())
					pairs.add(new String[] { p.getLeft(), p.getRight() });
			list.add(new ReportDataSource.Link(l.getAlias(), target, pairs));
		}
		try {
			return ds.withLinks(list);
		} catch (IllegalArgumentException e) {
			throw new ReportException(e, "rb.error.invalidLink", e.getMessage());
		}
	}

	public static boolean isAllowed(ReportDataSource ds, ReportSecurity security) {
		// deliberately no admin shortcut: REPORT_BUILDER_ADMIN manages saved reports, it does not grant data access
		return ds != null && (ds.getRequiredRole() == null || security.hasPermission(ds.getRequiredRole()));
	}

	/**
	 * An entity the application publishes through a hand-written data source
	 * with forced filters (e.g. the user's warehouses) is not also offered
	 * automatically - that copy would bypass the restriction. Sub and super
	 * classes count: auto.SalesInvoice contains rows of a restricted Invoice.
	 */
	static boolean restrictedByHandWritten(ReportDataSource automatic, java.util.Collection<ReportDataSource> handWritten) {
		Class<?> root = automatic.getRootEntity();
		for (ReportDataSource ds : handWritten)
			if (ds.getForcedFilter() != null
					&& (ds.getRootEntity().isAssignableFrom(root) || root.isAssignableFrom(ds.getRootEntity())))
				return true;
		return false;
	}

	private Map<String, ReportDataSource> all() {
		Map<String, ReportDataSource> map = dataSources;
		if (map == null) {
			synchronized (this) {
				map = dataSources;
				if (map == null) {
					map = new LinkedHashMap<>();
					List<ReportDataSource> automatic = new ArrayList<>();
					for (ReportDataSourceProvider provider : providers) {
						List<ReportDataSource> list = provider.getDataSources();
						if (list == null)
							continue;
						for (ReportDataSource ds : list) {
							ds.checkConsistency();
							if (ds.isAutomatic()) {
								automatic.add(ds);
							} else if (map.put(ds.getKey(), ds) != null) {
								throw new IllegalStateException("Duplicate report data source key: " + ds.getKey());
							}
						}
					}
					for (ReportDataSource ds : automatic)
						if (!restrictedByHandWritten(ds, map.values()) && !map.containsKey(ds.getKey()))
							map.put(ds.getKey(), ds);
					map = Collections.unmodifiableMap(map);
					dataSources = map;
				}
			}
		}
		return map;
	}
}
