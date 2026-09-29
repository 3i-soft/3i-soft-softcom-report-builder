package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.persistence.EntityManager;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.softcom.reportbuilder.auto.EntityDiscovery;
import org.softcom.reportbuilder.auto.LabelResolver;
import org.softcom.reportbuilder.export.ReportFormatter;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.FilterNode.Logic;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;
import org.softcom.reportbuilder.spi.ReportLookup;
import org.softcom.reportbuilder.spi.RowRestrictions;
import org.softcom.reportbuilder.testmodel.TCustomer;
import org.softcom.reportbuilder.testmodel.TItem;
import org.softcom.reportbuilder.testmodel.TWarehouse;

/** Pick lists of related records: which fields get one, the search, and names in results. */
public class LookupTest {

	private static EntityManager em;
	private static Map<String, ReportDataSource> sources;
	private final ReportExecutor executor = new ReportExecutor();

	@BeforeClass
	public static void init() {
		em = TestDb.emf().createEntityManager();
		sources = new LinkedHashMap<>();
		for (ReportDataSource ds : EntityDiscovery.discover(em.getMetamodel(),
				new LabelResolver(Collections.emptyList(), Collections.emptyList(), null), new EntityDiscovery.Options()))
			sources.put(ds.getKey(), ds);
		// suppliers keep their main warehouse as a plain number
		em.getTransaction().begin();
		em.createNativeQuery("UPDATE t_supplier SET maintwarehouseid = 10 WHERE id = 4").executeUpdate();
		em.createNativeQuery("UPDATE t_supplier SET maintwarehouseid = 11 WHERE id = 5").executeUpdate();
		em.getTransaction().commit();
	}

	@AfterClass
	public static void close() {
		em.close();
	}

	private static ReportLookup lookup(String source, String field) {
		ReportField f = sources.get(source).getField(field);
		assertNotNull(source + " " + field, f);
		return f.getLookup();
	}

	private static List<String> labels(List<LookupQueries.Item> items) {
		List<String> out = new ArrayList<>();
		for (LookupQueries.Item i : items)
			out.add(i.getLabel());
		return out;
	}

	@Test
	public void relatedRecordsAreChosenByName() {
		ReportLookup warehouse = lookup("auto.TInvoice", "warehouse.id");
		assertEquals(TWarehouse.class, warehouse.getEntity());
		assertEquals(Collections.singletonList("name"), warehouse.getLabelAttributes());
		assertFalse("the relation's id column keeps showing ids", warehouse.isNamesInResults());
		ReportLookup item = lookup("auto.TInvoiceLine", "item.id");
		assertEquals(TItem.class, item.getEntity());
		assertEquals("code", item.getCodeAttribute());
		assertEquals(TCustomer.class, lookup("auto.TOrder", "customer.id").getEntity());
		assertNull("the row's own id is not a pick list", lookup("auto.TInvoice", "id"));
		assertNull("a number that names no entity", lookup("auto.TInvoiceLine", "lineNo"));
		assertFalse("ids are not summed", sources.get("auto.TInvoice").getField("warehouse.id").isAggregatable());
	}

	@Test
	public void aPlainNumberNamedAfterAnEntityIsALookupShownByName() {
		ReportField f = sources.get("auto.TSupplier").getField("mainTWarehouseId");
		assertTrue(f.hasLookup());
		assertEquals(TWarehouse.class, f.getLookup().getEntity());
		assertTrue(f.getLookup().isNamesInResults());
		assertFalse("a warehouse number is not summed", f.isAggregatable());
	}

	@Test
	public void searchByNameOrCode() {
		ReportLookup item = lookup("auto.TInvoiceLine", "item.id");
		assertEquals(Collections.singletonList("apple (F-001)"), labels(LookupQueries.search(em, item, "APP", 30, null, 5)));
		assertEquals(Collections.singletonList("bread (B-001)"), labels(LookupQueries.search(em, item, "b-00", 30, null, 5)));
		assertEquals("empty text lists the first ones, by name", Arrays.asList("O'Brien 50% mix (O-001)", "apple (F-001)",
				"bread (B-001)"), labels(LookupQueries.search(em, item, "", 30, null, 5)));
		assertEquals("LIKE characters are searched as text", Collections.singletonList("O'Brien 50% mix (O-001)"),
				labels(LookupQueries.search(em, item, "50%", 30, null, 5)));
		assertEquals(2, LookupQueries.search(em, item, "", 2, null, 5).size());
		assertEquals("100", LookupQueries.search(em, item, "apple", 30, null, 5).get(0).getId());
	}

	@Test
	public void searchKeepsTheUsersRowRestrictions() {
		ReportLookup warehouse = lookup("auto.TInvoice", "warehouse.id");
		RowRestrictions r = new RowRestrictions("u1", null, null);
		r.allowOnly(TWarehouse.class, Arrays.asList(10L));
		ReportRunContext run = new ReportRunContext("u1", null, null, r);
		assertEquals(Collections.singletonList("Main"), labels(LookupQueries.search(em, warehouse, "", 30, run, 5)));
		RowRestrictions none = new RowRestrictions("u1", null, null);
		none.denyAll();
		assertTrue(LookupQueries.search(em, warehouse, "", 30, new ReportRunContext("u1", null, null, none), 5).isEmpty());
	}

	@Test
	public void namesOfIdsWhateverTheirNumberType() {
		ReportLookup warehouse = lookup("auto.TInvoice", "warehouse.id");
		Map<String, String> names = LookupQueries.names(em, warehouse, Arrays.asList("10", 11.0, 20L, "x", null, 999), 5);
		assertEquals("Main", names.get("10"));
		assertEquals("Branch", names.get(LookupQueries.key(11.0)));
		assertEquals("Other company", names.get("20"));
		assertEquals(3, names.size());
	}

	@Test
	public void aConditionOnAChosenRecordAndNamesInTheColumn() {
		ReportDataSource supplier = sources.get("auto.TSupplier");
		ReportSpec s = new ReportSpec();
		s.setDataSource(supplier.getKey());
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("supplierName", Aggregate.NONE),
				new ColumnSpec("mainTWarehouseId", Aggregate.NONE))));
		s.setFilter(FilterNode.group(Logic.AND, FilterNode.rule("w", "mainTWarehouseId", Operator.IN, "10", "11")));
		ReportResult r = executor.run(em, supplier, s, null, 0, 10);
		assertEquals(2, r.getRows().size());
		assertEquals(FieldType.STRING, r.getColumns().get(1).getType());
		List<String> shown = new ArrayList<>();
		for (Object[] row : r.getRows())
			shown.add(ReportFormatter.format(row[1], r.getColumns().get(1), supplier, new Locale("ar"), r.getNames(1)));
		assertEquals(Arrays.asList("Main", "Branch"), shown);
		assertNull("no names for other columns", r.getNames(0));
	}
}
