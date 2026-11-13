package org.softcom.reportbuilder.spec;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * A table linked to the report's data source by equal columns that are not a
 * declared relation, e.g. invoice lines and item statistics by
 * {@code item_id = item_id AND warehouse_id = warehouse_id}. Only the rows
 * that have a matching row in the linked table are kept (an inner join), and
 * the linked table's fields can be used like the data source's own: columns,
 * totals, conditions and sorting. Its fields are named {@code alias:path},
 * e.g. {@code l1:totalAvailableQuantity}.
 */
public class LinkSpec implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Separates a link's alias from the linked data source's field path ({@code l1:item.name}). */
	public static final char SEPARATOR = ':';

	/** Aliases are l1 .. l9: short, safe in field paths, and never a JPA attribute name with a colon. */
	public static final String ALIAS_PATTERN = "l[1-9]";

	private String alias;
	private String dataSource;
	private List<Pair> on = new ArrayList<>();

	public LinkSpec() {
	}

	public LinkSpec(String alias, String dataSource) {
		this.alias = alias;
		this.dataSource = dataSource;
	}

	/** One pair of equal columns: a field of the report (or of an earlier link) and a field of the linked table. */
	public static class Pair implements Serializable {

		private static final long serialVersionUID = 1L;

		private String left;
		private String right;

		public Pair() {
		}

		public Pair(String left, String right) {
			this.left = left;
			this.right = right;
		}

		public Pair copy() {
			return new Pair(left, right);
		}

		/** A field of the data source, or {@code alias:path} of an earlier link. */
		public String getLeft() {
			return left;
		}

		public void setLeft(String left) {
			this.left = left;
		}

		/** A field path of the linked data source (without the alias). */
		public String getRight() {
			return right;
		}

		public void setRight(String right) {
			this.right = right;
		}

		public boolean isComplete() {
			return left != null && !left.isEmpty() && right != null && !right.isEmpty();
		}
	}

	public LinkSpec copy() {
		LinkSpec l = new LinkSpec(alias, dataSource);
		for (Pair p : on)
			l.on.add(p.copy());
		return l;
	}

	/** The prefix of this link's fields: {@code l1:}. */
	public String fieldPrefix() {
		return alias + SEPARATOR;
	}

	/** The alias of a field path {@code l1:...}, or null for a field of the data source itself. */
	public static String aliasOf(String fieldPath) {
		if (fieldPath == null)
			return null;
		int i = fieldPath.indexOf(SEPARATOR);
		return i < 0 ? null : fieldPath.substring(0, i);
	}

	public String getAlias() {
		return alias;
	}

	public void setAlias(String alias) {
		this.alias = alias;
	}

	public String getDataSource() {
		return dataSource;
	}

	public void setDataSource(String dataSource) {
		this.dataSource = dataSource;
	}

	public List<Pair> getOn() {
		return on;
	}

	public void setOn(List<Pair> on) {
		this.on = on == null ? new ArrayList<Pair>() : on;
	}
}
