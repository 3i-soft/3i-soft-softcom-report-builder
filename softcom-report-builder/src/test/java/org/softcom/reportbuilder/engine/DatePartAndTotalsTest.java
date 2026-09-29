package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.persistence.EntityManager;
import javax.persistence.criteria.JoinType;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.softcom.reportbuilder.export.ReportFormatter;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.DatePart;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.FilterNode.Logic;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SortSpec;
import org.softcom.reportbuilder.spec.SpecJson;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;
import org.softcom.reportbuilder.testmodel.TInvoice;

/**
 * Month / year columns, the grand total and subtotals, on the invoice lines of
 * {@link TestDb} (company A, closed): Main 2026-01-05 apple 5 + bread 2,
 * Branch 2026-01-31 apple 10 + O-001 1, Main 2026-02-01 bread 7.
 */
public class DatePartAndTotalsTest {

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

	private static ReportDataSource lines() {
		return new ReportDataSource("t.lines", TInvoice.class)
				.join("lines", JoinType.INNER).join("lines.item", JoinType.LEFT).join("warehouse", JoinType.LEFT)
				.grain("lines", "id")
				.add(ReportField.of("lines.item.code", FieldType.STRING, "المادة", "Item"))
				.add(ReportField.of("warehouse.name", FieldType.STRING, "المستودع", "Warehouse"))
				.add(ReportField.of("lines.quantity", FieldType.DECIMAL, "الكمية", "Quantity"))
				.add(ReportField.of("lines.price", FieldType.DECIMAL, "السعر", "Price"))
				.add(ReportField.of("lines.lineNo", FieldType.INTEGER, "رقم السطر", "Line").aggregatable(false))
				.add(ReportField.of("invoiceDate", FieldType.DATETIME, "التاريخ", "Date"))
				.forcedFilter(ctx -> Arrays.asList(
						ctx.getCriteriaBuilder().equal(ctx.path("company.id"), 1L),
						ctx.getCriteriaBuilder().isTrue(ctx.<Boolean>path("closed"))));
	}

	private static ReportSpec spec(ColumnSpec... cols) {
		ReportSpec s = new ReportSpec();
		s.setDataSource("t.lines");
		s.setColumns(new ArrayList<>(Arrays.asList(cols)));
		s.setFilter(FilterNode.group(Logic.AND,
				FilterNode.rule("p", "invoiceDate", Operator.BETWEEN, "2026-01-01", "2026-12-31")));
		return s;
	}

	private static ColumnSpec date(DatePart part) {
		ColumnSpec c = new ColumnSpec("invoiceDate", Aggregate.NONE);
		c.setDatePart(part);
		return c;
	}

	private static ColumnSpec col(String field, Aggregate agg) {
		return new ColumnSpec(field, agg);
	}

	private static String text(ReportResult r, Object[] row, int column) {
		return ReportFormatter.format(row[column], r.getColumns().get(column), null, new Locale("ar"));
	}

