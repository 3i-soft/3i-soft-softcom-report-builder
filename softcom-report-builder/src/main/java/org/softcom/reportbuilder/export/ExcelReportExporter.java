package org.softcom.reportbuilder.export;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
import org.softcom.reportbuilder.engine.ReportExecutor;
import org.softcom.reportbuilder.engine.ResultColumn;
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
	private List<ResultColumn> columns;
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
			String pattern = excelPattern(c);
			if (pattern != null)
				style.setDataFormat(df.getFormat(pattern));
			styles.add(style);
		}
		sheet.createFreezePane(0, 1);
	}

	private static String excelPattern(ResultColumn c) {
		FieldType t = c.getType();
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
		for (Object[] values : rows) {
			Row row = sheet.createRow(nextRow++);
			for (ResultColumn c : columns) {
				Object v = values[c.getIndex()];
				if (v == null)
					continue;
				Cell cell = row.createCell(c.getIndex());
				if (v instanceof Number) {
					cell.setCellValue(v instanceof BigDecimal ? ((BigDecimal) v).doubleValue() : ((Number) v).doubleValue());
					cell.setCellStyle(styles.get(c.getIndex()));
				} else if (v instanceof Date) {
					cell.setCellValue((Date) v);
					cell.setCellStyle(styles.get(c.getIndex()));
				} else {
					String text = ReportFormatter.format(v, c, ds, locale);
					cell.setCellValue(text.length() > MAX_CELL_TEXT ? text.substring(0, MAX_CELL_TEXT) : text);
				}
			}
		}
	}

	/** Adds a visible note when the export limit cut the result, so totals computed in Excel are not trusted blindly. */
	@Override
	public void end(boolean cut, int limit) {
		this.truncated = cut;
		if (!cut)
			return;
		String note;
		try {
			note = MessageFormat.format(ResourceBundle.getBundle("resources.rbbundle", locale == null ? new Locale("ar") : locale)
					.getString("rb.export.truncated"), limit);
		} catch (MissingResourceException e) {
			note = "Truncated: only the first " + limit + " rows were exported";
		}
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
