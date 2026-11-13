package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.softcom.reportbuilder.auto.EntityDiscovery;
import org.softcom.reportbuilder.auto.LabelResolver;
import org.softcom.reportbuilder.service.ReportCatalog;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.LinkSpec;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SortSpec;
import org.softcom.reportbuilder.spec.SpecJson;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;
import org.softcom.reportbuilder.spi.RowRestrictions;

/**
 * Tables linked by equal columns without a relation: invoice lines and the
 * stock of their item in their invoice's warehouse (TItemStock, plain
 * item_id / warehouse_id numbers).
 *
 * <pre>
 * line invoice warehouse item  qty | stock (item, warehouse)
 * 1    1000    10        apple 5   | 40
 * 2    1000    10        bread 2   | 8
 * 3    1001    11        apple 10  | 15
 * 4    1001    11        quote 1   | -  (no stock row: not shown)
 * 5    1002    10        bread 7   | 8
 * 6    1003    10        apple 100 | 40
 * 7    1004    20        apple 1000| -  (other company's warehouse: not shown)
 * </pre>
 */
public class LinkedTablesTest {

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

	/** Lines linked to the stock of their item in their invoice's warehouse. */
	private static ReportSpec spec(String... columns) {
		ReportSpec s = new ReportSpec();
		s.setDataSource("auto.TInvoiceLine");
		LinkSpec l = new LinkSpec("l1", "auto.TItemStock");
		l.getOn().add(new LinkSpec.Pair("item.id", "item_id"));
		l.getOn().add(new LinkSpec.Pair("invoice.warehouse.id", "warehouse_id"));
		s.getLinks().add(l);
		for (String c : columns)
			s.getColumns().add(new ColumnSpec(c, Aggregate.NONE));
		return s;
	}

	private static ReportDataSource resolve(ReportSpec s) {
		return ReportCatalog.withLinks(sources.get(s.getDataSource()), s.getLinks(), sources::get, false);
	}

	private ReportResult run(ReportSpec s) {
		return executor.run(em, resolve(s), s, null, 0, 100);
	}

	private static List<Long> firstColumn(ReportResult r) {
		List<Long> out = new ArrayList<>();
		for (Object[] row : r.getRows())
			out.add(((Number) row[0]).longValue());
		return out;
	}

