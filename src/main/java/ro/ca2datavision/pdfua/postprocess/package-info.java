/**
 * The post-processor: an untagged PDF plus a
 * {@link ro.ca2datavision.pdfua.layout.DocumentLayout} become a PDF/UA-1 document.
 * Depends on PDFBox and on the {@code layout} package only, never on JasperReports.
 *
 * <ul>
 *   <li>{@link ro.ca2datavision.pdfua.postprocess.PdfUaPostProcessor}: entry point; checks the
 *       preconditions, runs the page tagging and sets the document level entries;</li>
 *   <li>{@code ContentScanner}: finds what every operator of a page content stream paints and
 *       where;</li>
 *   <li>{@code PageTagger}: decides for each of those whether it is content of a layout element
 *       or an artifact, rewrites the content stream with marked content and creates the
 *       structure elements of the page;</li>
 *   <li>{@code StructureBuilder}: structure tree, parent tree, role map;</li>
 *   <li>{@code SignatureFields}: empty signature fields with a description;</li>
 *   <li>{@link ro.ca2datavision.pdfua.postprocess.StructureDump}: text view of any tagged PDF.</li>
 * </ul>
 */
package ro.ca2datavision.pdfua.postprocess;
