package org.softcom.reportbuilder.spi;

import java.util.Map;

/**
 * Optional, written once per application (a CDI bean): the users a report can
 * be shared with, so the designer offers a searchable list of names instead
 * of logins to type. The library caches the list for a few minutes.
 */
public interface ReportUserDirectory {

	/** @return user (as {@code ReportSecurity.getCurrentUser()} gives it) -&gt; display name */
	Map<String, String> users();
}