	private static void assertDecimal(String expected, Object actual) {
		assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));
	}

	private void assertRefused(ReportSpec s, ReportDataSource ds, String key) {
		try {
			SpecValidator.validate(s, ds, true);
			fail("should be refused with " + key);
		} catch (ReportException e) {
			assertEquals(key, e.getMessageKey());
		}
	}

	@Test
	public void onlyLinesWithAMatchingRowShowTheLinkedColumns() {
		ReportResult r = run(spec("id", "quantity", "l1:available"));
		assertEquals("no stock for line 4 (third item) nor line 7 (other warehouse)", Arrays.asList(1L, 2L, 3L, 5L, 6L),
				firstColumn(r));
		assertDecimal("40", r.getRows().get(0)[2]);
		assertDecimal("8", r.getRows().get(1)[2]);
		assertDecimal("15", r.getRows().get(2)[2]);
		assertDecimal("8", r.getRows().get(3)[2]);
		assertDecimal("40", r.getRows().get(4)[2]);
		assertEquals("the linked column is named after its table", 3, r.getColumns().size());
		String table = sources.get("auto.TItemStock").getLabel(null);
		String own = sources.get("auto.TItemStock").getField("available").getLabel(null);
		assertEquals(table + " - " + own, r.getColumns().get(2).getLabel(null));
	}

	@Test
	public void conditionsAndSortingOnLinkedFields() {
		ReportSpec s = spec("id", "l1:available");
		s.setFilter(FilterNode.rule("a", "l1:available", Operator.GT, "10"));
		s.getSort().add(new SortSpec(1, false));
		ReportResult r = run(s);
		// 15 (line 3) then the two 40s in line order (the tie-breaker)
		assertEquals(Arrays.asList(3L, 1L, 6L), firstColumn(r));
	}

	@Test
	public void totalsAndGroupingOverLinkedRows() {
		ReportSpec s = spec("item.name");
		s.getColumns().add(new ColumnSpec("quantity", Aggregate.SUM));
		s.getColumns().add(new ColumnSpec("l1:available", Aggregate.MAX));
		s.getColumns().add(new ColumnSpec(ReportDataSource.ROW_COUNT_FIELD, Aggregate.COUNT));
		s.setTotals(true);
		s.getSort().add(new SortSpec(0, false));
		ReportResult r = run(s);
		assertEquals(2, r.getRows().size());
		assertEquals("apple", r.getRows().get(0)[0]);
		assertDecimal("115", r.getRows().get(0)[1]); // 5 + 10 + 100
		assertDecimal("40", r.getRows().get(0)[2]);
		assertEquals(3L, ((Number) r.getRows().get(0)[3]).longValue());
		assertEquals("bread", r.getRows().get(1)[0]);
		assertDecimal("9", r.getRows().get(1)[1]); // 2 + 7
		assertDecimal("124", r.getTotals().getValues()[1]);
		assertEquals(5L, ((Number) r.getTotals().getValues()[3]).longValue());
	}

	/**
	 * Linked by the item only, an apple line matches its two stock rows
	 * (warehouses 10 and 11) and appears twice: pages must neither repeat nor
	 * skip such rows.
	 */
	@Test
	public void repeatedRowsArePagedInAStableOrder() {
		ReportSpec s = new ReportSpec();
		s.setDataSource("auto.TInvoiceLine");
		LinkSpec l = new LinkSpec("l1", "auto.TItemStock");
		l.getOn().add(new LinkSpec.Pair("item.id", "item_id"));
		s.getLinks().add(l);
		s.getColumns().add(new ColumnSpec("id", Aggregate.NONE));
		s.getColumns().add(new ColumnSpec("l1:id", Aggregate.NONE));
		ReportDataSource ds = resolve(s);
		List<String> all = new ArrayList<>();
		for (int first = 0; first < 30; first += 3) {
			ReportResult page = executor.run(em, ds, s, null, first, 3);
			for (Object[] row : page.getRows())
				all.add(row[0] + "/" + row[1]);
			if (!page.isHasMore())
				break;
		}
		// apple lines 1, 3, 6, 7 x stock 1 and 2; bread lines 2, 5 x stock 3
		assertEquals(10, all.size());
		assertEquals("no row twice", 10, new HashSet<>(all).size());
		assertEquals(Arrays.asList("1/1", "1/2", "2/3", "3/1", "3/2", "5/3", "6/1", "6/2", "7/1", "7/2"), all);
	}

	@Test
	public void rowRestrictionsApplyToTheLinkedTable() {
		// the user may only see warehouse 10's values: a rule on the stock table's warehouse_id column
		RowRestrictions restrictions = new RowRestrictions("u1", null, null).allowOnlyValues(Collections.singletonList(10.0),
				"warehouse_id");
		ReportRunContext run = new ReportRunContext("u1", null, null, restrictions);
		ReportSpec s = spec("id", "l1:available");
		ReportResult r = executor.run(em, resolve(s), s, run, 0, 100);
		assertEquals("line 3 is linked to warehouse 11's stock", Arrays.asList(1L, 2L, 5L, 6L), firstColumn(r));
	}

	@Test
	public void aSecondLinkMayUseTheFieldsOfTheFirst() {
		ReportSpec s = spec("id", "l1:available", "l2:available");
		// the same stock row again, reached through the first link's columns
		LinkSpec l2 = new LinkSpec("l2", "auto.TItemStock");
		l2.getOn().add(new LinkSpec.Pair("l1:id", "id"));
		s.getLinks().add(l2);
		ReportResult r = run(s);
		assertEquals(Arrays.asList(1L, 2L, 3L, 5L, 6L), firstColumn(r));
		for (Object[] row : r.getRows())
			assertDecimal(row[1].toString(), row[2]);
	}

	@Test
	public void linkedDefinitionIsSavedAsJson() {
		ReportSpec s = spec("id", "l1:available");
		s.setFilter(FilterNode.rule("a", "l1:warehouse_id", Operator.EQ, "10"));
		ReportSpec back = SpecJson.fromJson(SpecJson.toJson(s));
		assertEquals(1, back.getLinks().size());
		LinkSpec l = back.getLinks().get(0);
		assertEquals("l1", l.getAlias());
		assertEquals("auto.TItemStock", l.getDataSource());
		assertEquals(2, l.getOn().size());
		assertEquals("invoice.warehouse.id", l.getOn().get(1).getLeft());
		assertEquals("warehouse_id", l.getOn().get(1).getRight());
		assertEquals(Arrays.asList(1L, 2L, 5L, 6L), firstColumn(run(back)));
		ReportSpec plain = new ReportSpec();
		plain.setDataSource("auto.TItem");
		assertFalse(SpecJson.toJson(plain).contains("links"));
		assertTrue(SpecJson.fromJson(SpecJson.toJson(plain)).getLinks().isEmpty());
	}

	@Test
	public void theLinkedViewLeavesTheCatalogUnchanged() {
		ReportDataSource lines = sources.get("auto.TInvoiceLine");
		ReportSpec s = spec("id");
		ReportDataSource view = resolve(s);
		assertSame(lines, view.getBase());
		assertEquals(lines.getKey(), view.getKey());
		assertNull("the catalog's data source has no linked fields", lines.getField("l1:available"));
		ReportField f = view.getField("l1:available");
		assertTrue(f.isLinked());
		assertEquals("l1", f.getLinkAlias());
		assertEquals("available", f.getLinkedField().getPath());
		assertNull("not a join of the data source", f.getJoinPath());
		assertSame("no links: the data source itself", lines,
				ReportCatalog.withLinks(lines, Collections.<LinkSpec>emptyList(), sources::get, false));
	}

	@Test
	public void designerViewLeavesOutUnfinishedLinks() {
		ReportSpec s = spec("id");
		s.getLinks().add(new LinkSpec("l2", null)); // table not chosen yet
		s.getLinks().get(0).getOn().add(new LinkSpec.Pair("item.id", null)); // pair not finished
		ReportDataSource view = ReportCatalog.withLinks(sources.get("auto.TInvoiceLine"), s.getLinks(), sources::get, true);
		assertEquals(1, view.getLinks().size());
		assertEquals(2, view.getLinks().get("l1").getPairs().size());
		try {
			ReportCatalog.withLinks(sources.get("auto.TInvoiceLine"), s.getLinks(), sources::get, false);
			fail("running needs every link complete");
		} catch (ReportException e) {
			assertEquals("rb.error.invalidLink", e.getMessageKey());
		}
	}

	@Test
	public void invalidLinksAreRefused() {
		// incomplete pair
		ReportSpec s = spec("id");
		s.getLinks().get(0).getOn().get(1).setRight(null);
		assertRefused(s, resolve(s), "rb.error.linkNeedsPair");
		// text compared with a number
		s = spec("id");
		s.getLinks().get(0).getOn().get(0).setLeft("item.name");
		assertRefused(s, resolve(s), "rb.error.linkTypes");
		// a field the linked table does not have
		s = spec("id");
		s.getLinks().get(0).getOn().get(0).setRight("nothing");
		assertRefused(s, resolve(s), "rb.error.unknownField");
		// a link cannot use its own fields, nor those of a later link
		s = spec("id");
		s.getLinks().get(0).getOn().get(0).setLeft("l1:item_id");
		assertRefused(s, resolve(s), "rb.error.unknownField");
		// the links the data source was resolved with must be the report's
		s = spec("id");
		assertRefused(s, sources.get("auto.TInvoiceLine"), "rb.error.invalidLink");
		ReportSpec other = spec("id");
		other.getLinks().get(0).getOn().remove(1);
		assertRefused(s, resolve(other), "rb.error.invalidLink");
		// bad alias
		s = spec("id");
		s.getLinks().get(0).setAlias("x");
		try {
			resolve(s);
			fail();
		} catch (ReportException e) {
			assertEquals("rb.error.invalidLink", e.getMessageKey());
		}
		// too many links
		s = spec("id");
		for (int i = 2; i <= SpecValidator.MAX_LINKS + 1; i++) {
			LinkSpec l = new LinkSpec("l" + i, "auto.TItemStock");
			l.getOn().add(new LinkSpec.Pair("item.id", "item_id"));
			s.getLinks().add(l);
		}
		assertRefused(s, resolve(s), "rb.error.tooManyLinks");
		// a linked field reached through a collection is not offered
		Set<String> paths = new HashSet<>();
		ReportSpec orders = new ReportSpec();
		orders.setDataSource("auto.TItem");
		LinkSpec l = new LinkSpec("l1", "auto.TOrder");
		l.getOn().add(new LinkSpec.Pair("id", "id"));
		orders.getLinks().add(l);
		for (ReportField f : resolve(orders).getFields())
			paths.add(f.getPath());
		for (ReportField f : sources.get("auto.TOrder").getFields())
			assertEquals(f.getPath(), !f.isViaCollection(), paths.contains("l1:" + f.getPath()));
	}

	@Test
	public void typesThatCanBeCompared() {
		assertTrue(SpecValidator.comparable(org.softcom.reportbuilder.spi.FieldType.LONG,
				org.softcom.reportbuilder.spi.FieldType.DOUBLE));
		assertTrue(SpecValidator.comparable(org.softcom.reportbuilder.spi.FieldType.STRING,
				org.softcom.reportbuilder.spi.FieldType.ENUM));
		assertTrue(SpecValidator.comparable(org.softcom.reportbuilder.spi.FieldType.DATE,
				org.softcom.reportbuilder.spi.FieldType.DATETIME));
		assertFalse(SpecValidator.comparable(org.softcom.reportbuilder.spi.FieldType.STRING,
				org.softcom.reportbuilder.spi.FieldType.LONG));
		assertFalse(SpecValidator.comparable(org.softcom.reportbuilder.spi.FieldType.BOOLEAN,
				org.softcom.reportbuilder.spi.FieldType.INTEGER));
	}
}
