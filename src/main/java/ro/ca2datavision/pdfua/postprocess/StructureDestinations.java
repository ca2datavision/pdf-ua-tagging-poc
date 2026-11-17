package ro.ca2datavision.pdfua.postprocess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

import ro.ca2datavision.pdfua.layout.DocumentLayout;
import ro.ca2datavision.pdfua.layout.LayoutElement;
import ro.ca2datavision.pdfua.layout.PageLayout;

/**
 * PDF/UA-2 (ISO 14289-2, 8.8): every destination inside the document is a structure
 * destination, so that a reader who follows a link or a bookmark arrives at an element of the
 * logical structure, not just at a place on a page.
 *
 * <p>A structure destination (ISO 32000-2:2020, 12.3.2.3) has the form of an explicit
 * destination with a structure element where the page would be. It goes into the SD entry of a
 * go-to action (12.6.4.2), next to the page destination in D, which readers without PDF 2.0
 * support keep using. Link annotations and outline items that have a plain destination (Dest)
 * get such an action instead; the same is done for the open action of the document.</p>
 *
 * <p>The structure element is found exactly when the destination is the anchor of a report
 * element: JasperReports writes one named destination per anchor, and the layout knows which
 * element carries which anchor. Any other destination inside the document, such as a link to a
 * page, leads to the first structure element in reading order that starts at or below the
 * destination point on that page.</p>
 */
final class StructureDestinations {

    private static final COSName SD = COSName.getPDFName("SD");
    private static final COSName GOTO = COSName.getPDFName("GoTo");
    private static final COSName ACTION = COSName.getPDFName("Action");
    private static final COSName XYZ = COSName.getPDFName("XYZ");
    private static final float TOLERANCE = 1.5f;

    private final PDDocument document;
    private final DocumentLayout layout;
    private final StructureBuilder structure;
    private final PostProcessingReport report;

    /** Layout elements by the name of their anchor. */
    private final Map<String, LayoutElement> anchors = new HashMap<>();
    /** Named destinations: the destination array by name, and the name by array (identity). */
    private final Map<String, COSArray> destinationsByName = new HashMap<>();
    private final Map<COSBase, String> namesByDestination = new IdentityHashMap<>();

    StructureDestinations(PDDocument document, DocumentLayout layout, StructureBuilder structure, PostProcessingReport report) {
        this.document = document;
        this.layout = layout;
        this.structure = structure;
        this.report = report;
    }

    /**
     * The structure destination that corresponds to a page destination: the same array, with the
     * structure element in place of the page.
     */
    static COSArray structureDestination(COSDictionary element, COSArray pageDestination) {
        COSArray destination = new COSArray();
        destination.add(element);
        for (int i = 1; i < pageDestination.size(); i++) {
            destination.add(pageDestination.get(i));
        }
        return destination;
    }

    /** A go-to action with a page destination for older readers and a structure destination. */
    static COSDictionary goTo(COSBase destination, COSArray structureDestination) {
        COSDictionary action = new COSDictionary();
        action.setItem(COSName.TYPE, ACTION);
        action.setItem(COSName.S, GOTO);
        action.setItem(COSName.D, destination);
        action.setItem(SD, structureDestination);
        return action;
    }

