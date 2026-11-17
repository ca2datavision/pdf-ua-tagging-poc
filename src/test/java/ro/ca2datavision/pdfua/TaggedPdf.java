package ro.ca2datavision.pdfua;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.TextPosition;

/**
 * Test helper that reads a tagged PDF back with plain PDFBox, independently of the code that
 * wrote it: the structure tree in reading order and the marking of every painting operator.
 */
final class TaggedPdf {

    /**
     * A structure element.
     *
     * @param page        1-based page of the element's content, 0 for grouping elements
     * @param text        the text of the element including its descendants
     * @param annotations the annotations referenced directly by the element
     * @param element     the structure element dictionary itself
     */
    record Node(String type, String parentType, int page, String alt, String text, List<COSDictionary> annotations,
                List<Node> children, COSDictionary element) {

        /** An attribute from one of the attribute objects of the element (Scope, ColSpan, ListNumbering, ...). */
        String attribute(String name) {
            COSBase attributes = element.getDictionaryObject(COSName.A);
            List<COSBase> objects = new ArrayList<>();
            if (attributes instanceof COSArray array) {
                for (int i = 0; i < array.size(); i++) {
                    objects.add(array.getObject(i));
                }
            } else if (attributes != null) {
                objects.add(attributes);
            }
            for (COSBase object : objects) {
                if (object instanceof COSDictionary dictionary && dictionary.containsKey(COSName.getPDFName(name))) {
                    COSBase value = dictionary.getDictionaryObject(COSName.getPDFName(name));
                    return value instanceof COSName n ? n.getName()
                            : value instanceof COSNumber number ? String.valueOf(number.intValue())
                            : value instanceof COSString string ? string.getString() : String.valueOf(value);
                }
            }
            return null;
        }

        /** The name of the namespace the element belongs to, or {@code null} if it has none (PDF 1.7 default). */
        String namespace() {
            COSBase namespace = element.getDictionaryObject(COSName.getPDFName("NS"));
            return namespace instanceof COSDictionary dictionary ? dictionary.getString(COSName.getPDFName("NS")) : null;
        }

        /** A text entry of the element itself, such as Lang, ActualText or ID. */
        String entry(String name) {
            return element.getString(COSName.getPDFName(name));
        }

        /** Number of columns the cell covers. */
        int colSpan() {
            return attribute("ColSpan") == null ? 1 : Integer.parseInt(attribute("ColSpan"));
        }

        int rowSpan() {
            return attribute("RowSpan") == null ? 1 : Integer.parseInt(attribute("RowSpan"));
        }

        List<Node> children(String childType) {
            return children.stream().filter(child -> child.type().equals(childType)).toList();
        }

        /** This element and everything below it, depth first. */
        List<Node> subtree() {
            List<Node> all = new ArrayList<>();
            all.add(this);
            children.forEach(child -> all.addAll(child.subtree()));
            return all;
        }
    }

    /**
     * A painting operator of a page content stream.
     *
     * @param tags       the enclosing marked-content tags, outermost first
     * @param properties the property list of the innermost enclosing marked-content sequence
     */
    record Painted(int page, String operator, String operand, List<String> tags, boolean artifact, boolean tagged,
                   COSDictionary properties) {
        boolean isPath() {
            return PATH_OPERATORS.contains(operator);
        }
    }

    private static final Set<String> PATH_OPERATORS = Set.of("S", "s", "f", "F", "f*", "B", "B*", "b", "b*");
    private static final Set<String> TEXT_OPERATORS = Set.of("Tj", "TJ", "'", "\"");

    private final PDDocument document;
    private final List<Map<Integer, String>> textByMcid = new ArrayList<>();

