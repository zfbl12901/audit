package com.fb.audit.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import com.fb.audit.model.AuditReport;
import com.fb.audit.model.GeneratedReports;
import com.fb.audit.model.OrphanFkDetail;
import com.fb.audit.model.OrphanFkResult;
import com.fb.audit.model.OverlapResult;
import com.fb.audit.model.WarningEntry;

@Service
public class ReportGeneratorService {

	public GeneratedReports generate(AuditReport report) {
		byte[] excel = generateExcel(report);
		byte[] html = generateHtml(report).getBytes(StandardCharsets.UTF_8);
		return new GeneratedReports(excel, html, report);
	}

	private byte[] generateExcel(AuditReport report) {
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			CellStyle headerStyle = createHeaderStyle(workbook);
			CellStyle alertStyle = createAlertStyle(workbook);

			writeOverlapSheet(workbook, report.overlaps(), headerStyle, alertStyle);
			writeOrphanSheet(workbook, report.orphanFks(), headerStyle, alertStyle);
			writeWarningSheet(workbook, report.warnings(), headerStyle);

			workbook.write(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("Échec de la génération Excel", e);
		}
	}

	private void writeOverlapSheet(
			Workbook workbook, List<OverlapResult> overlaps, CellStyle headerStyle, CellStyle alertStyle) {

		Sheet sheet = workbook.createSheet("Empiètements ID");
		String[] headers = {
				"Table", "Min TEST", "Max TEST", "Min DEV", "Max DEV",
				"Empiètement", "ID le plus bas incriminé (TEST)"
		};
		createHeaderRow(sheet, headers, headerStyle);

		int rowIdx = 1;
		for (OverlapResult overlap : overlaps) {
			Row row = sheet.createRow(rowIdx++);
			setCell(row, 0, overlap.tableName());
			setCell(row, 1, overlap.minTest());
			setCell(row, 2, overlap.maxTest());
			setCell(row, 3, overlap.minDev());
			setCell(row, 4, overlap.maxDev());
			setCell(row, 5, overlap.overlap() ? "OUI" : "NON");
			setCell(row, 6, overlap.lowestCollidingTestId());

			if (overlap.overlap()) {
				for (int c = 0; c <= 6; c++) {
					row.getCell(c).setCellStyle(alertStyle);
				}
			}
		}
		autosize(sheet, headers.length);
	}

	private void writeOrphanSheet(
			Workbook workbook, List<OrphanFkResult> orphanFks, CellStyle headerStyle, CellStyle alertStyle) {

		Sheet sheet = workbook.createSheet("FK orphelines");
		String[] headers = {
				"Table enfant", "Colonne FK", "Table parente", "Colonne parente",
				"ID enfant TEST", "Valeur FK manquante", "Total orphelins", "Note"
		};
		createHeaderRow(sheet, headers, headerStyle);

		int rowIdx = 1;
		for (OrphanFkResult result : orphanFks) {
			if (result.details().isEmpty() && result.totalOrphanCount() == 0) {
				continue;
			}
			if (result.details().isEmpty()) {
				Row row = sheet.createRow(rowIdx++);
				fillOrphanRow(row, result, null, result.limitationNote());
				applyAlertStyle(row, 8, alertStyle);
				continue;
			}
			for (OrphanFkDetail detail : result.details()) {
				Row row = sheet.createRow(rowIdx++);
				String note = result.totalOrphanCount() > result.details().size()
						? "Affichage limité à " + result.details().size() + " / " + result.totalOrphanCount()
						: result.limitationNote();
				fillOrphanRow(row, result, detail, note);
				applyAlertStyle(row, 8, alertStyle);
			}
		}
		autosize(sheet, headers.length);
	}

	private void fillOrphanRow(Row row, OrphanFkResult result, OrphanFkDetail detail, String note) {
		setCell(row, 0, result.childTable());
		setCell(row, 1, result.fkColumn());
		setCell(row, 2, result.parentTable());
		setCell(row, 3, result.parentColumn());
		setCell(row, 4, detail != null ? detail.childPk() : null);
		setCell(row, 5, detail != null ? detail.missingFkValue() : null);
		setCell(row, 6, result.totalOrphanCount());
		setCell(row, 7, note);
	}

	private void writeWarningSheet(Workbook workbook, List<WarningEntry> warnings, CellStyle headerStyle) {
		Sheet sheet = workbook.createSheet("Avertissements");
		String[] headers = { "Table", "Message" };
		createHeaderRow(sheet, headers, headerStyle);

		int rowIdx = 1;
		for (WarningEntry warning : warnings) {
			Row row = sheet.createRow(rowIdx++);
			setCell(row, 0, warning.tableName());
			setCell(row, 1, warning.message());
		}
		autosize(sheet, headers.length);
	}

