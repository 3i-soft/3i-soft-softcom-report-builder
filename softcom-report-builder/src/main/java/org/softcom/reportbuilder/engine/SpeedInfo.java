package org.softcom.reportbuilder.engine;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the database says about a data source's table: its estimated size and
 * the fields a condition can use an index on ("fast" fields).
 */
public final class SpeedInfo implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Nothing known (not PostgreSQL, statistics not readable...): no size rule is applied. */
	public static final SpeedInfo UNKNOWN = new SpeedInfo(-1, Collections.<String>emptySet(), false);

	private final long estimatedRows;
	private final Set<String> fastFields;
	private final Set<String> keyFields;
	private final boolean large;

	public SpeedInfo(long estimatedRows, Collection<String> fastFields, boolean large) {
		this(estimatedRows, fastFields, Collections.<String>emptySet(), large);
	}

	/** @param keyFields the fast fields that are primary keys (indexed, but rarely what a report filters on) */
	public SpeedInfo(long estimatedRows, Collection<String> fastFields, Collection<String> keyFields, boolean large) {
		this.estimatedRows = estimatedRows;
		this.fastFields = Collections.unmodifiableSet(new LinkedHashSet<>(fastFields));
		Set<String> keys = new LinkedHashSet<>(keyFields);
		keys.retainAll(this.fastFields);
		this.keyFields = Collections.unmodifiableSet(keys);
		this.large = large;
	}

	/** Estimated number of rows, or -1 when unknown. */
	public long getEstimatedRows() {
		return estimatedRows;
	}

	/** Field paths stored in an indexed column (first column of a valid, non-partial index). */
	public Set<String> getFastFields() {
		return fastFields;
	}

	/** Fast fields other than primary keys: the ones a report can sensibly be asked to filter on. */
	public Set<String> getUsefulFastFields() {
		Set<String> useful = new LinkedHashSet<>(fastFields);
		useful.removeAll(keyFields);
		return useful;
	}

	public boolean isFast(String fieldPath) {
		return fieldPath != null && fastFields.contains(fieldPath);
	}

	/** Large enough that every report needs a condition on a fast field. */
	public boolean isLarge() {
		return large;
	}
}
