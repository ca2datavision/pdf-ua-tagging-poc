package ro.ca2datavision.pdfua.layout;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A rectangle in PDF user space: points, origin in the lower left corner of the page.
 */
public record Box(float llx, float lly, float urx, float ury) {

    /** Converts a rectangle given in report coordinates (origin top left, y pointing down). */
    public static Box fromTopLeft(float x, float y, float width, float height, float pageHeight) {
        return new Box(x, pageHeight - y - height, x + width, pageHeight - y);
    }

    public boolean contains(double x, double y, float tolerance) {
        return x >= llx - tolerance && x <= urx + tolerance && y >= lly - tolerance && y <= ury + tolerance;
    }

    @JsonIgnore
    public float width() {
        return urx - llx;
    }

    @JsonIgnore
    public float height() {
        return ury - lly;
    }

    @JsonIgnore
    public float area() {
        return width() * height();
    }
}
