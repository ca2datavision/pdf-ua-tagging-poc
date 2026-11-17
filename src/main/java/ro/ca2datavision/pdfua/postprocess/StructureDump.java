package ro.ca2datavision.pdfua.postprocess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDArtifactMarkedContent;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.TextPosition;

/**
 * Prints the logical structure of a tagged PDF as indented text: the PDF/UA part it claims, the
 * structure tree with the content of every element in the order a screen reader presents it,
 * the bookmarks with their targets, and the artifacts of each page. Meant for humans who want to
 * see what the post-processor produced without opening a PDF inspection tool.
 */
public final class StructureDump {

    private static final COSName NS = COSName.getPDFName("NS");
    private static final COSName NAMESPACES = COSName.getPDFName("Namespaces");
    private static final COSName SD = COSName.getPDFName("SD");
    private static final Pattern PART = Pattern.compile("pdfuaid:part\\s*(?:>|=\\s*\")\\s*(\\d+)");
    private static final Pattern REVISION = Pattern.compile("pdfuaid:rev\\s*(?:>|=\\s*\")\\s*(\\d+)");

    private final PDDocument document;
    private final StringBuilder out = new StringBuilder();
    /** page index -> MCID -> text of the marked-content sequence */
    private final List<Map<Integer, String>> content = new ArrayList<>();
    private final List<List<String>> artifacts = new ArrayList<>();

    private StructureDump(PDDocument document) {
        this.document = document;
    }