    TaggedPdf(PDDocument document) throws IOException {
        this.document = document;
        for (PDPage page : document.getPages()) {
            Map<Integer, String> texts = new HashMap<>();
            PDFMarkedContentExtractor extractor = new PDFMarkedContentExtractor();
            extractor.processPage(page);
            for (PDMarkedContent markedContent : extractor.getMarkedContents()) {
                if (markedContent.getMCID() >= 0) {
                    StringBuilder text = new StringBuilder();
                    for (Object item : markedContent.getContents()) {
                        if (item instanceof TextPosition position) {
                            text.append(position.getUnicode());
                        }
                    }
                    texts.put(markedContent.getMCID(), text.toString());
                }
            }
            textByMcid.add(texts);
        }
    }

    /** The children of the Document element. */
    List<Node> topLevel() {
        PDStructureTreeRoot root = document.getDocumentCatalog().getStructureTreeRoot();
        PDStructureElement documentElement = (PDStructureElement) root.getKids().get(0);
        return read(documentElement, null).children();
    }

    /** All structure elements below the Document element, depth first: the reading order. */
    List<Node> readingOrder() {
        List<Node> all = new ArrayList<>();
        collect(topLevel(), all);
        return all;
    }

    private static void collect(List<Node> nodes, List<Node> all) {
        for (Node node : nodes) {
            all.add(node);
            collect(node.children(), all);
        }
    }

    private Node read(PDStructureElement element, String parentType) {
        int page = element.getPage() == null ? 0 : document.getPages().indexOf(element.getPage()) + 1;
        StringBuilder text = new StringBuilder();
        List<COSDictionary> annotations = new ArrayList<>();
        List<Node> children = new ArrayList<>();
        for (Object kid : element.getKids()) {
            if (kid instanceof PDStructureElement child) {
                Node node = read(child, element.getStructureType());
                children.add(node);
                text.append(node.text());
            } else if (kid instanceof Integer mcid) {
                text.append(textByMcid.get(page - 1).getOrDefault(mcid, ""));
            } else if (kid instanceof PDMarkedContentReference reference) {
                int referencedPage = reference.getPage() == null ? page : document.getPages().indexOf(reference.getPage()) + 1;
                text.append(textByMcid.get(referencedPage - 1).getOrDefault(reference.getMCID(), ""));
            } else if (kid instanceof PDObjectReference reference) {
                annotations.add(reference.getCOSObject().getCOSDictionary(COSName.getPDFName("Obj")));
            }
        }
        return new Node(element.getStructureType(), parentType, page, element.getAlternateDescription(),
                text.toString(), annotations, children, element.getCOSObject());
    }

    /** Every operator that puts marks on a page, with the marked content it sits in. */
    List<Painted> painted() throws IOException {
        List<Painted> result = new ArrayList<>();
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            Deque<String> tags = new ArrayDeque<>();
            Deque<COSDictionary> properties = new ArrayDeque<>();
            List<COSBase> operands = new ArrayList<>();
            for (Object token : new PDFStreamParser(page).parse()) {
                if (!(token instanceof Operator operator)) {
                    operands.add((COSBase) token);
                    continue;
                }
                String name = operator.getName();
                if ("BMC".equals(name) || "BDC".equals(name)) {
                    tags.addLast(((COSName) operands.get(0)).getName());
                    properties.addLast(operands.size() > 1 && operands.get(1) instanceof COSDictionary dictionary
                            ? dictionary : new COSDictionary());
                } else if ("EMC".equals(name)) {
                    tags.removeLast();
                    properties.removeLast();
                } else if (PATH_OPERATORS.contains(name) || TEXT_OPERATORS.contains(name)
                        || "Do".equals(name) || "sh".equals(name) || "BI".equals(name)) {
                    boolean artifact = tags.contains("Artifact");
                    boolean tagged = properties.stream().anyMatch(p -> p.containsKey(COSName.MCID));
                    String operand = "Do".equals(name) ? ((COSName) operands.get(0)).getName() : null;
                    result.add(new Painted(pageNumber, name, operand, List.copyOf(tags), artifact, tagged,
                            properties.isEmpty() ? null : properties.getLast()));
                }
                operands.clear();
            }
            if (!tags.isEmpty()) {
                throw new IllegalStateException("Unbalanced marked content on page " + pageNumber);
            }
        }
        return result;
    }
}
