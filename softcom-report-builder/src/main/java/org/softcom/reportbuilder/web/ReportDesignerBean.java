package org.softcom.reportbuilder.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;
import javax.faces.model.SelectItemGroup;
import javax.faces.view.ViewScoped;
import javax.inject.Named;

import org.softcom.reportbuilder.engine.ReportException;
import org.softcom.reportbuilder.engine.SpeedRules;
import org.softcom.reportbuilder.engine.SpecValidator;
import org.softcom.reportbuilder.model.ReportDefinition;
import org.softcom.reportbuilder.service.ReportDefinitionFacade;
import org.softcom.reportbuilder.spec.Aggregate;
import org.softcom.reportbuilder.spec.ColumnSpec;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spec.SortSpec;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Backing bean of {@code /report-builder/designer-snippet.xhtml}: pick a data
 * source, columns (with totals), conditions in groups, sorting, then preview
 * and save. Opened with {@code ?id=} it edits (or copies) a saved report.
 */
@Named("rbDesigner")
@ViewScoped
public class ReportDesignerBean extends AbstractReportBean {

	private static final long serialVersionUID = 1L;

	private static final int PREVIEW_SIZE = 50;

	@EJB
	private ReportDefinitionFacade definitions;

	private Long definitionId;
	private Integer definitionVersion;
	private boolean editable = true;
	private String name;
	private String description;
	private boolean shared;
	private String sharedRoles;
	private String dataSourceKey;
	private List<ColumnSpec> columns = new ArrayList<>();
	private FilterNode.Logic topLogic = FilterNode.Logic.AND;
	private List<GroupEditor> groups = new ArrayList<>();
	private List<SortSpec> sorts = new ArrayList<>();
	private int ruleSeq;

	@PostConstruct
	public void init() {
		pageSize = PREVIEW_SIZE;
		if (!security.canDesign()) {
			ReportUi.error("rb.error.accessDenied");
			return;
		}
		String id = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().get("id");
		if (id != null && id.matches("\\d{1,18}")) {
			try {
				load(Long.valueOf(id));
			} catch (RuntimeException e) {
				ReportUi.error(e, getDataSource());
			}
		}
	}

	/** Loads a saved report; nothing is changed unless the whole report can be shown. */
	private void load(Long id) {
		ReportDefinition d = definitions.findVisible(id);
		ReportSpec spec = definitions.spec(d);
		ReportDataSource ds = catalog.get(spec.getDataSource());
		// conditions first: they are the part that can be too complex for the designer
		List<GroupEditor> loadedGroups = new ArrayList<>();
		FilterNode.Logic loadedLogic = FilterNode.Logic.AND;
		int maxSeq = 0;
		FilterNode root = spec.getFilter();
		if (root != null) {
			List<FilterNode> children = root.isGroup() ? root.getChildren() : Collections.singletonList(root);
			if (root.isGroup())
				loadedLogic = root.getLogic();
			for (FilterNode child : children) {
				GroupEditor g = new GroupEditor();
				List<FilterNode> rules = child.isGroup() ? child.getChildren() : Collections.singletonList(child);
				if (child.isGroup())
					g.setLogic(child.getLogic());
				for (FilterNode r : rules) {
					if (r.isGroup())
						throw new ReportException("rb.error.tooComplexForDesigner");
					ReportField f = ds == null || r.getField() == null ? null : ds.getField(r.getField());
					g.getRules().add(RuleEditor.from(r, f == null ? null : f.getType(), f != null && f.hasChoices()));
					maxSeq = Math.max(maxSeq, numericSuffix(r.getId()));
				}
				loadedGroups.add(g);
			}
		}
		definitionId = d.getId();
		definitionVersion = d.getVersion();
		editable = definitions.canEdit(d);
		name = d.getName();
		description = d.getDescription();
		// a copy of someone else's report starts private
		shared = editable && d.isShared();
		sharedRoles = editable ? d.getSharedRoles() : null;
		dataSourceKey = spec.getDataSource();
		columns = new ArrayList<>();
		for (ColumnSpec c : spec.getColumns())
			columns.add(c.copy());
		sorts = new ArrayList<>();
		for (SortSpec s : spec.getSort())
			sorts.add(s.copy());
		groups = loadedGroups;
		topLogic = loadedLogic;
		ruleSeq = maxSeq;
		if (!editable)
			ReportUi.info("rb.info.readOnlyCopy");
	}