	private String generateHtml(AuditReport report) {
		StringBuilder html = new StringBuilder();
		html.append("""
				<!DOCTYPE html>
				<html lang="fr">
				<head>
				  <meta charset="UTF-8">
				  <title>Rapport d'audit pré-export/import</title>
				  <style>
				    body { font-family: Arial, sans-serif; margin: 24px; color: #222; }
				    h1, h2 { color: #1a365d; }
				    table { border-collapse: collapse; width: 100%%; margin-bottom: 32px; }
				    th, td { border: 1px solid #ccc; padding: 8px 12px; text-align: left; }
				    th { background: #2c5282; color: white; }
				    tr.alert { background: #fed7d7; }
				    .meta { color: #555; margin-bottom: 24px; }
				  </style>
				</head>
				<body>
				  <h1>Rapport d'audit pré-export/import</h1>
				  <p class="meta">Requêtes SQL exécutées : %d</p>
				""".formatted(report.sqlQueryCount()));

		html.append("<h2>Empiètements ID</h2>");
		html.append("<table><thead><tr>");
		for (String h : List.of("Table", "Min TEST", "Max TEST", "Min DEV", "Max DEV",
				"Empiètement", "ID le plus bas incriminé (TEST)")) {
			html.append("<th>").append(escape(h)).append("</th>");
		}
		html.append("</tr></thead><tbody>");
		for (OverlapResult o : report.overlaps()) {
			html.append("<tr").append(o.overlap() ? " class=\"alert\"" : "").append(">");
			html.append("<td>").append(escape(o.tableName())).append("</td>");
			html.append("<td>").append(o.minTest()).append("</td>");
			html.append("<td>").append(o.maxTest()).append("</td>");
			html.append("<td>").append(o.minDev()).append("</td>");
			html.append("<td>").append(o.maxDev()).append("</td>");
			html.append("<td>").append(o.overlap() ? "OUI" : "NON").append("</td>");
			html.append("<td>").append(o.lowestCollidingTestId() != null ? o.lowestCollidingTestId() : "")
					.append("</td></tr>");
		}
		html.append("</tbody></table>");

		html.append("<h2>FK orphelines</h2>");
		html.append("<table><thead><tr>");
		for (String h : List.of("Table enfant", "Colonne FK", "Table parente", "Colonne parente",
				"ID enfant TEST", "Valeur FK manquante", "Total orphelins", "Note")) {
			html.append("<th>").append(escape(h)).append("</th>");
		}
		html.append("</tr></thead><tbody>");
		for (OrphanFkResult result : report.orphanFks()) {
			if (result.totalOrphanCount() == 0) {
				continue;
			}
			if (result.details().isEmpty()) {
				appendOrphanHtmlRow(html, result, null, result.limitationNote());
				continue;
			}
			for (OrphanFkDetail detail : result.details()) {
				String note = result.totalOrphanCount() > result.details().size()
						? "Affichage limité à " + result.details().size() + " / " + result.totalOrphanCount()
						: result.limitationNote();
				appendOrphanHtmlRow(html, result, detail, note);
			}
		}
		html.append("</tbody></table>");

		html.append("<h2>Avertissements</h2>");
		html.append("<table><thead><tr><th>Table</th><th>Message</th></tr></thead><tbody>");
		for (WarningEntry w : report.warnings()) {
			html.append("<tr><td>").append(escape(w.tableName())).append("</td>");
			html.append("<td>").append(escape(w.message())).append("</td></tr>");
		}
		html.append("</tbody></table></body></html>");
		return html.toString();
	}

	private void appendOrphanHtmlRow(StringBuilder html, OrphanFkResult result, OrphanFkDetail detail, String note) {
		html.append("<tr class=\"alert\">");
		html.append("<td>").append(escape(result.childTable())).append("</td>");
		html.append("<td>").append(escape(result.fkColumn())).append("</td>");
		html.append("<td>").append(escape(result.parentTable())).append("</td>");
		html.append("<td>").append(escape(result.parentColumn())).append("</td>");
		html.append("<td>").append(detail != null && detail.childPk() != null ? escape(String.valueOf(detail.childPk())) : "")
				.append("</td>");
		html.append("<td>").append(detail != null ? escape(String.valueOf(detail.missingFkValue())) : "")
				.append("</td>");
		html.append("<td>").append(result.totalOrphanCount()).append("</td>");
		html.append("<td>").append(note != null ? escape(note) : "").append("</td></tr>");
	}

	private CellStyle createHeaderStyle(Workbook workbook) {
		CellStyle style = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		style.setFont(font);
		return style;
	}

	private CellStyle createAlertStyle(Workbook workbook) {
		CellStyle style = workbook.createCellStyle();
		style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
		style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		return style;
	}

	private void createHeaderRow(Sheet sheet, String[] headers, CellStyle headerStyle) {
		Row row = sheet.createRow(0);
		for (int i = 0; i < headers.length; i++) {
			Cell cell = row.createCell(i);
			cell.setCellValue(headers[i]);
			cell.setCellStyle(headerStyle);
		}
	}

	private void setCell(Row row, int col, Object value) {
		Cell cell = row.createCell(col);
		if (value == null) {
			cell.setBlank();
		} else if (value instanceof Number number) {
			cell.setCellValue(number.doubleValue());
		} else {
			cell.setCellValue(String.valueOf(value));
		}
	}

	private void applyAlertStyle(Row row, int colCount, CellStyle alertStyle) {
		for (int c = 0; c < colCount; c++) {
			Cell cell = row.getCell(c);
			if (cell != null) {
				cell.setCellStyle(alertStyle);
			}
		}
	}

	private void autosize(Sheet sheet, int colCount) {
		for (int i = 0; i < colCount; i++) {
			sheet.autoSizeColumn(i);
		}
	}

	private String escape(String value) {
		return value
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;");
	}
}
