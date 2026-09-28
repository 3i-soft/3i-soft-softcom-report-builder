package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.JoinType;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
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
import org.softcom.reportbuilder.testmodel.TInvoice;

/**
 * The generalWarehouse shape: the root is the invoice and each row is one of
 * its lines (a collection join), plus a computed field and a choice list.
 * Uses the shared data of {@link TestDb}.
 */
public class GrainAndComputedFieldTest {

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

	private static ReportDataSource invoiceRooted() {
		Map<String, String> names = new LinkedHashMap<>();
		names.put("F-001", "تفاح");
		names.put("B-001", "خبز");
		return new ReportDataSource("t.invoiceLines", TInvoice.class)
				.join("lines", JoinType.INNER).join("lines.item", JoinType.LEFT).join("warehouse", JoinType.LEFT)
				.grain("lines", "id")
				.add(ReportField.of("lines.item.code", FieldType.STRING, "المادة", "Item")
						.choices(() -> names))
				.add(ReportField.of("lines.quantity", FieldType.DECIMAL, "الكمية", "Quantity"))
				.add(ReportField.of("invoiceDate", FieldType.DATE, "التاريخ", "Date"))
				.add(ReportField.computed("lineValue", FieldType.DECIMAL, "القيمة", "Value", ctx -> {
					CriteriaBuilder cb = ctx.getCriteriaBuilder();
					Expression<BigDecimal> q = ctx.path("lines.quantity");
					Expression<BigDecimal> p = ctx.path("lines.price");
					return cb.prod(q, p);
				}))
				.requireFilterOn("invoiceDate", 0)
				.forcedFilter(ctx -> Arrays.asList(
						ctx.getCriteriaBuilder().equal(ctx.path("company.id"), 1L),
						ctx.getCriteriaBuilder().isTrue(ctx.<Boolean>path("closed"))));
	}

	private static ReportSpec spec(ColumnSpec... cols) {
		ReportSpec s = new ReportSpec();
		s.setDataSource("t.invoiceLines");
		s.setColumns(new ArrayList<>(Arrays.asList(cols)));
		s.setFilter(FilterNode.group(Logic.AND,
				FilterNode.rule("p", "invoiceDate", Operator.BETWEEN, "2026-01-01", "2026-01-31")));
		return s;
	}

	@Test
	public void oneRowPerCollectionElementWithStablePaging() {
		ReportDataSource ds = invoiceRooted();
		ds.checkConsistency();
		ReportSpec s = spec(new ColumnSpec("lines.quantity", Aggregate.NONE));
		Set<String> seen = new HashSet<>();
		List<BigDecimal> all = new ArrayList<>();
		for (int first = 0; first < 10; first++) {
			ReportResult page = executor.run(em, ds, s, null, first, 1);
			if (page.getRows().isEmpty())
				break;
			all.add(new BigDecimal(page.getRows().get(0)[0].toString()));
			seen.add(first + ":" + page.getRows().get(0)[0]);
			if (!page.isHasMore())
				break;
		}
		assertEquals("4 January lines of company A, each exactly once", 4, all.size());
	}

	@Test
	public void computedFieldIsSummedByTheDatabase() {
		ReportResult r = executor.run(em, invoiceRooted(), spec(new ColumnSpec("lineValue", Aggregate.SUM)), null, 0, 10);
		// 5*2.50 + 2*1.00 + 10*2.40 + 1*9.99 = 48.49
		assertEquals(0, new BigDecimal("48.49").compareTo(new BigDecimal(r.getRows().get(0)[0].toString())));
	}

	@Test
	public void computedFieldCanBeFiltered() {
		ReportSpec s = spec(new ColumnSpec("lines.item.code", Aggregate.NONE));
		s.getFilter().getChildren().add(FilterNode.rule("v", "lineValue", Operator.GT, "20"));
		ReportResult r = executor.run(em, invoiceRooted(), s, null, 0, 10);
		assertEquals(1, r.getRows().size()); // only 10 x 2.40
	}

	@Test
	public void choiceLabelsReplaceStoredCodes() {
		ReportDataSource ds = invoiceRooted();
		ReportSpec s = spec(new ColumnSpec("lines.item.code", Aggregate.NONE), new ColumnSpec("lines.quantity", Aggregate.SUM));
		ReportResult r = executor.run(em, ds, s, null, 0, 10);
		Set<String> labels = new HashSet<>();
		for (Object[] row : r.getRows())
			labels.add(ReportFormatter.format(row[0], r.getColumns().get(0), ds, new Locale("ar")));
		assertTrue(labels.contains("تفاح"));
		assertTrue(labels.contains("خبز"));
		assertTrue("codes without a label are shown as stored", labels.contains("O-001"));
		assertFalse(labels.contains("F-001"));
	}
}
