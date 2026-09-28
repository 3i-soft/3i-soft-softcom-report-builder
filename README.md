# softcom-report-builder

Runtime (user-defined) reports for 3i-soft Java EE 7 applications. Users pick a
**data source**, the **columns** (optionally with totals: sum, average, min, max,
count), **conditions** in AND/OR groups, and **sorting**. They can then preview,
save, share, run and export the report to Excel. The engine turns each saved
definition into a single JPA Criteria query on the host application's own
database (one database at a time).

- Java 8, Java EE 7 (EJB 3.2, CDI 1.1, JSF 2.2), JPA 2.1 / EclipseLink 2.6, PrimeFaces 6.2, PostgreSQL.
- No runtime dependencies of its own. JSON uses JSON-P (`javax.json`, part of Java EE 7). POI, `softcom-persistence`
  and the Java EE APIs are `provided` by the host application.

## Why it is safe and fast

| Concern | How it is handled |
|---|---|
| SQL injection | Users only choose among whitelisted fields declared by the host. Values are converted to the attribute's Java type and handed to the Criteria API. Report queries re-enable JDBC parameter binding with the `eclipselink.jdbc.bind-parameters` hint, even when the persistence unit disables it globally (acc-expert does). LIKE wildcards are escaped. |
| Tenant / permission leaks | Each data source declares *forced filters* (company, allowed warehouses, cancelled/deleted flags...). They are ANDed around the user's conditions, so a user's OR can never widen them. Values they need (e.g. the current company) come from server-side `ReportAttributesProvider` beans, never from the page. A data source's `requiredRole` also applies to report-builder admins. |
| Wrong totals | Rows are always at the most detailed grain. Only many-to-one joins (plus one declared *grain* collection, which is always joined) are allowed, so SUM/COUNT cannot double count. Grouping by a DATE field groups by day, even on timestamp columns. An export that hits the row limit says so in its last row. |
| Slow queries | Only the selected columns are fetched (no entities). Totals are computed by the database (GROUP BY). Joins are created only when used. Paging uses LIMIT/OFFSET with a stable ORDER BY and no count query. A data source can require a date range of at most N days. Each query has a timeout, rows per page and export rows are capped, and Excel is streamed (SXSSF). Every run is logged in `rb_report_run_log` with its duration. |

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

5. **Data sources.** Add a CDI bean implementing `ReportDataSourceProvider`. See
   `GwReportDataSourceProvider` in generalWarehouse for a complete example. Example:

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
work on timestamp columns and still use an index.

## Known limitations

- Excel export reads the result in chunks of 5,000 rows (separate queries, outside a transaction). If rows are
  inserted or deleted while a long export runs, some rows can shift between chunks.

## Not in 1.0 (possible next steps)

- Grouping by month/year of a date, charts, scheduled/e-mailed reports, PDF (JasperReports) layouts.
- Reports across several applications' databases (would need a reporting database, e.g. with `postgres_fdw`).
