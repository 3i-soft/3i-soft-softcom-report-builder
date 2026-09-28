package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import javax.persistence.EntityManager;
import javax.persistence.criteria.JoinType;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;
import org.softcom.reportbuilder.export.ExcelReportExporter;
import org.softcom.reportbuilder.model.ReportDefinition;
import org.softcom.reportbuilder.model.ReportRunLog;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SpecJson;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;
import org.softcom.reportbuilder.testmodel.TInvoiceLine;

/** Library entities (ids from sequences, text column) and the streaming Excel export. */
public class PersistenceAndExportTest {

	private static ReportSpec sampleSpec() {
		ReportSpec s = new ReportSpec();
		s.setDataSource("t.simple");
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("item.name", Aggregate.NONE),
				new ColumnSpec("quantity", Aggregate.SUM), new ColumnSpec("invoice.invoiceDate", Aggregate.MAX))));
		s.setFilter(FilterNode.group(FilterNode.Logic.AND,
				FilterNode.rule("p", "invoice.invoiceDate", Operator.BETWEEN, "2026-01-01", "2026-01-31")));
		return s;
	}

	private static ReportDataSource simple() {
		return new ReportDataSource("t.simple", TInvoiceLine.class).join("invoice", JoinType.INNER).join("item", JoinType.INNER)
				.add(ReportField.of("item.name", FieldType.STRING, "المادة", "Item"))
				.add(ReportField.of("quantity", FieldType.DECIMAL, "الكمية", "Quantity"))
				.add(ReportField.of("invoice.invoiceDate", FieldType.DATE, "التاريخ", "Date"))
				.forcedFilter(ctx -> Arrays.asList(ctx.getCriteriaBuilder().equal(ctx.path("invoice.company.id"), 1L),
						ctx.getCriteriaBuilder().isTrue(ctx.<Boolean>path("invoice.closed"))));
	}

	@Test
	public void definitionsAndRunLogsArePersistedWithSequenceIds() {
		EntityManager em = TestDb.emf().createEntityManager();
		try {
			em.getTransaction().begin();
			ReportDefinition d = new ReportDefinition();
			d.setName("مبيعات كانون");
			d.setDataSourceKey("t.simple");
			d.setDefinition(SpecJson.toJson(sampleSpec()));
			d.setOwner("u1");
			d.setShared(true);
			d.setSharedRoles("A, B");
			d.setCreatedDate(new Date());
			em.persist(d);
			ReportRunLog log = new ReportRunLog();
			log.setKind(ReportRunLog.Kind.RUN.name());
			log.setStartedAt(new Date());
			log.setDurationMs(12);
			log.setRowCount(3);
			log.setSuccess(true);
			em.persist(log);
			em.getTransaction().commit();
			assertNotNull(d.getId());
			assertNotNull(log.getId());
			em.clear();
			ReportDefinition back = em.find(ReportDefinition.class, d.getId());
			assertEquals(Arrays.asList("A", "B"), back.getSharedRoleList());
			assertEquals(SpecJson.toJson(sampleSpec()), SpecJson.toJson(SpecJson.fromJson(back.getDefinition())));
			assertEquals(1, em.createNamedQuery(ReportDefinition.FIND_OWNED_OR_SHARED, ReportDefinition.class)
					.setParameter("owner", "someone-else").getResultList().stream().filter(x -> x.getId().equals(d.getId())).count());
		} finally {
			if (em.getTransaction().isActive())
				em.getTransaction().rollback();
			em.close();
		}
	}

	@Test
	public void excelExportIsRightToLeftWithTypedCells() throws Exception {
		EntityManager em = TestDb.emf().createEntityManager();
		ReportDataSource ds = simple();
		ExcelReportExporter exporter = new ExcelReportExporter(ds, "تقرير", new Locale("ar"));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			int rows = new ReportExecutor().export(em, ds, sampleSpec(), null, 0, exporter);
			exporter.write(out);
			assertEquals(3, rows);
		} finally {
			exporter.dispose();
			em.close();
		}
		try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
			Sheet sheet = wb.getSheetAt(0);
			assertTrue(sheet.isRightToLeft());
			assertEquals("المادة", sheet.getRow(0).getCell(0).getStringCellValue());
			assertTrue(sheet.getRow(0).getCell(1).getStringCellValue().startsWith("الكمية"));
			assertEquals(4, sheet.getPhysicalNumberOfRows()); // header + 3 items
			double total = 0;
			for (int r = 1; r <= 3; r++)
				total += sheet.getRow(r).getCell(1).getNumericCellValue();
			assertEquals(18.0, total, 0.0001); // 15 apple + 2 bread + 1 other
			assertNotNull(sheet.getRow(1).getCell(2).getDateCellValue());
		}
	}
}
