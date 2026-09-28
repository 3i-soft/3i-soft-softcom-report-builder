package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.JoinType;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.FilterNode.Logic;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SortSpec;
import org.softcom.reportbuilder.spec.SpecJson;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;
import org.softcom.reportbuilder.testmodel.CapturingSessionLog;
import org.softcom.reportbuilder.testmodel.TInvoiceLine;

/**
 * Runs the real engine on EclipseLink 2.6.0 + H2 with parameter binding
 * disabled at persistence-unit level (like acc-expert's accounting-PU).
 * Data: see {@link TestDb}.
 */
public class ReportExecutorTest {

	private static EntityManagerFactory emf;
	private EntityManager em;
	private final ReportExecutor executor = new ReportExecutor();
	private ReportDataSource ds;
	private ReportRunContext companyA;

	@BeforeClass
	public static void setUpData() {
		emf = TestDb.emf();
	}

	@Before
	public void setUp() {
		em = emf.createEntityManager();
		Map<String, String> kinds = new LinkedHashMap<>();
		kinds.put("FRUIT", "Fruit");
		kinds.put("BAKERY", "Bakery");
		kinds.put("OTHER", "Other");
		ds = new ReportDataSource("t.lines", TInvoiceLine.class).labels("بنود", "Lines")
				.join("invoice", JoinType.INNER).join("invoice.warehouse", JoinType.INNER).join("item", JoinType.INNER)
				.join("invoice.customer", JoinType.LEFT)
				.add(ReportField.of("item.name", FieldType.STRING, "المادة", "Item"))
				.add(ReportField.of("item.code", FieldType.STRING, "الرمز", "Code"))
				.add(ReportField.of("item.kind", FieldType.ENUM, "النوع", "Kind").enumValues(kinds))
				.add(ReportField.of("item.active", FieldType.BOOLEAN, "فعال", "Active"))
				.add(ReportField.of("quantity", FieldType.DECIMAL, "الكمية", "Quantity"))
				.add(ReportField.of("price", FieldType.DECIMAL, "السعر", "Price"))
				.add(ReportField.of("lineNo", FieldType.INTEGER, "السطر", "Line").aggregatable(false))
				.add(ReportField.of("invoice.invoiceDate", FieldType.DATE, "التاريخ", "Date"))
				.add(ReportField.of("invoice.warehouse.name", FieldType.STRING, "المستودع", "Warehouse"))
				.add(ReportField.of("invoice.customer.name", FieldType.STRING, "الزبون", "Customer"))
				.requireFilterOn("invoice.invoiceDate", 366)
				.forcedFilter(ctx -> {
					CriteriaBuilder cb = ctx.getCriteriaBuilder();
					return Arrays.asList(cb.equal(ctx.path("invoice.company.id"), ctx.getAttributes().get("companyId")),
							cb.isTrue(ctx.<Boolean>path("invoice.closed")));
				});
		ds.checkConsistency();
		Map<String, Object> attrs = new HashMap<>();
		attrs.put("companyId", 1L);
		companyA = new ReportRunContext("tester", role -> false, attrs);
		CapturingSessionLog.SQL.clear();
	}

	// ---------------------------------------------------------------- helpers

	private static FilterNode january() {
		return FilterNode.rule("period", "invoice.invoiceDate", Operator.BETWEEN, "2026-01-01", "2026-01-31");
	}

	private ReportSpec spec(FilterNode filter, ColumnSpec... columns) {
		ReportSpec s = new ReportSpec();
		s.setDataSource("t.lines");
		s.setColumns(new ArrayList<>(Arrays.asList(columns)));
		s.setFilter(filter);
		return s;
	}

	private static ColumnSpec col(String field) {
		return new ColumnSpec(field, Aggregate.NONE);
	}

	private static ColumnSpec col(String field, Aggregate agg) {
		return new ColumnSpec(field, agg);
	}

	private ReportResult run(ReportSpec spec) {
		return executor.run(em, ds, spec, companyA, 0, 100);
	}

	private static Set<Object> firstColumn(ReportResult r) {
		Set<Object> set = new HashSet<>();
		for (Object[] row : r.getRows())
			set.add(row[0]);
		return set;
	}

	private void expectError(String key, ReportSpec spec) {
		try {
			run(spec);
			fail("expected " + key);
		} catch (ReportException e) {
			assertEquals(key, e.getMessageKey());
		}
	}

