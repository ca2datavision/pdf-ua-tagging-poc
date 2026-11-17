/**
 * Digital signatures for the signature fields that the post-processor creates.
 *
 * <p>{@link ro.ca2datavision.pdfua.sign.PdfSigner} signs a field of a PDF/UA document as an
 * incremental update and keeps the document PDF/UA. It depends on PDFBox and BouncyCastle, not
 * on the rest of the project: any PDF with an empty signature field will do.
 * {@link ro.ca2datavision.pdfua.sign.SigningIdentity} holds the key and certificate, and can
 * make a throw-away one for demonstrations.</p>
 */
package ro.ca2datavision.pdfua.sign;
