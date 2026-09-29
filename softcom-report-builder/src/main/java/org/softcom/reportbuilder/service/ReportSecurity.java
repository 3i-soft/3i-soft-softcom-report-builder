package org.softcom.reportbuilder.service;

/**
 * Who is the current user and what may they do. The library ships
 * {@link DefaultReportSecurity} (container roles). A host application with its
 * own permission system replaces it with a CDI alternative, e.g.
 *
 * <pre>
 * &#64;Alternative
 * &#64;Priority(Interceptor.Priority.APPLICATION + 10)
 * &#64;ApplicationScoped
 * public class MyReportSecurity implements ReportSecurity { ... }
 * </pre>
 */
public interface ReportSecurity {

	/** Stable identifier of the current user; stored as the owner of saved reports. */
	String getCurrentUser();

	/** Whether the current user holds the given permission / role. */
	boolean hasPermission(String permission);

	/** May run reports shared with them (and their own). */
	default boolean canRun() {
		return hasPermission(ReportRoles.run()) || canDesign();
	}

	/** May create, edit and preview reports. */
	default boolean canDesign() {
		return hasPermission(ReportRoles.design()) || isAdmin();
	}

	/** Sees, edits and deletes every saved report. */
	default boolean isAdmin() {
		return hasPermission(ReportRoles.admin());
	}

	/** May change the names of tables and fields on the labels page. */
	default boolean canEditLabels() {
		return isAdmin() || hasPermission(ReportRoles.labels());
	}
}
