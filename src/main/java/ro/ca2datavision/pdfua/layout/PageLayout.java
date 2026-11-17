package ro.ca2datavision.pdfua.layout;

import java.util.List;

/**
 * The elements of one page, in reading order.
 *
 * @param number 1-based page number
 */
public record PageLayout(int number, float width, float height, List<LayoutElement> elements) {
}
