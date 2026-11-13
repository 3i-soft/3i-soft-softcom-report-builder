package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.softcom.reportbuilder.auto.EntityDiscovery;
import org.softcom.reportbuilder.auto.LabelCatalog;
import org.softcom.reportbuilder.auto.LabelResolver;
import org.softcom.reportbuilder.model.ReportLabel;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/** Tables and fields hidden on the labels page are not offered in reports. */
public class HiddenTablesAndFieldsTest {

	private static EntityManager em;

	@BeforeClass
	public static void init() {
		em = TestDb.emf().createEntityManager();
	}

	@AfterClass
	public static void close() {
		em.close();
	}

	private static Map<String, ReportDataSource> discover(String... hidden) {
		EntityDiscovery.Options options = new EntityDiscovery.Options();
		for (String h : hidden)
			options.hide(h);
		Map<String, ReportDataSource> map = new LinkedHashMap<>();
		for (ReportDataSource ds : EntityDiscovery.discover(em.getMetamodel(), new LabelResolver(null, null, null), options))
			map.put(ds.getKey(), ds);
		return map;
	}

	private static Set<String> paths(ReportDataSource ds) {
		Set<String> out = new HashSet<>();
		for (ReportField f : ds.getFields())
			out.add(f.getPath());
		return out;
	}

	@Test
	public void aHiddenTableIsNeitherADataSourceNorReachedThroughRelations() {
		Map<String, ReportDataSource> all = discover();
		assertNotNull(all.get("auto.TWarehouse"));
		assertNotNull(all.get("auto.TInvoice").getField("warehouse.name"));
		Map<String, ReportDataSource> hidden = discover("TWarehouse");
		assertNull(hidden.get("auto.TWarehouse"));
		for (String p : paths(hidden.get("auto.TInvoice")))
			assertFalse(p, p.startsWith("warehouse."));
		for (String p : paths(hidden.get("auto.TInvoiceLine")))
			assertFalse(p, p.startsWith("invoice.warehouse."));
		assertNotNull("the rest of the invoice stays", hidden.get("auto.TInvoice").getField("invoiceDate"));
		assertEquals("only that table is gone", all.size() - 1, hidden.size());
	}

	@Test
	public void aHiddenFieldIsGoneEverywhere() {
		Map<String, ReportDataSource> hidden = discover("TItem.name");
		assertNull(hidden.get("auto.TItem").getField("name"));
		assertNull("also through the lines' relation", hidden.get("auto.TInvoiceLine").getField("item.name"));
		assertNotNull(hidden.get("auto.TInvoiceLine").getField("item.code"));
		assertNull("and in conditions through a collection",
				hidden.get("auto.TOrder").getField("lines.item.name"));
	}

	@Test
	public void hidingARelationHidesWhatIsBehindIt() {
		Map<String, ReportDataSource> hidden = discover("TInvoiceLine.item");
		for (String p : paths(hidden.get("auto.TInvoiceLine")))
			assertFalse(p, p.startsWith("item."));
		assertNotNull("the item table itself stays", hidden.get("auto.TItem"));
	}

	@Test
	public void hidingACollectionRemovesItsRowsAndConditions() {
		assertNotNull(discover().get("auto.TOrder.lines"));
		Map<String, ReportDataSource> hidden = discover("TOrder.lines");
		assertNull(hidden.get("auto.TOrder.lines"));
		for (String p : paths(hidden.get("auto.TOrder")))
			assertFalse(p, p.startsWith("lines."));
	}

	@Test
	public void aFieldOfASharedBaseClassIsHiddenInEveryTable() {
		// TSupplier's id is declared by the mapped superclass TBaseEntity: one key for every table inheriting it
		assertNotNull(discover().get("auto.TSupplier").getField("id"));
		assertNull(discover("TBaseEntity.id").get("auto.TSupplier").getField("id"));
	}

	@Test
	public void theLabelsPageStillListsHiddenTablesSoTheyCanBeShownAgain() {
		// the page builds its list from the configuration only, never from the hidden flags
		boolean listed = false;
		for (LabelCatalog.Entry e : LabelCatalog.entries(em.getMetamodel(), new LabelResolver(null, null, null),
				new EntityDiscovery.Options()))
			if (e.getKey().equals("TWarehouse") && e.getKind() == LabelCatalog.Kind.TABLE)
				listed = true;
		assertTrue(listed);
	}

	@Test
	public void theHiddenFlagIsSaved() {
		em.getTransaction().begin();
		ReportLabel l = em.find(ReportLabel.class, "TWarehouse");
		if (l == null) {
			l = new ReportLabel("TWarehouse");
			em.persist(l);
		}
		l.setHidden(true);
		em.getTransaction().commit();
		em.clear();
		assertTrue(em.find(ReportLabel.class, "TWarehouse").isHidden());
		em.getTransaction().begin();
		em.remove(em.find(ReportLabel.class, "TWarehouse"));
		em.getTransaction().commit();
	}
}
