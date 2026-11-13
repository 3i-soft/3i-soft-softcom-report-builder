package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.softcom.reportbuilder.auto.EntityDiscovery;
import org.softcom.reportbuilder.auto.LabelResolver;
import org.softcom.reportbuilder.service.ReportCatalog;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.LinkSpec;
import org.softcom.reportbuilder.spec.PivotSpec;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SpecJson;
import org.softcom.reportbuilder.spi.ReportDataSource;

/**
 * Pivot tables on the invoice lines: one row per item, one column per
 * warehouse, the quantity in the cells.
 *
 * <pre>
 *                  Branch  Main  Other company | total
 * apple              10    105       1000      | 1115   (lines 3; 1 + 6; 7)
 * bread               -      9          -      |    9   (lines 2 + 5)
 * O'Brien 50% mix     1      -          -      |    1   (line 4)
 * total              11    114       1000      | 1125
 * </pre>
 */
public class PivotTest {

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

	private static ReportSpec spec(String across, String value, Aggregate agg) {
		ReportSpec s = new ReportSpec();
		s.setDataSource("auto.TInvoiceLine");
		s.getColumns().add(new ColumnSpec("item.name", Aggregate.NONE));
		PivotSpec p = new PivotSpec(across, value, agg);
		p.setRowTotal(true);
		s.setPivot(p);
		s.setTotals(true);
		return s;
	}

	private ReportResult run(ReportSpec s, int pageSize) {
		ReportDataSource ds = ReportCatalog.withLinks(sources.get(s.getDataSource()), s.getLinks(), sources::get, false);
		return executor.run(em, ds, s, null, 0, pageSize);
	}

	private static List<String> headers(ReportResult r) {
		List<String> out = new ArrayList<>();
		for (ResultColumn c : r.getColumns())
			out.add(c.getLabel(java.util.Locale.ENGLISH));
		return out;
	}

	/** Rows by their first column (the item name). */
	private static Map<Object, Object[]> byItem(ReportResult r) {
		Map<Object, Object[]> m = new HashMap<>();
		for (Object[] row : r.getRows())
			m.put(row[0], row);
		return m;
	}

	private static void assertCell(String expected, Object actual) {
		if (expected == null) {
			assertNull(actual);
			return;
		}
		assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
	}

	@Test
	public void valuesOfAFieldBecomeColumns() {
		ReportResult r = run(spec("invoice.warehouse.name", "quantity", Aggregate.SUM), 100);
		List<String> h = headers(r);
		assertEquals(5, h.size());
		assertEquals(Arrays.asList("Branch", "Main", "Other company"), h.subList(1, 4));
		assertTrue(h.get(4), h.get(4).startsWith("Total"));
		Map<Object, Object[]> rows = byItem(r);
		assertEquals(3, rows.size());
		Object[] apple = rows.get("apple");
		assertCell("10", apple[1]);
		assertCell("105", apple[2]);
		assertCell("1000", apple[3]);
		assertCell("1115", apple[4]);
		Object[] bread = rows.get("bread");
		assertCell(null, bread[1]);
		assertCell("9", bread[2]);
		assertCell("9", bread[4]);
		Object[] t = r.getTotals().getValues();
		assertCell("11", t[1]);
		assertCell("114", t[2]);
		assertCell("1000", t[3]);
		assertCell("1125", t[4]);
		assertEquals("the word total goes in the first column", 0, r.getTotals().getLabelColumn());
	}

	@Test
	public void idColumnsAreNamedAndCountsWork() {
		// the warehouse id has a pick list: its columns show the warehouses' names
		ReportResult r = run(spec("invoice.warehouse.id", ReportDataSource.ROW_COUNT_FIELD, Aggregate.COUNT), 100);
		assertEquals(Arrays.asList("Main", "Branch", "Other company"), headers(r).subList(1, 4)); // ids 10, 11, 20
		Object[] apple = byItem(r).get("apple");
		assertEquals(2L, ((Number) apple[1]).longValue()); // lines 1 and 6 in Main (10)
		assertEquals(4L, ((Number) apple[4]).longValue());
	}

	@Test
	public void averagesHaveNoTotals() {
		ReportResult r = run(spec("invoice.warehouse.name", "quantity", Aggregate.AVG), 100);
		assertEquals("no total column", 4, r.getColumns().size());
		assertNull("no total row", r.getTotals());
		assertCell("52.5", byItem(r).get("apple")[2]); // (5 + 100) / 2
	}

	@Test
	public void pagesComeFromMemory() {
		ReportResult first = run(spec("invoice.warehouse.name", "quantity", Aggregate.SUM), 2);
		assertTrue(first.isComplete());
		assertEquals(3, first.getTotalRows());
		assertEquals(2, first.getRows().size());
		assertTrue(first.isHasMore());
		assertTrue("the total row on the first page", first.getTotals() != null);
		ReportResult second = first.page(2, 2);
		assertEquals(1, second.getRows().size());
		assertFalse(second.isHasMore());
		assertNull(second.getTotals());
		assertEquals(2, second.getFirst());
		List<Object> all = new ArrayList<>();
		for (Object[] row : first.getRows())
			all.add(row[0]);
		all.add(second.getRows().get(0)[0]);
		assertEquals("every row once", 3, new java.util.HashSet<>(all).size());
		ReportResult plain = executor.run(em, sources.get("auto.TItem"), plainItems(), null, 0, 10);
		assertFalse("a normal report is read page by page", plain.isComplete());
		assertNull(plain.page(10, 10));
	}

