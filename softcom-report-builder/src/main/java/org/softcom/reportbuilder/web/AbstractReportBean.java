package org.softcom.reportbuilder.web;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.ejb.EJB;
import javax.faces.model.SelectItem;
import javax.faces.model.SelectItemGroup;
import javax.inject.Inject;

import org.softcom.reportbuilder.engine.ReportExecutor;
import org.softcom.reportbuilder.engine.ReportResult;
import org.softcom.reportbuilder.engine.ResultColumn;
import org.softcom.reportbuilder.engine.SpeedInfo;
import org.softcom.reportbuilder.engine.SpeedRules;
import org.softcom.reportbuilder.export.ReportFormatter;
import org.softcom.reportbuilder.service.ReportCatalog;
import org.softcom.reportbuilder.service.ReportSecurity;
import org.softcom.reportbuilder.service.ReportService;
import org.softcom.reportbuilder.spec.Operator;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/** What the designer and the viewer share: field/operator lists, value editors, result paging and formatting. */
public abstract class AbstractReportBean implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final int[] PAGE_SIZES = { 25, 50, 100, 200, ReportExecutor.MAX_PAGE_SIZE };

	@Inject
	protected ReportCatalog catalog;

	@Inject
	protected ReportSecurity security;

	@EJB
	protected ReportService service;

	protected ReportResult result;
	protected int first;
	protected int pageSize = 50;
	/** Table statistics of the current data source, asked once per data source and view. */
	private SpeedInfo speed;
	private String speedKey;

	/** The data source currently being edited or run, may be null. */
	public abstract ReportDataSource getDataSource();

	/** Runs the current page ({@link #first}, {@link #pageSize}). */
	protected abstract void execute();

	// --------------------------------------------------------------- paging

	public void next() {
		if (result != null && result.isHasMore()) {
			first += pageSize;
			execute();
		}
	}

	public void previous() {
		if (first > 0) {
			first = Math.max(0, first - pageSize);
			execute();
		}
	}

	public void onPageSizeChange() {
		first = 0;
		if (result != null)
			execute();
	}

	public boolean isHasPrevious() {
		return result != null && first > 0;
	}

	public String getPageInfo() {
		if (result == null || result.getRows().isEmpty())
			return "";
		return ReportUi.text("rb.pageInfo", first + 1, first + result.getRows().size(), result.getDurationMs());
	}

	// ----------------------------------------------------------- formatting

	public String format(Object row, ResultColumn column) {
		if (!(row instanceof Object[]))
			return "";
		Object[] values = (Object[]) row;
		return column.getIndex() < values.length
				? ReportFormatter.format(values[column.getIndex()], column, getDataSource(), getLocale())
				: "";
	}

	public String columnLabel(ResultColumn column) {
		return column.getLabel(getLocale());
	}

	/** CSS class aligning numbers to the end. */
	public String columnStyle(ResultColumn column) {
		return column.getType().isNumeric() ? "rb-num" : "";
	}

	// --------------------------------------------------------- field lists

	public Locale getLocale() {
		return ReportUi.locale();
	}

	public ReportField field(String path) {
		ReportDataSource ds = getDataSource();
		return ds == null || path == null ? null : ds.getField(path);
	}

	public String fieldLabel(String path) {
		ReportField f = field(path);
		if (ReportDataSource.ROW_COUNT_FIELD.equals(path))
			return ReportUi.text("rb.rowCount");
		return f == null ? path : f.getLabel(getLocale());
	}

	public String fieldHint(String path) {
		ReportField f = field(path);
		return f == null ? null : f.getHint(getLocale());
	}

	/**
	 * Fields as menu items; fast fields (indexed column) are marked with the
	 * {@link #FAST_MARK}. Fields reached through a labelled relation are grouped
	 * under it (automatic data sources can have a hundred fields).
	 */
	protected List<SelectItem> fieldItems(boolean filterableOnly) {
		List<SelectItem> items = new ArrayList<>();
		ReportDataSource ds = getDataSource();
		if (ds == null)
			return items;
		SpeedInfo sp = getSpeed();
		Map<String, List<SelectItem>> groups = new LinkedHashMap<>();
		for (ReportField f : ds.getFields(filterableOnly)) {
			String label = f.getLabel(getLocale());
			SelectItem item = new SelectItem(f.getPath(), sp.isFast(f.getPath()) ? label + " " + FAST_MARK : label);
			String join = f.getJoinPath();
			String groupLabel = join == null || !ds.isAutomatic() ? null : ds.getJoinLabel(join, getLocale());
			if (groupLabel == null) {
				items.add(item);
			} else {
				List<SelectItem> g = groups.get(groupLabel);
				if (g == null)
					groups.put(groupLabel, g = new ArrayList<>());
				g.add(item);
			}
		}
		for (Map.Entry<String, List<SelectItem>> g : groups.entrySet()) {
			SelectItemGroup group = new SelectItemGroup(g.getKey());
			group.setSelectItems(g.getValue().toArray(new SelectItem[g.getValue().size()]));
			items.add(group);
		}
		return items;
	}

	/** Marks fields a condition can use an index on. */
	public static final String FAST_MARK = "\u26A1";

	/** Size and fast fields of the current data source (asked once per data source). */
	public SpeedInfo getSpeed() {
		ReportDataSource ds = getDataSource();
		if (ds == null)
			return SpeedInfo.UNKNOWN;
		if (speed == null || !ds.getKey().equals(speedKey)) {
			SpeedInfo s;
			try {
				s = service.speed(ds.getKey());
			} catch (RuntimeException e) {
				s = SpeedInfo.UNKNOWN;
			}
			speed = s == null ? SpeedInfo.UNKNOWN : s;
			speedKey = ds.getKey();
		}
		return speed;
	}

	/** Explains that the table is large and which fields keep a report fast, or null. */
	public String getSpeedNote() {
		ReportDataSource ds = getDataSource();
		SpeedInfo sp = getSpeed();
		if (!SpeedRules.applies(ds, sp))
			return null;
		List<String> fast = SpeedRules.usefulFastFields(ds, sp);
		if (fast.isEmpty())
			return ReportUi.text("rb.speed.largeNoIndex", sp.getEstimatedRows(), ds.getQueryTimeoutSeconds());
		List<String> labels = new ArrayList<>();
		for (String path : fast)
			if (labels.size() < 6)
				labels.add(fieldLabel(path));
		return ReportUi.text("rb.speed.large", sp.getEstimatedRows(), String.join(ReportUi.listSeparator(getLocale()), labels),
				ReportExecutor.maxDateRangeDays());
	}

	public List<SelectItem> operatorItems(RuleEditor rule) {
		List<SelectItem> items = new ArrayList<>();
		ReportField f = field(rule.getField());
		if (f == null)
			return items;
		for (Operator op : Operator.values())
			if (op.supports(f.getType()))
				items.add(new SelectItem(op, ReportUi.text("rb.op." + op.name())));
		return items;
	}

	public List<SelectItem> choiceItems(RuleEditor rule) {
		List<SelectItem> items = new ArrayList<>();
		ReportField f = field(rule.getField());
		if (f == null)
			return items;
		for (Map.Entry<String, String> e : f.getChoices().entrySet())
			items.add(new SelectItem(e.getKey(), e.getValue()));
		return items;
	}

	/**
	 * Which input the value editor shows: none, text, number, date, datetime,
	 * choice (one value from a list), choiceList (several), list (free text list).
	 */
	public String valueKind(RuleEditor rule) {
		ReportField f = field(rule.getField());
		Operator op = rule.getOperator();
		if (f == null || op == null || op.getArity() == 0)
			return "none";
		boolean choices = f.hasChoices() && (op == Operator.EQ || op == Operator.NE || op.getArity() < 0);
		if (choices)
			return op.getArity() < 0 ? "choiceList" : "choice";
		if (op.getArity() < 0)
			return "list";
		if (f.getType() == FieldType.DATE)
			return "date";
		// reports think in days: a DATETIME field gets a day picker (whole-day comparison in the engine),
		// unless a saved value already carries a time, which must stay editable
		if (f.getType() == FieldType.DATETIME)
			return hasTime(rule.getValue()) || hasTime(rule.getValue2()) ? "datetime" : "date";
		return f.getType().isNumeric() ? "number" : "text";
	}

	private static boolean hasTime(String value) {
		return value != null && value.trim().length() > 10;
	}

	/**
	 * Called from f:event preRenderView so the view-scoped bean (and its
	 * start-up messages) exists before p:messages is rendered.
	 */
	public void touch() {
		// creating the bean is enough
	}

	/** Explains the mandatory condition of the data source, or null. */
	public String getRequiredFilterNote() {
		ReportDataSource ds = getDataSource();
		if (ds == null || ds.getRequiredFilterField() == null)
			return null;
		String label = fieldLabel(ds.getRequiredFilterField());
		return ds.getMaxDateRangeDays() > 0 ? ReportUi.text("rb.requiredRangeNote", label, ds.getMaxDateRangeDays())
				: ReportUi.text("rb.requiredNote", label);
	}

	public boolean hasSecondValue(RuleEditor rule) {
		return rule.getOperator() != null && rule.getOperator().getArity() == 2;
	}

	protected Operator defaultOperator(ReportField f) {
		if (f == null)
			return null;
		if (f.getType() == FieldType.BOOLEAN)
			return Operator.IS_TRUE;
		if (f.getType().isTemporal())
			return Operator.BETWEEN;
		if (f.getType() == FieldType.STRING && !f.hasChoices())
			return Operator.CONTAINS;
		return Operator.EQ;
	}

	public List<SelectItem> getPageSizeItems() {
		List<SelectItem> items = new ArrayList<>();
		for (int s : PAGE_SIZES)
			items.add(new SelectItem(s, String.valueOf(s)));
		return items;
	}

	public ReportResult getResult() {
		return result;
	}

	public int getPageSize() {
		return pageSize;
	}

	public void setPageSize(int pageSize) {
		this.pageSize = Math.max(1, Math.min(pageSize, ReportExecutor.MAX_PAGE_SIZE));
	}

	public boolean isCanDesign() {
		return security.canDesign();
	}

	public boolean isCanRun() {
		return security.canRun();
	}
}
