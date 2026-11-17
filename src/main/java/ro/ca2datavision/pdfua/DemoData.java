package ro.ca2datavision.pdfua;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Invented data for the price table of the demo report: enough rows to run over a page break. */
final class DemoData {

    private static final String[] AREAS = {"Sales", "Stock", "Purchasing", "Production", "Logistics", "Finance", "Staff"};
    private static final String[] REPORTS = {"daily report", "trend analysis", "anomaly alerts", "management dashboard"};
    private static final String[] UNITS = {"per site", "per site", "per data source", "per user"};
    private static final int[] PRICES = {120, 180, 95, 14};

    private DemoData() {
    }

    /** One map per table row, with the keys {@code service}, {@code unit}, {@code support} and {@code net}. */
    static List<Map<String, Object>> prices() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int area = 0; area < AREAS.length; area++) {
            for (int report = 0; report < REPORTS.length; report++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("service", AREAS[area] + ": " + REPORTS[report]);
                row.put("unit", UNITS[report]);
                row.put("support", report == 2 || area % 3 == 0);
                row.put("net", BigDecimal.valueOf(PRICES[report] + 5L * area).setScale(2));
                rows.add(row);
            }
        }
        return rows;
    }

    /** Parameters of the demo report. */
    static Map<String, Object> parameters() {
        return Map.of("PRICES", prices());
    }
}
