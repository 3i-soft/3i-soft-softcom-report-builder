package org.softcom.reportbuilder.auto;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.faces.context.FacesContext;
import javax.servlet.ServletContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.softcom.reportbuilder.spi.ReportBuilderConfig;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Display names of discovered entities and fields, found without any setup:
 * <ol>
 * <li>the application's optional {@code rblabels} bundle (rblabels_ar /
 * rblabels_en .properties), keys {@code Entity}, {@code Entity.attribute},
 * {@code attribute} or {@code EnumType.CONSTANT};</li>
 * <li>the labels the entity's author already wrote in 3i-soft annotations:
 * {@code @EntityInfo(label)} on the class, {@code @FieldInfo(label)} and
 * {@code @FieldViewConfiguration(displayName)} on the attribute (found by
 * name, so no dependency on those libraries);</li>
 * <li>the application's own JSF resource bundles (those declared in
 * faces-config.xml), whose keys are often the attribute name or its
 * snake_case form (purchasePrice / purchase_price);</li>
 * <li>a few common words built into the library (name, code, date...);</li>
 * <li>the name split into words: purchasePrice becomes "Purchase price".</li>
 * </ol>
 * An Arabic label is only taken from a value written in Arabic letters and an
 * English one only from a value without them, so a bundle in the other
 * language is never shown by mistake.
 */
public class LabelResolver {

	private static final Logger LOG = Logger.getLogger(LabelResolver.class.getName());

	public static final String OVERRIDES_BUNDLE = "rblabels";
	private static final String OWN_BUNDLE = "resources.rbbundle";
	private static final String DEFAULT_WORD_PREFIX = "rb.word.";

	private final List<ResourceBundle> overrides;
	private final List<ResourceBundle> hostBundles;
	private final List<ResourceBundle> defaults;

	/**
	 * @param overrides   rblabels bundles (any language)
	 * @param hostBundles the application's own bundles (only their Arabic values are used)
	 * @param defaults    bundles with the library's built-in words ({@code rb.word.name}...)
	 */
	public LabelResolver(List<ResourceBundle> overrides, List<ResourceBundle> hostBundles, List<ResourceBundle> defaults) {
		this.overrides = nonNull(overrides);
		this.hostBundles = nonNull(hostBundles);
		this.defaults = nonNull(defaults);
	}

	/** Labels from the running application: its rblabels bundle and the bundles its faces-config files declare. */
	public static LabelResolver fromApplication() {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		if (cl == null)
			cl = LabelResolver.class.getClassLoader();
		Locale ar = new Locale("ar");
		List<ResourceBundle> overrides = new ArrayList<>();
		addBundle(overrides, OVERRIDES_BUNDLE, ar, cl);
		addBundle(overrides, OVERRIDES_BUNDLE, Locale.ENGLISH, cl);
		List<ResourceBundle> host = new ArrayList<>();
		for (String base : declaredBundles(cl))
			if (!OWN_BUNDLE.equals(base))
				addBundle(host, base, ar, cl);
		List<ResourceBundle> defaults = new ArrayList<>();
		addBundle(defaults, OWN_BUNDLE, ar, LabelResolver.class.getClassLoader());
		addBundle(defaults, OWN_BUNDLE, Locale.ENGLISH, LabelResolver.class.getClassLoader());
		return new LabelResolver(overrides, host, defaults);
	}

	// ------------------------------------------------------------ lookups

	public String entityAr(String entity) {
		return entityAr(entity, null);
	}

	/** @param type the entity class, whose {@code @EntityInfo(label)} is used when present */
	public String entityAr(String entity, Class<?> type) {
		String s = find(overrides, true, entity);
		if (s == null)
			s = annotationLabel(type, ENTITY_LABELS, true);
		return s != null ? s : find(hostBundles, true, snake(entity), entity.toLowerCase(Locale.ROOT), entity, decapitalize(entity));
	}

	public String entityEn(String entity) {
		return entityEn(entity, null);
	}

	public String entityEn(String entity, Class<?> type) {
		String s = find(overrides, false, entity);
		if (s == null)
			s = annotationLabel(type, ENTITY_LABELS, false);
		return s != null ? s : humanize(entity);
	}

	/** Arabic label of an attribute, or null when none is known. */
	public String attributeAr(String entity, String attribute) {
		return attributeAr(entity, attribute, null);
	}

	/** @param member the attribute's field or getter, whose label annotations are used when present */
	public String attributeAr(String entity, String attribute, AnnotatedElement member) {
		String s = find(overrides, true, entity + "." + attribute, attribute);
		if (s == null)
			s = annotationLabel(member, FIELD_LABELS, true);
		if (s == null)
			s = find(hostBundles, true, attribute, snake(attribute), attribute.toLowerCase(Locale.ROOT));
		return s != null ? s : find(defaults, true, DEFAULT_WORD_PREFIX + attribute);
	}

	public String attributeEn(String entity, String attribute) {
		return attributeEn(entity, attribute, null);
	}

	public String attributeEn(String entity, String attribute, AnnotatedElement member) {
		String s = find(overrides, false, entity + "." + attribute, attribute);
		if (s == null)
			s = annotationLabel(member, FIELD_LABELS, false);
		if (s == null)
			s = find(defaults, false, DEFAULT_WORD_PREFIX + attribute);
		return s != null ? s : humanize(attribute);
	}

