package org.softcom.reportbuilder.service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ejb.EJB;
import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.persistence.EntityManager;

import org.softcom.persistence.IPersistenceHelper;
import org.softcom.reportbuilder.engine.ReportException;
import org.softcom.reportbuilder.model.ReportLabel;

/**
 * The labels entered on the labels page. Saving rebuilds the data sources, so
 * the new names are shown at once, without a restart.
 */
@Stateless
public class ReportLabelService {

	public static final int MAX_LENGTH = 300;

	@EJB
	private IPersistenceHelper persistenceHelper;

	@Inject
	private ReportSecurity security;

	@Inject
	private ReportCatalog catalog;

	/**
	 * key -&gt; label, all saved labels (anyone may read them: they are shown in
	 * every report). Outside the caller's transaction: before the rb_label
	 * table exists, the failed read must not spoil the caller's work.
	 */
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Map<String, ReportLabel> findAll() {
		Map<String, ReportLabel> m = new HashMap<>();
		List<ReportLabel> all = em().createNamedQuery(ReportLabel.FIND_ALL, ReportLabel.class).getResultList();
		for (ReportLabel l : all)
			m.put(l.getKey(), l);
		return m;
	}

	/**
	 * Saves changed labels: key -&gt; {Arabic, English}; both empty removes the
	 * saved label (the automatic one comes back).
	 *
	 * @return how many labels were changed
	 */
	public int save(Map<String, String[]> changes) {
		if (!security.canEditLabels())
			throw new ReportException("rb.error.accessDenied");
		String user = security.getCurrentUser();
		Date now = new Date();
		int changed = 0;
		for (Map.Entry<String, String[]> c : changes.entrySet()) {
			String key = c.getKey() == null ? "" : c.getKey().trim();
			if (key.isEmpty() || key.length() > MAX_LENGTH)
				continue;
			String ar = clean(c.getValue().length > 0 ? c.getValue()[0] : null);
			String en = clean(c.getValue().length > 1 ? c.getValue()[1] : null);
			ReportLabel l = em().find(ReportLabel.class, key);
			if (ar == null && en == null) {
				if (l != null) {
					em().remove(l);
					changed++;
				}
				continue;
			}
			if (l == null) {
				l = new ReportLabel(key);
				em().persist(l);
			}
			l.setLabelAr(ar);
			l.setLabelEn(en);
			l.setUpdatedBy(user);
			l.setUpdatedDate(now);
			changed++;
		}
		em().flush();
		catalog.refresh();
		return changed;
	}

	private static String clean(String s) {
		if (s == null || s.trim().isEmpty())
			return null;
		String t = s.trim();
		if (t.length() > MAX_LENGTH)
			throw new ReportException("rb.error.textTooLong", MAX_LENGTH);
		return t;
	}

	private EntityManager em() {
		return persistenceHelper.getEntityManager();
	}
}
