package org.softcom.reportbuilder.spi;

/**
 * Written once per application (a CDI bean, e.g. {@code @ApplicationScoped})
 * to limit every report to the rows the current user may see - not per
 * report: it applies to all data sources, automatic and hand-written, before
 * each query (preview, run and Excel export).
 *
 * <pre>
 * &#64;ApplicationScoped
 * public class GwReportRowFilter implements ReportRowFilter {
 *     &#64;EJB private WarehouseFacade warehouses;
 *
 *     public void restrict(RowRestrictions r) {
 *         List&lt;Double&gt; ids = ...the current user's warehouse ids...;
 *         r.allowOnly(Warehouse.class, ids)
 *          .allowOnlyValues(ids, "warehouse_id", "towarehouse_id");
 *     }
 * }
 * </pre>
 *
 * An exception thrown here stops the report (nothing is shown unrestricted).
 */
public interface ReportRowFilter {

	void restrict(RowRestrictions restrictions);
}
