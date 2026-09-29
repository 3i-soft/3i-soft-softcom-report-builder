package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;

import org.junit.Test;
import org.softcom.reportbuilder.auto.EntityDiscovery;
import org.softcom.reportbuilder.auto.LabelCatalog;
import org.softcom.reportbuilder.auto.LabelResolver;
import org.softcom.reportbuilder.model.ReportLabel;
import org.softcom.reportbuilder.spi.ReportDataSource;

/** Names entered on the labels page: what the page lists, and that saved names win. */
public class LabelsPageTest {

	private static final LabelResolver NONE = new LabelResolver(Collections.emptyList(), Collections.emptyList(), null);

	private static Map<String, LabelCatalog.Entry> entries(EntityManager em) {
		Map<String, LabelCatalog.Entry> m = new HashMap<>();
		for (LabelCatalog.Entry e : LabelCatalog.entries(em.getMetamodel(), NONE, new EntityDiscovery.Options()))
			m.put(e.getKey(), e);
		return m;
	}

	@Test
	public void thePageListsTablesFieldsAndValuesOnce() {
		EntityManager em = TestDb.emf().createEntityManager();
		try {
			Map<String, LabelCatalog.Entry> m = entries(em);
			assertEquals(LabelCatalog.Kind.TABLE, m.get("TItem").getKind());
			assertEquals(LabelCatalog.Kind.FIELD, m.get("TItem.name").getKind());
			assertEquals("TItem", m.get("TItem.name").getOwner());
			assertEquals(LabelCatalog.Kind.VALUE, m.get("TItemKind.FRUIT").getKind());
			assertEquals("relations and collections are named too", LabelCatalog.Kind.FIELD, m.get("TOrder.lines").getKind());
			assertNotNull(m.get("TInvoiceLine.item"));
			// the id of the shared base class is named once, for every table inheriting it
			assertNotNull(m.get("TBaseEntity.id"));
			assertNull(m.get("TSupplier.id"));
			assertNull("never password-like fields", m.get("TOrder.accessToken"));
			assertNull("nor the library's own tables", m.get("ReportLabel"));
			assertNull("no Arabic name found", m.get("TItem.name").getAutoAr());
			assertEquals("Name", m.get("TItem.name").getAutoEn());
		} finally {
			em.close();
		}
	}

	@Test
	public void savedNamesWinEverywhere() {
		Map<String, String> ar = new HashMap<>();
		ar.put("TItem", "المواد");
		ar.put("TItem.name", "اسم المادة");
		ar.put("TBaseEntity.id", "الرقم");
		ar.put("TItemKind.FRUIT", "فاكهة");
		Map<String, String> en = new HashMap<>();
		en.put("TItem.name", "Item name");
		EntityManager em = TestDb.emf().createEntityManager();
		try {
			Map<String, ReportDataSource> sources = new HashMap<>();
			for (ReportDataSource ds : EntityDiscovery.discover(em.getMetamodel(), NONE.withSaved(ar, en),
					new EntityDiscovery.Options()))
				sources.put(ds.getKey(), ds);
			assertEquals("المواد", sources.get("auto.TItem").getLabelAr());
			assertEquals("اسم المادة", sources.get("auto.TItem").getField("name").getLabelAr());
			assertEquals("Item name", sources.get("auto.TItem").getField("name").getLabelEn());
			assertEquals("a field of the base class, in every table", "الرقم",
					sources.get("auto.TSupplier").getField("id").getLabelAr());
			assertEquals("فاكهة", sources.get("auto.TItem").getField("kind").choiceLabel("FRUIT"));
			assertTrue("related fields are named with the saved names",
					sources.get("auto.TInvoiceLine").getField("item.name").getLabelAr().contains("اسم المادة"));
		} finally {
			em.close();
		}
	}

	@Test
	public void savedLabelsArePersisted() {
		EntityManager em = TestDb.emf().createEntityManager();
		try {
			em.getTransaction().begin();
			// the database may keep the row of an earlier run (PostgreSQL)
			ReportLabel l = em.find(ReportLabel.class, "TItem.code");
			if (l == null) {
				l = new ReportLabel("TItem.code");
				em.persist(l);
			}
			l.setLabelAr("رمز المادة");
			em.getTransaction().commit();
			em.clear();
			List<ReportLabel> all = em.createNamedQuery(ReportLabel.FIND_ALL, ReportLabel.class).getResultList();
			boolean found = false;
			for (ReportLabel r : all)
				found |= "TItem.code".equals(r.getKey()) && "رمز المادة".equals(r.getLabelAr());
			assertTrue(found);
			assertFalse(all.isEmpty());
		} finally {
			em.close();
		}
	}
}
