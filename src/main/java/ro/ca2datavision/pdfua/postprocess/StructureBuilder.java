package ro.ca2datavision.pdfua.postprocess;

import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;

import ro.ca2datavision.pdfua.layout.Attributes;
import ro.ca2datavision.pdfua.layout.Roles;

/**
 * Builds the logical structure of the document: the tree of structure elements and the parent
 * tree that leads from page content (marked-content identifiers) and annotations back to the
 * structure element they belong to.
 *
 * <p>The tree is written as plain COS dictionaries, which keeps the result easy to compare
 * with ISO 32000-1, 14.7 "Logical structure".</p>
 *
 * <p>For PDF/UA-2 every structure element also gets a namespace, and some types are written
 * differently; see {@link StructureTypes}.</p>
 */
final class StructureBuilder {

    private static final COSName STRUCT_ELEM = COSName.getPDFName("StructElem");
    private static final COSName OBJR = COSName.getPDFName("OBJR");
    private static final COSName OBJ = COSName.getPDFName("Obj");
    private static final COSName STRUCT_PARENT = COSName.getPDFName("StructParent");
    private static final COSName STRUCT_PARENTS = COSName.getPDFName("StructParents");
    private static final COSName NS = COSName.getPDFName("NS");
    private static final COSName NAMESPACE = COSName.getPDFName("Namespace");
    private static final COSName NAMESPACES = COSName.getPDFName("Namespaces");

    private final PdfUaPart part;
    private final COSDictionary root = new COSDictionary();
    private final COSDictionary document;
    private final COSDictionary roleMap = new COSDictionary();
    /** Parent tree entries: key = /StructParents of a page or /StructParent of an annotation. */
    private final Map<Integer, COSBase> parentTree = new TreeMap<>();
    private final Map<PDPage, COSArray> contentParents = new IdentityHashMap<>();
    /** Structure elements that have parts on several pages, by the id the layout gives them. */
    private final Map<String, COSDictionary> shared = new HashMap<>();
    /** Elements with an /ID entry; they need the ID tree. */
    private final TreeMap<String, COSDictionary> identified = new TreeMap<>();
    /** Namespace dictionaries by namespace name, in the order of first use (PDF/UA-2 only). */
    private final Map<String, COSDictionary> namespaces = new LinkedHashMap<>();
    /** The structure element made for each layout element, by the id of the layout element. */
    private final Map<String, COSDictionary> byLayoutId = new HashMap<>();
    private int nextKey;

    StructureBuilder(PdfUaPart part) {
        this.part = part;
        root.setItem(COSName.TYPE, COSName.STRUCT_TREE_ROOT);
        document = newElement(Roles.DOCUMENT, null);
        document.setItem(COSName.P, root);
        COSArray kids = new COSArray();
        kids.add(document);
        root.setItem(COSName.K, kids);
    }

    /** The part of PDF/UA the structure is written for. */
    PdfUaPart part() {
        return part;
    }

    /** The Document element, parent of everything else. */
    COSDictionary document() {
        return document;
    }

    /**
     * Creates a structure element that is not attached to the tree yet.
     *
     * @param role the role from the layout, which is a PDF 1.7 structure type or {@code Title};
     *             for PDF/UA-2 it is translated by {@link StructureTypes#forPdfUa2(String)}
     * @param page the page that holds the content of the element, or {@code null} for an
     *             element that only groups other elements
     */
    COSDictionary newElement(String role, PDPage page) {
        COSDictionary element = new COSDictionary();
        element.setItem(COSName.TYPE, STRUCT_ELEM);
        if (part == PdfUaPart.PDF_UA_2) {
            StructureTypes.Type type = StructureTypes.forPdfUa2(role);
            element.setName(COSName.S, type.name());
            element.setItem(NS, namespace(type.namespace()));
            String fallback = StructureTypes.PDF_1_7_FALLBACK.get(type.name());
            if (fallback != null && StructureTypes.PDF_2_0_NAMESPACE.equals(type.namespace())) {
                // for readers without PDF 2.0 support only; see StructureTypes
                roleMap.setName(type.name(), fallback);
            }
        } else {
            element.setName(COSName.S, role);
            if (Roles.CUSTOM.containsKey(role)) {
                roleMap.setName(role, Roles.CUSTOM.get(role));
            }
        }
        if (page != null) {
            element.setItem(COSName.PG, page);
        }
        if (Roles.NOTE.equals(role)) {
            // PDF/UA-1 7.9: every Note has an ID that is unique in the document. A PDF/UA-2
            // FENote keeps it: it costs nothing and lets other elements refer to the note.
            String id = String.format("note-%04d", identified.size() + 1);
            element.setItem(COSName.ID, new COSString(id));
            identified.put(id, element);
        }
        return element;
    }

    /** The namespace dictionary for a namespace name; one indirect object per namespace. */
    private COSDictionary namespace(String name) {
        return namespaces.computeIfAbsent(name, key -> {
            COSDictionary namespace = new COSDictionary();
            namespace.setItem(COSName.TYPE, NAMESPACE);
            namespace.setString(NS, key);
            return namespace;
        });
    }

    /** Remembers which structure element represents a layout element. */
    void register(String layoutId, COSDictionary element) {
        byLayoutId.putIfAbsent(layoutId, element);
    }

    /** The structure element that represents a layout element, or {@code null} if it has none. */
    COSDictionary elementOf(String layoutId) {
        return byLayoutId.get(layoutId);
    }

    /** The structure element already created for parts with this id, or {@code null}. */
    COSDictionary shared(String structureId) {
        return shared.get(structureId);
    }

    void share(String structureId, COSDictionary element) {
        shared.put(structureId, element);
    }

