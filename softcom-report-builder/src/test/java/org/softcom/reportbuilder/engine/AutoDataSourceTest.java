package org.softcom.reportbuilder.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

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
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/** Data sources discovered from the JPA metamodel of the test persistence unit, and reports run on them. */
public class AutoDataSourceTest {

	private static final Locale AR = new Locale("ar");
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

	private static ResourceBundle bundle(final Object[][] entries) {
		return new ListResourceBundle() {
			@Override
			protected Object[][] getContents() {
				return entries;
			}
		};
	}

	/** rblabels overrides one label; the "application bundle" knows two attribute names (one in snake_case). */
	private static LabelResolver labels() {
		ResourceBundle overrides = bundle(new Object[][] { { "TItem.name", "اسم المادة" }, { "TItem", "المواد" } });
		ResourceBundle host = bundle(new Object[][] { { "order_date", "تاريخ الطلب" }, { "customer", "الزبون" },
				{ "quantity", "Quantity in English" } });
		return new LabelResolver(Collections.singletonList(overrides), Collections.singletonList(host), null);
	}

	private static Map<String, ReportDataSource> discover(EntityDiscovery.Options options) {
		Map<String, ReportDataSource> map = new LinkedHashMap<>();
		for (ReportDataSource ds : EntityDiscovery.discover(em.getMetamodel(), labels(), options)) {
			ds.checkConsistency();
			map.put(ds.getKey(), ds);
		}
		return map;
	}

	private static Map<String, ReportDataSource> discover() {
		return discover(new EntityDiscovery.Options());
	}

	@Test
	public void everyEntityBecomesADataSourceExceptTheLibrarysOwn() {
		Map<String, ReportDataSource> all = discover();
		for (String key : Arrays.asList("auto.TInvoice", "auto.TInvoiceLine", "auto.TItem", "auto.TOrder", "auto.TOrderLine",
				"auto.TCompany", "auto.TWarehouse", "auto.TCustomer"))
			assertTrue(key, all.containsKey(key));
		assertFalse(all.containsKey("auto.ReportDefinition"));
		assertFalse(all.containsKey("auto.ReportRunLog"));
		for (ReportDataSource ds : all.values())
			assertTrue(ds.isAutomatic());
	}