	private static int numericSuffix(String id) {
		if (id == null)
			return 0;
		String digits = id.replaceAll("\\D", "");
		try {
			return digits.isEmpty() ? 0 : Integer.parseInt(digits.length() > 9 ? digits.substring(0, 9) : digits);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public ReportSpec buildSpec() {
		ReportSpec spec = new ReportSpec();
		spec.setDataSource(dataSourceKey);
		// designer row index -> spec column index (rows without a field are skipped)
		java.util.Map<Integer, Integer> columnIndex = new java.util.HashMap<>();
		for (int i = 0; i < columns.size(); i++) {
			ColumnSpec c = columns.get(i);
			if (c.getField() != null && !c.getField().isEmpty()) {
				columnIndex.put(i, spec.getColumns().size());
				spec.getColumns().add(c.copy());
			}
		}
		FilterNode root = FilterNode.group(topLogic);
		for (GroupEditor g : groups) {
			FilterNode gn = FilterNode.group(g.getLogic());
			for (RuleEditor r : g.getRules())
				if (r.getField() != null && r.getOperator() != null)
					gn.getChildren().add(r.toNode());
			if (gn.getChildren().size() == 1)
				root.getChildren().add(gn.getChildren().get(0));
			else if (!gn.getChildren().isEmpty())
				root.getChildren().add(gn);
		}
		spec.setFilter(root.getChildren().isEmpty() ? null : root);
		for (SortSpec s : sorts) {
			Integer target = columnIndex.get(s.getColumn());
			if (target != null)
				spec.getSort().add(new SortSpec(target, s.isDescending()));
		}
		return spec;
	}

	// ------------------------------------------------------------- actions

	public void onDataSourceChange() {
		columns = new ArrayList<>();
		groups = new ArrayList<>();
		sorts = new ArrayList<>();
		result = null;
		ReportDataSource ds = getDataSource();
		// start with the mandatory condition so the report can run: the data source's own required field, or on
		// a large table a period on an indexed date field
		String start = ds == null ? null
				: ds.getRequiredFilterField() != null ? ds.getRequiredFilterField() : SpeedRules.suggestedDateField(ds, getSpeed());
		if (start != null) {
			GroupEditor g = new GroupEditor();
			RuleEditor r = newRule(start);
			r.setAskAtRun(true);
			r.setLabel(fieldLabel(start));
			g.getRules().add(r);
			groups.add(g);
		}
	}

	public void addColumn() {
		columns.add(new ColumnSpec(null, Aggregate.NONE));
	}

	public void addRowCountColumn() {
		columns.add(new ColumnSpec(ReportDataSource.ROW_COUNT_FIELD, Aggregate.COUNT));
	}

	public void removeColumn(int index) {
		if (index >= 0 && index < columns.size()) {
			columns.remove(index);
			List<SortSpec> kept = new ArrayList<>();
			for (SortSpec s : sorts) {
				if (s.getColumn() == index)
					continue;
				if (s.getColumn() > index)
					s.setColumn(s.getColumn() - 1);
				kept.add(s);
			}
			sorts = kept;
		}
	}

	public void moveColumn(int index, int delta) {
		int target = index + delta;
		if (index < 0 || target < 0 || index >= columns.size() || target >= columns.size())
			return;
		Collections.swap(columns, index, target);
		for (SortSpec s : sorts) {
			if (s.getColumn() == index)
				s.setColumn(target);
			else if (s.getColumn() == target)
				s.setColumn(index);
		}
	}

	public void onColumnFieldChange(ColumnSpec column) {
		ReportField f = field(column.getField());
		if (f == null || !column.getAggregate().supports(f.getType(), f.isAggregatable()))
			column.setAggregate(Aggregate.NONE);
	}

	public List<SelectItem> aggregateItems(ColumnSpec column) {
		List<SelectItem> items = new ArrayList<>();
		if (ReportDataSource.ROW_COUNT_FIELD.equals(column.getField())) {
			items.add(new SelectItem(Aggregate.COUNT, ReportUi.text("rb.agg.COUNT")));
			return items;
		}
		ReportField f = field(column.getField());
		for (Aggregate a : Aggregate.values())
			if (f == null ? a == Aggregate.NONE : a.supports(f.getType(), f.isAggregatable()))
				items.add(new SelectItem(a, ReportUi.text("rb.agg." + a.name())));
		return items;
	}

	public void addGroup() {
		GroupEditor g = new GroupEditor();
		g.getRules().add(newRule(null));
		groups.add(g);
	}

	public void removeGroup(GroupEditor g) {
		groups.remove(g);
	}

	public void addRule(GroupEditor g) {
		g.getRules().add(newRule(null));
	}

	public void removeRule(GroupEditor g, RuleEditor r) {
		g.getRules().remove(r);
		if (g.getRules().isEmpty())
			groups.remove(g);
	}

	private RuleEditor newRule(String fieldPath) {
		RuleEditor r = new RuleEditor("r" + (++ruleSeq));
		if (fieldPath != null) {
			r.setField(fieldPath);
			ReportField f = field(fieldPath);
			r.resetFor(f == null ? null : f.getType(), defaultOperator(f));
		}
		return r;
	}

	public void onRuleOperatorChange(RuleEditor r) {
		r.onOperatorChanged();
	}

	public void onRuleFieldChange(RuleEditor r) {
		ReportField f = field(r.getField());
		r.resetFor(f == null ? null : f.getType(), defaultOperator(f));
	}

	public void addSort() {
		if (!columns.isEmpty())
			sorts.add(new SortSpec(0, false));
	}

	public void removeSort(int index) {
		if (index >= 0 && index < sorts.size())
			sorts.remove(index);
	}

	public List<SelectItem> getSortColumnItems() {
		List<SelectItem> items = new ArrayList<>();
		for (int i = 0; i < columns.size(); i++) {
			ColumnSpec c = columns.get(i);
			String label = c.getLabel() != null && !c.getLabel().trim().isEmpty() ? c.getLabel() : fieldLabel(c.getField());
			items.add(new SelectItem(i, (i + 1) + ". " + (label == null ? "" : label)));
		}
		return items;
	}

	public void preview() {
		first = 0;
		execute();
	}

	@Override
	protected void execute() {
		try {
			result = service.preview(buildSpec(), null, first, pageSize);
		} catch (RuntimeException e) {
			result = null;
			ReportUi.error(e, getDataSource());
		}
	}

	public void save() {
		save(false);
	}

	public void saveAsNew() {
		save(true);
	}

	private void save(boolean asNew) {
		try {
			ReportDefinition input = new ReportDefinition();
			boolean update = !asNew && editable && definitionId != null;
			input.setId(update ? definitionId : null);
			input.setVersion(update ? definitionVersion : null);
			input.setName(name);
			input.setDescription(description);
			input.setShared(shared);
			input.setSharedRoles(sharedRoles);
			ReportDefinition saved = definitions.save(input, buildSpec());
			definitionId = saved.getId();
			definitionVersion = saved.getVersion();
			editable = true;
			ReportUi.info("rb.info.saved", saved.getName());
		} catch (RuntimeException e) {
			ReportUi.error(e, getDataSource());
		}
	}

	public void delete() {
		if (definitionId == null)
			return;
		try {
			definitions.delete(definitionId);
			ReportUi.info("rb.info.deleted", name);
			newReport();
		} catch (RuntimeException e) {
			ReportUi.error(e, getDataSource());
		}
	}

	public void newReport() {
		definitionId = null;
		definitionVersion = null;
		editable = true;
		name = null;
		description = null;
		shared = false;
		sharedRoles = null;
		dataSourceKey = null;
		columns = new ArrayList<>();
		groups = new ArrayList<>();
		sorts = new ArrayList<>();
		topLogic = FilterNode.Logic.AND;
		result = null;
		ruleSeq = 0;
	}

	/** Non-ajax: downloads the current (possibly unsaved) design as Excel. */
	public void exportExcel() {
		final ReportSpec spec = buildSpec();
		final String title = name == null || name.trim().isEmpty() ? "report" : name;
		try {
			SpecValidator.validate(spec, catalog.get(dataSourceKey), true);
		} catch (RuntimeException e) {
			ReportUi.error(e, getDataSource());
			return;
		}
		ReportUi.downloadXlsx(title, out -> service.exportExcel(null, spec, null, title, getLocale(), out),
				getDataSource());
	}

	// ------------------------------------------------------------ accessors

	@Override
	public ReportDataSource getDataSource() {
		return catalog.get(dataSourceKey);
	}

	/** Hand-written data sources first, then the application's tables (automatic ones), each group by name. */
	public List<SelectItem> getDataSourceItems() {
		List<SelectItem> prepared = new ArrayList<>();
		List<SelectItem> tables = new ArrayList<>();
		for (ReportDataSource ds : catalog.visibleTo(security))
			(ds.isAutomatic() ? tables : prepared).add(new SelectItem(ds.getKey(), ds.getLabel(getLocale())));
		Comparator<SelectItem> byLabel = Comparator.comparing(SelectItem::getLabel, String.CASE_INSENSITIVE_ORDER);
		tables.sort(byLabel);
		if (tables.isEmpty())
			return prepared;
		List<SelectItem> items = new ArrayList<>();
		if (!prepared.isEmpty())
			items.add(group(ReportUi.text("rb.group.prepared"), prepared));
		items.add(group(ReportUi.text("rb.group.tables"), tables));
		return items;
	}

	private static SelectItemGroup group(String label, List<SelectItem> items) {
		SelectItemGroup g = new SelectItemGroup(label);
		g.setSelectItems(items.toArray(new SelectItem[items.size()]));
		return g;
	}

	public List<SelectItem> getColumnFieldItems() {
		return fieldItems(false);
	}

	public List<SelectItem> getFilterFieldItems() {
		return fieldItems(true);
	}

	public List<SelectItem> getLogicItems() {
		List<SelectItem> items = new ArrayList<>();
		items.add(new SelectItem(FilterNode.Logic.AND, ReportUi.text("rb.logic.AND")));
		items.add(new SelectItem(FilterNode.Logic.OR, ReportUi.text("rb.logic.OR")));
		return items;
	}

	public boolean isGrouped() {
		for (ColumnSpec c : columns)
			if (c.isAggregated())
				return true;
		return false;
	}

	public Long getDefinitionId() {
		return definitionId;
	}

	public boolean isEditable() {
		return editable;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isShared() {
		return shared;
	}

	public void setShared(boolean shared) {
		this.shared = shared;
	}

	public String getSharedRoles() {
		return sharedRoles;
	}

	public void setSharedRoles(String sharedRoles) {
		this.sharedRoles = sharedRoles;
	}

	public String getDataSourceKey() {
		return dataSourceKey;
	}

	public void setDataSourceKey(String dataSourceKey) {
		this.dataSourceKey = dataSourceKey;
	}

	public List<ColumnSpec> getColumns() {
		return columns;
	}

	public FilterNode.Logic getTopLogic() {
		return topLogic;
	}

	public void setTopLogic(FilterNode.Logic topLogic) {
		this.topLogic = topLogic == null ? FilterNode.Logic.AND : topLogic;
	}

	public List<GroupEditor> getGroups() {
		return groups;
	}

	public List<SortSpec> getSorts() {
		return sorts;
	}
}
