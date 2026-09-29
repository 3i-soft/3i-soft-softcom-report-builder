package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.persistence.EntityManager;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.softcom.reportbuilder.auto.EntityDiscovery;
import org.softcom.reportbuilder.auto.LabelResolver;
import org.softcom.reportbuilder.export.ExcelReportExporter;
import org.softcom.reportbuilder.export.ReportFormatter;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.FilterNode.Logic;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;
import org.softcom.reportbuilder.testmodel.TSupplier;

/**
 * Ids are not always integers: generalWarehouse maps {@code double id} on a
 * {@code numeric} column. They are shown exactly as stored, on screen and in
 * Excel, and can be used in conditions.
 */
public class IdFormatTest {

	private static EntityManager em;
	private static ReportDataSource supplier;
	private final ReportExecutor executor = new ReportExecutor();

	@BeforeClass
	public static void init() {
		em = TestDb.emf().createEntityManager();
		em.getTransaction().begin();
		if (em.find(TSupplier.class, 12.5) == null)
			em.persist(new TSupplier(12.5, "Id test decimal"));
		if (em.find(TSupplier.class, 20260929150412.0) == null)
			em.persist(new TSupplier(20260929150412.0, "Id test large"));
		em.getTransaction().commit();
		for (ReportDataSource ds : EntityDiscovery.discover(em.getMetamodel(),
				new LabelResolver(Collections.emptyList(), Collections.emptyList(), null), new EntityDiscovery.Options()))
			if (ds.getKey().equals("auto.TSupplier"))
				supplier = ds;
	}

	@AfterClass
	public static void close() {
		em.close();
	}

	private static ReportSpec spec(FilterNode rule) {
		ReportSpec s = new ReportSpec();
		s.setDataSource(supplier.getKey());
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("id", Aggregate.NONE))));
		s.setFilter(FilterNode.group(Logic.AND, rule));
		return s;
	}

	@Test
	public void idsAreShownExactly() {
		assertEquals(ReportField.PLAIN_FORMAT, supplier.getField("id").getFormat());
		ReportResult r = executor.run(em, supplier, spec(FilterNode.rule("i", "id", Operator.IN, "12.5", "20260929150412")), null,
				0, 10);
		Set<String> shown = new HashSet<>();
		for (Object[] row : r.getRows())
			shown.add(ReportFormatter.format(row[0], r.getColumns().get(0), supplier, new Locale("ar")));
		assertEquals(new HashSet<>(Arrays.asList("12.5", "20260929150412")), shown);
	}

	@Test
	public void aDecimalIdInACondition() {
		ReportResult r = executor.run(em, supplier, spec(FilterNode.rule("i", "id", Operator.EQ, "12.5")), null, 0, 10);
		assertEquals(1, r.getRows().size());
		assertEquals(0, new BigDecimal("12.5").compareTo(new BigDecimal(r.getRows().get(0)[0].toString())));
	}

	@Test
	public void idsAreTextInExcel() throws Exception {
		ExcelReportExporter exporter = new ExcelReportExporter(supplier, "ids", new Locale("ar"));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			executor.export(em, supplier, spec(FilterNode.rule("i", "id", Operator.IN, "12.5", "20260929150412")), null, 0,
					exporter);
			exporter.write(out);
		} finally {
			exporter.dispose();
		}
		try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
			Sheet sheet = wb.getSheetAt(0);
			Set<String> cells = new HashSet<>();
			for (int i = 1; i <= 2; i++)
				cells.add(sheet.getRow(i).getCell(0).getStringCellValue());
			assertEquals(new HashSet<>(Arrays.asList("12.5", "20260929150412")), cells);
		}
	}

	@Test
	public void plainNumbers() {
		assertEquals("1234567890123456789.1", ReportFormatter.plain(new BigDecimal("1234567890123456789.10")));
		assertEquals("20260929150412", ReportFormatter.plain(20260929150412.0));
		assertEquals("12.5", LookupQueries.key(12.5));
		assertEquals("7", ReportFormatter.plain(7L));
	}
}