	// ------------------------------------------------------------------ tests

	@Test
	public void forcedFilterExcludesOtherCompanyAndOpenInvoices() {
		ReportResult r = run(spec(january(), col("quantity"), col("item.name")));
		// lines 1,2,3,4 (January, closed, company A); 6 (open) and 7 (company B) excluded; 5 is February
		assertEquals(4, r.getRows().size());
		for (Object[] row : r.getRows()) {
			BigDecimal q = (BigDecimal) row[0];
			assertTrue("unexpected quantity " + q, q.compareTo(new BigDecimal("100")) < 0);
		}
		assertFalse(r.isHasMore());
	}

	@Test
	public void dateBetweenIncludesTheWholeLastDay() {
		ReportResult r = run(spec(january(), col("quantity")));
		Set<Object> q = firstColumn(r);
		assertTrue("line of 2026-01-31 15:30 must be included", q.contains(new BigDecimal("10.00")) || containsNumber(q, 10));
		ReportResult eq = run(spec(FilterNode.group(Logic.AND,
				FilterNode.rule("d", "invoice.invoiceDate", Operator.BETWEEN, "2026-01-31", "2026-01-31")), col("quantity")));
		assertEquals(2, eq.getRows().size()); // lines 3 and 4 only; the 2026-02-01 00:00 invoice is excluded
	}

	private static boolean containsNumber(Set<Object> values, int n) {
		for (Object v : values)
			if (v instanceof Number && ((Number) v).intValue() == n)
				return true;
		return false;
	}

	@Test
	public void groupedTotalsAreComputedByTheDatabase() {
		ReportSpec s = spec(january(), col("item.name"), col("quantity", Aggregate.SUM), col("*", Aggregate.COUNT));
		s.getSort().add(new SortSpec(1, true));
		ReportResult r = run(s);
		assertEquals(3, r.getRows().size());
		Object[] top = r.getRows().get(0);
		assertEquals("apple", top[0]);
		assertEquals(0, new BigDecimal("15").compareTo(new BigDecimal(top[1].toString())));
		assertEquals(2L, ((Number) top[2]).longValue());
		assertEquals("Quantity (SUM)", r.getColumns().get(1).getLabel(java.util.Locale.ENGLISH));
	}

	@Test
	public void userOrCannotEscapeForcedFilter() {
		// (date in January) AND (item = apple OR quantity >= -1): the OR is always true for company A rows,
		// but company B's 1000-unit line and the open invoice must still be excluded.
		FilterNode filter = FilterNode.group(Logic.AND, january(), FilterNode.group(Logic.OR,
				FilterNode.rule("a", "item.name", Operator.EQ, "apple"),
				FilterNode.rule("b", "quantity", Operator.GE, "-1")));
		ReportResult r = run(spec(filter, col("quantity", Aggregate.SUM)));
		assertEquals(0, new BigDecimal("18").compareTo(new BigDecimal(r.getRows().get(0)[0].toString())));
	}

	@Test
	public void requiredDateFilterInsideAnOrGroupIsRejected() {
		FilterNode filter = FilterNode.group(Logic.OR, january(), FilterNode.rule("x", "item.name", Operator.EQ, "apple"));
		// this data source also caps the range, so the date-range variant of the message is used
		expectError("rb.error.requiredDateRange", spec(filter, col("quantity")));
		ReportDataSource uncapped = new ReportDataSource("t.lines", TInvoiceLine.class)
				.join("invoice", JoinType.INNER).join("item", JoinType.INNER)
				.add(ReportField.of("item.name", FieldType.STRING, null, "Item"))
				.add(ReportField.of("quantity", FieldType.DECIMAL, null, "Quantity"))
				.add(ReportField.of("invoice.invoiceDate", FieldType.DATE, null, "Date"))
				.requireFilterOn("invoice.invoiceDate", 0);
		try {
			executor.run(em, uncapped, spec(filter, col("quantity")), companyA, 0, 10);
			fail("expected rb.error.requiredFilter");
		} catch (ReportException e) {
			assertEquals("rb.error.requiredFilter", e.getMessageKey());
		}
	}

	@Test
	public void dateRangeLongerThanAllowedIsRejected() {
		FilterNode filter = FilterNode.rule("p", "invoice.invoiceDate", Operator.BETWEEN, "2024-01-01", "2026-01-31");
		expectError("rb.error.requiredDateRange", spec(filter, col("quantity")));
	}

