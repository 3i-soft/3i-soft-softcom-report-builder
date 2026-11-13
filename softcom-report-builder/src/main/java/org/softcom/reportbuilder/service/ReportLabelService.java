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
		return save(changes, java.util.Collections.<String, Boolean>emptyMap());
	}

	/**
	 * Saves changed labels (key -&gt; {Arabic, English}) and changed hidden
	 * flags (key -&gt; hidden) of tables and fields. A key with no label and
	 * not hidden is removed. Keys only in {@code hidden} keep their labels.
	 *
	 * @return how many keys were changed
	 */
	public int save(Map<String, String[]> changes, Map<String, Boolean> hidden) {
		if (!security.canEditLabels())
			throw new ReportException("rb.error.accessDenied");
		String user = security.getCurrentUser();
		Date now = new Date();
		int changed = 0;
		java.util.Set<String> keys = new java.util.LinkedHashSet<>(changes.keySet());
		keys.addAll(hidden.keySet());
		for (String rawKey : keys) {
			String key = rawKey == null ? "" : rawKey.trim();
			if (key.isEmpty() || key.length() > MAX_LENGTH)
				continue;
			ReportLabel l = em().find(ReportLabel.class, key);
			String[] labels = changes.get(rawKey);
			String ar = labels != null ? clean(labels.length > 0 ? labels[0] : null) : l == null ? null : l.getLabelAr();
			String en = labels != null ? clean(labels.length > 1 ? labels[1] : null) : l == null ? null : l.getLabelEn();
			Boolean h = hidden.get(rawKey);
			boolean hide = h != null ? h : l != null && l.isHidden();
			if (ar == null && en == null && !hide) {
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
			l.setHidden(hide);
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
