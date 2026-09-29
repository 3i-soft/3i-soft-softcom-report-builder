package org.softcom.reportbuilder.auto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.softcom.reportbuilder.spi.ChoiceSource;

/**
 * Names of one code kind, from the application's code provider, sorted by
 * name and cached for some minutes (the results page asks per cell).
 */
public class CodeChoiceSource implements ChoiceSource {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = Logger.getLogger(CodeChoiceSource.class.getName());
	static final long TTL_MS = TimeUnit.MINUTES.toMillis(10);

	private static final Map<String, Object[]> CACHE = new ConcurrentHashMap<>();

	private final String kind;
	private final transient Function<String, Map<String, String>> provider;

	public CodeChoiceSource(String kind, Function<String, Map<String, String>> provider) {
		this.kind = kind;
		this.provider = provider;
	}

	public String getKind() {
		return kind;
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<String, String> choices() {
		if (provider == null)
			return Collections.emptyMap();
		long now = System.currentTimeMillis();
		Object[] cached = CACHE.get(kind);
		if (cached != null && now < (Long) cached[0])
			return (Map<String, String>) cached[1];
		Map<String, String> sorted;
		try {
			Map<String, String> m = provider.apply(kind);
			List<Map.Entry<String, String>> entries = new ArrayList<>(m == null ? Collections.<String, String>emptyMap().entrySet()
					: m.entrySet());
			entries.sort((a, b) -> String.valueOf(a.getValue()).compareTo(String.valueOf(b.getValue())));
			sorted = new LinkedHashMap<>();
			for (Map.Entry<String, String> e : entries)
				if (e.getKey() != null)
					sorted.put(e.getKey(), e.getValue() == null ? e.getKey() : e.getValue());
			sorted = Collections.unmodifiableMap(sorted);
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Report builder: codes of kind " + kind + " are not available", e);
			// keep showing the stored codes; try again a little later
			sorted = Collections.emptyMap();
			now -= TTL_MS - TimeUnit.MINUTES.toMillis(1);
		}
		CACHE.put(kind, new Object[] { now + TTL_MS, sorted });
		return sorted;
	}

	/** Forgets the cached lists (tests). */
	static void clearCache() {
		CACHE.clear();
	}
}
