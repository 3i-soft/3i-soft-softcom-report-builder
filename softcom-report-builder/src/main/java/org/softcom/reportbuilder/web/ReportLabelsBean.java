package org.softcom.reportbuilder.web;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.faces.model.SelectItem;
import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.softcom.persistence.IPersistenceHelper;
import org.softcom.reportbuilder.auto.EntityDiscovery;
import org.softcom.reportbuilder.auto.LabelCatalog;
import org.softcom.reportbuilder.auto.LabelResolver;
import org.softcom.reportbuilder.model.ReportLabel;
import org.softcom.reportbuilder.service.ReportLabelService;
import org.softcom.reportbuilder.service.ReportSecurity;

/**
 * Backing bean of {@code /report-builder/labels-snippet.xhtml}: the names of
 * the tables, fields and values the reports show, the ones without an Arabic
 * name first. Saved names are used at once (no restart).
 */
@Named("rbLabels")
@ViewScoped
public class ReportLabelsBean implements Serializable {

	private static final long serialVersionUID = 1L;
	private static final int PAGE_SIZE = 50;

	@Inject
	private ReportSecurity security;

	@EJB
	private ReportLabelService labelService;

	@EJB
	private IPersistenceHelper persistenceHelper;

	private List<LabelRow> rows = new ArrayList<>();
	private List<LabelRow> filtered = new ArrayList<>();
	private String search;
	private String kind;
	private boolean missingOnly = true;
	private boolean hiddenOnly;
	private int first;
	private boolean allowed;

	/** One name on the page. */
	public static final class LabelRow implements Serializable {
		private static final long serialVersionUID = 1L;
		private final LabelCatalog.Entry entry;
		private final String savedAr;
		private final String savedEn;
		private final boolean savedHidden;
		private String ar;
		private String en;
		private boolean hidden;

		LabelRow(LabelCatalog.Entry entry, ReportLabel saved) {
			this.entry = entry;
			this.savedAr = saved == null ? null : saved.getLabelAr();
			this.savedEn = saved == null ? null : saved.getLabelEn();
			this.savedHidden = saved != null && saved.isHidden();
			this.ar = savedAr;
			this.en = savedEn;
			this.hidden = savedHidden;
		}

		/** Tables and fields can be hidden from reports; values (enum constants) cannot. */
		public boolean isHideable() {
			return entry.getKind() != LabelCatalog.Kind.VALUE;
		}

		/** The page's checkbox: "shown in reports". */
		public boolean isShown() {
			return !hidden;
		}

		public void setShown(boolean shown) {
			if (isHideable())
				this.hidden = !shown;
		}

		public boolean isHidden() {
			return hidden;
		}

		boolean isHiddenChanged() {
			return hidden != savedHidden;
		}

		boolean isLabelChanged() {
			return !same(ar, savedAr) || !same(en, savedEn);
		}

		public LabelCatalog.Entry getEntry() {
			return entry;
		}

		/** No Arabic name, neither found automatically nor saved. */
		public boolean isMissing() {
			return entry.getAutoAr() == null && blank(savedAr);
		}

		boolean isChanged() {
			return isLabelChanged() || isHiddenChanged();
		}

		public String getAr() {
			return ar;
		}

		public void setAr(String ar) {
			this.ar = ar;
		}

		public String getEn() {
			return en;
		}

		public void setEn(String en) {
			this.en = en;
		}

		private static boolean same(String a, String b) {
			return (blank(a) ? "" : a.trim()).equals(blank(b) ? "" : b.trim());
		}
	}

	@PostConstruct
	public void init() {
		allowed = security.canEditLabels();
		if (!allowed) {
			ReportUi.error("rb.error.accessDenied");
			return;
		}
		load();
	}

	private void load() {
		try {
			// the names without the labels page, to show which ones are missing
			List<LabelCatalog.Entry> entries = LabelCatalog.entries(persistenceHelper.getEntityManager().getMetamodel(),
					LabelResolver.fromApplication(), EntityDiscovery.Options.fromConfig());
			Map<String, ReportLabel> saved = labelService.findAll();
			rows = new ArrayList<>(entries.size());
			for (LabelCatalog.Entry e : entries)
				rows.add(new LabelRow(e, saved.get(e.getKey())));
		} catch (RuntimeException e) {
			rows = new ArrayList<>();
			ReportUi.error(e, null);
		}
		applyFilter();
	}

	public void applyFilter() {
		String q = blank(search) ? null : search.trim().toLowerCase(Locale.ROOT);
		filtered = new ArrayList<>();
		for (LabelRow r : rows) {
			if (missingOnly && !r.isMissing())
				continue;
			if (hiddenOnly && !r.isHidden())
				continue;
			if (!blank(kind) && !r.getEntry().getKind().name().equals(kind))
				continue;
			if (q != null && !contains(r.getEntry().getKey(), q) && !contains(r.getEntry().getAutoAr(), q)
					&& !contains(r.getEntry().getAutoEn(), q) && !contains(r.ar, q) && !contains(r.en, q))
				continue;
			filtered.add(r);
		}
		first = 0;
	}

