package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.softcom.reportbuilder.auto.EntityDiscovery;
import org.softcom.reportbuilder.auto.LabelResolver;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.FilterNode.Logic;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.RowRestrictions;
import org.softcom.reportbuilder.testmodel.TCompany;
import org.softcom.reportbuilder.testmodel.TInvoice;
import org.softcom.reportbuilder.testmodel.TInvoiceLine;
import org.softcom.reportbuilder.testmodel.TWarehouse;

/** The application's one-time row restrictions (e.g. the user's warehouses) on every data source. */
public class RowRestrictionTest {

	private static EntityManager em;
	private static Map<String, ReportDataSource> sources;
	private final ReportExecutor executor = new ReportExecutor();

	@BeforeClass
	public static void init() {
		em = TestDb.emf().createEntityManager();
		sources = new LinkedHashMap<>();
		for (ReportDataSource ds : EntityDiscovery.discover(em.getMetamodel(), new LabelResolver(null, null, null),
				new EntityDiscovery.Options()))
			sources.put(ds.getKey(), ds);
	}

	@AfterClass
	public static void close() {
		em.close();
	}

	private static RowRestrictions restrictions() {
		return new RowRestrictions("u1", null, null);
	}

	/** Ids (first column) of all rows of the data source under the restrictions, sorted. */
	private List<Long> ids(String key, RowRestrictions r, FilterNode filter) {
		ReportDataSource ds = sources.get(key);
		ReportSpec s = new ReportSpec();
		s.setDataSource(key);
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec(ds.getGrainPath() == null ? "id" : ds.getGrainPath() + ".id",
				Aggregate.NONE))));
		s.setFilter(filter);
		ReportResult result = executor.run(em, ds, s, new ReportRunContext("u1", null, null, r), 0, 100);
		List<Long> ids = new ArrayList<>();
		for (Object[] row : result.getRows())
			ids.add(((Number) row[0]).longValue());
		Collections.sort(ids);
		return ids;
	}

	private List<Long> ids(String key, RowRestrictions r) {
		return ids(key, r, null);
	}

	@Test
	public void rowsLinkedToAllowedWarehousesOnly() {
		RowRestrictions r = restrictions().allowOnly(TWarehouse.class, Arrays.asList(10.0)); // Double ids on Long columns
		assertEquals("the warehouse itself", Arrays.asList(10L), ids("auto.TWarehouse", r));
		assertEquals("invoice.warehouse", Arrays.asList(1000L, 1002L, 1003L), ids("auto.TInvoice", r));
		assertEquals("two relations away: line.invoice.warehouse", Arrays.asList(1L, 2L, 5L, 6L), ids("auto.TInvoiceLine", r));
		assertEquals("items are not linked to a warehouse", Arrays.asList(100L, 101L, 102L), ids("auto.TItem", r));
		assertEquals(Arrays.asList(1L, 2L, 3L), ids("auto.TOrder.lines", r));
	}

	@Test
	public void inACollectionDataSourceTheOwnerDecides() {
		// order lines: the order (root) is limited by a value rule, so the lines' own column does not decide again
		RowRestrictions r = restrictions().allowOnly(org.softcom.reportbuilder.testmodel.TCustomer.class, Arrays.asList(500L))
				.allowOnlyValues(Arrays.asList(999), "quantity");
		assertEquals("order 1 (customer 500): its lines 1 and 2, whatever their quantity", Arrays.asList(1L, 2L),
				ids("auto.TOrder.lines", r));
		// no rule concerns the order: the lines' column is used
		assertEquals(Collections.<Long>emptyList(),
				ids("auto.TOrder.lines", restrictions().allowOnlyValues(Arrays.asList(999), "quantity")));
		assertEquals(Arrays.asList(3L), ids("auto.TOrder.lines", restrictions().allowOnlyValues(Arrays.asList(5), "quantity")));
	}

	@Test
	public void userConditionsCannotWidenTheRestriction() {
		RowRestrictions r = restrictions().allowOnly(TWarehouse.class, Arrays.asList(10L));
		FilterNode or = FilterNode.group(Logic.OR, FilterNode.rule("a", "warehouse.id", Operator.EQ, "11"),
				FilterNode.rule("b", "warehouse.id", Operator.EQ, "20"));
		assertEquals(Collections.<Long>emptyList(), ids("auto.TInvoice", r, or));
	}

	@Test
	public void onlyTheNearestLinksCount() {
		// an invoice has a company directly and through its warehouse: the direct one decides
		RowRestrictions r = restrictions().allowOnly(TCompany.class, Arrays.asList(2L));
		assertEquals(Arrays.asList(1004L), ids("auto.TInvoice", r));
		assertEquals(Arrays.asList(20L), ids("auto.TWarehouse", r));
	}

	@Test
	public void noAllowedIdsMeansNoLinkedRows() {
		RowRestrictions r = restrictions().allowOnly(TWarehouse.class, Collections.emptyList());
		assertEquals(Collections.<Long>emptyList(), ids("auto.TInvoice", r));
		assertEquals(Arrays.asList(100L, 101L, 102L), ids("auto.TItem", r));
		assertEquals(Collections.<Long>emptyList(), ids("auto.TItem", restrictions().denyAll()));
	}

	@Test
	public void valueRulesAndCustomConditions() {
		RowRestrictions values = restrictions().allowOnlyValues(Arrays.asList(1), "lineNo", "noSuchColumn");
		assertEquals(Arrays.asList(1L, 3L, 5L, 6L, 7L), ids("auto.TInvoiceLine", values));
		assertEquals("entities without the column are not limited", Arrays.asList(100L, 101L, 102L), ids("auto.TItem", values));

		RowRestrictions custom = restrictions().where(TInvoice.class,
				ctx -> Arrays.asList(ctx.getCriteriaBuilder().isFalse(ctx.<Boolean>path("closed"))));
		assertEquals(Arrays.asList(1003L), ids("auto.TInvoice", custom));
		assertEquals("only data sources rooted at that entity", 7, ids("auto.TInvoiceLine", custom).size());

		RowRestrictions lineRule = restrictions().where(TInvoiceLine.class,
				ctx -> Arrays.asList(ctx.getCriteriaBuilder().equal(ctx.path("item.id"), 101L)));
		assertEquals(Arrays.asList(2L, 5L), ids("auto.TInvoiceLine", lineRule));
	}
}
