package org.softcom.reportbuilder.spi;

import java.io.Serializable;
import java.util.Map;

/**
 * Allowed values of a field, looked up when needed (e.g. from a code table):
 * key = stored value, value = display label. Used for pick lists in conditions
 * and to show labels instead of stored codes in results.
 */
@FunctionalInterface
public interface ChoiceSource extends Serializable {

	Map<String, String> choices();

	/**
	 * Display label of one stored value, or null when unknown. Override it when
	 * a single value can be resolved more reliably than through the full list
	 * (e.g. a code service that refreshes its cache on a miss).
	 */
	default String label(String storedValue) {
		Map<String, String> all = choices();
		return all == null ? null : all.get(storedValue);
	}
}
