package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;

import org.junit.AfterClass;
import org.junit.Assume;
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

/** The large-table rule, the field-to-column mapping behind it, and the catalog statistics on PostgreSQL. */
public class SpeedTest {

	private static EntityManager em;
	private final ReportExecutor executor = new ReportExecutor();

	@BeforeClass
	public static void init() {
		em = TestDb.emf().createEntityManager();
	}

	@AfterClass
	public static void close() {
		em.close();
	}

	private static ReportDataSource auto(String key) {
		for (ReportDataSource ds : EntityDiscovery.discover(em.getMetamodel(), new LabelResolver(null, null, null),
				new EntityDiscovery.Options()))
			if (ds.getKey().equals(key))
				return ds;
		throw new AssertionError(key);
	}

	private static final SpeedInfo LARGE = new SpeedInfo(2500000, Arrays.asList("id", "customer.id", "orderDate"), true);

	private static ReportSpec spec(FilterNode filter) {
		ReportSpec s = new ReportSpec();
		s.setDataSource("auto.TOrder");
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("id", Aggregate.NONE))));
		s.setFilter(filter);
		return s;
	}

	private static void assertRefused(ReportSpec s, ReportDataSource ds, SpeedInfo speed) {
		try {
			SpeedRules.check(s, ds, speed, 366);
			fail("should need a fast condition");
		} catch (ReportException e) {
			assertEquals("rb.error.needFastFilter", e.getMessageKey());
		}
	}

	@Test
	public void largeTableNeedsAFastConditionOnTheWholeResult() {
		ReportDataSource ds = auto("auto.TOrder");
		try {
			SpeedRules.check(spec(null), ds, LARGE, 366);
			fail();
		} catch (ReportException e) {
			assertEquals("rb.error.needFastFilter", e.getMessageKey());
			assertEquals("dates first", Arrays.asList("orderDate", "id", "customer.id"), e.getArgs()[0]);
			assertEquals(2500000L, e.getArgs()[1]);
		}
		SpeedRules.check(spec(FilterNode.group(Logic.AND, FilterNode.rule("a", "orderDate", Operator.BETWEEN, "2026-01-01",
				"2026-12-31"))), ds, LARGE, 366);
		SpeedRules.check(spec(FilterNode.rule("a", "id", Operator.EQ, "5")), ds, LARGE, 366);
		SpeedRules.check(spec(FilterNode.rule("a", "customer.id", Operator.IN, "5", "6")), ds, LARGE, 366);
		assertRefused(spec(FilterNode.rule("a", "orderDate", Operator.BETWEEN, "2025-01-01", "2026-12-31")), ds, LARGE);
		assertRefused(spec(FilterNode.rule("a", "orderDate", Operator.GE, "2026-01-01")), ds, LARGE);
		assertRefused(spec(FilterNode.rule("a", "customer.name", Operator.EQ, "X")), ds, LARGE);
		assertRefused(spec(FilterNode.group(Logic.OR, FilterNode.rule("a", "id", Operator.EQ, "5"),
				FilterNode.rule("b", "customer.name", Operator.EQ, "X"))), ds, LARGE);
	}

	@Test
	public void noRuleForSmallOrUnindexedTablesOrOwnRequiredFilters() {
		ReportDataSource ds = auto("auto.TOrder");
		SpeedRules.check(spec(null), ds, new SpeedInfo(1000, LARGE.getFastFields(), false), 366);
		SpeedRules.check(spec(null), ds, new SpeedInfo(9000000, new LinkedHashSet<String>(), true), 366);
		SpeedInfo onlyKey = new SpeedInfo(9000000, Arrays.asList("id"), Arrays.asList("id"), true);
		assertTrue(onlyKey.getUsefulFastFields().isEmpty());
		SpeedRules.check(spec(null), ds, onlyKey, 366); // "id = ..." is no report filter: left to the timeout
		SpeedInfo keyAndDate = new SpeedInfo(9000000, Arrays.asList("id", "orderDate"), Arrays.asList("id"), true);
		assertRefused(spec(null), ds, keyAndDate);
		SpeedRules.check(spec(FilterNode.rule("a", "id", Operator.EQ, "5")), ds, keyAndDate, 366); // still fast
		SpeedRules.check(spec(null), ds, SpeedInfo.UNKNOWN, 366);
		SpeedRules.check(spec(null), ds, null, 366);
		ReportDataSource handWritten = new ReportDataSource("auto.TOrder", ds.getRootEntity())
				.add(org.softcom.reportbuilder.spi.ReportField.of("id", org.softcom.reportbuilder.spi.FieldType.LONG, null, "Id"));
		SpeedRules.check(spec(null), handWritten, LARGE, 366); // designed by a developer: the rule is not applied
	}

	@Test
	public void executorAppliesTheRuleAndRunsFastReports() {
		ReportDataSource ds = auto("auto.TOrder");
		try {
			executor.run(em, ds, spec(null), null, 0, 10, LARGE);
			fail();
		} catch (ReportException e) {
			assertEquals("rb.error.needFastFilter", e.getMessageKey());
		}
		ReportResult r = executor.run(em, ds,
				spec(FilterNode.rule("a", "orderDate", Operator.BETWEEN, "2026-03-01", "2026-03-31")), null, 0, 10, LARGE);
		assertEquals(2, r.getRows().size());
		assertEquals("orderDate", SpeedRules.suggestedDateField(ds, LARGE));
		assertNull(SpeedRules.suggestedDateField(ds, new SpeedInfo(10, LARGE.getFastFields(), false)));
	}

	@Test
	public void fieldsMapToColumnsOfTheirOwnTableOnly() {
		Map<String, String> order = TableStats.columns(em, auto("auto.TOrder"));
		assertEquals("t_order.orderdate", order.get("orderDate"));
		assertEquals("t_order.id", order.get("id"));
		assertEquals("the foreign key column", "t_order.customer_id", order.get("customer.id"));
		assertFalse("a joined table's column does not make the root scan cheaper", order.containsKey("customer.name"));

		Map<String, String> lines = TableStats.columns(em, auto("auto.TOrder.lines"));
		assertEquals("t_orderline.quantity", lines.get("lines.quantity"));
		assertEquals("t_orderline.item_id", lines.get("lines.item.id"));
		assertEquals("t_order.orderdate", lines.get("orderDate"));
		assertFalse(lines.containsKey("lines.item.name"));
	}

	@Test
	public void unknownWithoutPostgresCatalog() {
		Assume.assumeFalse(isPostgres());
		TableStats.clear();
		assertSame(SpeedInfo.UNKNOWN, TableStats.of(em, auto("auto.TOrder"), 1));
	}

	@Test
	public void postgresSizeAndIndexes() {
		Assume.assumeTrue(isPostgres());
		em.getTransaction().begin();
		em.createNativeQuery("CREATE INDEX IF NOT EXISTS ix_t_order_orderdate ON t_order (orderdate)").executeUpdate();
		em.createNativeQuery("CREATE INDEX IF NOT EXISTS ix_t_order_part ON t_order (customer_id) WHERE id > 0").executeUpdate();
		em.getTransaction().commit();
		em.getTransaction().begin();
		em.createNativeQuery("ANALYZE t_order").executeUpdate();
		em.getTransaction().commit();
		TableStats.clear();
		SpeedInfo info = TableStats.of(em, auto("auto.TOrder"), 1);
		assertTrue(info.getEstimatedRows() >= 2);
		assertTrue(info.isLarge());
		Set<String> fast = info.getFastFields();
		assertTrue(fast.toString(), fast.contains("orderDate"));
		assertTrue("primary key", fast.contains("id"));
		assertEquals("the primary key is fast but not what reports filter on", new LinkedHashSet<>(Arrays.asList("orderDate")),
				info.getUsefulFastFields());
		assertFalse("a partial index may not apply", fast.contains("customer.id"));

		// collection data source: the order date only counts when the lines' link column to the order is indexed
		em.getTransaction().begin();
		em.createNativeQuery("DROP INDEX IF EXISTS ix_t_orderline_order").executeUpdate();
		em.getTransaction().commit();
		TableStats.clear();
		SpeedInfo lines = TableStats.of(em, auto("auto.TOrder.lines"), 1);
		assertFalse(lines.getFastFields().toString(), lines.isFast("orderDate"));
		assertTrue("the line's own primary key", lines.isFast("lines.id"));
		em.getTransaction().begin();
		em.createNativeQuery("CREATE INDEX ix_t_orderline_order ON t_orderline (order_id)").executeUpdate();
		em.getTransaction().commit();
		TableStats.clear();
		assertTrue(TableStats.of(em, auto("auto.TOrder.lines"), 1).isFast("orderDate"));
	}

	@Test
	public void bundleNamesAreReadFromFacesConfig() throws Exception {
		String xml = "<?xml version=\"1.0\"?><faces-config xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" version=\"2.2\">"
				+ "<application><resource-bundle><base-name>resources.bilalbundle</base-name><var>b</var></resource-bundle>"
				+ "<message-bundle>resources.messages</message-bundle></application></faces-config>";
		Set<String> names = new LinkedHashSet<>();
		java.lang.reflect.Method m = LabelResolver.class.getDeclaredMethod("readBundleNames", java.io.InputStream.class, Set.class);
		m.setAccessible(true);
		m.invoke(null, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), names);
		assertEquals(Arrays.asList("resources.bilalbundle", "resources.messages"), new ArrayList<>(names));
		assertEquals("Purchase price", LabelResolver.humanize("purchasePrice"));
		assertEquals("Sales invoice", LabelResolver.humanize("SALES_INVOICE"));
		assertEquals("Vat number", LabelResolver.humanize("VATNumber"));
	}

	private static boolean isPostgres() {
		String url = System.getProperty("rb.test.pg.url");
		return url != null && !url.isEmpty();
	}
}
