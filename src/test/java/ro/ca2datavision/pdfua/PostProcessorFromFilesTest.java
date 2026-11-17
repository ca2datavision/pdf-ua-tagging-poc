package ro.ca2datavision.pdfua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import ro.ca2datavision.pdfua.layout.ArtifactInfo;
import ro.ca2datavision.pdfua.layout.Attributes;
import ro.ca2datavision.pdfua.layout.DocumentLayout;
import ro.ca2datavision.pdfua.layout.ElementKind;
import ro.ca2datavision.pdfua.layout.LayoutElement;
import ro.ca2datavision.pdfua.layout.LayoutJson;
import ro.ca2datavision.pdfua.layout.PageLayout;
import ro.ca2datavision.pdfua.layout.Roles;
import ro.ca2datavision.pdfua.postprocess.PdfUaPart;
import ro.ca2datavision.pdfua.postprocess.PdfUaPostProcessor;
import ro.ca2datavision.pdfua.postprocess.PostProcessingReport;

/**
 * The post-processor needs nothing from JasperReports at run time: the raw PDF and the layout
 * file are enough. These tests start from the two intermediate files on disk.
 */
class PostProcessorFromFilesTest {

    @TempDir
    static Path directory;

    private static Path rawPdf;
    private static DocumentLayout layout;

    @BeforeAll
    static void generateIntermediateFiles() throws Exception {
        PdfUaPipeline.Result result = PdfUaPipeline.run(PdfUaPipeline.DEMO_TEMPLATE, directory);
        rawPdf = result.rawPdf();
        layout = LayoutJson.read(result.layoutJson());
    }

    @Test
    void rawPdfPlusLayoutFileGiveAPdfUaDocument() throws Exception {
        Path target = directory.resolve("from-files.pdf");
        PostProcessingReport report = PdfUaPostProcessor.process(rawPdf, layout, target);

        assertEquals(List.of(), report.warnings());
        assertTrue(VeraPdf.validate(target, PDFAFlavour.PDFUA_1).compliant());
    }

    @Test
    void everythingInsideAnArtifactFrameIsAnArtifact() throws Exception {
        // Turn the BlockQuote frame of page 1 into an artifact: its paragraph must disappear from the
        // structure tree although the paragraph itself still has the role P.
        DocumentLayout changed = new DocumentLayout(layout.title(), layout.author(), layout.subject(), layout.language(),
                layout.pages().stream()
                        .map(page -> new PageLayout(page.number(), page.width(), page.height(),
                                page.elements().stream().map(PostProcessorFromFilesTest::hideBlockQuote).toList()))
                        .toList());

        Path target = directory.resolve("artifact-frame.pdf");
        PostProcessingReport report = PdfUaPostProcessor.process(rawPdf, changed, target);
        assertEquals(List.of(), report.warnings());

        VeraPdf.Outcome outcome = VeraPdf.validate(target, PDFAFlavour.PDFUA_1);
        assertTrue(outcome.compliant(), outcome::toString);

        try (PDDocument document = Loader.loadPDF(target.toFile())) {
            TaggedPdf tagged = new TaggedPdf(document);
            List<String> types = tagged.readingOrder().stream().map(TaggedPdf.Node::type).toList();
            assertFalse(types.contains("BlockQuote"));
            String text = tagged.readingOrder().stream().map(TaggedPdf.Node::text).collect(Collectors.joining("\n"));
            assertFalse(text.contains("The platform is available"), "quoted text is not read any more");
            assertTrue(text.contains("1.2 Service availability"), "the rest of the page is untouched");
            for (TaggedPdf.Painted operator : tagged.painted()) {
                assertTrue(operator.artifact() ^ operator.tagged(), "still nothing unmarked");
            }
        }
    }

