/**
 * Entry points of the proof of concept.
 *
 * <p>{@link ro.ca2datavision.pdfua.PdfUaPipeline} runs the whole flow and keeps every
 * intermediate file; {@link ro.ca2datavision.pdfua.PdfUaDemo} is its command line wrapper.
 * The flow has two halves that only share the {@code layout} package:</p>
 * <ul>
 *   <li>{@code jasper}: JasperReports fills the template, exports an untagged PDF and the
 *       layout (role and position of every element) is read from the filled report;</li>
 *   <li>{@code postprocess}: PDFBox turns the untagged PDF plus the layout into PDF/UA-1.</li>
 * </ul>
 *
 * <p>A third, independent package, {@code sign}, signs a signature field of the result.</p>
 */
package ro.ca2datavision.pdfua;
