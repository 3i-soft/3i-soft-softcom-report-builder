package org.softcom.reportbuilder.engine;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.softcom.reportbuilder.testmodel.TCompany;
import org.softcom.reportbuilder.testmodel.TCustomer;
import org.softcom.reportbuilder.testmodel.TInvoice;
import org.softcom.reportbuilder.testmodel.TInvoiceLine;
import org.softcom.reportbuilder.testmodel.TItem;
import org.softcom.reportbuilder.testmodel.TItemKind;
import org.softcom.reportbuilder.testmodel.TWarehouse;

/**
 * One database with fixed data, shared by all test classes of the JVM:
 * in-memory H2 by default, or a real PostgreSQL with
 * {@code -Drb.test.pg.url=jdbc:postgresql://host:port/db [-Drb.test.pg.user=... -Drb.test.pg.password=...]}.
 * On PostgreSQL the rb_* tables are expected to exist already (created by the
 * library's SQL script) and are only extended, never dropped.
 */
final class TestDb {

	private static EntityManagerFactory emf;

	private TestDb() {
	}

	static synchronized EntityManagerFactory emf() {
		if (emf == null) {
			Map<String, String> props = new HashMap<>();
			String pgUrl = System.getProperty("rb.test.pg.url");
			if (pgUrl != null && !pgUrl.isEmpty()) {
				props.put("javax.persistence.jdbc.driver", "org.postgresql.Driver");
				props.put("javax.persistence.jdbc.url", pgUrl);
				props.put("javax.persistence.jdbc.user", System.getProperty("rb.test.pg.user", "postgres"));
				props.put("javax.persistence.jdbc.password", System.getProperty("rb.test.pg.password", ""));
				props.put("eclipselink.ddl-generation", "create-or-extend-tables");
			} else {
				// H2 has no date(ts) function; PostgreSQL (the default "date") does
				System.setProperty("org.softcom.reportbuilder.dayFunction", "TRUNC");
			}
			emf = Persistence.createEntityManagerFactory("rb-test", props);
			EntityManager em = emf.createEntityManager();
			boolean seeded = em.find(TCompany.class, 1L) != null;
			em.close();
			if (!seeded)
				seed(emf);
		}
		return emf;
	}

	static Date at(int y, int m, int d, int h, int min) {
		return Date.from(LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.systemDefault()).toInstant());
	}

	private static void seed(EntityManagerFactory factory) {
		EntityManager em = factory.createEntityManager();
		em.getTransaction().begin();
		TCompany a = new TCompany(1L, "A");
		TCompany b = new TCompany(2L, "B");
		TWarehouse w1 = new TWarehouse(10L, "Main", a);
		TWarehouse w2 = new TWarehouse(11L, "Branch", a);
		TWarehouse wb = new TWarehouse(20L, "Other company", b);
		TItem apple = new TItem(100L, "F-001", "apple", TItemKind.FRUIT, true);
		TItem bread = new TItem(101L, "B-001", "bread", TItemKind.BAKERY, true);
		TItem quote = new TItem(102L, "O-001", "O'Brien 50% mix", TItemKind.OTHER, false);
		TCustomer cust = new TCustomer(500L, "Customer X");
		TInvoice i1 = new TInvoice(1000L, at(2026, 1, 5, 9, 0), true, w1, a, cust);
		TInvoice i2 = new TInvoice(1001L, at(2026, 1, 31, 15, 30), true, w2, a, null); // afternoon of the last day
		TInvoice i3 = new TInvoice(1002L, at(2026, 2, 1, 0, 0), true, w1, a, null); // just outside January
		TInvoice open = new TInvoice(1003L, at(2026, 1, 10, 10, 0), false, w1, a, null); // not closed -> forced out
		TInvoice other = new TInvoice(1004L, at(2026, 1, 12, 10, 0), true, wb, b, null); // other company -> forced out
		for (Object o : Arrays.asList(a, b, w1, w2, wb, apple, bread, quote, cust, i1, i2, i3, open, other))
			em.persist(o);
		em.persist(new TInvoiceLine(1L, i1, apple, "5", "2.50", 1));
		em.persist(new TInvoiceLine(2L, i1, bread, "2", "1.00", 2));
		em.persist(new TInvoiceLine(3L, i2, apple, "10", "2.40", 1));
		em.persist(new TInvoiceLine(4L, i2, quote, "1", "9.99", 2));
		em.persist(new TInvoiceLine(5L, i3, bread, "7", "1.10", 1));
		em.persist(new TInvoiceLine(6L, open, apple, "100", "2.00", 1));
		em.persist(new TInvoiceLine(7L, other, apple, "1000", "2.00", 1));
		em.getTransaction().commit();
		em.close();
	}
}
