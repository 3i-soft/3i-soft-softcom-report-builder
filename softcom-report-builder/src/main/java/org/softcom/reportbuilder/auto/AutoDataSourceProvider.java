package org.softcom.reportbuilder.auto;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.EJB;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.softcom.persistence.IPersistenceHelper;
import org.softcom.reportbuilder.model.ReportLabel;
import org.softcom.reportbuilder.service.ReportLabelService;
import org.softcom.reportbuilder.spi.ReportBuilderConfig;
import org.softcom.reportbuilder.spi.ReportCodeProvider;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportDataSourceProvider;

/**
 * Offers every entity of the application's persistence unit as a data source
 * (see {@link EntityDiscovery}). Found by CDI like any other provider, so a
 * project only adds the library; switch it off with the context parameter
 * {@value ReportBuilderConfig#AUTO_DATA_SOURCES} = false.
 */
@ApplicationScoped
public class AutoDataSourceProvider implements ReportDataSourceProvider {

	private static final Logger LOG = Logger.getLogger(AutoDataSourceProvider.class.getName());

	@EJB
	private IPersistenceHelper persistenceHelper;

	@EJB
	private ReportLabelService labelService;

	@Inject
	@Any
	private Instance<ReportCodeProvider> codeProviders;

	@Override
	public List<ReportDataSource> getDataSources() {
		if (!ReportBuilderConfig.getBoolean(ReportBuilderConfig.AUTO_DATA_SOURCES, true))
			return Collections.emptyList();
		try {
			EntityDiscovery.Options options = EntityDiscovery.Options.fromConfig();
			if (!codeProviders.isUnsatisfied())
				options.codeLookup(this::codes);
			List<ReportDataSource> list = EntityDiscovery.discover(persistenceHelper.getEntityManager().getMetamodel(),
					withSavedLabels(LabelResolver.fromApplication()), options);
			LOG.info("Report builder: " + list.size() + " automatic data sources");
			return list;
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Report builder: automatic data sources are not available", e);
			return Collections.emptyList();
		}
	}

	/** The labels entered on the labels page first; without the rb_label table (script not run yet), none. */
	private LabelResolver withSavedLabels(LabelResolver labels) {
		try {
			Map<String, String> ar = new HashMap<>();
			Map<String, String> en = new HashMap<>();
			for (ReportLabel l : labelService.findAll().values()) {
				if (l.getLabelAr() != null)
					ar.put(l.getKey(), l.getLabelAr());
				if (l.getLabelEn() != null)
					en.put(l.getKey(), l.getLabelEn());
			}
			return labels.withSaved(ar, en);
		} catch (RuntimeException e) {
			LOG.log(Level.INFO, "Report builder: no saved labels (has the rb_label table been created?)", e);
			return labels;
		}
	}

	/** The first non-empty list of the application's code providers. */
	private Map<String, String> codes(String kind) {
		for (ReportCodeProvider p : codeProviders) {
			Map<String, String> m = p.codes(kind);
			if (m != null && !m.isEmpty())
				return m;
		}
		return null;
	}
}