    void apply() throws IOException {
        for (PageLayout page : layout.pages()) {
            collectAnchors(page.elements());
        }
        collectNamedDestinations(document.getDocumentCatalog());

        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationLink) {
                    convert(annotation.getCOSObject(), "link on page " + pageNumber);
                }
            }
        }

        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline != null) {
            convertOutline(outline);
        }
        convertOpenAction(document.getDocumentCatalog().getCOSObject());
    }

    private void collectAnchors(List<LayoutElement> elements) {
        for (LayoutElement element : elements) {
            if (element.anchor() != null) {
                anchors.putIfAbsent(element.anchor(), element);
            }
            collectAnchors(element.children());
        }
    }

    private void collectNamedDestinations(PDDocumentCatalog catalog) throws IOException {
        PDDocumentNameDictionary names = catalog.getNames();
        if (names != null && names.getDests() != null) {
            collect(names.getDests());
        }
        // the older form: a dictionary in the catalog, names as keys
        COSDictionary dests = catalog.getCOSObject().getCOSDictionary(COSName.DESTS);
        if (dests != null) {
            for (COSName key : dests.keySet()) {
                COSBase value = dests.getDictionaryObject(key);
                if (value instanceof COSDictionary dictionary) {
                    value = dictionary.getDictionaryObject(COSName.D);
                }
                if (value instanceof COSArray array) {
                    remember(key.getName(), array);
                }
            }
        }
    }

    private void collect(PDNameTreeNode<PDPageDestination> node) throws IOException {
        Map<String, PDPageDestination> entries = node.getNames();
        if (entries != null) {
            entries.forEach((name, destination) -> remember(name, destination.getCOSObject()));
        }
        List<PDNameTreeNode<PDPageDestination>> kids = node.getKids();
        if (kids != null) {
            for (PDNameTreeNode<PDPageDestination> kid : kids) {
                collect(kid);
            }
        }
    }

    private void remember(String name, COSArray destination) {
        destinationsByName.putIfAbsent(name, destination);
        namesByDestination.putIfAbsent(destination, name);
    }

    private void convertOutline(PDOutlineNode node) {
        for (PDOutlineItem item : node.children()) {
            convert(item.getCOSObject(), "bookmark \"" + item.getTitle() + "\"");
            convertOutline(item);
        }
    }

    /** Link annotations and outline items: a Dest entry, or a go-to action in A. */
    private void convert(COSDictionary holder, String what) {
        COSBase destination = holder.getDictionaryObject(COSName.DEST);
        if (destination != null) {
            COSArray target = structureDestinationFor(destination, what);
            if (target != null) {
                // keep the original destination, possibly an indirect object, as D
                holder.setItem(COSName.A, goTo(holder.getItem(COSName.DEST), target));
                holder.removeItem(COSName.DEST);
            }
            return;
        }
        if (holder.getDictionaryObject(COSName.A) instanceof COSDictionary action) {
            addStructureDestination(action, what);
        }
    }

    private void convertOpenAction(COSDictionary catalog) {
        COSBase openAction = catalog.getDictionaryObject(COSName.OPEN_ACTION);
        if (openAction instanceof COSArray destination) {
            COSArray target = structureDestinationFor(destination, "open action");
            if (target != null) {
                catalog.setItem(COSName.OPEN_ACTION, goTo(catalog.getItem(COSName.OPEN_ACTION), target));
            }
        } else if (openAction instanceof COSDictionary action) {
            addStructureDestination(action, "open action");
        }
    }

    private void addStructureDestination(COSDictionary action, String what) {
        if (!GOTO.equals(action.getCOSName(COSName.S)) || action.containsKey(SD)) {
            return; // a link to the outside, or already done
        }
        COSBase destination = action.getDictionaryObject(COSName.D);
        if (destination != null) {
            COSArray target = structureDestinationFor(destination, what);
            if (target != null) {
                action.setItem(SD, target);
            }
        }
    }

    /** The structure destination for a destination inside the document, or {@code null} with a warning. */
    private COSArray structureDestinationFor(COSBase destination, String what) {
        String name = null;
        COSArray explicit = null;
        if (destination instanceof COSString string) {
            name = string.getString();
            explicit = destinationsByName.get(name);
        } else if (destination instanceof COSName key) {
            name = key.getName();
            explicit = destinationsByName.get(name);
        } else if (destination instanceof COSArray array) {
            explicit = array;
            name = namesByDestination.get(array);
        }

        COSDictionary element = null;
        LayoutElement anchored = name == null ? null : anchors.get(name);
        if (anchored != null) {
            element = structure.elementOf(anchored.id());
        }
        if (element == null && explicit != null) {
            element = firstElementFrom(explicit);
        }
        if (element == null) {
            report.warn("PDF/UA-2: the " + what + " leads to " + (name != null ? "\"" + name + "\"" : "a place")
                    + " that no structure element could be found for; it has no structure destination");
            return null;
        }
        report.structureDestinations++;
        if (explicit != null) {
            return structureDestination(element, explicit);
        }
        COSArray xyz = new COSArray();
        xyz.add(COSNull.NULL); // replaced by the element
        xyz.add(XYZ);
        xyz.add(new COSFloat(anchored.box().llx()));
        xyz.add(new COSFloat(anchored.box().ury()));
        xyz.add(COSNull.NULL);
        return structureDestination(element, xyz);
    }

    /**
     * The first structure element, in reading order, that starts at or below the point of a page
     * destination; for a destination without a vertical position, the first one on the page.
     */
    private COSDictionary firstElementFrom(COSArray explicit) {
        if (explicit.size() < 2 || !(explicit.getObject(0) instanceof COSDictionary pageDictionary)) {
            return null;
        }
        int pageIndex = document.getPages().indexOf(new PDPage(pageDictionary));
        if (pageIndex < 0 || pageIndex >= layout.pages().size()) {
            return null;
        }
        String type = explicit.getObject(1) instanceof COSName name ? name.getName() : "";
        Float top = switch (type) {
            case "XYZ" -> number(explicit, 3);
            case "FitH", "FitBH" -> number(explicit, 2);
            case "FitR" -> number(explicit, 5);
            default -> null;
        };
        List<LayoutElement> inReadingOrder = new ArrayList<>();
        flatten(layout.pages().get(pageIndex).elements(), inReadingOrder);
        for (LayoutElement element : inReadingOrder) {
            COSDictionary structElement = structure.elementOf(element.id());
            if (structElement != null && (top == null || element.box().ury() <= top + TOLERANCE)) {
                return structElement;
            }
        }
        return null;
    }

    private static void flatten(List<LayoutElement> elements, List<LayoutElement> into) {
        for (LayoutElement element : elements) {
            into.add(element);
            flatten(element.children(), into);
        }
    }

    private static Float number(COSArray array, int index) {
        return index < array.size() && array.getObject(index) instanceof COSNumber number ? number.floatValue() : null;
    }
}
