package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.criteria.JoinType;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.softcom.reportbuilder.export.ReportFormatter;
import org.softcom.reportbuilder.service.ReportCatalog;
import org.softcom.reportbuilder.service.ReportSecurity;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;
import org.softcom.reportbuilder.testmodel.TInvoice;
import org.softcom.reportbuilder.testmodel.TInvoiceLine;

/** Regression tests for the findings of the engine review. Data: {@link TestDb}. */
public class ReviewFixesTest {

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
		return new ReportDataSource("t.lines", TInvoiceLine.class).join("invoice", JoinType.INNER).join("item", JoinType.INNER)
				.join("invoice.warehouse", JoinType.INNER)
				.add(ReportField.of("item.name", FieldType.STRING, null, "Item"))
				.add(ReportField.of("quantity", FieldType.DECIMAL, null, "Quantity"))
				.add(ReportField.of("lineNo", FieldType.INTEGER, null, "Line").format("0"))
				.add(ReportField.of("invoice.invoiceDate", FieldType.DATE, null, "Date").format("yyyy-MM-dd"))
				.add(ReportField.of("invoice.warehouse.name", FieldType.STRING, null, "Warehouse"))
				.add(ReportField.computed("double", FieldType.DECIMAL, null, "Double",
						ctx -> ctx.getCriteriaBuilder().prod(ctx.<BigDecimal>path("quantity"), new BigDecimal("2"))))
				.forcedFilter(ctx -> Arrays.asList(ctx.getCriteriaBuilder().equal(ctx.path("invoice.company.id"), 1L),
						ctx.getCriteriaBuilder().isTrue(ctx.<Boolean>path("invoice.closed"))));
	}

	private static ReportSpec spec(String ds, FilterNode filter, ColumnSpec... cols) {
		ReportSpec s = new ReportSpec();
		s.setDataSource(ds);
		s.setColumns(new ArrayList<>(Arrays.asList(cols)));
		s.setFilter(filter);
		return s;
	}

	private static FilterNode jan(String field) {
		return FilterNode.group(FilterNode.Logic.AND, FilterNode.rule("p", field, Operator.BETWEEN, "2026-01-01", "2026-01-31"));
	}

	@Test
	public void sameFieldTwiceIsAllowed() {
		ReportResult r = executor.run(em, lines(), spec("t.lines", jan("invoice.invoiceDate"),
				new ColumnSpec("item.name", Aggregate.NONE), new ColumnSpec("quantity", Aggregate.NONE), new ColumnSpec("item.name", Aggregate.NONE)),
				null, 0, 10);
		assertEquals(4, r.getRows().size());
		assertEquals(r.getRows().get(0)[0], r.getRows().get(0)[2]);
	}

	@Test
	public void groupingByADateFieldGroupsByDay() {
		// line 3 (10) and line 4 (1) are on 2026-01-31 15:30, lines 1,2 on 2026-01-05 09:00: two days -> two rows
		ReportResult r = executor.run(em, lines(), spec("t.lines", jan("invoice.invoiceDate"),
				new ColumnSpec("invoice.invoiceDate", Aggregate.NONE), new ColumnSpec("quantity", Aggregate.SUM),
				new ColumnSpec("invoice.invoiceDate", Aggregate.COUNT_DISTINCT)), null, 0, 10);
		assertEquals(2, r.getRows().size());
		assertEquals(0, new BigDecimal("7").compareTo(new BigDecimal(r.getRows().get(0)[1].toString())));
		assertEquals(0, new BigDecimal("11").compareTo(new BigDecimal(r.getRows().get(1)[1].toString())));
		assertEquals(1L, ((Number) r.getRows().get(0)[2]).longValue());
	}

	@Test
	public void computedFieldsAreNotGroupableByDefault() {
		try {
			executor.run(em, lines(), spec("t.lines", jan("invoice.invoiceDate"), new ColumnSpec("double", Aggregate.NONE),
					new ColumnSpec("*", Aggregate.COUNT)), null, 0, 10);
			fail();
		} catch (ReportException e) {
			assertEquals("rb.error.notGroupable", e.getMessageKey());
		}
		// still usable as a plain column and as a total
		ReportResult r = executor.run(em, lines(), spec("t.lines", jan("invoice.invoiceDate"), new ColumnSpec("double", Aggregate.SUM)),
				null, 0, 10);
		assertEquals(0, new BigDecimal("36").compareTo(new BigDecimal(r.getRows().get(0)[0].toString())));
	}

	@Test
	public void aggregatedColumnsDoNotInheritTheFieldFormat() {
		ReportDataSource ds = lines();
		ReportResult r = executor.run(em, ds, spec("t.lines", jan("invoice.invoiceDate"),
				new ColumnSpec("invoice.invoiceDate", Aggregate.COUNT), new ColumnSpec("lineNo", Aggregate.AVG)), null, 0, 10);
		String count = ReportFormatter.format(r.getRows().get(0)[0], r.getColumns().get(0), ds, Locale.ENGLISH);
		String avg = ReportFormatter.format(r.getRows().get(0)[1], r.getColumns().get(1), ds, Locale.ENGLISH);
		assertEquals("4", count);
		// PostgreSQL returns 1.5; H2 averages an integer column as an integer (1). Either way the field's "0" format
		// must not be applied, i.e. the value is shown as the database returned it.
		Object raw = r.getRows().get(0)[1];
		assertEquals(new BigDecimal(raw.toString()).stripTrailingZeros().toPlainString(), avg);
	}

	@Test
	public void grainRowCountDoesNotDependOnSelectedColumns() {
		ReportDataSource ds = new ReportDataSource("t.inv", TInvoice.class).join("lines", JoinType.INNER).grain("lines", "id")
				.join("warehouse", JoinType.INNER)
				.add(ReportField.of("warehouse.name", FieldType.STRING, null, "Warehouse"))
				.add(ReportField.of("lines.quantity", FieldType.DECIMAL, null, "Quantity"))
				.add(ReportField.of("invoiceDate", FieldType.DATE, null, "Date"))
				.forcedFilter(ctx -> Arrays.asList(ctx.getCriteriaBuilder().equal(ctx.path("company.id"), 1L),
						ctx.getCriteriaBuilder().isTrue(ctx.<Boolean>path("closed"))));
		ReportResult onlyCount = executor.run(em, ds, spec("t.inv", jan("invoiceDate"), new ColumnSpec("warehouse.name", Aggregate.NONE),
				new ColumnSpec("*", Aggregate.COUNT)), null, 0, 10);
		ReportResult withSum = executor.run(em, ds, spec("t.inv", jan("invoiceDate"), new ColumnSpec("warehouse.name", Aggregate.NONE),
				new ColumnSpec("*", Aggregate.COUNT), new ColumnSpec("lines.quantity", Aggregate.SUM)), null, 0, 10);
		for (int i = 0; i < onlyCount.getRows().size(); i++)
			assertEquals("row count must be per line in both cases", withSum.getRows().get(i)[1], onlyCount.getRows().get(i)[1]);
		long total = 0;
		for (Object[] row : onlyCount.getRows())
			total += ((Number) row[1]).longValue();
		assertEquals(4, total); // 4 January lines, not 2 invoices
	}

	@Test
	public void exportMarksTruncation() {
		ReportDataSource ds = lines().maxExportRows(3);
		final boolean[] cut = new boolean[1];
		final List<Object[]> out = new ArrayList<>();
		int n = executor.export(em, ds, spec("t.lines", jan("invoice.invoiceDate"), new ColumnSpec("quantity", Aggregate.NONE)), null, 0,
				new ReportExecutor.RowSink() {
					@Override
					public void begin(List<ResultColumn> columns) {
					}

					@Override
					public void rows(List<Object[]> rows) {
						out.addAll(rows);
					}

					@Override
					public void end(boolean truncated, int limit) {
						cut[0] = truncated;
					}
				});
		assertEquals(3, n);
		assertEquals(3, out.size());
		assertTrue("4 rows exist, limit 3", cut[0]);
		cut[0] = true;
		executor.export(em, lines().maxExportRows(4), spec("t.lines", jan("invoice.invoiceDate"), new ColumnSpec("quantity", Aggregate.NONE)),
				null, 0, new ReportExecutor.RowSink() {
					@Override
					public void begin(List<ResultColumn> columns) {
					}

					@Override
					public void rows(List<Object[]> rows) {
					}

					@Override
					public void end(boolean truncated, int limit) {
						cut[0] = truncated;
					}
				});
		assertFalse("exactly 4 rows, limit 4: not truncated", cut[0]);
	}

	@Test
	public void numberParsing() {
		assertEquals(0, new BigDecimal("1234.5").compareTo(ValueConverter.parseNumber("1,234.5")));
		assertEquals(0, new BigDecimal("1234").compareTo(ValueConverter.parseNumber("١٬٢٣٤")));
		assertEquals(0, new BigDecimal("2.5").compareTo(ValueConverter.parseNumber("٢٫٥")));
		for (String bad : new String[] { "2,5", "12,34", "1e30000000" }) {
			try {
				ValueConverter.parseNumber(bad);
				fail("should reject " + bad);
			} catch (IllegalArgumentException expected) {
				// ok
			}
		}
	}

	@Test
	public void invalidDateInTheRequiredRangeIsAReportException() {
		ReportDataSource ds = lines().requireFilterOn("invoice.invoiceDate", 366);
		try {
			executor.run(em, ds, spec("t.lines", FilterNode.group(FilterNode.Logic.AND,
					FilterNode.rule("p", "invoice.invoiceDate", Operator.BETWEEN, "2026-01-01", "2026-13-45")),
					new ColumnSpec("quantity", Aggregate.NONE)), null, 0, 10);
			fail();
		} catch (ReportException e) {
			assertEquals("rb.error.invalidValue", e.getMessageKey());
		}
	}

	@Test
	public void hugeOffsetIsClamped() {
		ReportResult r = executor.run(em, lines(), spec("t.lines", jan("invoice.invoiceDate"), new ColumnSpec("quantity", Aggregate.NONE)),
				null, Integer.MAX_VALUE - 5, 10);
		assertEquals(0, r.getRows().size());
		assertEquals(ReportExecutor.MAX_FIRST_ROW, r.getFirst());
	}

	@Test
	public void reportAdminDoesNotBypassADataSourceRole() {
		ReportDataSource payroll = lines().requiredRole("HR_PAYROLL");
		Map<String, Boolean> perms = new HashMap<>();
		perms.put("REPORT_BUILDER_ADMIN", true);
		ReportSecurity admin = new ReportSecurity() {
			@Override
			public String getCurrentUser() {
				return "admin";
			}

			@Override
			public boolean hasPermission(String permission) {
				return Boolean.TRUE.equals(perms.get(permission));
			}
		};
		assertFalse(ReportCatalog.isAllowed(payroll, admin));
		perms.put("HR_PAYROLL", true);
		assertTrue(ReportCatalog.isAllowed(payroll, admin));
	}

	/** The pattern GwReportDataSourceProvider uses for "no customer or an allowed customer". */
	@Test
	public void optionalRelationNullCheckThroughIdKeepsRowsWithoutTheRelation() {
		ReportDataSource ds = new ReportDataSource("t.lines", TInvoiceLine.class).join("invoice", JoinType.INNER)
				.join("invoice.customer", JoinType.LEFT)
				.add(ReportField.of("invoice.customer.name", FieldType.STRING, null, "Customer"))
				.add(ReportField.of("quantity", FieldType.DECIMAL, null, "Quantity"))
				.add(ReportField.of("invoice.invoiceDate", FieldType.DATE, null, "Date"))
				.forcedFilter(ctx -> {
					javax.persistence.criteria.CriteriaBuilder cb = ctx.getCriteriaBuilder();
					return Arrays.asList(cb.equal(ctx.path("invoice.company.id"), 1L), cb.isTrue(ctx.<Boolean>path("invoice.closed")),
							cb.or(cb.isNull(ctx.path("invoice.customer.id")), ctx.path("invoice.customer.id").in(Arrays.asList(999L))));
				});
		// customer 500 is not allowed: only the 2 January lines without a customer remain, also when the customer is selected
		ReportResult r = executor.run(em, ds, spec("t.lines", jan("invoice.invoiceDate"), new ColumnSpec("invoice.customer.name", Aggregate.NONE),
				new ColumnSpec("quantity", Aggregate.NONE)), null, 0, 10);
		assertEquals(2, r.getRows().size());
		for (Object[] row : r.getRows())
			assertEquals(null, row[0]);
	}

	@Test
	public void longLabelsAreRejected() {
		ColumnSpec c = new ColumnSpec("quantity", Aggregate.NONE);
		c.setLabel(new String(new char[SpecValidator.MAX_LABEL_LENGTH + 1]).replace('\0', 'x'));
		try {
			SpecValidator.validate(spec("t.lines", null, c), lines(), false);
			fail();
		} catch (ReportException e) {
			assertEquals("rb.error.labelTooLong", e.getMessageKey());
		}
	}
}
