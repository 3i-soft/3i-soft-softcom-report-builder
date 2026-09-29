package org.softcom.reportbuilder.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;
import org.softcom.reportbuilder.model.ReportDefinition;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.testmodel.TItem;

/** Who sees a saved report: owner, admins, everyone, or listed users and roles. */
public class SharingTest {

	private static ReportSecurity user(final String name, final String... permissions) {
		final Set<String> granted = new HashSet<>(Arrays.asList(permissions));
		granted.add(ReportRoles.run());
		return new ReportSecurity() {
			@Override
			public String getCurrentUser() {
				return name;
			}

			@Override
			public boolean hasPermission(String permission) {
				return granted.contains(permission);
			}
		};
	}

	private static ReportDefinitionFacade facade(ReportSecurity security) throws Exception {
		ReportDefinitionFacade f = new ReportDefinitionFacade();
		set(f, "security", security);
		set(f, "catalog", new ReportCatalog() {
			private static final long serialVersionUID = 1L;

			@Override
			public ReportDataSource get(String key) {
				return new ReportDataSource(key, TItem.class);
			}
		});
		return f;
	}

	private static void set(Object target, String field, Object value) throws Exception {
		Field f = target.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static ReportDefinition report(boolean shared, String users, String roles) {
		ReportDefinition d = new ReportDefinition();
		d.setOwner("owner");
		d.setDataSourceKey("t.items");
		d.setShared(shared);
		d.setSharedUsers(users);
		d.setSharedRoles(roles);
		return d;
	}

	@Test
	public void privateReportsAreSeenByTheirOwnerAndAdminsOnly() throws Exception {
		ReportDefinition d = report(false, null, null);
		assertTrue(facade(user("owner")).isVisible(d));
		assertTrue(facade(user("boss", ReportRoles.admin())).isVisible(d));
		assertFalse(facade(user("other")).isVisible(d));
	}

	@Test
	public void sharedWithoutUsersOrRolesMeansEveryone() throws Exception {
		assertTrue(facade(user("other")).isVisible(report(true, null, "")));
	}

	@Test
	public void sharedWithListedUsersAndRoles() throws Exception {
		ReportDefinition d = report(true, "u1, u2", "STORE_KEEPER");
		assertTrue(facade(user("u2")).isVisible(d));
		assertTrue(facade(user("u9", "STORE_KEEPER")).isVisible(d));
		assertFalse(facade(user("u9")).isVisible(d));
		assertFalse("the whole login, not a part", facade(user("u")).isVisible(d));
		assertTrue(facade(user("owner")).isVisible(d));
		assertEquals(Arrays.asList("u1", "u2"), d.getSharedUserList());
		assertTrue("users only", facade(user("u1")).isVisible(report(true, "u1", null)));
		assertFalse(facade(user("u3")).isVisible(report(true, "u1", null)));
	}

	@Test
	public void noOneWithoutTheRunPermission() throws Exception {
		ReportSecurity none = new ReportSecurity() {
			@Override
			public String getCurrentUser() {
				return "u1";
			}

			@Override
			public boolean hasPermission(String permission) {
				return false;
			}
		};
		assertFalse(facade(none).isVisible(report(true, "u1", null)));
	}
}
