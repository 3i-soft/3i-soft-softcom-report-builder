package org.softcom.reportbuilder.service;

/**
 * Default permission names used by {@link ReportSecurity}. They can be renamed
 * without code through system properties (e.g. in standalone.xml):
 * {@code org.softcom.reportbuilder.role.run}, {@code ...role.design},
 * {@code ...role.admin}, {@code ...role.labels}.
 */
public final class ReportRoles {

	public static final String DEFAULT_RUN = "REPORT_BUILDER_RUN";
	public static final String DEFAULT_DESIGN = "REPORT_BUILDER_DESIGN";
	public static final String DEFAULT_ADMIN = "REPORT_BUILDER_ADMIN";
	public static final String DEFAULT_LABELS = "REPORT_BUILDER_LABELS";

	private ReportRoles() {
	}

	public static String run() {
		return System.getProperty("org.softcom.reportbuilder.role.run", DEFAULT_RUN);
	}

	public static String design() {
		return System.getProperty("org.softcom.reportbuilder.role.design", DEFAULT_DESIGN);
	}

	public static String admin() {
		return System.getProperty("org.softcom.reportbuilder.role.admin", DEFAULT_ADMIN);
	}

	public static String labels() {
		return System.getProperty("org.softcom.reportbuilder.role.labels", DEFAULT_LABELS);
	}
}
