package org.softcom.reportbuilder.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.internal.helper.DatabaseField;
import org.eclipse.persistence.internal.helper.DatabaseTable;
import org.eclipse.persistence.jpa.JpaHelper;
import org.eclipse.persistence.mappings.DatabaseMapping;
import org.eclipse.persistence.mappings.ForeignReferenceMapping;
import org.eclipse.persistence.mappings.OneToManyMapping;
import org.eclipse.persistence.mappings.OneToOneMapping;
import org.eclipse.persistence.sessions.Session;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Reads, for a data source, PostgreSQL's size estimate of its table(s) and
 * which fields sit in an indexed column. Fields are mapped to columns through
 * EclipseLink's descriptors (exact names, inheritance and join columns
 * included); the statistics come from the catalog (pg_class, pg_index), which
 * costs nothing on the tables themselves. Results are cached for some hours.
 * Anything unexpected (another database, another JPA provider...) gives
 * {@link SpeedInfo#UNKNOWN}: no size rule, the query timeout still applies.
 */
public final class TableStats {

	private static final Logger LOG = Logger.getLogger(TableStats.class.getName());

	private static final long TTL_MS = TimeUnit.HOURS.toMillis(6);
	private static final long FAILURE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
	/** Used when a table was never analyzed: a rough row size to estimate rows from its size on disk. */
	private static final long BYTES_PER_ROW_GUESS = 100;

	private static final String VISIBLE = "pg_table_is_visible(c.oid)";
	private static final String IN_SCHEMA = "lower(n.nspname) = lower(?2)";
	private static final String ROWS_SQL = "SELECT CAST(c.reltuples AS BIGINT), CAST(pg_relation_size(c.oid) AS BIGINT)"
			+ " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
			+ " WHERE lower(c.relname) = lower(?1) AND c.relkind IN ('r', 'p', 'm') AND ";
	private static final String INDEXED_SQL = "SELECT DISTINCT a.attname"
			+ " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_index i ON i.indrelid = c.oid"
			+ " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = i.indkey[0]"
			+ " WHERE lower(c.relname) = lower(?1) AND i.indisvalid AND i.indpred IS NULL AND ";

	private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

	private TableStats() {
	}

	private static final class Cached {
		final SpeedInfo info;
		final long expires;

		Cached(SpeedInfo info, long expires) {
			this.info = info;
			this.expires = expires;
		}
	}

	/** Size and fast fields of the data source, from the cache when fresh. Never throws. */
	public static SpeedInfo of(EntityManager em, ReportDataSource ds, long largeTableRows) {
		long now = System.currentTimeMillis();
		Cached c = CACHE.get(ds.getKey());
		if (c != null && now < c.expires)
			return c.info;
		SpeedInfo info;
		long ttl = TTL_MS;
		try {
			info = compute(em, ds, largeTableRows);
		} catch (RuntimeException | LinkageError e) {
			LOG.log(Level.FINE, "No table statistics for report data source " + ds.getKey(), e);
			info = SpeedInfo.UNKNOWN;
			ttl = FAILURE_TTL_MS;
		}
		CACHE.put(ds.getKey(), new Cached(info, now + ttl));
		return info;
	}

	/** Forgets cached statistics (tests, or after creating an index). */
	public static void clear() {
		CACHE.clear();
	}

	static SpeedInfo compute(EntityManager em, ReportDataSource ds, long largeTableRows) {
		Session session = JpaHelper.getServerSession(em.getEntityManagerFactory());
		ClassDescriptor root = session.getDescriptor(ds.getRootEntity());
		if (root == null || root.getTables().isEmpty())
			return SpeedInfo.UNKNOWN;
		ClassDescriptor element = null;
		if (ds.getGrainPath() != null) {
			DatabaseMapping m = root.getMappingForAttributeName(ds.getGrainPath());
			if (m instanceof ForeignReferenceMapping)
				element = ((ForeignReferenceMapping) m).getReferenceDescriptor();
		}
		Map<String, TableInfo> tables = new HashMap<>();
		// the tables whose size matters: the root's and, for a collection data source, the element's
		long rows = rows(em, root.getTables().get(0), tables);
		if (element != null && !element.getTables().isEmpty())
			rows = Math.max(rows, rows(em, element.getTables().get(0), tables));
		Set<String> primaryKeys = primaryKeyColumns(root);
		if (element != null)
			primaryKeys.addAll(primaryKeyColumns(element));
		// Collection data source: a condition on the owner (the invoice date) only avoids scanning the element table
		// (the lines) when the element's link column to the owner is indexed.
		boolean ownerFieldsCount = element == null || linkIndexed(em, root, element, ds.getGrainPath(), tables);
		String elementPrefix = ds.getGrainPath() + ".";
		Set<String> fast = new LinkedHashSet<>();
		Set<String> keys = new LinkedHashSet<>();
		for (ReportField f : ds.getFields()) {
			if (f.isComputed() || (!ownerFieldsCount && !f.getPath().startsWith(elementPrefix)))
				continue;
			Column col = column(root, element, ds.getGrainPath(), f.getPath());
			if (col == null || !info(em, col.table, tables).indexed.contains(col.name.toLowerCase(Locale.ROOT)))
				continue;
			fast.add(f.getPath());
			if (primaryKeys.contains(col.qualified()))
				keys.add(f.getPath());
		}
		return new SpeedInfo(rows, fast, keys, rows >= largeTableRows);
	}

	/** Whether the element table's column pointing to the owner (the collection's join column) leads an index. */
	private static boolean linkIndexed(EntityManager em, ClassDescriptor root, ClassDescriptor element, String grainPath,
			Map<String, TableInfo> tables) {
		DatabaseMapping m = root.getMappingForAttributeName(grainPath);
		if (!(m instanceof OneToManyMapping))
			return false;
		List<DatabaseField> links = ((OneToManyMapping) m).getTargetForeignKeyFields();
		if (links == null || links.size() != 1)
			return false;
		Column link = new Column(tableOf(element, links.get(0)), links.get(0).getName());
		return info(em, link.table, tables).indexed.contains(link.name.toLowerCase(Locale.ROOT));
	}

	/** table.column (lower case) of a descriptor's primary key. */
	private static Set<String> primaryKeyColumns(ClassDescriptor d) {
		Set<String> out = new HashSet<>();
		if (d.getPrimaryKeyFields() != null)
			for (DatabaseField f : d.getPrimaryKeyFields())
				out.add(new Column(tableOf(d, f), f.getName()).qualified());
		return out;
	}

	// ------------------------------------------------------ field -> column

	private static final class Column {
		final DatabaseTable table;
		final String name;

		Column(DatabaseTable table, String name) {
			this.table = table;
			this.name = unquote(name);
		}

		String qualified() {
			return (unquote(table.getName()) + "." + name).toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * The column holding a field when it is on the root (or element) table
	 * itself: a simple attribute, or {@code relation.id} which is the foreign
	 * key column. Fields of joined tables return null (an index there does not
	 * make the scan of the root table cheaper).
	 */
	static Column column(ClassDescriptor root, ClassDescriptor element, String grainPath, String path) {
		String[] p = path.split("\\.");
		ClassDescriptor d = root;
		int i = 0;
		if (element != null && grainPath != null && p.length >= 2 && p[0].equals(grainPath)) {
			d = element;
			i = 1;
		}
		int rest = p.length - i;
		if (rest == 1)
			return direct(d, d.getMappingForAttributeName(p[i]));
		if (rest == 2) {
			DatabaseMapping m = d.getMappingForAttributeName(p[i]);
			if (!(m instanceof OneToOneMapping))
				return null;
			OneToOneMapping relation = (OneToOneMapping) m;
			Map<DatabaseField, DatabaseField> keys = relation.getSourceToTargetKeyFields();
			ClassDescriptor target = relation.getReferenceDescriptor();
			if (keys == null || keys.size() != 1 || target == null)
				return null;
			Map.Entry<DatabaseField, DatabaseField> key = keys.entrySet().iterator().next();
			DatabaseMapping targetMapping = target.getMappingForAttributeName(p[i + 1]);
			if (targetMapping == null || !targetMapping.isDirectToFieldMapping() || targetMapping.getField() == null)
				return null;
			if (!unquote(targetMapping.getField().getName()).equalsIgnoreCase(unquote(key.getValue().getName())))
				return null;
			return new Column(tableOf(d, key.getKey()), key.getKey().getName());
		}
		return null;
	}

	private static Column direct(ClassDescriptor d, DatabaseMapping m) {
		if (m == null || !m.isDirectToFieldMapping() || m.getField() == null)
			return null;
		DatabaseField f = m.getField();
		return new Column(tableOf(d, f), f.getName());
	}

	private static DatabaseTable tableOf(ClassDescriptor d, DatabaseField f) {
		DatabaseTable t = f.getTable();
		return t != null && t.getName() != null && !t.getName().isEmpty() ? t : d.getTables().get(0);
	}

	// ---------------------------------------------------------- catalog

	private static final class TableInfo {
		long rows = -1;
		Set<String> indexed = new HashSet<>();
	}

	private static long rows(EntityManager em, DatabaseTable t, Map<String, TableInfo> tables) {
		return info(em, t, tables).rows;
	}

	private static TableInfo info(EntityManager em, DatabaseTable t, Map<String, TableInfo> tables) {
		String name = unquote(t.getName());
		String schema = unquote(t.getTableQualifier());
		String key = schema + "." + name;
		TableInfo info = tables.get(key);
		if (info != null)
			return info;
		info = new TableInfo();
		List<?> sizes = query(em, ROWS_SQL, name, schema);
		if (!sizes.isEmpty()) {
			Object[] row = (Object[]) sizes.get(0);
			long reltuples = ((Number) row[0]).longValue();
			long bytes = ((Number) row[1]).longValue();
			// never analyzed: -1 (PostgreSQL 14+) or 0 with data on disk (older versions)
			info.rows = reltuples > 0 || bytes == 0 ? Math.max(reltuples, 0) : bytes / BYTES_PER_ROW_GUESS;
		}
		for (Object column : query(em, INDEXED_SQL, name, schema))
			info.indexed.add(String.valueOf(column).toLowerCase(Locale.ROOT));
		tables.put(key, info);
		return info;
	}

	private static List<?> query(EntityManager em, String sql, String table, String schema) {
		boolean qualified = !schema.isEmpty();
		Query q = em.createNativeQuery(sql + (qualified ? IN_SCHEMA : VISIBLE));
		q.setHint(ReportExecutor.HINT_BIND_PARAMETERS, ReportExecutor.HINT_TRUE);
		q.setParameter(1, table);
		if (qualified)
			q.setParameter(2, schema);
		return q.getResultList();
	}

	private static String unquote(String identifier) {
		if (identifier == null)
			return "";
		String s = identifier.trim();
		return s.length() > 1 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
	}

	/** For tests: the columns found for the fields of a data source (field path -&gt; table.column). */
	static Map<String, String> columns(EntityManager em, ReportDataSource ds) {
		Session session = JpaHelper.getServerSession(em.getEntityManagerFactory());
		ClassDescriptor root = session.getDescriptor(ds.getRootEntity());
		ClassDescriptor element = null;
		if (ds.getGrainPath() != null) {
			DatabaseMapping m = root.getMappingForAttributeName(ds.getGrainPath());
			if (m instanceof ForeignReferenceMapping)
				element = ((ForeignReferenceMapping) m).getReferenceDescriptor();
		}
		Map<String, String> out = new HashMap<>();
		List<ReportField> fields = new ArrayList<>(ds.getFields());
		for (ReportField f : fields) {
			Column c = f.isComputed() ? null : column(root, element, ds.getGrainPath(), f.getPath());
			if (c != null)
				out.put(f.getPath(), c.qualified());
		}
		return out;
	}
}
