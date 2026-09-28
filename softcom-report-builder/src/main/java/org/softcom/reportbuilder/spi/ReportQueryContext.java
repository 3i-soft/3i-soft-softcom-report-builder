package org.softcom.reportbuilder.spi;

import java.util.Map;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Root;

/**
 * What a {@link ForcedFilter} gets to build its predicates. {@link #path(String)}
 * and {@link #join(String)} reuse the joins of the report query, so forced
 * conditions never add duplicate joins.
 */
public interface ReportQueryContext {

	CriteriaBuilder getCriteriaBuilder();

	CriteriaQuery<?> getQuery();

	Root<?> getRoot();

	/**
	 * Resolves an attribute path such as {@code invoice.closed} (any attribute,
	 * not only whitelisted ones), going through the declared joins.
	 * <p>
	 * To test whether an optional relation is empty, use its id:
	 * {@code cb.isNull(ctx.path("customer.id"))}. {@code ctx.path("customer")}
	 * is the plain relation attribute; testing it next to a declared LEFT join
	 * makes EclipseLink add an inner join and drop the rows without a customer.
	 * </p>
	 */
	<T> Path<T> path(String attributePath);

	/** The (shared) join for a relation path such as {@code invoice} or {@code invoice.warehouse}. */
	From<?, ?> join(String relationPath);

	/** Login name of the user running the report, may be null outside a request. */
	String getCurrentUser();

	boolean isUserInRole(String role);

	/** Free-form values the host application attached to the run (e.g. current branch id). */
	Map<String, Object> getAttributes();
}