	/** {annotation simple name, element holding the label}, in order of preference. */
	private static final String[][] FIELD_LABELS = { { "FieldInfo", "label" }, { "FieldViewConfiguration", "displayName" } };
	private static final String[][] ENTITY_LABELS = { { "EntityInfo", "label" } };

	/** The first label annotation value in the wanted language, or null. */
	static String annotationLabel(AnnotatedElement element, String[][] kinds, boolean arabic) {
		if (element == null)
			return null;
		Annotation[] annotations;
		try {
			annotations = element.getAnnotations();
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
		for (String[] kind : kinds)
			for (Annotation a : annotations) {
				if (!a.annotationType().getSimpleName().equals(kind[0]))
					continue;
				try {
					Object v = a.annotationType().getMethod(kind[1]).invoke(a);
					String label = v instanceof String ? ((String) v).trim() : "";
					if (!label.isEmpty() && hasArabic(label) == arabic)
						return label;
				} catch (ReflectiveOperationException | RuntimeException e) {
					// annotation without that element: ignore it
				}
			}
		return null;
	}

	public String enumAr(Class<?> enumType, String constant) {
		String s = find(overrides, true, enumType.getSimpleName() + "." + constant);
		return s != null ? s : find(hostBundles, true, constant, constant.toLowerCase(Locale.ROOT));
	}

	public String enumEn(Class<?> enumType, String constant) {
		String s = find(overrides, false, enumType.getSimpleName() + "." + constant);
		return s != null ? s : humanize(constant);
	}

	private static String find(List<ResourceBundle> bundles, boolean arabic, String... keys) {
		for (String key : keys) {
			if (key == null || key.isEmpty())
				continue;
			for (ResourceBundle b : bundles) {
				String v;
				try {
					v = b.containsKey(key) ? b.getString(key) : null;
				} catch (MissingResourceException | ClassCastException e) {
					v = null;
				}
				if (v != null && !v.trim().isEmpty() && hasArabic(v) == arabic)
					return v.trim();
			}
		}
		return null;
	}

	// ------------------------------------------------------------ helpers

	static boolean hasArabic(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= '؀' && c <= 'ۿ')
				return true;
		}
		return false;
	}

	/** purchasePrice -&gt; "Purchase price", SALES_INVOICE -&gt; "Sales invoice". */
	public static String humanize(String name) {
		if (name == null || name.isEmpty())
			return name;
		String s = name.replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
				.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
		return s.isEmpty() ? name : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** purchasePrice -&gt; purchase_price. */
	static String snake(String name) {
		return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
	}

	private static String decapitalize(String name) {
		return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
	}

	private static List<ResourceBundle> nonNull(List<ResourceBundle> list) {
		return list == null ? Collections.<ResourceBundle>emptyList() : new ArrayList<>(list);
	}

	private static void addBundle(List<ResourceBundle> out, String base, Locale locale, ClassLoader cl) {
		try {
			ResourceBundle b = ResourceBundle.getBundle(base, locale, cl);
			if (!out.contains(b))
				out.add(b);
		} catch (MissingResourceException e) {
			// optional
		}
	}

	/** Base names of the resource/message bundles declared in WEB-INF/faces-config.xml and in META-INF/faces-config.xml of the jars. */
	static Set<String> declaredBundles(ClassLoader cl) {
		Set<String> names = new LinkedHashSet<>();
		try {
			FacesContext fc = FacesContext.getCurrentInstance();
			ServletContext sc = ReportBuilderConfig.getServletContext();
			InputStream stream = fc != null ? fc.getExternalContext().getResourceAsStream("/WEB-INF/faces-config.xml")
					: sc != null ? sc.getResourceAsStream("/WEB-INF/faces-config.xml") : null;
			try (InputStream in = stream) {
				if (in != null)
					readBundleNames(in, names);
			}
		} catch (IOException | RuntimeException | LinkageError e) {
			LOG.log(Level.FINE, "Could not read WEB-INF/faces-config.xml", e);
		}
		try {
			Enumeration<URL> urls = cl.getResources("META-INF/faces-config.xml");
			while (urls.hasMoreElements()) {
				URL url = urls.nextElement();
				try (InputStream in = url.openStream()) {
					readBundleNames(in, names);
				} catch (IOException | RuntimeException e) {
					LOG.log(Level.FINE, "Could not read " + url, e);
				}
			}
		} catch (IOException e) {
			LOG.log(Level.FINE, "Could not list faces-config.xml files", e);
		}
		return names;
	}

	static void readBundleNames(InputStream in, Set<String> names) throws IOException {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(false);
			f.setValidating(false);
			f.setExpandEntityReferences(false);
			feature(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			feature(f, "http://xml.org/sax/features/external-general-entities", false);
			feature(f, "http://xml.org/sax/features/external-parameter-entities", false);
			Document d = f.newDocumentBuilder().parse(in);
			for (String tag : new String[] { "base-name", "message-bundle" }) {
				NodeList nl = d.getElementsByTagName(tag);
				for (int i = 0; i < nl.getLength(); i++) {
					String s = nl.item(i).getTextContent();
					if (s != null && !s.trim().isEmpty())
						names.add(s.trim());
				}
			}
		} catch (ParserConfigurationException | org.xml.sax.SAXException e) {
			throw new IOException(e);
		}
	}

	private static void feature(DocumentBuilderFactory f, String name, boolean value) {
		try {
			f.setFeature(name, value);
		} catch (ParserConfigurationException | RuntimeException e) {
			// parser without this feature
		}
	}
}
