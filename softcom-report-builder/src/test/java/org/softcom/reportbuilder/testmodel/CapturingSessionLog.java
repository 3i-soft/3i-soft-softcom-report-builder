package org.softcom.reportbuilder.testmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.persistence.logging.AbstractSessionLog;
import org.eclipse.persistence.logging.SessionLog;
import org.eclipse.persistence.logging.SessionLogEntry;

/** Records the SQL EclipseLink sends, so tests can assert user values never appear inside the SQL text. */
public class CapturingSessionLog extends AbstractSessionLog {

	public static final List<String> SQL = Collections.synchronizedList(new ArrayList<String>());

	@Override
	public void log(SessionLogEntry entry) {
		if (SessionLog.SQL.equals(entry.getNameSpace()) && entry.getMessage() != null)
			SQL.add(entry.getMessage());
	}
}