	@Test
	public void sqlInjectionAttemptIsTreatedAsPlainValue() {
		String attack = "x' OR '1'='1";
		ReportResult r = run(spec(FilterNode.group(Logic.AND, january(), FilterNode.rule("n", "item.name", Operator.EQ, attack)),
				col("quantity")));
		assertEquals(0, r.getRows().size());
		assertFalse("expected SQL to be captured", CapturingSessionLog.SQL.isEmpty());
		boolean sawBoundName = false;
		for (String entry : CapturingSessionLog.SQL) {
			// EclipseLink appends the bound values as "bind => [...]" to the same log entry; check only the SQL text
			int bind = entry.indexOf("bind =>");
			String sql = bind < 0 ? entry : entry.substring(0, bind);
			assertFalse("user value must not be inlined into SQL: " + sql, sql.contains("'1'='1"));
			if (sql.contains("NAME = ?"))
				sawBoundName = true;
		}
		assertTrue("the item name condition must be a bound parameter", sawBoundName);
	}

	@Test
	public void forcedFilterOnForeignKeyIdAddsNoExtraJoin() {
		run(spec(january(), col("quantity")));
		for (String entry : CapturingSessionLog.SQL)
			assertFalse("company id should come from the invoice foreign key, not a join: " + entry,
					entry.toLowerCase().contains("t_company"));
	}

	@Test
	public void quotesAndLikeWildcardsAreMatchedLiterally() {
		ReportResult quote = run(spec(FilterNode.group(Logic.AND, january(),
				FilterNode.rule("n", "item.name", Operator.CONTAINS, "O'Brien")), col("item.name")));
		assertEquals(Collections.singleton((Object) "O'Brien 50% mix"), firstColumn(quote));
		ReportResult percent = run(spec(FilterNode.group(Logic.AND, january(),
				FilterNode.rule("n", "item.name", Operator.CONTAINS, "%")), col("item.name")));
		assertEquals("'%' must match only names containing a literal percent sign", 1, percent.getRows().size());
		ReportResult underscore = run(spec(FilterNode.group(Logic.AND, january(),
				FilterNode.rule("n", "item.name", Operator.CONTAINS, "_")), col("item.name")));
		assertEquals(0, underscore.getRows().size());
	}

	@Test
	public void whitelistIsEnforced() {
		expectError("rb.error.unknownField", spec(january(), col("invoice.company.name")));
		expectError("rb.error.unknownField",
				spec(FilterNode.group(Logic.AND, january(), FilterNode.rule("c", "invoice.closed", Operator.IS_FALSE)), col("quantity")));
		expectError("rb.error.operatorNotAllowed",
				spec(FilterNode.group(Logic.AND, january(), FilterNode.rule("q", "quantity", Operator.CONTAINS, "1")), col("quantity")));
		expectError("rb.error.aggregateNotAllowed", spec(january(), col("item.name", Aggregate.SUM)));
		expectError("rb.error.aggregateNotAllowed", spec(january(), col("lineNo", Aggregate.SUM)));
	}

	@Test
	public void pagingIsStableAndSignalsMore() {
		ReportSpec s = spec(january(), col("quantity"));
		ReportResult p1 = executor.run(em, ds, s, companyA, 0, 3);
		ReportResult p2 = executor.run(em, ds, s, companyA, 3, 3);
		assertEquals(3, p1.getRows().size());
		assertTrue(p1.isHasMore());
		assertEquals(1, p2.getRows().size());
		assertFalse(p2.isHasMore());
	}

	@Test
	public void enumBooleanAndInOperators() {
		ReportResult fruit = run(spec(FilterNode.group(Logic.AND, january(),
				FilterNode.rule("k", "item.kind", Operator.IN, "FRUIT", "OTHER")), col("item.name")));
		assertEquals(new HashSet<Object>(Arrays.asList("apple", "O'Brien 50% mix")), firstColumn(fruit));
		ReportResult inactive = run(spec(FilterNode.group(Logic.AND, january(),
				FilterNode.rule("a", "item.active", Operator.IS_FALSE)), col("item.name")));
		assertEquals(Collections.singleton((Object) "O'Brien 50% mix"), firstColumn(inactive));
		ReportResult notIn = run(spec(FilterNode.group(Logic.AND, january(),
				FilterNode.rule("c", "item.code", Operator.NOT_IN, "F-001", "O-001")), col("item.name")));
		assertEquals(Collections.singleton((Object) "bread"), firstColumn(notIn));
	}

