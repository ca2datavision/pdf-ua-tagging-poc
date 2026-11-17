package ro.ca2datavision.pdfua.postprocess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;

import ro.ca2datavision.pdfua.layout.DocumentLayout;
import ro.ca2datavision.pdfua.layout.PageLayout;
import ro.ca2datavision.pdfua.postprocess.PageTagger.Heading;

/**
 * Turns an untagged PDF into a PDF/UA document: PDF/UA-1 (ISO 14289-1) by default, or PDF/UA-2
 * (ISO 14289-2) on request; see {@link PdfUaPart} for what differs.
 *
 * <p>Input is the PDF as exported by the report engine plus the {@link DocumentLayout} that
 * says what each element on each page means. The post-processor</p>
 * <ul>
 *   <li>marks all page content as either tagged content or artifact and builds the structure
 *       tree with headings, paragraphs, tables, lists, figures (with alternate text), links and
 *       form fields, exactly as nested in the layout;</li>
 *   <li>adds the signature fields;</li>
 *   <li>sets everything PDF/UA asks for at document level: the PDF/UA identifier and title in
 *       the XMP metadata, the title shown in the window title bar, the document language, the
 *       "marked" flag, structure based tab order;</li>
 *   <li>adds bookmarks for the headings;</li>
 *   <li>for PDF/UA-2, makes every destination inside the document a structure destination.</li>
 * </ul>
 *
 * <p>Two things cannot be repaired afterwards and are therefore checked up front: all fonts
 * must be embedded, and the document needs a title and a language.</p>
 */
public final class PdfUaPostProcessor {

    private static final String TOOL_NAME = "pdf-ua-tagging-poc";

    private final PDDocument document;
    private final DocumentLayout layout;
    private final PdfUaPart part;
    private final PostProcessingReport report;

    private PdfUaPostProcessor(PDDocument document, DocumentLayout layout, PdfUaPart part) {
        this.document = document;
        this.layout = layout;
        this.part = part;
        this.report = new PostProcessingReport(part);
    }

    /**
     * Writes a PDF/UA-1 document.
     *
     * @param rawPdf the untagged PDF produced by the report engine
     * @param layout the meaning and position of the elements of that PDF
     * @param target where to write the PDF/UA document
     */
    public static PostProcessingReport process(Path rawPdf, DocumentLayout layout, Path target) throws IOException {
        return process(rawPdf, layout, target, PdfUaPart.PDF_UA_1);
    }

    /**
     * Writes a document that conforms to the given part of PDF/UA.
     *
     * @param rawPdf the untagged PDF produced by the report engine
     * @param layout the meaning and position of the elements of that PDF
     * @param target where to write the PDF/UA document
     * @param part   PDF/UA-1 or PDF/UA-2
     */
    public static PostProcessingReport process(Path rawPdf, DocumentLayout layout, Path target, PdfUaPart part)
            throws IOException {
        try (PDDocument document = Loader.loadPDF(rawPdf.toFile())) {
            PdfUaPostProcessor processor = new PdfUaPostProcessor(document, layout, part);
            processor.run();
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.deleteIfExists(target);
            // No object streams: slightly larger, but the result can be inspected with a text editor.
            document.save(target.toFile(), CompressParameters.NO_COMPRESSION);
            return processor.report;
        }
    }

    private void run() throws IOException {
        checkPreconditions();

        StructureBuilder structure = new StructureBuilder(part);
        SignatureFields signatureFields = new SignatureFields(document, part);
        List<Heading> headings = new ArrayList<>();

        int index = 0;
        for (PDPage page : document.getPages()) {
            PageLayout pageLayout = layout.pages().get(index++);
            new PageTagger(document, page, pageLayout, structure, signatureFields, headings, report).tag();
            // PDF/UA-1 7.18.3: pages with annotations use the structure order as tab order
            page.getCOSObject().setName(COSName.getPDFName("Tabs"), "S");
            report.pages++;
        }

        PDDocumentCatalog catalog = document.getDocumentCatalog();
        structure.install(catalog);

        // ISO 32000-1 14.7.1: the document claims to be a tagged PDF
        PDMarkInfo markInfo = new PDMarkInfo();
        markInfo.setMarked(true);
        catalog.setMarkInfo(markInfo);

        // PDF/UA-1 7.2: default natural language of all text
        catalog.setLanguage(layout.language());

        // PDF/UA-1 7.1: the window title bar shows the document title, not the file name
        PDViewerPreferences viewerPreferences = catalog.getViewerPreferences();
        if (viewerPreferences == null) {
            viewerPreferences = new PDViewerPreferences(new COSDictionary());
            catalog.setViewerPreferences(viewerPreferences);
        }
        viewerPreferences.setDisplayDocTitle(true);

        if (part == PdfUaPart.PDF_UA_2) {
            // ISO 14289-2, 6.1: PDF/UA-2 is defined on PDF 2.0; the header says so, and the catalog too
            document.getDocument().setVersion(2.0f);
            catalog.setVersion(part.pdfVersion());
        } else {
            // PDF/UA-1 is defined on top of PDF 1.7
            document.setVersion(1.7f);
        }

        writeMetadata(catalog);
        writeBookmarks(catalog, headings);
        if (part == PdfUaPart.PDF_UA_2) {
            // ISO 14289-2, 8.8: links inside the document lead to structure elements
            new StructureDestinations(document, layout, structure, report).apply();
        }
    }