	private static ReportSpec plainItems() {
		ReportSpec s = new ReportSpec();
		s.setDataSource("auto.TItem");
		s.getColumns().add(new ColumnSpec("name", Aggregate.NONE));
		return s;
	}

	@Test
	public void exportWritesTheWholeTableAndItsTotal() {
		final List<Object[]> rows = new ArrayList<>();
		final Object[][] total = new Object[1][];
		final List<ResultColumn> columns = new ArrayList<>();
		ReportSpec s = spec("invoice.warehouse.name", "quantity", Aggregate.SUM);
		int n = executor.export(em, sources.get("auto.TInvoiceLine"), s, null, 0, new ReportExecutor.RowSink() {
			@Override
			public void begin(List<ResultColumn> cols) {
				columns.addAll(cols);
			}

			@Override
			public void rows(List<Object[]> r) {
				rows.addAll(r);
			}

			@Override
			public void total(Object[] values, int labelColumn) {
				total[0] = values;
			}
		});
		assertEquals(3, n);
		assertEquals(5, columns.size());
		assertEquals(3, rows.size());
		assertCell("1125", total[0][4]);
	}

	@Test
	public void linkedTablesCanBePivoted() {
		// the stock of each item per warehouse (a table linked by item_id), warehouses as columns
		ReportSpec s = spec("l1:warehouse_id", "l1:available", Aggregate.MAX);
		LinkSpec l = new LinkSpec("l1", "auto.TItemStock");
		l.getOn().add(new LinkSpec.Pair("item.id", "item_id"));
		s.getLinks().add(l);
		ReportResult r = run(s, 100);
		Object[] apple = byItem(r).get("apple");
		// columns: warehouse_id 10, 11 (the warehouse ids of the stock rows)
		assertEquals(4, r.getColumns().size());
		assertCell("40", apple[1]);
		assertCell("15", apple[2]);
		assertCell("40", apple[3]); // the greatest of the row
		assertCell("8", byItem(r).get("bread")[1]);
		assertCell(null, byItem(r).get("bread")[2]);
	}

	@Test
	public void definitionIsSavedAsJson() {
		ReportSpec s = spec("invoice.warehouse.name", "quantity", Aggregate.MAX);
		ReportSpec back = SpecJson.fromJson(SpecJson.toJson(s));
		assertEquals("invoice.warehouse.name", back.getPivot().getField());
		assertEquals("quantity", back.getPivot().getValue());
		assertEquals(Aggregate.MAX, back.getPivot().getAggregate());
		assertTrue(back.getPivot().isRowTotal());
		assertNull(SpecJson.fromJson(SpecJson.toJson(plainItems())).getPivot());
		assertFalse(SpecJson.toJson(plainItems()).contains("pivot"));
	}

	@Test
	public void invalidPivotsAreRefused() {
		ReportDataSource ds = sources.get("auto.TInvoiceLine");
		ReportSpec s = spec("invoice.warehouse.name", "quantity", Aggregate.SUM);
		s.getColumns().add(new ColumnSpec("price", Aggregate.SUM));
		refused(s, ds, "rb.error.pivotRowColumns");
		s = spec(null, "quantity", Aggregate.SUM);
		refused(s, ds, "rb.error.pivotField");
		s = spec("invoice.warehouse.name", "quantity", Aggregate.NONE);
		refused(s, ds, "rb.error.pivotValue");
		s = spec("invoice.warehouse.name", "item.name", Aggregate.SUM);
		refused(s, ds, "rb.error.aggregateNotAllowed");
		s = spec("invoice.warehouse.name", ReportDataSource.ROW_COUNT_FIELD, Aggregate.SUM);
		refused(s, ds, "rb.error.rowCountNeedsCount");
	}

	private static void refused(ReportSpec s, ReportDataSource ds, String key) {
		try {
			SpecValidator.validate(s, ds, true);
			fail(key);
		} catch (ReportException e) {
			assertEquals(key, e.getMessageKey());
		}
	}

	@Test
	public void totalsOfCells() {
		assertEquals(5L, PivotTable.combine(2L, 3L, Aggregate.COUNT));
		assertEquals(new BigDecimal("3.5"), PivotTable.combine(new BigDecimal("1.5"), 2L, Aggregate.SUM));
		assertEquals(2L, PivotTable.combine(2L, null, Aggregate.SUM));
		assertEquals(new BigDecimal("1.5"), PivotTable.combine(new BigDecimal("1.5"), 2L, Aggregate.MIN));
		assertEquals("b", PivotTable.combine("a", "b", Aggregate.MAX));
		assertNull(PivotTable.combine(1L, 2L, Aggregate.AVG));
	}
}
