# softcom-report-builder

Runtime (user-defined) reports for 3i-soft Java EE 7 applications. Users pick a
**data source**, the **columns** (optionally with totals: sum, average, min, max,
count), **conditions** in AND/OR groups, and **sorting**. They can then preview,
save, share, run and export the report to Excel. The engine turns each saved
definition into a single JPA Criteria query on the host application's own
database (one database at a time).

Every JPA entity of the application is offered automatically (see
[Automatic data sources](#automatic-data-sources)); an application can add
hand-written data sources with curated labels and forced filters on top.

- Java 8, Java EE 7 (EJB 3.2, CDI 1.1, JSF 2.2), JPA 2.1 / EclipseLink 2.6, PrimeFaces 6.2, PostgreSQL.
- No runtime dependencies of its own. JSON uses JSON-P (`javax.json`, part of Java EE 7). POI, `softcom-persistence`
  and the Java EE APIs are `provided` by the host application.

## Why it is safe and fast

| Concern | How it is handled |
|---|---|
| SQL injection | Users only choose among whitelisted fields declared by the host. Values are converted to the attribute's Java type and handed to the Criteria API. Report queries re-enable JDBC parameter binding with the `eclipselink.jdbc.bind-parameters` hint, even when the persistence unit disables it globally (acc-expert does). LIKE wildcards are escaped. |
| Tenant / permission leaks | Each hand-written data source declares *forced filters* (company, allowed warehouses, cancelled/deleted flags...). They are ANDed around the user's conditions, so a user's OR can never widen them. Values they need (e.g. the current company) come from server-side `ReportAttributesProvider` beans, never from the page. A data source's `requiredRole` also applies to report-builder admins. **Automatic data sources have no forced filters**: whoever may run reports sees all rows of those entities. Restrict them with `AUTO_REQUIRED_ROLE` / `AUTO_EXCLUDE`, and publish sensitive tables through hand-written data sources instead - an entity that a hand-written data source publishes with forced filters (and its sub/super classes) is then not offered automatically. Password-like, `@Lob` and `@Version` attributes are never offered. |
| Wrong totals | Rows are always at the most detailed grain. Only many-to-one joins (plus one declared *grain* collection, which is always joined) are allowed, so SUM/COUNT cannot double count. Grouping by a DATE field groups by day, even on timestamp columns. An export that hits the row limit says so in its last row. |
| Slow queries | Only the selected columns are fetched (no entities). Totals are computed by the database (GROUP BY). Joins are created only when used. Paging uses LIMIT/OFFSET with a stable ORDER BY and no count query. A data source can require a date range of at most N days; on tables PostgreSQL estimates at 200,000+ rows every report must restrict an indexed field (see [Speed rules](#speed-rules)). Each query has a timeout (the database cancels it), at most 6 report queries run at once per application, rows per page and export rows are capped, and Excel is streamed (SXSSF). Every run is logged in `rb_report_run_log` with its duration. |

The engine is tested on EclipseLink 2.6.0 against H2 and against a real
PostgreSQL 16 (`mvn test -Drb.test.pg.url=jdbc:postgresql://localhost:5432/rbtest`).

## Adding it to an application

1. **Maven.** In the module that holds the entities, add:

   ```xml
   <dependency>
     <groupId>org.softcom</groupId>
     <artifactId>softcom-report-builder</artifactId>
     <version>1.0.0</version>
   </dependency>
   ```

   Publish the library to the internal repository first (`mvn deploy`, same as the other `softcom-*` libraries).

2. **EntityManager.** The library injects `org.softcom.persistence.IPersistenceHelper` (same convention as
   `softcom-customized-view`). generalWarehouse already has one (`PersistenceHelper`). Other apps add a
   `@Stateless` class implementing it that returns their `EntityManager` and `DataSource`.

3. **persistence.xml.** List the two entities in the persistence unit(s) that `IPersistenceHelper` uses:

   ```xml
   <class>org.softcom.reportbuilder.model.ReportDefinition</class>
   <class>org.softcom.reportbuilder.model.ReportRunLog</class>
   ```

4. **Tables.** Copy `src/main/resources/META-INF/softcom-report-builder/sql/postgresql-1.0.0.sql` into the app's
   Flyway folder as its next version. The script is idempotent.

5. **Data sources.** Nothing to do: every entity is offered automatically (generalWarehouse has no report code at
   all). Keep the library in the WAR's own `WEB-INF/lib` (its caches are per application). Hand-written data sources
   remain possible for special cases - forced filters such as the user's warehouses, computed fields - with a CDI
   bean implementing `ReportDataSourceProvider`, e.g.:

   ```java
   @ApplicationScoped
   public class MyReportDataSources implements ReportDataSourceProvider {
       @Override
       public List<ReportDataSource> getDataSources() {
           return Arrays.asList(new ReportDataSource("acc.journalLines", TransactionDetail.class)
                   .labels("قيود اليومية", "Journal lines")
                   .join("transaction", JoinType.INNER)
                   .join("account", JoinType.LEFT)
                   .add(ReportField.of("account.accountNb", FieldType.STRING, "رقم الحساب", "Account no."))
                   .add(ReportField.of("account.description", FieldType.STRING, "الحساب", "Account"))
                   .add(ReportField.of("dMainCurrencyVal", FieldType.DECIMAL, "مدين", "Debit"))
                   .add(ReportField.of("transaction.transactionDate", FieldType.DATE, "التاريخ", "Date"))
                   .requireFilterOn("transaction.transactionDate", 366)
                   .forcedFilter(ctx -> Arrays.asList(
                           ctx.getCriteriaBuilder().equal(ctx.path("transaction.company.id"), ctx.getAttributes().get("companyId")),
                           ctx.getCriteriaBuilder().isFalse(ctx.<Boolean>path("transaction.canceled")))));
       }
   }
   ```

   (Attribute names taken from acc-expert's `TransactionDetail`/`Transaction`/`Account`. The company would come from
   a `ReportAttributesProvider`, see step 6.)

   - `join(path, INNER|LEFT)`: many-to-one relations only (INNER when the FK is NOT NULL, it is faster).
   - `grain(collectionJoin, idAttribute)`: when rows can only be reached through a collection of the root
     (generalWarehouse: `Invoice.invoiceLines`).
   - `ReportField.computed(...)`: values computed by the database (e.g. price x quantity). Not groupable by default (a
     constant inside the expression would be bound separately in SELECT and GROUP BY); call `.groupable(true)` only for
     expressions without constants.
   - Grouping by a DATE field uses the SQL function `date(...)` (PostgreSQL). Use `.dayFunction("...")` on the data source
     or the system property `org.softcom.reportbuilder.dayFunction` for another database.
   - `.choices(...)` / `.enumValues(...)`: pick lists, and labels instead of stored codes.
   - `.hint(ar, en)`: tooltip in the designer (e.g. "in the invoice currency").
   - Forced filters may use any attribute path, not only whitelisted fields.

6. **Request values for forced filters (optional).** Implement `ReportAttributesProvider`, e.g. to pass the current
   company id from the session.

7. **Permissions.** The default `ReportSecurity` uses container roles `REPORT_BUILDER_RUN`, `REPORT_BUILDER_DESIGN`
   and `REPORT_BUILDER_ADMIN`. You can rename them with the system properties `org.softcom.reportbuilder.role.*`.
   An app with its own permission service provides a CDI alternative instead:

   ```java
   @Alternative @Priority(Interceptor.Priority.APPLICATION + 10) @RequestScoped
   public class MyReportSecurity implements ReportSecurity { ... }
   ```

   See `GwReportSecurity` in generalWarehouse (sys-mng `isUserHasPermission`).

8. **Pages.** Include the snippets outside any `h:form`:

   ```xml
   <ui:include src="/report-builder/viewer-snippet.xhtml">
     <ui:param name="designerPage" value="customReportDesigner" />
   </ui:include>

   <ui:include src="/report-builder/designer-snippet.xhtml">
     <ui:param name="viewerPage" value="customReports" />
   </ui:include>
   ```

   Then add the menu entries, navigation cases and `web.xml` security constraints as for any other page.

## Report definition (stored as JSON in `rb_report_definition.definition`)

```json
{ "version": 1,
  "dataSource": "gw.invoiceLines",
  "columns": [ {"field": "invoiceLines.item.itemName"},
               {"field": "invoiceLines.quantity", "aggregate": "SUM"},
               {"field": "*", "aggregate": "COUNT"} ],
  "filter": { "logic": "AND", "children": [
      {"id": "r1", "field": "completionDate", "operator": "BETWEEN",
       "value": "2026-01-01", "value2": "2026-01-31", "askAtRun": true, "label": "الفترة"},
      {"logic": "OR", "children": [
          {"id": "r2", "field": "dtype", "operator": "IN", "values": ["SalesInvoice", "ConsumptionInvoice"]} ]} ]},
  "sort": [ {"column": 1, "desc": true} ] }
```

When any column has a total, the other columns become the GROUP BY. Conditions marked `askAtRun` are asked for each
time the report is run. DATE fields use whole-day semantics (`BETWEEN d1 AND d2` includes all of `d2`), so they also
work on timestamp columns and still use an index. DATETIME fields do the same when the values are plain days
(the designer offers a day picker for them); values with a time are compared exactly.

## Automatic data sources

`AutoDataSourceProvider` reads the JPA metamodel of the persistence unit behind `IPersistenceHelper`:

- `auto.<Entity>`: one row per entity, with its simple attributes and those of its many-to-one / one-to-one
  relations, three levels deep (`customer.name`, `customer.city.name`, `customer.city.country.name`). Joins are only
  made when a report uses them. Fields are taken level by level (at most 500 per data source), so a large model never
  pushes out the direct fields.
- **Conditions through collections** (not columns): each one-to-many / many-to-many collection of the entity adds
  condition-only fields, listed as "يحتوي على: ..." / "Contains: ...". On purchase invoices,
  `invoiceLines.item.itemName = X` returns the invoices having at least one line with item X - each invoice once
  (an `EXISTS` subquery). Several such conditions are checked independently (each may match a different line).
- `auto.<Entity>.<collection>`: one row per element of a one-to-many collection whose element has no relation back
  (generalWarehouse's `Invoice.invoiceLines`). The element's fields come first; the owner's numbers are not summable
  there (they repeat per line).
- Subclasses are data sources of their own (`auto.SalesInvoice` only returns sales invoices).
- Only the row's own numbers can be summed or averaged: a related entity's number (`invoice.total` on an invoice
  line) would be counted once per row.
- Not offered: the library's own tables; entities excluded with `AUTO_EXCLUDE` (and their subclasses); entities
  without a simple single id (paging needs one); entities published by a hand-written data source with forced
  filters, and their sub/super classes (the automatic copy would bypass the restriction); password-like attributes
  (`password`, `token`, `secret`, `apiKey`, words such as `pin`, `otp`, `hash`, `salt`...), `@Lob`, `@Version`,
  embeddables and binary attributes. An attribute that cannot be read is skipped on its own.

**Labels**, first found wins (an Arabic label is only taken from Arabic text):

1. `rblabels_ar.properties` / `rblabels_en.properties` in the WAR (optional): `SalesInvoice=فواتير المبيعات`,
   `SalesInvoice.completionDate=تاريخ الإنهاء`, `completionDate=...` (any entity) or `MyEnum.CONSTANT=...`;
2. the labels already written on the entities with the 3i-soft annotations: `@EntityInfo(label)` on the class,
   `@FieldInfo(label)` / `@FieldViewConfiguration(displayName)` on the attribute (matched by annotation name, so the
   library does not depend on those jars);
3. the application's own bundles declared in its `faces-config.xml` files, by attribute name or its snake_case
   (`purchasePrice` or `purchase_price`);
4. common words built into the library (`name`, `code`, `quantity`, `price`, `date`...);
5. the name split into words (`purchasePrice` -> "Purchase price").

**Settings** (`web.xml` context parameters, or system properties with the same name; all optional):

| Parameter | Default | Meaning |
|---|---|---|
| `softcom.reportbuilder.AUTO_DATA_SOURCES` | `true` | `false` turns the automatic data sources off |
| `softcom.reportbuilder.AUTO_EXCLUDE` | - | entity names never offered nor reachable, e.g. `AuditEntity, ExceptionLog` |
| `softcom.reportbuilder.AUTO_REQUIRED_ROLE` | - | permission needed to see the automatic data sources |
| `softcom.reportbuilder.AUTO_DEPTH` | `3` | relation levels offered (0-3) |
| `softcom.reportbuilder.LARGE_TABLE_ROWS` | `200000` | from this estimated size a report needs a fast condition |
| `softcom.reportbuilder.MAX_DATE_RANGE_DAYS` | `366` | longest period of a fast date condition on a large table |
| `softcom.reportbuilder.QUERY_TIMEOUT_SECONDS` | `30` | query timeout of the automatic data sources |
| `softcom.reportbuilder.MAX_CONCURRENT_QUERIES` | `6` | report queries running at the same time in the application (a report waits up to 15 s for a slot, before touching the database) |

## Row restrictions (written once per application)

Automatic data sources show every row. An application limits what each user may see with **one** CDI bean - not
per report - that applies to every data source (automatic and hand-written), in preview, run and Excel export:

```java
@ApplicationScoped
public class GwReportRowFilter implements ReportRowFilter {
    @EJB private WarehouseFacade warehouseFacade;

    @Override
    public void restrict(RowRestrictions r) {
        if (noLoggedUser()) { r.denyAll(); return; }
        List<Double> ids = ...ids of warehouseFacade.getUserWarehouses()...;
        r.allowOnly(Warehouse.class, ids)                            // warehouses and everything linked to them
         .allowOnlyValues(ids, "warehouse_id", "towarehouse_id");   // copied id columns (invoice lines)
    }
}
```

- `allowOnly(Entity.class, ids)`: rows of that entity, and rows linked to it through many-to-one / one-to-one
  relations, are limited to those ids. Only the nearest links count, at most two relations away: an invoice by its
  warehouse or destination warehouse (either may be allowed), a payment through its invoice. A row whose links are all
  empty is not shown; tables not linked at all (items, suppliers) are not limited.
- `allowOnlyValues(values, "attr", ...)`: rows having one of these simple columns are limited to those where one of
  them is in the list.
- `where(Entity.class, ctx -> predicates)`: any other condition for data sources rooted at that entity.
- `denyAll()`: nothing (e.g. no logged-in user). An exception in the bean stops the report.
- In a collection data source (invoice -> lines) the invoice decides; the lines' own columns are only used when no
  rule concerns the invoice. Restrictions are ANDed around the report's conditions, so a user's OR cannot widen them.

generalWarehouse's `GwReportRowFilter` limits every report to the user's warehouses this way.

## Speed rules

For each data source the library asks PostgreSQL's catalog (never the tables themselves; cached 6 hours) for the
estimated row count and which columns lead a valid index; fields are mapped to columns through EclipseLink's
descriptors. Fields a condition can use an index on are marked ⚡ in the designer.

On a large table (`LARGE_TABLE_ROWS`) a report on an automatic data source must contain, on its AND path (not inside an OR group), a condition on
a ⚡ field: `=`, `IN`, or `BETWEEN` - at most `MAX_DATE_RANGE_DAYS` for dates. The designer starts such reports with
an ask-at-run period on the indexed date field and explains the rule; a report without it is refused with the list of
fast fields. Hand-written data sources are not checked (their developer sets `requireFilterOn`). Tables whose only
index is the primary key, views, and databases other than PostgreSQL are not restricted - the query timeout still
stops them. In a collection data source the owner's fields (the invoice date) only count as fast when the element's
link column (the lines' `invoice_id`) is indexed too, otherwise the lines table would still be read in full. To make a field fast, create an index on its column; statistics are
re-read within 6 hours (or after a restart).

Measured on WildFly 8.2.1 + PostgreSQL 16 with 600,000 invoices / 1.8 million lines (automatic
`auto.Invoice.invoiceLines`, total quantity per item): one month 54-77 ms, a full year 131-157 ms; an unrestricted
heavy report was stopped by a 1-second test timeout and PostgreSQL cancelled the statement.

## Known limitations

- Excel export reads the result in chunks of 5,000 rows (separate queries, outside a transaction). If rows are
  inserted or deleted while a long export runs, some rows can shift between chunks.

- Automatic data sources show English names where no label is found; add them to `rblabels_ar.properties`.
- Automatic data sources cannot apply per-user row restrictions (e.g. the user's warehouses); use hand-written data
  sources for that.

## Not in 1.0 (possible next steps)

- Grouping by month/year of a date, charts, scheduled/e-mailed reports, PDF (JasperReports) layouts.
- Reports across several applications' databases (would need a reporting database, e.g. with `postgres_fdw`).
