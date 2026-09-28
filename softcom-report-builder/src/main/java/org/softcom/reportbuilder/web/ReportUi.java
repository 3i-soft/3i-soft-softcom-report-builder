package org.softcom.reportbuilder.web;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.EJBException;
import javax.faces.application.FacesMessage;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;

import org.softcom.reportbuilder.engine.ReportException;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/** JSF helpers: localized texts from the {@code rbbundle} bundle, messages and file download. */
public final class ReportUi {

	private static final Logger LOG = Logger.getLogger(ReportUi.class.getName());
	public static final String BUNDLE_VAR = "rbbundle";
	public static final String BUNDLE_BASE = "resources.rbbundle";

	private ReportUi() {
	}

	public static Locale locale() {
		FacesContext fc = FacesContext.getCurrentInstance();
		if (fc == null || fc.getViewRoot() == null)
			return new Locale("ar");
		return fc.getViewRoot().getLocale();
	}

	public static String text(String key, Object... args) {
		String pattern = key;
		FacesContext fc = FacesContext.getCurrentInstance();
		try {
			ResourceBundle bundle = fc == null ? ResourceBundle.getBundle(BUNDLE_BASE, locale())
					: fc.getApplication().getResourceBundle(fc, BUNDLE_VAR);
			if (bundle != null && bundle.containsKey(key))
				pattern = bundle.getString(key);
		} catch (MissingResourceException e) {
			// fall back to the key
		}
		return args == null || args.length == 0 ? pattern : MessageFormat.format(pattern, args);
	}

	public static void info(String key, Object... args) {
		add(FacesMessage.SEVERITY_INFO, text(key, args));
	}

	public static void error(String key, Object... args) {
		add(FacesMessage.SEVERITY_ERROR, text(key, args));
	}

	/** Shows a report problem; field paths in the arguments are replaced by field labels. */
	public static void error(Throwable t, ReportDataSource ds) {
		ReportException re = find(t);
		if (re == null) {
			LOG.log(Level.SEVERE, "Unexpected report builder error", t);
			error("rb.error.unexpected");
			return;
		}
		Object[] args = re.getArgs();
		Locale locale = locale();
		for (int i = 0; i < args.length; i++) {
			if (ds != null && args[i] instanceof String) {
				ReportField f = ds.getField((String) args[i]);
				if (f != null)
					args[i] = f.getLabel(locale);
			}
			if (args[i] instanceof Operator)
				args[i] = text("rb.op." + ((Operator) args[i]).name());
			else if (args[i] instanceof Aggregate)
				args[i] = text("rb.agg." + ((Aggregate) args[i]).name());
		}
		error(re.getMessageKey(), args);
	}

	private static ReportException find(Throwable t) {
		Throwable c = t;
		for (int depth = 0; c != null && depth < 20; depth++) {
			if (c instanceof ReportException)
				return (ReportException) c;
			Throwable next = c instanceof EJBException && ((EJBException) c).getCausedByException() != null
					? ((EJBException) c).getCausedByException()
					: c.getCause();
			if (next == c)
				break;
			c = next;
		}
		return null;
	}

	private static void add(FacesMessage.Severity severity, String text) {
		FacesContext fc = FacesContext.getCurrentInstance();
		if (fc != null)
			fc.addMessage(null, new FacesMessage(severity, text, null));
	}

	/** Receives the response stream of a download. */
	public interface DownloadWriter {
		void write(OutputStream out) throws IOException;
	}

	/**
	 * Sends an .xlsx file as the response of a non-ajax request. The file is
	 * built completely in a temporary file first; only then is the response
	 * touched, so a failed export (validation, timeout...) simply renders the
	 * page again with the error message (a response filter such as OmniFaces'
	 * gzip filter cannot be reset once its output stream was obtained).
	 */
	public static void downloadXlsx(String baseName, DownloadWriter writer, ReportDataSource ds) {
		Path tmp = null;
		try {
			tmp = Files.createTempFile("rb-export-", ".xlsx");
			try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
				writer.write(out);
			}
		} catch (IOException | RuntimeException e) {
			delete(tmp);
			error(e, ds);
			return;
		}
		FacesContext fc = FacesContext.getCurrentInstance();
		ExternalContext ec = fc.getExternalContext();
		try {
			ec.responseReset();
			ec.setResponseContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
			ec.setResponseHeader("Content-Disposition",
					"attachment; filename=\"report.xlsx\"; filename*=UTF-8''" + encode(baseName) + ".xlsx");
			long size = Files.size(tmp);
			if (size < Integer.MAX_VALUE)
				ec.setResponseContentLength((int) size);
			Files.copy(tmp, ec.getResponseOutputStream());
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Sending the report export failed (client disconnected?)", e);
		} finally {
			fc.responseComplete();
			delete(tmp);
		}
	}

	private static void delete(Path tmp) {
		if (tmp == null)
			return;
		try {
			Files.deleteIfExists(tmp);
		} catch (IOException e) {
			LOG.log(Level.FINE, "Could not delete " + tmp, e);
		}
	}

	private static String encode(String name) {
		String n = name == null || name.trim().isEmpty() ? "report" : name.trim();
		try {
			return URLEncoder.encode(n, "UTF-8").replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			return "report";
		}
	}
}