	@Test
	public void idInheritedFromAMappedSuperclassLikeGeneralWarehouse() {
		ReportDataSource supplier = discover().get("auto.TSupplier");
		assertNotNull("generalWarehouse entities inherit '@Id double id' from MainEntity", supplier);
		assertNotNull(supplier.getField("id"));
		assertNotNull(supplier.getField("supplierName"));
		ReportSpec s = new ReportSpec();
		s.setDataSource(supplier.getKey());
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("supplierName", Aggregate.NONE))));
		assertEquals(5, executor.run(em, supplier, s, null, 0, 10).getRows().size());
	}

	private static List<String> texts(ReportResult r, ReportDataSource ds, int column) {
		List<String> out = new ArrayList<>();
		for (Object[] row : r.getRows())
			out.add(org.softcom.reportbuilder.export.ReportFormatter.format(row[column], r.getColumns().get(column), ds,
					new Locale("ar")));
		return out;
	}

	@Test
	public void deletedFlagsBecomeVisibleDefaultConditions() {
		ReportDataSource supplier = discover().get("auto.TSupplier");
		assertEquals(1, supplier.getDefaultConditions().size());
		assertEquals("deleted", supplier.getDefaultConditions().get(0)[0]);
		assertEquals(Operator.IS_NOT_TRUE.name(), supplier.getDefaultConditions().get(0)[1]);
		assertTrue(discover(new EntityDiscovery.Options().defaultConditions(false)).get("auto.TSupplier").getDefaultConditions()
				.isEmpty());
		assertTrue("no flag, no default", discover().get("auto.TItem").getDefaultConditions().isEmpty());
		// rows older than the flag have NULL in it: they are not deleted
		em.getTransaction().begin();
		em.createNativeQuery("UPDATE t_supplier SET deleted = NULL WHERE id = 4").executeUpdate();
		em.getTransaction().commit();
		ReportSpec s = new ReportSpec();
		s.setDataSource(supplier.getKey());
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("supplierName", Aggregate.NONE))));
		s.setFilter(FilterNode.group(Logic.AND, FilterNode.rule("d", "deleted", Operator.IS_NOT_TRUE)));
		List<String> names = texts(executor.run(em, supplier, s, null, 0, 10), supplier, 0);
		assertEquals(4, names.size());
		assertTrue(names.contains("Supplier D"));
		assertFalse(names.contains("Supplier C (deleted)"));
	}

	@Test
	public void codeFieldsShowTheNamesOfTheApplicationsCodes() {
		Map<String, String> cities = new HashMap<>();
		cities.put("01", "بغداد");
		cities.put("02", "البصرة");
		assertFalse("without a code provider the codes stay as stored",
				discover().get("auto.TSupplier").getField("cityCode").hasChoices());
		ReportDataSource supplier = discover(new EntityDiscovery.Options().codeLookup(k -> "CITY".equals(k) ? cities : null))
				.get("auto.TSupplier");
		ReportField city = supplier.getField("cityCode");
		assertTrue("a pick list in conditions", city.hasChoices());
		assertEquals("sorted by name", Arrays.asList("02", "01"), new ArrayList<>(city.getChoices().keySet()));
		assertEquals("بغداد", city.choiceLabel("01"));
		assertEquals("unknown codes are shown as stored", "09", city.choiceLabel("09"));
		ReportSpec s = new ReportSpec();
		s.setDataSource(supplier.getKey());
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("cityCode", Aggregate.NONE),
				new ColumnSpec(ReportDataSource.ROW_COUNT_FIELD, Aggregate.COUNT))));
		s.setFilter(FilterNode.group(Logic.AND, FilterNode.rule("c", "cityCode", Operator.IN, "01", "02")));
		ReportResult r = executor.run(em, supplier, s, null, 0, 10);
		assertEquals(Arrays.asList("بغداد", "البصرة"), texts(r, supplier, 0));
	}

	@Test
	public void oneWayCollectionsGetTheirOwnDataSource() {
		Map<String, ReportDataSource> all = discover();
		ReportDataSource lines = all.get("auto.TOrder.lines");
		assertNotNull("order lines cannot navigate to the order: only reachable through the collection", lines);
		assertEquals("lines", lines.getGrainPath());
		assertNotNull(lines.getField("lines.quantity"));
		assertNotNull(lines.getField("lines.item.name"));
		assertTrue(lines.getField("lines.quantity").isAggregatable());
		assertFalse("the order's own numbers repeat on each line", lines.getField("id").isAggregatable());
		assertFalse("invoice lines can navigate to their invoice: TInvoiceLine itself covers them",
				all.containsKey("auto.TInvoice.lines"));
	}

	@Test
	public void simpleAndRelatedFieldsWithTypesButNoSensitiveOnes() {
		ReportDataSource order = discover().get("auto.TOrder");
		assertEquals(FieldType.DATE, order.getField("orderDate").getType());
		assertEquals(FieldType.LONG, order.getField("id").getType());
		assertFalse("ids are not summed", order.getField("id").isAggregatable());
		assertNotNull(order.getField("customer.name"));
		assertNotNull(order.getField("customer.id"));
		assertNull("password-like attributes are never offered", order.getField("accessToken"));
		assertNull("@Lob is never offered", order.getField("notes"));
		assertNull("@Version is never offered", order.getField("version"));
		assertNull("collections are not fields", order.getField("lines"));

		ReportDataSource line = discover().get("auto.TInvoiceLine");
		assertEquals(FieldType.DATETIME, line.getField("invoice.invoiceDate").getType());
		assertNotNull("two relations deep", line.getField("invoice.warehouse.name"));
		assertNotNull("three relations deep (the default)", line.getField("invoice.warehouse.company.name"));
		assertNull("depth 2", discover(new EntityDiscovery.Options().depth(2)).get("auto.TInvoiceLine")
				.getField("invoice.warehouse.company.name"));
		assertEquals(FieldType.BOOLEAN, line.getField("invoice.closed").getType());

		ReportField kind = discover().get("auto.TItem").getField("kind");
		assertEquals(FieldType.ENUM, kind.getType());
		assertEquals(Arrays.asList("FRUIT", "BAKERY", "OTHER"), new ArrayList<>(kind.getChoices().keySet()));
	}

	@Test
	public void onlyTheRowsOwnNumbersCanBeSummed() {
		Map<String, ReportDataSource> all = discover();
		assertTrue(all.get("auto.TItem").getField("shelfLifeDays").isAggregatable());
		ReportDataSource line = all.get("auto.TInvoiceLine");
		assertTrue(line.getField("quantity").isAggregatable());
		assertFalse("counted once per line", line.getField("item.shelfLifeDays").isAggregatable());
		ReportDataSource orderLines = all.get("auto.TOrder.lines");
		assertTrue(orderLines.getField("lines.quantity").isAggregatable());
		assertFalse(orderLines.getField("lines.item.shelfLifeDays").isAggregatable());
	}

	@Test
	public void passwordLikeNamesAreRecognisedAsWholeWords() {
		for (String name : Arrays.asList("password", "userPassword", "accessToken", "apiKey", "secretCode", "pinCode", "otp",
				"pwd", "passwordHash", "salt", "credentials"))
			assertTrue(name, EntityDiscovery.isSensitive(name));
		for (String name : Arrays.asList("shipping", "opinion", "keyword", "passport", "name", "saltwater"))
			assertFalse(name, EntityDiscovery.isSensitive(name));
	}

	@Test
	public void labelsComeFromOverridesThenApplicationBundlesThenTheName() {
		Map<String, ReportDataSource> all = discover();
		assertEquals("المواد", all.get("auto.TItem").getLabel(AR));
		assertEquals("اسم المادة", all.get("auto.TItem").getField("name").getLabel(AR));
		ReportDataSource order = all.get("auto.TOrder");
		assertEquals("snake_case key of the application bundle", "تاريخ الطلب", order.getField("orderDate").getLabel(AR));
		assertEquals("Order date", order.getField("orderDate").getLabel(Locale.ENGLISH));
		assertEquals("الزبون - Name", order.getField("customer.name").getLabel(AR));
		assertEquals("Customer - Name", order.getField("customer.name").getLabel(Locale.ENGLISH));
		assertEquals("الزبون", order.getJoinLabel("customer", AR));
		assertEquals("an English value is not taken as the Arabic label", "Quantity",
				all.get("auto.TOrderLine").getField("quantity").getLabel(AR));
	}

	@Test
	public void labelsTheEntityAuthorWroteInAnnotationsAreUsed() {
		Map<String, ReportDataSource> all = discover();
		assertEquals("@EntityInfo(label), trimmed", "المخازن", all.get("auto.TWarehouse").getLabel(AR));
		assertEquals("@FieldInfo(label)", "اسم المخزن", all.get("auto.TWarehouse").getField("name").getLabel(AR));
		ReportField company = all.get("auto.TCompany").getField("name");
		assertEquals("Arabic from @FieldViewConfiguration(displayName)", "اسم الشركة", company.getLabel(AR));
		assertEquals("English from @FieldInfo(label)", "Company name", company.getLabel(Locale.ENGLISH));
		assertEquals("through a relation", "Warehouse - اسم المخزن",
				all.get("auto.TInvoice").getField("warehouse.name").getLabel(AR));
	}

	@Test
	public void conditionsThroughACollectionReturnEachRowOnce() {
		ReportDataSource invoice = discover().get("auto.TInvoice");
		ReportField itemName = invoice.getField("lines.item.name");
		assertNotNull("invoices -> lines -> item, as a condition", itemName);
		assertTrue(itemName.isViaCollection());
		assertEquals("lines", itemName.getCollection());
		assertFalse(itemName.isGroupable());
		assertNotNull(invoice.getField("lines.quantity"));

		ReportSpec s = new ReportSpec();
		s.setDataSource(invoice.getKey());
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("id", Aggregate.NONE))));
		s.setFilter(FilterNode.rule("i", "lines.item.name", Operator.EQ, "bread"));
		assertEquals("invoices having a bread line", Arrays.asList(1000L, 1002L), ids(executor.run(em, invoice, s, null, 0, 10)));
		s.setFilter(FilterNode.rule("q", "lines.quantity", Operator.GT, "9"));
		assertEquals(Arrays.asList(1001L, 1003L, 1004L), ids(executor.run(em, invoice, s, null, 0, 10)));
		s.setFilter(FilterNode.rule("a", "lines.item.name", Operator.EQ, "apple"));
		assertEquals("each invoice once", Arrays.asList(1000L, 1001L, 1003L, 1004L),
				ids(executor.run(em, invoice, s, null, 0, 10)));
		s.setFilter(FilterNode.group(Logic.AND, FilterNode.rule("a", "lines.item.name", Operator.EQ, "apple"),
				FilterNode.rule("c", "closed", Operator.IS_FALSE)));
		assertEquals(Arrays.asList(1003L), ids(executor.run(em, invoice, s, null, 0, 10)));

		ReportDataSource order = discover().get("auto.TOrder");
		ReportSpec o = new ReportSpec();
		o.setDataSource(order.getKey());
		o.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("id", Aggregate.NONE))));
		o.setFilter(FilterNode.rule("i", "lines.item.name", Operator.IN, "bread"));
		assertEquals("one-way collection too", Arrays.asList(1L), ids(executor.run(em, order, o, null, 0, 10)));
	}

	@Test
	public void conditionOnlyFieldsCannotBeColumns() {
		ReportDataSource invoice = discover().get("auto.TInvoice");
		ReportSpec s = new ReportSpec();
		s.setDataSource(invoice.getKey());
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("lines.item.name", Aggregate.NONE))));
		try {
			executor.run(em, invoice, s, null, 0, 10);
			org.junit.Assert.fail();
		} catch (ReportException e) {
			assertEquals("rb.error.conditionOnly", e.getMessageKey());
		}
	}

	@Test
	public void aFieldBudgetKeepsTheNearestFields() {
		// invoice line: 4 own fields + invoice (3) and item (6) = 13; the depth-2/3 fields do not fit
		ReportDataSource line = discover(new EntityDiscovery.Options().maxFields(13)).get("auto.TInvoiceLine");
		assertEquals(13, line.getFields().size());
		assertNotNull(line.getField("item.name"));
		assertNotNull(line.getField("invoice.closed"));
		assertNull(line.getField("invoice.warehouse.name"));
		// collection data source: the lines and their item come before the order's own relations
		ReportDataSource lines = discover(new EntityDiscovery.Options().maxFields(9)).get("auto.TOrder.lines");
		assertNotNull(lines.getField("lines.item.name"));
		assertNotNull(lines.getField("id"));
		assertNull(lines.getField("customer.name"));
	}

	private static List<Long> ids(ReportResult r) {
		List<Long> ids = new ArrayList<>();
		for (Object[] row : r.getRows())
			ids.add(((Number) row[0]).longValue());
		java.util.Collections.sort(ids);
		return ids;
	}

	@Test
	public void excludedEntitiesAreNeitherOfferedNorReachable() {
		Map<String, ReportDataSource> all = discover(new EntityDiscovery.Options().exclude("TCustomer").depth(1));
		assertFalse(all.containsKey("auto.TCustomer"));
		assertNull(all.get("auto.TOrder").getField("customer.name"));
		assertNull("depth 1", all.get("auto.TInvoiceLine").getField("invoice.warehouse.name"));
		assertNotNull(all.get("auto.TInvoiceLine").getField("invoice.invoiceDate"));
	}

	@Test
	public void reportOnACollectionDataSource() {
		ReportDataSource lines = discover().get("auto.TOrder.lines");
		ReportSpec s = new ReportSpec();
		s.setDataSource(lines.getKey());
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("lines.item.name", Aggregate.NONE),
				new ColumnSpec("lines.quantity", Aggregate.SUM))));
		ReportResult r = executor.run(em, lines, s, null, 0, 10);
		Map<String, BigDecimal> sums = new HashMap<>();
		for (Object[] row : r.getRows())
			sums.put((String) row[0], new BigDecimal(row[1].toString()));
		assertEquals(0, new BigDecimal("8").compareTo(sums.get("apple")));
		assertEquals(0, new BigDecimal("4").compareTo(sums.get("bread")));
	}

	@Test
	public void reportThroughTwoRelations() {
		ReportDataSource line = discover().get("auto.TInvoiceLine");
		ReportSpec s = new ReportSpec();
		s.setDataSource(line.getKey());
		s.setColumns(new ArrayList<>(Arrays.asList(new ColumnSpec("invoice.warehouse.name", Aggregate.NONE),
				new ColumnSpec("quantity", Aggregate.SUM))));
		s.setFilter(FilterNode.group(Logic.AND, FilterNode.rule("d", "invoice.invoiceDate", Operator.BETWEEN, "2026-01-01",
				"2026-01-31"), FilterNode.rule("c", "invoice.closed", Operator.IS_TRUE)));
		ReportResult r = executor.run(em, line, s, null, 0, 10);
		Map<String, BigDecimal> sums = new HashMap<>();
		for (Object[] row : r.getRows())
			sums.put((String) row[0], new BigDecimal(row[1].toString()));
		// closed January invoices, the 31st afternoon included (plain days on a timestamp): Main 5+2, Branch 10+1,
		// Other company 1000
		assertEquals(0, new BigDecimal("7").compareTo(sums.get("Main")));
		assertEquals(0, new BigDecimal("11").compareTo(sums.get("Branch")));
		assertEquals(0, new BigDecimal("1000").compareTo(sums.get("Other company")));
		List<String> labels = new ArrayList<>();
		for (ResultColumn c : r.getColumns())
			labels.add(c.getLabel(Locale.ENGLISH));
		assertEquals(Arrays.asList("Invoice - Warehouse - Name", "Quantity (SUM)"), labels);
	}
}
