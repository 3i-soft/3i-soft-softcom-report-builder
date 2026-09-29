package org.softcom.reportbuilder.engine;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.softcom.reportbuilder.spi.RowRestrictions;

/** Who runs the report and any extra values the host passes to forced filters. */
public class ReportRunContext implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String user;
	private final transient Predicate<String> roleCheck;
	private final Map<String, Object> attributes;
	private final RowRestrictions restrictions;

	public ReportRunContext(String user, Predicate<String> roleCheck, Map<String, Object> attributes) {
		this(user, roleCheck, attributes, null);
	}

	/** @param restrictions the rows the user may see (from the application's ReportRowFilter beans), or null */
	public ReportRunContext(String user, Predicate<String> roleCheck, Map<String, Object> attributes,
			RowRestrictions restrictions) {
		this.restrictions = restrictions;
		this.user = user;
		this.roleCheck = roleCheck;
		this.attributes = attributes == null ? Collections.<String, Object>emptyMap()
				: Collections.unmodifiableMap(new HashMap<>(attributes));
	}

	public static ReportRunContext anonymous() {
		return new ReportRunContext(null, null, null);
	}

	public String getUser() {
		return user;
	}

	public boolean isUserInRole(String role) {
		return roleCheck != null && role != null && roleCheck.test(role);
	}

	public Map<String, Object> getAttributes() {
		return attributes;
	}

	public RowRestrictions getRestrictions() {
		return restrictions;
	}
}
