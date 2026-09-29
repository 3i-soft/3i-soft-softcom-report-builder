package org.softcom.reportbuilder.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.faces.context.FacesContext;
import javax.faces.model.SelectItem;
import javax.faces.view.ViewScoped;
import javax.inject.Named;

import org.softcom.reportbuilder.model.ReportDefinition;
import org.softcom.reportbuilder.service.ReportDefinitionFacade;
import org.softcom.reportbuilder.spec.FilterNode;
import org.softcom.reportbuilder.spec.ReportSpec;
import org.softcom.reportbuilder.spi.ReportDataSource;
import org.softcom.reportbuilder.spi.ReportField;

/**
 * Backing bean of {@code /report-builder/viewer-snippet.xhtml}: choose a saved
 * report, fill its "ask when running" conditions, run page by page, export.
 */
@Named("rbViewer")
@ViewScoped
public class ReportViewerBean extends AbstractReportBean {

	private static final long serialVersionUID = 1L;

	@EJB
	private ReportDefinitionFacade definitions;

	private List<SelectItem> reportItems = new ArrayList<>();
	private Long selectedId;
	private String selectedName;
	private String selectedDescription;
	private boolean selectedEditable;
	private String dataSourceKey;
	private List<RuleEditor> parameters = new ArrayList<>();

	@PostConstruct
	public void init() {
		if (!security.canRun()) {
			ReportUi.error("rb.error.accessDenied");
			return;
		}
		try {
			// reports without a folder first, then one group per folder
			java.util.Map<String, List<SelectItem>> folders = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			for (ReportDefinition d : definitions.findVisible()) {
				ReportDataSource ds = catalog.get(d.getDataSourceKey());
				String group = ds == null ? "" : " - " + ds.getLabel(getLocale());
				SelectItem item = new SelectItem(d.getId(), d.getName() + group);
				String folder = d.getFolder() == null ? "" : d.getFolder().trim();
				if (folder.isEmpty()) {
					reportItems.add(item);
				} else {
					List<SelectItem> g = folders.get(folder);
					if (g == null)
						folders.put(folder, g = new ArrayList<>());
					g.add(item);
				}
			}
			for (java.util.Map.Entry<String, List<SelectItem>> f : folders.entrySet()) {
				javax.faces.model.SelectItemGroup g = new javax.faces.model.SelectItemGroup(f.getKey());
				g.setSelectItems(f.getValue().toArray(new SelectItem[f.getValue().size()]));
				reportItems.add(g);
			}
		} catch (RuntimeException e) {
			ReportUi.error(e, null);
		}
		String id = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().get("id");
		if (id != null && id.matches("\\d{1,18}")) {
			selectedId = Long.valueOf(id);
			onSelect();
		}
	}

	public void onSelect() {
		result = null;
		first = 0;
		parameters = new ArrayList<>();
		dataSourceKey = null;
		selectedName = null;
		selectedDescription = null;
		selectedEditable = false;
		if (selectedId == null)
			return;
		try {
			ReportDefinition d = definitions.findVisible(selectedId);
			ReportSpec spec = definitions.spec(d);
			selectedName = d.getName();
			selectedDescription = d.getDescription();
			selectedEditable = definitions.canEdit(d);
			dataSourceKey = spec.getDataSource();
			for (FilterNode r : spec.getParameters()) {
				ReportField f = field(r.getField());
				parameters.add(RuleEditor.from(r, f == null ? null : f.getType(), f != null && (f.hasChoices() || f.hasLookup())));
			}
		} catch (RuntimeException e) {
			selectedId = null;
			ReportUi.error(e, getDataSource());
		}
	}

	public void run() {
		first = 0;
		execute();
	}

	@Override
	protected void execute() {
		if (selectedId == null)
			return;
		try {
			result = service.run(selectedId, parameterValues(), first, pageSize);
		} catch (RuntimeException e) {
			result = null;
			ReportUi.error(e, getDataSource());
		}
	}

	private Map<String, List<String>> parameterValues() {
		Map<String, List<String>> map = new HashMap<>();
		for (RuleEditor r : parameters)
			map.put(r.getId(), r.parameterValues());
		return map;
	}

	/** Non-ajax: downloads the whole report (up to the data source's export limit) as Excel. */
	public void exportExcel() {
		if (selectedId == null)
			return;
		final Long id = selectedId;
		final Map<String, List<String>> params = parameterValues();
		final String title = selectedName;
		ReportUi.downloadXlsx(title, out -> service.exportExcel(id, null, params, title, getLocale(), out),
				getDataSource());
	}

	/** Prompt for a parameter: its label, or the field label. */
	public String parameterLabel(RuleEditor r) {
		if (r.getLabel() != null && !r.getLabel().trim().isEmpty())
			return r.getLabel();
		return fieldLabel(r.getField()) + " (" + ReportUi.text("rb.op." + r.getOperator()) + ")";
	}

	@Override
	public ReportDataSource getDataSource() {
		return catalog.get(dataSourceKey);
	}

	public List<SelectItem> getReportItems() {
		return reportItems;
	}

	public Long getSelectedId() {
		return selectedId;
	}

	public void setSelectedId(Long selectedId) {
		this.selectedId = selectedId;
	}

	public String getSelectedName() {
		return selectedName;
	}

	public String getSelectedDescription() {
		return selectedDescription;
	}

	public boolean isSelectedEditable() {
		return selectedEditable;
	}

	public List<RuleEditor> getParameters() {
		return parameters;
	}
}
