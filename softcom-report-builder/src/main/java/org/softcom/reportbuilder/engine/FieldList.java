package org.softcom.reportbuilder.engine;

import java.util.ArrayList;
import java.util.Collection;

/** A {@link ReportException} argument listing field paths; the page shows it as their labels, comma separated. */
public class FieldList extends ArrayList<String> {

	private static final long serialVersionUID = 1L;

	public FieldList(Collection<String> paths) {
		super(paths);
	}
}
