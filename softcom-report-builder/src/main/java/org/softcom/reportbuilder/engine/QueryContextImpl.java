package org.softcom.reportbuilder.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.From;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Root;

import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;
import org.softcom.reportbuilder.spi.ReportQueryContext;

/**
 * Resolves attribute paths to Criteria paths, creating each join once and only
 * when a selected column, condition or forced filter actually needs it.
 */
class QueryContextImpl implements ReportQueryContext {

	private final CriteriaBuilder cb;
	private final CriteriaQuery<?> query;
	private final Root<?> root;
	private final ReportDataSource dataSource;
	private final ReportRunContext run;
	private final Map<String, From<?, ?>> joins = new HashMap<>();
	private final Map<String, Expression<?>> fieldExpressions = new HashMap<>();

	QueryContextImpl(CriteriaBuilder cb, CriteriaQuery<?> query, Root<?> root, ReportDataSource dataSource,
			ReportRunContext run) {
		this.cb = cb;
		this.query = query;
		this.root = root;
		this.dataSource = dataSource;
		this.run = run == null ? ReportRunContext.anonymous() : run;
	}

	/**
	 * Explicit join, created once per path. Declared joins use their declared
	 * type; an undeclared path (only reachable from trusted forced-filter code)
	 * is joined INNER, the natural meaning of a mandatory condition.
	 */
	@Override
	public From<?, ?> join(String relationPath) {
		From<?, ?> existing = joins.get(relationPath);
		if (existing != null)
			return existing;
		int dot = relationPath.lastIndexOf('.');
		From<?, ?> parent = dot < 0 ? root : join(relationPath.substring(0, dot));
		String attribute = dot < 0 ? relationPath : relationPath.substring(dot + 1);
		JoinType type = dataSource.getJoins().get(relationPath);
		From<?, ?> join = parent.join(attribute, type == null ? JoinType.INNER : type);
		joins.put(relationPath, join);
		return join;
	}

	/**
	 * Starts from the longest declared join prefix and navigates the rest with
	 * {@code get()}. Whitelisted fields always sit on declared joins; for other
	 * paths (e.g. {@code invoice.company.id} in a forced filter) plain navigation
	 * lets the provider use the foreign-key column instead of adding a join.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T> Path<T> path(String attributePath) {
		String[] parts = attributePath.split("\\.");
		Path<?> current = root;
		int start = 0;
		for (int i = parts.length - 1; i > 0; i--) {
			String prefix = join(parts, i);
			if (dataSource.getJoins().containsKey(prefix) || joins.containsKey(prefix)) {
				current = join(prefix);
				start = i;
				break;
			}
		}
		for (int i = start; i < parts.length; i++)
			current = current.get(parts[i]);
		return (Path<T>) current;
	}

	/** The expression of a whitelisted field (its path, or its computed expression), built once per query. */
	Expression<?> field(ReportField f) {
		Expression<?> e = fieldExpressions.get(f.getPath());
		if (e == null) {
			e = f.isComputed() ? f.getExpression().build(this) : path(f.getPath());
			fieldExpressions.put(f.getPath(), e);
		}
		return e;
	}

	private static String join(String[] parts, int count) {
		StringBuilder sb = new StringBuilder(parts[0]);
		for (int i = 1; i < count; i++)
			sb.append('.').append(parts[i]);
		return sb.toString();
	}

	@Override
	public CriteriaBuilder getCriteriaBuilder() {
		return cb;
	}

	@Override
	public CriteriaQuery<?> getQuery() {
		return query;
	}

	@Override
	public Root<?> getRoot() {
		return root;
	}

	@Override
	public String getCurrentUser() {
		return run.getUser();
	}

	@Override
	public boolean isUserInRole(String role) {
		return run.isUserInRole(role);
	}

	@Override
	public Map<String, Object> getAttributes() {
		return run.getAttributes() == null ? Collections.<String, Object>emptyMap() : run.getAttributes();
	}
}
