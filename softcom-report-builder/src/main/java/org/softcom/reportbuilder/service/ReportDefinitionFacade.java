package org.softcom.reportbuilder.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.persistence.EntityManager;

import org.softcom.persistence.IPersistenceHelper;
import org.softcom.reportbuilder.engine.ReportException;
import org.softcom.reportbuilder.engine.SpecValidator;
import org.softcom.reportbuilder.model.ReportDefinition;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SpecJson;
import org.softcom.reportbuilder.spi.ReportDataSource;

/**
 * Saved reports with their visibility rules: the owner and admins see a report;
 * others see it only when it is shared (optionally restricted to roles) and they
 * may use its data source.
 */
@Stateless
public class ReportDefinitionFacade {

	@EJB
	private IPersistenceHelper persistenceHelper;

	@Inject
	private ReportSecurity security;

	@Inject
	private ReportCatalog catalog;

	private EntityManager em() {
		return persistenceHelper.getEntityManager();
	}

	public List<ReportDefinition> findVisible() {
		List<ReportDefinition> result = new ArrayList<>();
		if (!security.canRun())
			return result;
		List<ReportDefinition> candidates = security.isAdmin()
				? em().createNamedQuery(ReportDefinition.FIND_ALL, ReportDefinition.class).getResultList()
				: em().createNamedQuery(ReportDefinition.FIND_OWNED_OR_SHARED, ReportDefinition.class)
						.setParameter("owner", security.getCurrentUser()).getResultList();
		for (ReportDefinition d : candidates)
			if (isVisible(d))
				result.add(d);
		return result;
	}

	/** @throws ReportException rb.error.notFound when missing or not visible to the current user. */
	public ReportDefinition findVisible(Long id) {
		ReportDefinition d = id == null ? null : em().find(ReportDefinition.class, id);
		if (d == null || !isVisible(d))
			throw new ReportException("rb.error.notFound");
		return d;
	}

	public boolean isVisible(ReportDefinition d) {
		if (!security.canRun() || !ReportCatalog.isAllowed(catalog.get(d.getDataSourceKey()), security))
			return false;
		if (security.isAdmin() || isOwner(d))
			return true;
		if (!d.isShared())
			return false;
		List<String> roles = d.getSharedRoleList();
		if (roles.isEmpty())
			return true;
		for (String r : roles)
			if (security.hasPermission(r))
				return true;
		return false;
	}

	public boolean canEdit(ReportDefinition d) {
		return d != null && security.canDesign() && (security.isAdmin() || isOwner(d));
	}

	private boolean isOwner(ReportDefinition d) {
		String user = security.getCurrentUser();
		return user != null && user.equals(d.getOwner());
	}

	/**
	 * Creates or updates a report. The definition is validated against its data
	 * source (ask-at-run rules may be empty).
	 */
	public ReportDefinition save(ReportDefinition input, ReportSpec spec) {
		if (!security.canDesign())
			throw new ReportException("rb.error.accessDenied");
		if (input.getName() == null || input.getName().trim().isEmpty())
			throw new ReportException("rb.error.nameRequired");
		checkLength(input.getName().trim(), 200);
		checkLength(input.getDescription(), 1000);
		checkLength(input.getSharedRoles(), 1000);
		ReportDataSource ds = catalog.get(spec.getDataSource());
		if (!ReportCatalog.isAllowed(ds, security))
			throw new ReportException("rb.error.unknownDataSource", spec.getDataSource());
		SpecValidator.validate(spec, ds, false);
		String json = SpecJson.toJson(spec);
		if (json.length() > SpecJson.MAX_LENGTH)
			throw new ReportException("rb.error.definitionTooLarge");
		String user = security.getCurrentUser();
		if (user == null || user.isEmpty() || user.length() > 100)
			throw new ReportException("rb.error.accessDenied");
		Date now = new Date();
		ReportDefinition d;
		if (input.getId() == null) {
			d = new ReportDefinition();
			d.setOwner(user);
			d.setCreatedBy(user);
			d.setCreatedDate(now);
		} else {
			d = em().find(ReportDefinition.class, input.getId());
			if (d == null)
				throw new ReportException("rb.error.notFound");
			if (!canEdit(d))
				throw new ReportException("rb.error.accessDenied");
			if (input.getVersion() != null && !input.getVersion().equals(d.getVersion()))
				throw new ReportException("rb.error.concurrentChange");
		}
		d.setName(input.getName().trim());
		d.setDescription(input.getDescription());
		d.setShared(input.isShared());
		d.setSharedRoles(input.getSharedRoles());
		d.setDataSourceKey(ds.getKey());
		d.setDefinition(json);
		d.setUpdatedBy(user);
		d.setUpdatedDate(now);
		if (d.getId() == null)
			em().persist(d);
		em().flush();
		return d;
	}

	private static void checkLength(String value, int max) {
		if (value != null && value.length() > max)
			throw new ReportException("rb.error.textTooLong", max);
	}

	public void delete(Long id) {
		ReportDefinition d = id == null ? null : em().find(ReportDefinition.class, id);
		if (d == null)
			throw new ReportException("rb.error.notFound");
		if (!canEdit(d))
			throw new ReportException("rb.error.accessDenied");
		em().remove(d);
	}

	public ReportSpec spec(ReportDefinition d) {
		return SpecJson.fromJson(d.getDefinition());
	}
}