	private static void assertNumber(String expected, Object actual) {
		assertTrue("expected " + expected + " but was " + actual,
				actual != null && new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())) == 0);
	}

	@Test
	public void groupsByMonth() {
		ReportResult r = executor.run(em, lines(), spec(date(DatePart.MONTH), col("lines.quantity", Aggregate.SUM)), null, 0, 10);
		assertEquals(2, r.getRows().size());
		assertEquals("2026-01", text(r, r.getRows().get(0), 0));
		assertNumber("18", r.getRows().get(0)[1]); // 5 + 2 + 10 + 1, the 31st afternoon included
		assertEquals("2026-02", text(r, r.getRows().get(1), 0));
		assertNumber("7", r.getRows().get(1)[1]);
		assertEquals(FieldType.DATE, r.getColumns().get(0).getType());
		assertEquals("التاريخ (شهر)", r.getColumns().get(0).getLabel(new Locale("ar")));
	}

	@Test
	public void groupsByYearAndDay() {
		ReportResult y = executor.run(em, lines(), spec(date(DatePart.YEAR), col("lines.quantity", Aggregate.SUM)), null, 0, 10);
		assertEquals(1, y.getRows().size());
		assertEquals("2026", text(y, y.getRows().get(0), 0));
		assertNumber("25", y.getRows().get(0)[1]);
		ReportResult d = executor.run(em, lines(), spec(date(DatePart.DAY), col("lines.quantity", Aggregate.SUM)), null, 0, 10);
		assertEquals(3, d.getRows().size());
		assertEquals("2026-01-31", text(d, d.getRows().get(1), 0));
	}

	@Test
	public void monthColumnInAPlainList() {
		ReportSpec s = spec(date(DatePart.MONTH), col("lines.quantity", Aggregate.NONE));
		ReportResult r = executor.run(em, lines(), s, null, 0, 10);
		assertEquals(5, r.getRows().size());
		assertEquals("2026-01", text(r, r.getRows().get(0), 0));
	}

	@Test
	public void datePartOnlyOnDates() {
		ColumnSpec c = col("lines.quantity", Aggregate.NONE);
		c.setDatePart(DatePart.MONTH);
		try {
			executor.run(em, lines(), spec(c), null, 0, 10);
			fail();
		} catch (ReportException e) {
			assertEquals("rb.error.datePartNotAllowed", e.getMessageKey());
		}
	}

	@Test
	public void grandTotalOfAGroupedReportCoversAllRows() {
		ReportSpec s = spec(col("lines.item.code", Aggregate.NONE), col("lines.quantity", Aggregate.SUM),
				col("lines.price", Aggregate.AVG), col(ReportDataSource.ROW_COUNT_FIELD, Aggregate.COUNT),
				col("lines.item.code", Aggregate.COUNT_DISTINCT));
		s.setTotals(true);
		ReportResult r = executor.run(em, lines(), s, null, 0, 1);
		ResultRow t = r.getTotals();
		assertEquals(ResultRow.Kind.TOTAL, t.getKind());
		assertEquals("the words go in the column without a total", 0, t.getLabelColumn());
		assertNull(t.getValues()[0]);
		assertNumber("25", t.getValues()[1]);
		// average of the 5 lines, not of the groups: (2.50 + 1.00 + 2.40 + 9.99 + 1.10) / 5
		assertNumber("3.398", ((Number) t.getValues()[2]).doubleValue() + "");
		assertNumber("5", t.getValues()[3]);
		assertNumber("3", t.getValues()[4]);
		// later pages do not repeat the totals query
		assertNull(executor.run(em, lines(), s, null, 1, 1).getTotals());
	}

	@Test
	public void grandTotalOfAPlainListSumsTheNumbers() {
		ReportSpec s = spec(col("lines.item.code", Aggregate.NONE), col("lines.quantity", Aggregate.NONE),
				col("lines.lineNo", Aggregate.NONE));
		s.setTotals(true);
		ResultRow t = executor.run(em, lines(), s, null, 0, 2).getTotals();
		assertEquals(0, t.getLabelColumn());
		assertNumber("25", t.getValues()[1]);
		assertNull("line numbers are not summed", t.getValues()[2]);
		ReportSpec none = spec(col("lines.item.code", Aggregate.NONE));
		none.setTotals(true);
		assertNull("nothing to total", executor.run(em, lines(), none, null, 0, 2).getTotals());
	}

	private static ReportSpec byWarehouseAndItem() {
		ReportSpec s = spec(col("warehouse.name", Aggregate.NONE), col("lines.item.code", Aggregate.NONE),
				col("lines.quantity", Aggregate.SUM));
		s.setSubtotals(true);
		return s;
	}

	/** "Branch/F-001 10" style rows, "=Branch 11" for subtotals. */
	private static List<String> describe(ReportResult r) {
		List<String> out = new ArrayList<>();
		for (ResultRow row : r.getDisplayRows()) {
			Object[] v = row.getValues();
			String qty = new BigDecimal(v[2].toString()).stripTrailingZeros().toPlainString();
			if (row.getKind() == ResultRow.Kind.SUBTOTAL) {
				assertEquals(1, row.getLabelColumn());
				out.add("=" + text(r, v, 0) + " " + qty);
			} else {
				out.add(text(r, v, 0) + "/" + v[1] + " " + qty);
			}
		}
		return out;
	}

	@Test
	public void subtotalAfterEachValueOfTheFirstColumn() {
		ReportResult r = executor.run(em, lines(), byWarehouseAndItem(), null, 0, 10);
		assertEquals(Arrays.asList("Branch/F-001 10", "Branch/O-001 1", "=Branch 11", "Main/B-001 9", "Main/F-001 5",
				"=Main 14"), describe(r));
		assertEquals("data rows only", 4, r.getRows().size());
	}

	@Test
	public void subtotalOfAValueGoingOnOverThePageComesWithItsLastRow() {
		ReportSpec s = byWarehouseAndItem();
		ReportResult p1 = executor.run(em, lines(), s, null, 0, 3);
		assertEquals(Arrays.asList("Branch/F-001 10", "Branch/O-001 1", "=Branch 11", "Main/B-001 9"), describe(p1));
		ReportResult p2 = executor.run(em, lines(), s, null, 3, 3);
		assertEquals(Arrays.asList("Main/F-001 5", "=Main 14"), describe(p2));
		ReportResult exact = executor.run(em, lines(), s, null, 0, 2);
		assertEquals("the extra row shows the value ended", Arrays.asList("Branch/F-001 10", "Branch/O-001 1", "=Branch 11"),
				describe(exact));
	}

	@Test
	public void subtotalsFollowTheDirectionOfTheirColumn() {
		ReportSpec s = byWarehouseAndItem();
		// a sort on the total comes after the first column, else the values would be interleaved
		s.getSort().add(new SortSpec(2, true));
		s.getSort().add(new SortSpec(0, true));
		ReportResult r = executor.run(em, lines(), s, null, 0, 10);
		assertEquals(Arrays.asList("Main/B-001 9", "Main/F-001 5", "=Main 14", "Branch/F-001 10", "Branch/O-001 1",
				"=Branch 11"), describe(r));
	}

	@Test
	public void subtotalsByMonth() {
		ReportSpec s = spec(date(DatePart.MONTH), col("lines.item.code", Aggregate.NONE), col("lines.quantity", Aggregate.SUM));
		s.setSubtotals(true);
		s.setTotals(true);
		ReportResult r = executor.run(em, lines(), s, null, 0, 10);
		assertEquals(Arrays.asList("2026-01/B-001 2", "2026-01/F-001 15", "2026-01/O-001 1", "=2026-01 18", "2026-02/B-001 7",
				"=2026-02 7"), describe(r));
		assertNumber("25", r.getTotals().getValues()[2]);
	}

	@Test
	public void noSubtotalsWithOneGroupingColumn() {
		ReportSpec s = spec(col("warehouse.name", Aggregate.NONE), col("lines.quantity", Aggregate.SUM));
		s.setSubtotals(true);
		assertEquals(-1, s.subtotalColumn());
		ReportResult r = executor.run(em, lines(), s, null, 0, 10);
		assertEquals(r.getRows().size(), r.getDisplayRows().size());
	}

	@Test
	public void exportWritesSubtotalsAcrossChunksAndTheTotalLast() {
		ReportSpec s = byWarehouseAndItem();
		s.setTotals(true);
		final List<String> out = new ArrayList<>();
		ReportExecutor.RowSink sink = new ReportExecutor.RowSink() {
			@Override
			public void begin(List<ResultColumn> columns) {
			}

			@Override
			public void rows(List<Object[]> rows) {
				for (Object[] r : rows)
					out.add(r[0] + "/" + r[1]);
			}

			@Override
			public void subtotal(Object[] values, int labelColumn) {
				out.add("=" + values[0] + " " + new BigDecimal(values[2].toString()).stripTrailingZeros().toPlainString());
			}

			@Override
			public void total(Object[] values, int labelColumn) {
				out.add("total " + new BigDecimal(values[2].toString()).stripTrailingZeros().toPlainString());
			}
		};
		int rows = executor.export(em, lines(), s, null, 100, sink);
		assertEquals(4, rows);
		assertEquals(Arrays.asList("Branch/F-001", "Branch/O-001", "=Branch 11", "Main/B-001", "Main/F-001", "=Main 14",
				"total 25"), out);
		out.clear();
		// cut by the export limit: the unfinished value gets no subtotal
		executor.export(em, lines(), s, null, 3, sink);
		assertEquals(Arrays.asList("Branch/F-001", "Branch/O-001", "=Branch 11", "Main/B-001", "total 25"), out);
	}

	@Test
	public void optionsSurviveJson() {
		ReportSpec s = byWarehouseAndItem();
		s.getColumns().add(date(DatePart.YEAR));
		s.setTotals(true);
		ReportSpec back = SpecJson.fromJson(SpecJson.toJson(s));
		assertTrue(back.isTotals());
		assertTrue(back.isSubtotals());
		assertEquals(DatePart.YEAR, back.getColumns().get(3).getDatePart());
		assertNull(back.getColumns().get(0).getDatePart());
		ReportSpec plain = SpecJson.fromJson(SpecJson.toJson(spec(col("lines.quantity", Aggregate.NONE))));
		assertFalse(plain.isTotals());
		assertFalse(plain.isSubtotals());
		assertTrue(back.copy().isSubtotals());
		assertEquals(DatePart.YEAR, back.copy().getColumns().get(3).getDatePart());
	}
}
