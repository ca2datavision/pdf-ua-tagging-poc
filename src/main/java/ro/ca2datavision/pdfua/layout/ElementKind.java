package ro.ca2datavision.pdfua.layout;

/** What a report element draws, which decides what kind of page content can belong to it. */
public enum ElementKind {
    /** Static text or text field. */
    TEXT,
    /** Raster or vector image. */
    IMAGE,
    /** Line, rectangle, ellipse: never real content. */
    GRAPHIC,
    /**
     * Container of other elements: a frame of the report, or a group that only exists in the
     * structure (a table row put together from its cells). May carry a grouping role such as
     * BlockQuote, Table, TR or L.
     */
    FRAME,
    /** Placeholder whose area becomes a signature form field. */
    SIGNATURE
}