	public void save() {
		Map<String, String[]> changes = new LinkedHashMap<>();
		Map<String, Boolean> hiddenChanges = new LinkedHashMap<>();
		for (LabelRow r : rows) {
			if (r.isLabelChanged())
				changes.put(r.getEntry().getKey(), new String[] { r.ar, r.en });
			if (r.isHiddenChanged())
				hiddenChanges.put(r.getEntry().getKey(), r.hidden);
		}
		if (changes.isEmpty() && hiddenChanges.isEmpty()) {
			ReportUi.info("rb.labels.nothingChanged");
			return;
		}
		try {
			int n = labelService.save(changes, hiddenChanges);
			ReportUi.info("rb.labels.saved", n);
			load();
		} catch (RuntimeException e) {
			ReportUi.error(e, null);
		}
	}

	/** Hides every table and field the filter shows (e.g. all tables, then show the few the users need). Saved by "Save". */
	public void hideFiltered() {
		setShownOfFiltered(false);
	}

	/** Shows every table and field the filter shows. Saved by "Save". */
	public void showFiltered() {
		setShownOfFiltered(true);
	}

	private void setShownOfFiltered(boolean shown) {
		int n = 0;
		for (LabelRow r : filtered)
			if (r.isHideable() && r.isShown() != shown) {
				r.setShown(shown);
				n++;
			}
		ReportUi.info(shown ? "rb.labels.markedShown" : "rb.labels.markedHidden", n);
	}

	public int getHiddenCount() {
		int n = 0;
		for (LabelRow r : rows)
			if (r.isHidden())
				n++;
		return n;
	}

	/** The names shown (after the filter) as Excel, e.g. to have the missing ones translated. */
	public void exportExcel() {
		final List<LabelRow> list = new ArrayList<>(filtered);
		final boolean arabic = "ar".equals(ReportUi.locale().getLanguage());
		ReportUi.downloadXlsx("labels", out -> {
			SXSSFWorkbook wb = new SXSSFWorkbook(200);
			try {
				Sheet sheet = wb.createSheet("labels");
				sheet.setRightToLeft(arabic);
				Row h = sheet.createRow(0);
				String[] titles = { ReportUi.text("rb.labels.key"), ReportUi.text("rb.labels.owner"),
						ReportUi.text("rb.labels.autoEn"), ReportUi.text("rb.labels.autoAr"), ReportUi.text("rb.labels.ar"),
						ReportUi.text("rb.labels.en"), ReportUi.text("rb.labels.shown") };
				for (int i = 0; i < titles.length; i++) {
					h.createCell(i).setCellValue(titles[i]);
					sheet.setColumnWidth(i, 30 * 256);
				}
				int n = 1;
				for (LabelRow r : list) {
					Row row = sheet.createRow(n++);
					row.createCell(0).setCellValue(r.getEntry().getKey());
					row.createCell(1).setCellValue(r.getEntry().getOwner());
					row.createCell(2).setCellValue(nz(r.getEntry().getAutoEn()));
					row.createCell(3).setCellValue(nz(r.getEntry().getAutoAr()));
					row.createCell(4).setCellValue(nz(r.ar));
					row.createCell(5).setCellValue(nz(r.en));
					if (r.isHideable())
						row.createCell(6).setCellValue(ReportUi.text(r.isShown() ? "rb.labels.yes" : "rb.labels.no"));
				}
				wb.write(out);
			} finally {
				wb.dispose();
			}
		}, null);
	}

	// ------------------------------------------------------------- paging

	public List<LabelRow> getPage() {
		return filtered.subList(Math.min(first, filtered.size()), Math.min(first + PAGE_SIZE, filtered.size()));
	}

	public void next() {
		if (first + PAGE_SIZE < filtered.size())
			first += PAGE_SIZE;
	}

	public void previous() {
		first = Math.max(0, first - PAGE_SIZE);
	}

	public boolean isHasNext() {
		return first + PAGE_SIZE < filtered.size();
	}

	public boolean isHasPrevious() {
		return first > 0;
	}

	public String getPageInfo() {
		if (filtered.isEmpty())
			return ReportUi.text("rb.labels.none");
		return ReportUi.text("rb.labels.pageInfo", first + 1, Math.min(first + PAGE_SIZE, filtered.size()), filtered.size());
	}

	public int getMissingCount() {
		int n = 0;
		for (LabelRow r : rows)
			if (r.isMissing())
				n++;
		return n;
	}

	public int getTotalCount() {
		return rows.size();
	}

	public List<SelectItem> getKindItems() {
		List<SelectItem> items = new ArrayList<>();
		for (LabelCatalog.Kind k : LabelCatalog.Kind.values())
			items.add(new SelectItem(k.name(), ReportUi.text("rb.labels.kind." + k.name())));
		return items;
	}

	public String kindLabel(LabelRow r) {
		return ReportUi.text("rb.labels.kind." + r.getEntry().getKind().name());
	}

	public boolean isAllowed() {
		return allowed;
	}

	public String getSearch() {
		return search;
	}

	public void setSearch(String search) {
		this.search = search;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public boolean isMissingOnly() {
		return missingOnly;
	}

	public void setMissingOnly(boolean missingOnly) {
		this.missingOnly = missingOnly;
	}

	public boolean isHiddenOnly() {
		return hiddenOnly;
	}

	public void setHiddenOnly(boolean hiddenOnly) {
		this.hiddenOnly = hiddenOnly;
	}

	public void touch() {
		// creating the bean is enough (see AbstractReportBean#touch)
	}

	private static boolean contains(String s, String q) {
		return s != null && s.toLowerCase(Locale.ROOT).contains(q);
	}

	private static boolean blank(String s) {
		return s == null || s.trim().isEmpty();
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}
}
