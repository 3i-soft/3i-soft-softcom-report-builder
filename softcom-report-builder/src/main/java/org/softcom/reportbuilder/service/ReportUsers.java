package org.softcom.reportbuilder.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.softcom.reportbuilder.spi.ReportUserDirectory;

/** The application's users for sharing (see {@link ReportUserDirectory}), cached for 5 minutes. */
@ApplicationScoped
public class ReportUsers {

	private static final Logger LOG = Logger.getLogger(ReportUsers.class.getName());
	private static final long TTL_MS = TimeUnit.MINUTES.toMillis(5);
	public static final int MAX_RESULTS = 30;

	@Inject
	@Any
	private Instance<ReportUserDirectory> directories;

	private volatile Map<String, String> users;
	private volatile long expires;

	/** Whether the application gives a list of users (else logins are typed). */
	public boolean isAvailable() {
		return !directories.isUnsatisfied();
	}

	/** Users whose name or login contains {@code text}, sorted by name. */
	public List<String> search(String text) {
		String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, String> u : users().entrySet()) {
			if (out.size() >= MAX_RESULTS)
				break;
			if (t.isEmpty() || u.getKey().toLowerCase(Locale.ROOT).contains(t)
					|| u.getValue().toLowerCase(Locale.ROOT).contains(t))
				out.add(u.getKey());
		}
		return out;
	}

	/** Display name of a user, or the login itself. */
	public String name(String user) {
		String n = user == null ? null : users().get(user);
		return n == null ? user : n;
	}

	private Map<String, String> users() {
		long now = System.currentTimeMillis();
		Map<String, String> m = users;
		if (m != null && now < expires)
			return m;
		Map<String, String> all = new LinkedHashMap<>();
		try {
			for (ReportUserDirectory d : directories) {
				Map<String, String> part = d.users();
				if (part != null)
					for (Map.Entry<String, String> e : part.entrySet())
						if (e.getKey() != null && !e.getKey().trim().isEmpty())
							all.put(e.getKey().trim(), e.getValue() == null || e.getValue().trim().isEmpty() ? e.getKey().trim()
									: e.getValue().trim());
			}
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Report builder: the list of users is not available", e);
			// keep the previous list; try again in a minute
			users = m == null ? Collections.<String, String>emptyMap() : m;
			expires = now + TimeUnit.MINUTES.toMillis(1);
			return users;
		}
		List<Map.Entry<String, String>> sorted = new ArrayList<>(all.entrySet());
		sorted.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : sorted)
			result.put(e.getKey(), e.getValue());
		users = Collections.unmodifiableMap(result);
		expires = now + TTL_MS;
		return users;
	}
}