    /**
     * Writes the attributes of the layout element: entries of the structure element itself
     * (language, replacement text) and attribute objects with the owners Table and List.
     */
    void applyAttributes(COSDictionary element, Map<String, String> attributes) {
        COSDictionary table = new COSDictionary();
        COSDictionary list = new COSDictionary();
        attributes.forEach((name, value) -> {
            switch (name) {
                case Attributes.LANG -> element.setString(COSName.LANG, value);
                case Attributes.ACTUAL_TEXT -> element.setString(COSName.ACTUAL_TEXT, value);
                case Attributes.SCOPE -> table.setName(name, value);
                case Attributes.COL_SPAN, Attributes.ROW_SPAN -> table.setInt(name, Integer.parseInt(value.trim()));
                case Attributes.SUMMARY -> table.setString(name, value);
                case Attributes.LIST_NUMBERING -> list.setName(name, value);
                default -> throw new IllegalArgumentException("Unknown attribute in the layout: " + name);
            }
        });
        if (table.size() > 0) {
            table.setName(COSName.O, "Table");
            addAttributeObject(element, table);
        }
        if (list.size() > 0) {
            list.setName(COSName.O, "List");
            addAttributeObject(element, list);
        }
    }

    /** /A holds one attribute object or an array of them. */
    private static void addAttributeObject(COSDictionary element, COSDictionary attributeObject) {
        COSBase existing = element.getDictionaryObject(COSName.A);
        if (existing == null) {
            element.setItem(COSName.A, attributeObject);
        } else if (existing instanceof COSArray array) {
            array.add(attributeObject);
        } else {
            COSArray array = new COSArray();
            array.add(existing);
            array.add(attributeObject);
            element.setItem(COSName.A, array);
        }
    }

    void append(COSDictionary parent, COSDictionary child) {
        child.setItem(COSName.P, parent);
        kids(parent).add(child);
    }

    boolean hasKids(COSDictionary element) {
        COSArray kids = element.getCOSArray(COSName.K);
        return kids != null && kids.size() > 0;
    }

    /** Makes the marked-content sequence {@code mcid} of the page a child of the element. */
    void addContent(COSDictionary element, PDPage page, int mcid) {
        // an integer kid refers to marked content on the page named by /Pg of the element
        element.setItem(COSName.PG, page);
        kids(element).add(COSInteger.get(mcid));

        COSArray parents = contentParents.computeIfAbsent(page, p -> {
            COSArray array = new COSArray();
            int key = nextKey++;
            p.getCOSObject().setInt(STRUCT_PARENTS, key);
            parentTree.put(key, array);
            return array;
        });
        while (parents.size() <= mcid) {
            parents.add(COSNull.NULL);
        }
        parents.set(mcid, element);
    }

    /** Makes an annotation (link, form field widget) a child of the element. */
    void addAnnotation(COSDictionary element, PDPage page, PDAnnotation annotation) {
        COSDictionary reference = new COSDictionary();
        reference.setItem(COSName.TYPE, OBJR);
        reference.setItem(OBJ, annotation.getCOSObject());
        reference.setItem(COSName.PG, page);
        kids(element).add(reference);

        int key = nextKey++;
        annotation.getCOSObject().setInt(STRUCT_PARENT, key);
        parentTree.put(key, element);
    }

    void setAlternateDescription(COSDictionary element, String alt) {
        element.setString(COSName.ALT, alt);
    }

    /** Layout attributes of a figure; validators expect a bounding box. */
    void setBoundingBox(COSDictionary element, Rectangle2D box) {
        COSArray bbox = new COSArray();
        bbox.add(new COSFloat((float) box.getMinX()));
        bbox.add(new COSFloat((float) box.getMinY()));
        bbox.add(new COSFloat((float) box.getMaxX()));
        bbox.add(new COSFloat((float) box.getMaxY()));
        COSDictionary attributes = new COSDictionary();
        attributes.setName(COSName.O, "Layout");
        attributes.setItem(COSName.BBOX, bbox);
        attributes.setName(COSName.getPDFName("Placement"), "Block");
        addAttributeObject(element, attributes);
    }

    /** Installs the finished tree in the document catalog, replacing any existing one. */
    void install(PDDocumentCatalog catalog) {
        COSArray nums = new COSArray();
        parentTree.forEach((key, value) -> {
            nums.add(COSInteger.get(key));
            nums.add(value);
        });
        COSDictionary parentTreeRoot = new COSDictionary();
        parentTreeRoot.setItem(COSName.NUMS, nums);
        root.setItem(COSName.PARENT_TREE, parentTreeRoot);
        root.setInt(COSName.PARENT_TREE_NEXT_KEY, nextKey);
        if (roleMap.size() > 0) {
            root.setItem(COSName.ROLE_MAP, roleMap);
        }
        if (!namespaces.isEmpty()) {
            // ISO 32000-2 14.7.2: all namespaces used in the structure tree
            COSArray used = new COSArray();
            namespaces.values().forEach(used::add);
            root.setItem(NAMESPACES, used);
        }
        if (!identified.isEmpty()) {
            // ISO 32000-1 14.7.2: name tree from element identifiers to the elements
            COSArray names = new COSArray();
            identified.forEach((id, element) -> {
                names.add(new COSString(id));
                names.add(element);
            });
            COSDictionary idTree = new COSDictionary();
            idTree.setItem(COSName.NAMES, names);
            root.setItem(COSName.getPDFName("IDTree"), idTree);
        }
        catalog.setStructureTreeRoot(new PDStructureTreeRoot(root));
    }

    private static COSArray kids(COSDictionary element) {
        COSArray kids = element.getCOSArray(COSName.K);
        if (kids == null) {
            kids = new COSArray();
            element.setItem(COSName.K, kids);
        }
        return kids;
    }
}