    public static String dump(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return dump(document);
        }
    }

    public static String dump(PDDocument document) throws IOException {
        return new StructureDump(document).run();
    }

    private String run() throws IOException {
        for (PDPage page : document.getPages()) {
            Map<Integer, String> pageContent = new HashMap<>();
            List<String> pageArtifacts = new ArrayList<>();
            PDFMarkedContentExtractor extractor = new PDFMarkedContentExtractor();
            extractor.processPage(page);
            for (PDMarkedContent markedContent : extractor.getMarkedContents()) {
                if (markedContent instanceof PDArtifactMarkedContent artifact) {
                    String type = artifact.getType() + (artifact.getSubtype() == null ? "" : "/" + artifact.getSubtype());
                    pageArtifacts.add(type + ": " + describe(markedContent));
                } else if (markedContent.getMCID() >= 0) {
                    pageContent.put(markedContent.getMCID(), describe(markedContent));
                }
            }
            content.add(pageContent);
            artifacts.add(pageArtifacts);
        }

        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDStructureTreeRoot root = catalog.getStructureTreeRoot();
        out.append("Title:       ").append(document.getDocumentInformation().getTitle()).append('\n');
        out.append("Language:    ").append(catalog.getLanguage()).append('\n');
        out.append("Conformance: ").append(conformance(catalog)).append('\n');
        COSArray namespaces = root == null ? null : root.getCOSObject().getCOSArray(NAMESPACES);
        if (namespaces != null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < namespaces.size(); i++) {
                if (namespaces.getObject(i) instanceof COSDictionary namespace) {
                    names.add(namespaceLabel(namespace.getString(NS)) + " (" + namespace.getString(NS) + ")");
                }
            }
            out.append("Namespaces:  ").append(String.join(", ", names)).append('\n');
        }
        out.append('\n').append("STRUCTURE TREE (reading order)").append('\n');
        if (root == null) {
            out.append("  none: the document is not tagged").append('\n');
        } else {
            Map<String, Object> roleMap = root.getRoleMap();
            for (Object kid : root.getKids()) {
                if (kid instanceof PDStructureElement element) {
                    print(element, roleMap, 1);
                }
            }
        }

        PDDocumentOutline outline = catalog.getDocumentOutline();
        if (outline != null) {
            out.append('\n').append("BOOKMARKS").append('\n');
            printOutline(outline, 1);
        }

        out.append('\n').append("ARTIFACTS (not presented by assistive technology)").append('\n');
        for (int i = 0; i < artifacts.size(); i++) {
            out.append("  Page ").append(i + 1).append('\n');
            for (String artifact : artifacts.get(i)) {
                out.append("    ").append(artifact).append('\n');
            }
        }
        return out.toString();
    }

    private void print(PDStructureElement element, Map<String, Object> roleMap, int depth) {
        String type = element.getStructureType();
        indent(depth).append(type);
        if (roleMap.get(type) != null) {
            // in the PDF 2.0 namespace the role map does not apply; it only helps older readers
            boolean pdf20 = element.getCOSObject().getDictionaryObject(NS) instanceof COSDictionary namespace
                    && StructureTypes.PDF_2_0_NAMESPACE.equals(namespace.getString(NS));
            out.append(pdf20 ? "  (PDF 1.7 readers: " : " (mapped to ").append(roleMap.get(type)).append(')');
        }
        if (element.getCOSObject().getDictionaryObject(NS) instanceof COSDictionary namespace
                && !StructureTypes.PDF_2_0_NAMESPACE.equals(namespace.getString(NS))) {
            // in a PDF 2.0 document, show the elements that are not in the PDF 2.0 namespace
            out.append("  [").append(namespaceLabel(namespace.getString(NS))).append(" namespace]");
        }
        int pageIndex = element.getPage() == null ? -1 : document.getPages().indexOf(element.getPage());
        if (pageIndex >= 0) {
            out.append("  [page ").append(pageIndex + 1).append(']');
        }
        if (element.getAlternateDescription() != null) {
            out.append("  Alt=\"").append(element.getAlternateDescription()).append('"');
        }
        printAttributes(element);
        out.append('\n');

        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement child) {
                print(child, roleMap, depth + 1);
            } else if (kid instanceof Integer mcid) {
                printContent(pageIndex, mcid, depth + 1);
            } else if (kid instanceof PDMarkedContentReference reference) {
                int page = reference.getPage() == null ? pageIndex : document.getPages().indexOf(reference.getPage());
                printContent(page, reference.getMCID(), depth + 1);
            } else if (kid instanceof PDObjectReference reference) {
                printAnnotation(reference.getCOSObject().getCOSDictionary(COSName.getPDFName("Obj")), depth + 1);
            }
        }
    }

    /** Language, replacement text, identifier, and the table and list attributes of an element. */
    private void printAttributes(PDStructureElement element) {
        COSDictionary dictionary = element.getCOSObject();
        if (dictionary.getString(COSName.LANG) != null) {
            out.append("  Lang=").append(dictionary.getString(COSName.LANG));
        }
        if (dictionary.getString(COSName.ACTUAL_TEXT) != null) {
            out.append("  ActualText=\"").append(dictionary.getString(COSName.ACTUAL_TEXT)).append('"');
        }
        if (dictionary.getString(COSName.ID) != null) {
            out.append("  ID=").append(dictionary.getString(COSName.ID));
        }
        COSBase attributes = dictionary.getDictionaryObject(COSName.A);
        List<COSBase> objects = new ArrayList<>();
        if (attributes instanceof COSArray array) {
            array.forEach(item -> objects.add(item instanceof COSObject reference ? reference.getObject() : item));
        } else if (attributes != null) {
            objects.add(attributes);
        }
        for (COSBase object : objects) {
            if (!(object instanceof COSDictionary attribute)) {
                continue;
            }
            String owner = attribute.getNameAsString(COSName.O);
            if (!"Table".equals(owner) && !"List".equals(owner)) {
                continue; // layout attributes (bounding boxes) would only clutter the listing
            }
            for (COSName key : attribute.keySet()) {
                if (COSName.O.equals(key)) {
                    continue;
                }
                COSBase value = attribute.getDictionaryObject(key);
                String text = value instanceof COSName name ? name.getName()
                        : value instanceof COSString string ? '"' + string.getString() + '"'
                        : value instanceof COSNumber number ? String.valueOf(number.intValue())
                        : String.valueOf(value);
                out.append("  ").append(key.getName()).append('=').append(text);
            }
        }
    }

    private void printContent(int pageIndex, int mcid, int depth) {
        String text = pageIndex < 0 ? null : content.get(pageIndex).get(mcid);
        indent(depth).append("- ").append(text == null ? "(content not found, MCID " + mcid + ")" : text).append('\n');
    }

    private void printAnnotation(COSDictionary annotation, int depth) {
        if (annotation == null) {
            return;
        }
        String subtype = annotation.getNameAsString(COSName.SUBTYPE);
        indent(depth).append("> ").append(subtype).append(" annotation");
        COSDictionary action = annotation.getCOSDictionary(COSName.A);
        if (action != null && action.getString(COSName.URI) != null) {
            out.append("  URI=").append(action.getString(COSName.URI));
        } else if (action != null && "GoTo".equals(action.getNameAsString(COSName.S))) {
            out.append("  GoTo ").append(target(action.getDictionaryObject(COSName.D)));
            appendStructureDestination(action.getDictionaryObject(SD));
        } else if (annotation.getDictionaryObject(COSName.DEST) != null) {
            out.append("  Dest ").append(target(annotation.getDictionaryObject(COSName.DEST)));
        }
        if (annotation.getString(COSName.CONTENTS) != null) {
            out.append("  Contents=\"").append(annotation.getString(COSName.CONTENTS)).append('"');
        }
        if ("Widget".equals(subtype)) {
            out.append("  FieldType=").append(annotation.getNameAsString(COSName.FT));
            out.append("  Name=").append(annotation.getString(COSName.T));
            out.append("  Description(TU)=\"").append(annotation.getString(COSName.TU)).append('"');
        }
        out.append('\n');
    }

    private void printOutline(PDOutlineNode node, int depth) {
        for (PDOutlineItem item : node.children()) {
            indent(depth).append(item.getTitle());
            COSDictionary dictionary = item.getCOSObject();
            COSBase destination = dictionary.getDictionaryObject(COSName.DEST);
            COSBase structureDestination = null;
            if (destination == null && dictionary.getDictionaryObject(COSName.A) instanceof COSDictionary action) {
                destination = action.getDictionaryObject(COSName.D);
                structureDestination = action.getDictionaryObject(SD);
            }
            out.append("  -> ").append(target(destination));
            appendStructureDestination(structureDestination);
            out.append('\n');
            printOutline(item, depth + 1);
        }
    }

    /** ", structure destination H2 "4.1 Signatures"" for a PDF 2.0 structure destination. */
    private void appendStructureDestination(COSBase structureDestination) {
        if (structureDestination instanceof COSArray array && array.size() > 0
                && array.getObject(0) instanceof COSDictionary element) {
            PDStructureElement target = new PDStructureElement(element);
            String text = firstContent(target);
            out.append(", structure destination ").append(target.getStructureType()).append(text == null ? "" : " " + text);
        }
    }

    /** "page 3" for a page destination, the name for a named destination. */
    private String target(COSBase destination) {
        if (destination instanceof COSArray array && array.size() > 0 && array.getObject(0) instanceof COSDictionary page) {
            int index = document.getPages().indexOf(new PDPage(page));
            return index < 0 ? "a page outside the document" : "page " + (index + 1);
        }
        if (destination instanceof COSString name) {
            return "named destination \"" + name.getString() + "\"";
        }
        if (destination instanceof COSName name) {
            return "named destination \"" + name.getName() + "\"";
        }
        return "(no destination)";
    }

    /** The text of the first marked content inside a structure element, or {@code null}. */
    private String firstContent(PDStructureElement element) {
        int pageIndex = element.getPage() == null ? -1 : document.getPages().indexOf(element.getPage());
        for (Object kid : element.getKids()) {
            if (kid instanceof Integer mcid && pageIndex >= 0) {
                return content.get(pageIndex).get(mcid);
            }
            if (kid instanceof PDMarkedContentReference reference) {
                int page = reference.getPage() == null ? pageIndex : document.getPages().indexOf(reference.getPage());
                return page < 0 ? null : content.get(page).get(reference.getMCID());
            }
            if (kid instanceof PDStructureElement child) {
                String text = firstContent(child);
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    /** "PDF/UA-2 (revision 2024), PDF 2.0", read from the XMP identification and the version. */
    private String conformance(PDDocumentCatalog catalog) throws IOException {
        String claim = "no PDF/UA identification";
        if (catalog.getMetadata() != null) {
            String xmp = new String(catalog.getMetadata().toByteArray(), StandardCharsets.UTF_8);
            Matcher part = PART.matcher(xmp);
            if (part.find()) {
                Matcher revision = REVISION.matcher(xmp);
                claim = "PDF/UA-" + part.group(1) + (revision.find() ? " (revision " + revision.group(1) + ")" : "");
            }
        }
        return claim + ", PDF " + document.getVersion();
    }

    private static String namespaceLabel(String namespace) {
        if (StructureTypes.PDF_2_0_NAMESPACE.equals(namespace)) {
            return "PDF 2.0";
        }
        return StructureTypes.PDF_1_7_NAMESPACE.equals(namespace) ? "PDF 1.7" : String.valueOf(namespace);
    }

    private StringBuilder indent(int depth) {
        return out.append("  ".repeat(depth));
    }

    /** Text of a marked-content sequence, or a placeholder when it only holds graphics. */
    private static String describe(PDMarkedContent markedContent) {
        StringBuilder text = new StringBuilder();
        boolean image = false;
        for (Object item : markedContent.getContents()) {
            if (item instanceof TextPosition position) {
                text.append(position.getUnicode());
            } else if (item instanceof PDXObject) {
                image = true;
            } else if (item instanceof PDMarkedContent nested) {
                text.append(describe(nested));
            }
        }
        if (!text.isEmpty()) {
            return '"' + text.toString() + '"';
        }
        return image ? "(image)" : "(graphics)";
    }
}
