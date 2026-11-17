package ro.ca2datavision.pdfua.layout;

/**
 * A signature field to create.
 *
 * @param name        the field name (/T)
 * @param description what the signer confirms by signing; becomes the field's alternate name
 *                    (/TU), which is what a screen reader announces when the field gets focus
 */
public record SignatureInfo(String name, String description) {
}