	@Test
	public void askAtRunParametersAreFilledAtRunTime() {
		FilterNode period = FilterNode.rule("period", "invoice.invoiceDate", Operator.BETWEEN);
		period.setAskAtRun(true);
		ReportSpec s = spec(FilterNode.group(Logic.AND, period), col("quantity", Aggregate.SUM));
		SpecValidator.validate(s, ds, false); // a design-time save is fine without values
		expectError("rb.error.valueRequired", s);
		Map<String, List<String>> params = new HashMap<>();
		params.put("period", Arrays.asList("2026-02-01", "2026-02-28"));
		ReportResult r = run(ReportExecutor.applyParameters(s, params));
		assertEquals(0, new BigDecimal("7").compareTo(new BigDecimal(r.getRows().get(0)[0].toString())));
	}

	@Test
	public void arabicIndicDigitsAreAccepted() {
		ReportResult r = run(spec(FilterNode.group(Logic.AND, january(),
				FilterNode.rule("q", "quantity", Operator.GE, "٥")), col("quantity"))); // ٥ = 5
		assertEquals(2, r.getRows().size()); // 5 and 10
	}

	@Test
	public void leftJoinKeepsRowsWithoutTheRelation() {
		ReportResult r = run(spec(january(), col("invoice.customer.name"), col("quantity")));
		assertEquals(4, r.getRows().size());
		int nulls = 0;
		for (Object[] row : r.getRows())
			if (row[0] == null)
				nulls++;
		assertEquals(2, nulls);
	}

	@Test
	public void minMaxOnDatesUseComparableAggregates() {
		ReportResult r = run(spec(january(), col("invoice.invoiceDate", Aggregate.MIN), col("invoice.invoiceDate", Aggregate.MAX)));
		Object[] row = r.getRows().get(0);
		assertNotNull(row[0]);
		assertTrue(((Date) row[0]).before((Date) row[1]));
	}

	@Test
	public void exportStreamsAllRowsWithinTheCap() {
		final List<Object[]> out = new ArrayList<>();
		final List<ResultColumn> cols = new ArrayList<>();
		int n = executor.export(em, ds, spec(january(), col("item.name"), col("quantity")), companyA, 0, new ReportExecutor.RowSink() {
			@Override
			public void begin(List<ResultColumn> columns) {
				cols.addAll(columns);
			}

			@Override
			public void rows(List<Object[]> rows) {
				out.addAll(rows);
			}
		});
		assertEquals(4, n);
		assertEquals(4, out.size());
		assertEquals(2, cols.size());
	}

	@Test
	public void jsonRoundTripPreservesTheDefinition() {
		FilterNode period = january();
		period.setAskAtRun(true);
		period.setLabel("الفترة");
		ReportSpec s = spec(FilterNode.group(Logic.AND, period, FilterNode.group(Logic.OR,
				FilterNode.rule("k", "item.kind", Operator.IN, "FRUIT", "BAKERY"),
				FilterNode.rule("n", "item.name", Operator.IS_NULL))), col("item.name"), col("quantity", Aggregate.SUM));
		s.getColumns().get(1).setLabel("المجموع");
		s.getSort().add(new SortSpec(1, true));
		ReportSpec back = SpecJson.fromJson(SpecJson.toJson(s));
		assertEquals(SpecJson.toJson(s), SpecJson.toJson(back));
		assertEquals(Aggregate.SUM, back.getColumns().get(1).getAggregate());
		assertEquals(Logic.OR, back.getFilter().getChildren().get(1).getLogic());
		assertEquals(Arrays.asList("FRUIT", "BAKERY"), back.getFilter().getChildren().get(1).getChildren().get(0).getValues());
		assertTrue(back.findRule("period").isAskAtRun());
		assertNull(back.findRule("missing"));
	}

	@Test
	public void malformedJsonIsReportedNotThrownRaw() {
		try {
			SpecJson.fromJson("{\"columns\": [ {\"field\": \"quantity\", \"aggregate\": \"DROP TABLE\"} ]}");
			fail();
		} catch (ReportException e) {
			assertEquals("rb.error.invalidDefinition", e.getMessageKey());
		}
		try {
			SpecJson.fromJson("{not json");
			fail();
		} catch (ReportException e) {
			assertEquals("rb.error.invalidDefinition", e.getMessageKey());
		}
	}
}
