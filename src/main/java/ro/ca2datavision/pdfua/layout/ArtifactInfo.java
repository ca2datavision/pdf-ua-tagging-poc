package ro.ca2datavision.pdfua.layout;

/**
 * Classification of an artifact as defined by ISO 32000-1, 14.8.2.2: the type is one of
 * Pagination, Layout, Page or Background; the subtype (Header, Footer, Watermark) only exists
 * for pagination artifacts.
 */
public record ArtifactInfo(String type, String subtype) {

    public static final ArtifactInfo LAYOUT = new ArtifactInfo("Layout", null);
    public static final ArtifactInfo HEADER = new ArtifactInfo("Pagination", "Header");
    public static final ArtifactInfo FOOTER = new ArtifactInfo("Pagination", "Footer");
    public static final ArtifactInfo BACKGROUND = new ArtifactInfo("Background", null);
}
