/**
 * The contract between the report engine and the post-processor: what every element of every
 * page means (its role) and where it is (its box, in PDF user space).
 *
 * <p>Plain records without behaviour, serialisable as JSON through
 * {@link ro.ca2datavision.pdfua.layout.LayoutJson}. The package depends on neither
 * JasperReports nor PDFBox, so a layout can be produced by another report engine or written by
 * hand, and consumed in another process.</p>
 */
package ro.ca2datavision.pdfua.layout;