    @Test
    void structureDestinationsFollowTheAnchorsOfTheLayout() throws Exception {
        // Move the anchor "signatures" from the heading of section 4.1 to the paragraph before
        // it. The named destination in the PDF still points at the position of the heading, so
        // only the anchor in the layout can lead the link to the paragraph: this shows that the
        // exact path is taken, not the fallback that looks for the first element at a position.
        DocumentLayout moved = new DocumentLayout(layout.title(), layout.author(), layout.subject(), layout.language(),
                layout.pages().stream()
                        .map(page -> new PageLayout(page.number(), page.width(), page.height(),
                                page.elements().stream().map(PostProcessorFromFilesTest::moveAnchor).toList()))
                        .toList());

        Path target = directory.resolve("anchor-moved.pdf");
        PostProcessingReport report = PdfUaPostProcessor.process(rawPdf, moved, target, PdfUaPart.PDF_UA_2);
        assertEquals(List.of(), report.warnings());

        try (PDDocument document = Loader.loadPDF(target.toFile())) {
            TaggedPdf tagged = new TaggedPdf(document);
            TaggedPdf.Node link = tagged.readingOrder().stream()
                    .filter(node -> "Link".equals(node.type()) && "Sign the agreement in section 4.1".equals(node.text()))
                    .findFirst().orElseThrow();
            COSArray structureDestination = link.annotations().get(0).getCOSDictionary(COSName.A)
                    .getCOSArray(COSName.getPDFName("SD"));
            TaggedPdf.Node paragraph = tagged.readingOrder().stream()
                    .filter(node -> node.text().startsWith("The agreement enters into force"))
                    .findFirst().orElseThrow();
            assertSame(paragraph.element(), structureDestination.getObject(0));
        }
    }

    @Test
    void pdfUa2WarnsAboutAListWithLabelsButNoNumbering() throws Exception {
        // the numbering of the first list is read from its "•" labels; remove it from the layout
        DocumentLayout withoutNumbering = new DocumentLayout(layout.title(), layout.author(), layout.subject(), layout.language(),
                layout.pages().stream()
                        .map(page -> new PageLayout(page.number(), page.width(), page.height(),
                                page.elements().stream().map(PostProcessorFromFilesTest::dropBulletNumbering).toList()))
                        .toList());

        PostProcessingReport pdfUa2 = PdfUaPostProcessor.process(rawPdf, withoutNumbering,
                directory.resolve("no-numbering-2.pdf"), PdfUaPart.PDF_UA_2);
        assertEquals(1, pdfUa2.warnings().size(), pdfUa2.warnings()::toString);
        assertTrue(pdfUa2.warnings().get(0).contains("pdfua.list.numbering"));

        // PDF/UA-1 does not require the numbering
        PostProcessingReport pdfUa1 = PdfUaPostProcessor.process(rawPdf, withoutNumbering, directory.resolve("no-numbering-1.pdf"));
        assertEquals(List.of(), pdfUa1.warnings());
    }

    /** Removes the numbering of the list whose first item is "nightly collection of your operational data". */
    private static LayoutElement dropBulletNumbering(LayoutElement element) {
        Map<String, String> attributes = element.attributes();
        if (Roles.L.equals(element.role()) && firstItemText(element).startsWith("nightly collection")) {
            attributes = new LinkedHashMap<>(attributes);
            attributes.remove(Attributes.LIST_NUMBERING);
        }
        return new LayoutElement(element.id(), element.kind(), element.role(), element.box(), element.text(),
                element.alt(), element.artifact(), element.signature(), element.anchor(), element.structureId(),
                attributes, element.children().stream().map(PostProcessorFromFilesTest::dropBulletNumbering).toList());
    }

    private static String firstItemText(LayoutElement list) {
        return list.children().stream().flatMap(item -> item.children().stream())
                .filter(part -> Roles.LBODY.equals(part.role()) && part.text() != null)
                .map(LayoutElement::text).findFirst().orElse("");
    }

    private static final String MOVED_ANCHOR = "signatures";

    private static LayoutElement moveAnchor(LayoutElement element) {
        String anchor = element.anchor();
        if (MOVED_ANCHOR.equals(anchor)) {
            anchor = null;
        } else if (element.text() != null && element.text().startsWith("The agreement enters into force")) {
            anchor = MOVED_ANCHOR;
        }
        return new LayoutElement(element.id(), element.kind(), element.role(), element.box(), element.text(),
                element.alt(), element.artifact(), element.signature(), anchor, element.structureId(),
                element.attributes(), element.children().stream().map(PostProcessorFromFilesTest::moveAnchor).toList());
    }

    private static LayoutElement hideBlockQuote(LayoutElement element) {
        if (element.kind() == ElementKind.FRAME && "BlockQuote".equals(element.role())) {
            return new LayoutElement(element.id(), element.kind(), Roles.ARTIFACT, element.box(), element.text(),
                    element.alt(), ArtifactInfo.LAYOUT, element.signature(), element.anchor(), null, null, element.children());
        }
        return element;
    }
}
