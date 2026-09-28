package org.softcom.reportbuilder.engine;

import javax.ejb.ApplicationException;

/**
 * A problem with a report definition or run that should be shown to the user.
 * {@link #getMessageKey()} is a key of the {@code rbbundle} resource bundle and
 * {@link #getArgs()} its {0}, {1}... arguments. Declared an application
 * exception so EJBs pass it to the caller unwrapped (not as EJBException).
 */
@ApplicationException(rollback = true)
public class ReportException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String messageKey;
	private final Object[] args;

	public ReportException(String messageKey, Object... args) {
		super(buildMessage(messageKey, args));
		this.messageKey = messageKey;
		this.args = args;
	}

	public ReportException(Throwable cause, String messageKey, Object... args) {
		super(buildMessage(messageKey, args), cause);
		this.messageKey = messageKey;
		this.args = args;
	}

	private static String buildMessage(String key, Object[] args) {
		StringBuilder sb = new StringBuilder(key);
		if (args != null && args.length > 0) {
			sb.append(' ');
			for (int i = 0; i < args.length; i++)
				sb.append(i == 0 ? "[" : ", ").append(args[i]);
			sb.append(']');
		}
		return sb.toString();
	}

	public String getMessageKey() {
		return messageKey;
	}

	public Object[] getArgs() {
		return args == null ? new Object[0] : args.clone();
	}
}
