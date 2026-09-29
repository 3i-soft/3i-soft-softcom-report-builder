package org.softcom.reportbuilder.export;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.softcom.reportbuilder.engine.LookupQueries;
import org.softcom.reportbuilder.engine.ReportExecutor;
import org.softcom.reportbuilder.engine.ResultColumn;
import org.softcom.reportbuilder.spec.DatePart;
import org.softcom.reportbuilder.spi.FieldType;
import org.softcom.reportbuilder.spi.ReportDataSource;

/**
 * Streams rows into an .xlsx file with POI's SXSSF (only a small window of rows
 * is kept in memory), right-to-left for Arabic. Uses only POI APIs present in
 * both 3.16 and 4.x.
 */
public class ExcelReportExporter implements ReportExecutor.RowSink {

	private static final int WINDOW = 200;
	/** Excel's maximum number of characters in one cell. */
	private static final int MAX_CELL_TEXT = 32767;

	private final ReportDataSource ds;
	private final Locale locale;
	private final SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW);
	private final Sheet sheet;
	private final List<CellStyle> styles = new ArrayList<>();
	/** Bold copies of {@link #styles} for subtotal and total rows. */
	private final List<CellStyle> boldStyles = new ArrayList<>();
	private CellStyle boldText;
	private List<ResultColumn> columns;
	private Map<Integer, Map<String, String>> names = java.util.Collections.emptyMap();
	private int nextRow;
	private boolean truncated;

	public ExcelReportExporter(ReportDataSource ds, String title, Locale locale) {
		this.ds = ds;
		this.locale = locale;
		String name = title == null || title.trim().isEmpty() ? "Report" : title;
		this.sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(name));
		boolean arabic = locale == null || "ar".equals(locale.getLanguage());
		sheet.setRightToLeft(arabic);
	}

	@Override
	public void begin(List<ResultColumn> cols) {
		this.columns = cols;
		DataFormat df = workbook.createDataFormat();
		Font bold = workbook.createFont();
		bold.setBold(true);
		CellStyle header = workbook.createCellStyle();
		header.setFont(bold);
		Row row = sheet.createRow(nextRow++);
		for (ResultColumn c : cols) {
			Cell cell = row.createCell(c.getIndex());
			cell.setCellValue(c.getLabel(locale));
			cell.setCellStyle(header);
			sheet.setColumnWidth(c.getIndex(), 20 * 256);
			CellStyle style = workbook.createCellStyle();
			CellStyle boldStyle = workbook.createCellStyle();
			boldStyle.setFont(bold);
			String pattern = excelPattern(c);
			if (pattern != null) {
				style.setDataFormat(df.getFormat(pattern));
				boldStyle.setDataFormat(df.getFormat(pattern));
			}
			styles.add(style);
			boldStyles.add(boldStyle);
		}
		boldText = header;
		sheet.createFreezePane(0, 1);
	}

	private static String excelPattern(ResultColumn c) {
		FieldType t = c.getType();
		if (c.getDatePart() == DatePart.MONTH)
			return "yyyy-mm";
		if (c.getDatePart() == DatePart.YEAR)
			return "yyyy";
		if (t == FieldType.DATE)
			return "yyyy-mm-dd";
		if (t == FieldType.DATETIME)
			return "yyyy-mm-dd hh:mm";
		if (t == FieldType.INTEGER || t == FieldType.LONG)
			return "#,##0";
		if (t.isNumeric())
			return "#,##0.00";
		return null;
	}

	@Override
	public void rows(List<Object[]> rows) {
		for (Object[] values : rows)
			write(values, -1, null, styles);
	}

	@Override
	public void names(Map<Integer, Map<String, String>> chunkNames) {
		this.names = chunkNames == null ? java.util.Collections.<Integer, Map<String, String>>emptyMap() : chunkNames;
	}

	@Override
	public void subtotal(Object[] values, int labelColumn) {
		write(values, labelColumn, "rb.subtotal", boldStyles);
	}

	@Override
	public void total(Object[] values, int labelColumn) {
		write(values, labelColumn, "rb.total", boldStyles);
	}

	private void write(Object[] values, int labelColumn, String labelKey, List<CellStyle> cellStyles) {
		Row row = sheet.createRow(nextRow++);
		for (ResultColumn c : columns) {
			if (c.getIndex() == labelColumn) {
				Cell cell = row.createCell(c.getIndex());
				cell.setCellValue(text(labelKey, labelKey));
				cell.setCellStyle(boldText);
				continue;
			}
			Object v = values == null ? null : values[c.getIndex()];
			if (v == null)
				continue;
			Cell cell = row.createCell(c.getIndex());
			Map<String, String> columnNames = names.get(c.getIndex());
			String name = columnNames == null ? null : columnNames.get(LookupQueries.key(v));
			if (name != null) {
				cell.setCellValue(name);
				if (cellStyles == boldStyles)
					cell.setCellStyle(boldText);
			} else if (v instanceof Number) {
				cell.setCellValue(v instanceof BigDecimal ? ((BigDecimal) v).doubleValue() : ((Number) v).doubleValue());
				cell.setCellStyle(cellStyles.get(c.getIndex()));
			} else if (v instanceof Date) {
				cell.setCellValue((Date) v);
				cell.setCellStyle(cellStyles.get(c.getIndex()));
			} else {
				String text = ReportFormatter.format(v, c, ds, locale);
				cell.setCellValue(text.length() > MAX_CELL_TEXT ? text.substring(0, MAX_CELL_TEXT) : text);
				if (cellStyles == boldStyles)
					cell.setCellStyle(boldText);
			}
		}
	}

	/** A text of the library's bundle in the export's language. */
	private String text(String key, String fallback, Object... args) {
		try {
			return MessageFormat.format(ResourceBundle.getBundle("resources.rbbundle", locale == null ? new Locale("ar") : locale)
					.getString(key), args);
		} catch (MissingResourceException e) {
			return fallback;
		}
	}

	/** Adds a visible note when the export limit cut the result, so totals computed in Excel are not trusted blindly. */
	@Override
	public void end(boolean cut, int limit) {
		this.truncated = cut;
		if (!cut)
			return;
		String note = text("rb.export.truncated", "Truncated: only the first " + limit + " rows were exported", limit);
		Row row = sheet.createRow(nextRow++);
		row.createCell(0).setCellValue(note);
	}

	public boolean isTruncated() {
		return truncated;
	}

	public void write(OutputStream out) throws IOException {
		workbook.write(out);
		out.flush();
	}

	/** Deletes the temporary files SXSSF keeps on disk. Always call it. */
	public void dispose() {
		workbook.dispose();
	}
}