    private void checkPreconditions() throws IOException {
        if (document.getNumberOfPages() != layout.pages().size()) {
            throw new IllegalArgumentException("The PDF has " + document.getNumberOfPages()
                    + " pages but the layout describes " + layout.pages().size());
        }
        if (layout.title() == null || layout.title().isBlank()) {
            throw new IllegalArgumentException("PDF/UA requires a document title; none was provided");
        }
        if (layout.language() == null || layout.language().isBlank()) {
            throw new IllegalArgumentException("PDF/UA requires the document language; none was provided");
        }
        if (document.isEncrypted()) {
            throw new IllegalArgumentException("Encrypted documents cannot be post-processed");
        }

        TreeSet<String> notEmbedded = new TreeSet<>();
        Map<COSBase, Boolean> visited = new IdentityHashMap<>();
        for (PDPage page : document.getPages()) {
            collectFontsNotEmbedded(page.getResources(), visited, notEmbedded);
        }
        if (!notEmbedded.isEmpty()) {
            throw new IllegalStateException("PDF/UA requires all fonts to be embedded, these are not: " + notEmbedded
                    + ". Use fonts from a JasperReports font extension (pdfEmbedded=true) in the template.");
        }
    }

    private static void collectFontsNotEmbedded(PDResources resources, Map<COSBase, Boolean> visited, TreeSet<String> notEmbedded)
            throws IOException {
        if (resources == null || visited.put(resources.getCOSObject(), Boolean.TRUE) != null) {
            return;
        }
        for (COSName name : resources.getFontNames()) {
            PDFont font = resources.getFont(name);
            if (font != null && !font.isEmbedded()) {
                notEmbedded.add(font.getName());
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDFormXObject form) {
                collectFontsNotEmbedded(form.getResources(), visited, notEmbedded);
            }
        }
    }

    /** Document information dictionary and XMP packet, kept consistent with each other. */
    private void writeMetadata(PDDocumentCatalog catalog) throws IOException {
        PDDocumentInformation info = document.getDocumentInformation();
        Calendar now = new GregorianCalendar();
        info.setTitle(layout.title());
        if (layout.author() != null) {
            info.setAuthor(layout.author());
        }
        if (layout.subject() != null) {
            info.setSubject(layout.subject());
        }
        if (info.getCreationDate() == null) {
            info.setCreationDate(now);
        }
        info.setModificationDate(now);
        String producer = info.getProducer() == null ? TOOL_NAME : info.getProducer() + "; " + TOOL_NAME;
        info.setProducer(producer);

        XmpMetadata xmp = new XmpMetadata();
        xmp.part = part.part();
        xmp.revision = part.revision();
        xmp.title = info.getTitle();
        xmp.author = info.getAuthor();
        xmp.subject = info.getSubject();
        xmp.language = layout.language();
        xmp.producer = producer;
        xmp.creatorTool = info.getCreator();
        xmp.created = toOffsetDateTime(info.getCreationDate());
        xmp.modified = toOffsetDateTime(now);

        PDMetadata metadata = new PDMetadata(document);
        metadata.importXMPMetadata(xmp.toBytes());
        catalog.setMetadata(metadata);
    }

    private static OffsetDateTime toOffsetDateTime(Calendar calendar) {
        ZoneId zone = calendar.getTimeZone() == null ? ZoneId.systemDefault() : calendar.getTimeZone().toZoneId();
        return calendar.toInstant().atZone(zone).toOffsetDateTime();
    }

    /**
     * One bookmark per heading, nested by heading level and linked to its structure element. For
     * PDF/UA-2 a bookmark leads there through a go-to action that holds both the page destination
     * and the structure destination; for PDF/UA-1 it has the page destination only.
     */
    private void writeBookmarks(PDDocumentCatalog catalog, List<Heading> headings) {
        if (headings.isEmpty()) {
            return;
        }
        PDDocumentOutline outline = new PDDocumentOutline();
        Deque<Heading> parents = new ArrayDeque<>();
        Map<Heading, PDOutlineItem> items = new IdentityHashMap<>();
        for (Heading heading : headings) {
            PDPageXYZDestination destination = new PDPageXYZDestination();
            destination.setPage(heading.page());
            destination.setLeft((int) Math.floor(heading.left()));
            destination.setTop((int) Math.ceil(heading.top()));

            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(heading.title());
            if (part == PdfUaPart.PDF_UA_2) {
                item.getCOSObject().setItem(COSName.A, StructureDestinations.goTo(destination.getCOSObject(),
                        StructureDestinations.structureDestination(heading.element(), destination.getCOSObject())));
                report.structureDestinations++;
            } else {
                item.setDestination(destination);
            }
            item.setStructureElement(new PDStructureElement(heading.element()));
            items.put(heading, item);

            while (!parents.isEmpty() && parents.peek().level() >= heading.level()) {
                parents.pop();
            }
            if (parents.isEmpty()) {
                outline.addLast(item);
            } else {
                items.get(parents.peek()).addLast(item);
            }
            parents.push(heading);
        }
        headings.forEach(heading -> items.get(heading).openNode());
        outline.openNode();
        catalog.setDocumentOutline(outline);
    }
}
