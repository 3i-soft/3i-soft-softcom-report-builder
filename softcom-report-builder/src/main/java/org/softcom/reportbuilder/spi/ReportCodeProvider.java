package org.softcom.reportbuilder.spi;

import java.util.Map;

/**
 * Written once per application (a CDI bean) when its entities mark code
 * fields with {@code @Code(codeKind = ...)}: gives the names of a code kind,
 * so reports show "مضادات حيوية" instead of "01" and conditions offer a pick
 * list. The library caches the lists for a few minutes.
 */
public interface ReportCodeProvider {

	/** @return stored code -&gt; display name for the kind, or null/empty when unknown */
	Map<String, String> codes(String kind);
}
