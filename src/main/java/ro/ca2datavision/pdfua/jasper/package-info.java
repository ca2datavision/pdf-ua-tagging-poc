/**
 * The JasperReports half of the flow, and the only package that depends on JasperReports.
 *
 * <p>{@link ro.ca2datavision.pdfua.jasper.JasperReportGenerator} is plain JasperReports
 * (compile, fill, export) and knows nothing about accessibility.
 * {@link ro.ca2datavision.pdfua.jasper.JasperLayoutExtractor} translates the filled report
 * into a {@link ro.ca2datavision.pdfua.layout.DocumentLayout}: it is where the {@code pdfua.*}
 * properties of a template, the tagging properties of JasperReports itself and the defaults for
 * elements without either are interpreted. {@code LayoutAssembler} then nests the elements:
 * cells into rows and tables, list items into lists, also across page breaks.</p>
 */
package ro.ca2datavision.pdfua.jasper;
