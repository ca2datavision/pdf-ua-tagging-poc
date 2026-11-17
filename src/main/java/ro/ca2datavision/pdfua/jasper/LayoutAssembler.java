package ro.ca2datavision.pdfua.jasper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ro.ca2datavision.pdfua.layout.ArtifactInfo;
import ro.ca2datavision.pdfua.layout.Attributes;
import ro.ca2datavision.pdfua.layout.Box;
import ro.ca2datavision.pdfua.layout.ElementKind;
import ro.ca2datavision.pdfua.layout.LayoutElement;
import ro.ca2datavision.pdfua.layout.Roles;
import ro.ca2datavision.pdfua.layout.SignatureInfo;

/**
 * Puts the elements of a filled report into the nesting that tables and lists need.
 *
 * <p>A report is a flat sequence of elements: the cells of a table are siblings of the
 * paragraph before it, and a table that runs over three pages is three unrelated runs of cells
 * with page headers in between. A tagged PDF needs {@code Table > TR > TH/TD} and
 * {@code L > LI > Lbl/LBody}, each table and list exactly once. The assembler reads the
 * elements of all pages in order and builds that nesting, in three ways that can be mixed:</p>
 *
 * <ul>
 *   <li><b>Frames with a role</b> are groups as they are.</li>
 *   <li><b>Start and end marks</b> on elements open and close groups; this is the tagging
 *       protocol of JasperReports ({@code net.sf.jasperreports.export.pdf.tag.table=start}, ...),
 *       which its table component writes by itself.</li>
 *   <li><b>Missing parents are added.</b> A cell that is not in a row gets one: cells at the
 *       same height form a row, consecutive rows form a table. A list label or body that is not
 *       in an item gets one, consecutive items form a list. A group ends by itself when an
 *       element arrives that cannot be its child (a paragraph after the last row).</li>
 * </ul>
 *
 * <p>Groups stay open across page breaks, so a table that continues on the next page is one
 * table; its parts share a structure id. Header rows that the report repeats at the top of the
 * continuation are recognised by their text and become artifacts.</p>
 */
final class LayoutAssembler {

    /** A layout element under construction: a report element, or one page's part of a group. */
    static final class Node {
        String id;
        ElementKind kind;
        String role;
        Box box;
        String text;
        String alt;
        ArtifactInfo artifact;
        SignatureInfo signature;
        String anchor;
        String structureId;
        final Map<String, String> attributes = new LinkedHashMap<>();
        final List<Node> children = new ArrayList<>();

        // ---- input of the assembly, not part of the layout ----
        int page;
        /** Identifies the template element this comes from; equal for the parts of a split frame. */
        int sourceId;
        /** Roles of groups that start with this element, outermost first. */
        final List<String> opens = new ArrayList<>();
        /** Roles of groups that end with this element, innermost first. */
        final List<String> closes = new ArrayList<>();
        /** Optional name that keeps two adjacent tables (or lists) apart. */
        String tableName;
        String listName;
        /** Attributes for the enclosing table or list. */
        String tableSummary;
        String listNumbering;

        private Group group;

        boolean isArtifact() {
            return Roles.ARTIFACT.equals(role);
        }

        LayoutElement toLayout() {
            List<LayoutElement> kids = children.stream().map(Node::toLayout).toList();
            Map<String, String> allAttributes = new LinkedHashMap<>(attributes);
            if (group != null) {
                allAttributes.putAll(group.attributes);
            }
            return new LayoutElement(id, kind, role, box != null ? box : enclosing(kids), text, alt, artifact, signature,
                    anchor, structureId, allAttributes, kids);
        }

        private static Box enclosing(List<LayoutElement> elements) {
            float llx = Float.MAX_VALUE, lly = Float.MAX_VALUE, urx = -Float.MAX_VALUE, ury = -Float.MAX_VALUE;
            for (LayoutElement element : elements) {
                llx = Math.min(llx, element.box().llx());
                lly = Math.min(lly, element.box().lly());
                urx = Math.max(urx, element.box().urx());
                ury = Math.max(ury, element.box().ury());
            }
            return elements.isEmpty() ? new Box(0, 0, 0, 0) : new Box(llx, lly, urx, ury);
        }
    }

    /** A structure element that groups others. It has one part ({@link Node}) per page it appears on. */
    private static final class Group {
        final String role;
        /** Added by the assembler because a child needed it; ends as soon as something does not fit. */
        final boolean automatic;
        /** The report frame that is this group, if it is one. */
        final Node frame;
        final List<Node> parts = new ArrayList<>();
        final Map<String, String> attributes = new LinkedHashMap<>();
        int sourceId;
        String name;

        // row
        int rowPage;
        float rowTop = Float.NaN;
        // table
        final List<List<String>> headerRows = new ArrayList<>();
        boolean dataRowSeen;
        int firstPage;
        int currentPage;
        int rowsOnCurrentPage;
        boolean headerRepetitionOver;
        // list item, list
        boolean hasLabel;
        boolean hasBody;
        String firstLabel;

        Group(String role, boolean automatic, Node frame) {
            this.role = role;
            this.automatic = automatic;
            this.frame = frame;
        }
    }

    /** An entry of the nesting stack: a group, or a report frame without a role that only holds elements. */
    private record Scope(Group group, Node frame) {
    }

    private static final Map<String, Set<String>> CHILDREN = Map.of(
            Roles.TABLE, Set.of(Roles.TR, "THead", "TBody", "TFoot", Roles.CAPTION),
            "THead", Set.of(Roles.TR),
            "TBody", Set.of(Roles.TR),
            "TFoot", Set.of(Roles.TR),
            Roles.TR, Set.of(Roles.TH, Roles.TD),
            Roles.L, Set.of(Roles.LI, Roles.CAPTION),
            Roles.LI, Set.of(Roles.LBL, Roles.LBODY));

    /** Structure types that only exist inside one of these parents; the first one is added when missing. */
    private static final Map<String, List<String>> PARENTS = Map.of(
            Roles.TH, List.of(Roles.TR),
            Roles.TD, List.of(Roles.TR),
            Roles.TR, List.of(Roles.TABLE, "THead", "TBody", "TFoot"),
            "THead", List.of(Roles.TABLE),
            "TBody", List.of(Roles.TABLE),
            "TFoot", List.of(Roles.TABLE),
            Roles.LBL, List.of(Roles.LI),
            Roles.LBODY, List.of(Roles.LI),
            Roles.LI, List.of(Roles.L));

    private static final ArtifactInfo REPEATED_HEADER = new ArtifactInfo("Pagination", null);

    private final List<List<Node>> pages = new ArrayList<>();
    private final List<Scope> stack = new ArrayList<>();
    private int structureIds;
    private int groupIds;

    /** The group closed last, as long as nothing but artifacts followed: a split frame may continue it. */
    private Group lastClosed;
    /** A caption that was just placed; the table that follows takes it in. */
    private Node pendingCaption;
    private List<Node> pendingCaptionHolder;

    /** Adds the elements of the next page, in reading order. */
    void addPage(List<Node> elements) {
        pages.add(new ArrayList<>());
        for (Node element : elements) {
            place(element);
        }
    }

    /** Ends everything that is still open and returns the assembled elements of each page. */
    List<List<LayoutElement>> finish() {
        closeDownTo(0);
        return pages.stream().map(nodes -> nodes.stream().map(Node::toLayout).toList()).toList();
    }

    // ---- placing one element ---------------------------------------------------------------

    private void place(Node node) {
        if (node.isArtifact()) {
            // not part of the structure: stays where it is and neither ends nor starts anything
            attach(node);
            return;
        }

        dropRepeatedStarts(node);
        String entry = entryRole(node);
        if (entry != null) {
            makeRoomFor(node, entry);
        }
        for (String role : node.opens) {
            open(new Group(role, false, null), node);
        }

        if (node.kind == ElementKind.FRAME) {
            placeFrame(node);
        } else {
            attach(node);
            placed(node);
        }

        for (String role : node.closes) {
            closeNearest(role);
        }
    }

    private void placeFrame(Node frame) {
        List<Node> children = new ArrayList<>(frame.children);
        frame.children.clear();

        Group group = null;
        if (frame.role != null) {
            group = continued(frame);
            if (group == null) {
                group = new Group(frame.role, false, frame);
                group.sourceId = frame.sourceId;
                group.name = Roles.TABLE.equals(frame.role) ? frame.tableName : frame.listName;
                group.firstPage = frame.page;
            } else {
                // the same frame, split by a page break: one structure element with two parts
                group.parts.forEach(part -> part.structureId = structureIdOf(part));
                frame.structureId = group.parts.get(0).structureId;
            }
            group.parts.add(frame);
            frame.group = group;
        }

        // attaching the frame must not make a caption before a table frame forget its table
        Node caption = pendingCaption;
        List<Node> captionHolder = pendingCaptionHolder;
        attach(frame);
        if (Roles.TABLE.equals(frame.role)) {
            pendingCaption = caption;
            pendingCaptionHolder = captionHolder;
        }
        int depth = stack.size();
        stack.add(new Scope(group, frame));
        if (group != null) {
            opened(group, frame);
        }
        placed(frame);
        for (Node child : children) {
            place(child);
        }
        closeDownTo(depth);
    }

    /** The group that a frame continues: a frame from the same template element, split over two pages. */
    private Group continued(Node frame) {
        Group candidate = lastClosed;
        if (candidate != null && candidate.frame != null && frame.sourceId != 0 && candidate.sourceId == frame.sourceId
                && candidate.role.equals(frame.role) && candidate.parts.get(candidate.parts.size() - 1).page < frame.page) {
            return candidate;
        }
        return null;
    }

    private String structureIdOf(Node part) {
        return part.structureId != null ? part.structureId : "s" + (++structureIds);
    }

    /**
     * A start mark on an element that the report prints again on every page (a column header)
     * does not start a second table while the first one is still open.
     */
    private void dropRepeatedStarts(Node node) {
        node.opens.removeIf(role -> {
            Group open = nearest(role);
            return (Roles.TABLE.equals(role) || Roles.L.equals(role))
                    && open != null && open.frame == null && !open.automatic && node.sourceId != 0 && open.sourceId == node.sourceId;
        });
    }

    /** The structure type with which the element enters the current group. */
    private static String entryRole(Node node) {
        if (!node.opens.isEmpty()) {
            return node.opens.get(0);
        }
        if (node.role != null) {
            return node.isArtifact() ? null : node.role;
        }
        for (Node child : node.children) {
            String role = entryRole(child);
            if (role != null) {
                return role;
            }
        }
        return null;
    }

    /** Ends the groups the element cannot be in, and adds the parents it needs. */
    private void makeRoomFor(Node node, String entry) {
        while (true) {
            int index = closableGroupIndex();
            if (index < 0) {
                break;
            }
            Group group = stack.get(index).group();
            if (!endsBefore(group, node, entry) && accepts(group, entry)) {
                break;
            }
            closeDownTo(index);
        }

        List<String> missing = new ArrayList<>();
        String role = entry;
        Group nearest = nearestGroup();
        while (!fits(nearest, role) && PARENTS.containsKey(role)) {
            role = PARENTS.get(role).get(0);
            missing.add(0, role);
        }
        for (String parent : missing) {
            open(new Group(parent, true, null), node);
        }
    }

    /** Whether the group ends before the element although the element could formally be its child. */
    private static boolean endsBefore(Group group, Node node, String entry) {
        if (Roles.TABLE.equals(group.role) && group.name != null && node.tableName != null && !group.name.equals(node.tableName)) {
            return true;
        }
        if (Roles.L.equals(group.role) && group.name != null && node.listName != null && !group.name.equals(node.listName)) {
            return true;
        }
        if (!group.automatic) {
            return false;
        }
        if (Roles.TR.equals(group.role) && Roles.isTableCell(entry)) {
            // cells of one row start at the same height
            return node.box == null || group.rowPage != node.page || Math.abs(group.rowTop - node.box.ury()) > 1f;
        }
        if (Roles.LI.equals(group.role)) {
            // an item has at most one label, followed by one body
            return Roles.LBL.equals(entry) ? group.hasLabel || group.hasBody : Roles.LBODY.equals(entry) && group.hasBody;
        }
        return false;
    }

    /** Whether the role, or a parent that can be added for it, may be a child of the group. */
    private static boolean accepts(Group group, String role) {
        String candidate = role;
        while (true) {
            if (fits(group, candidate)) {
                return true;
            }
            if (!PARENTS.containsKey(candidate)) {
                return false;
            }
            candidate = PARENTS.get(candidate).get(0);
        }
    }

    private static boolean fits(Group group, String role) {
        List<String> parents = PARENTS.get(role);
        if (parents != null) {
            return group != null && parents.contains(group.role);
        }
        Set<String> children = group == null ? null : CHILDREN.get(group.role);
        return children == null || children.contains(role);
    }

    // ---- the nesting stack -----------------------------------------------------------------

    private void open(Group group, Node first) {
        group.sourceId = group.sourceId != 0 ? group.sourceId : first.sourceId;
        group.name = Roles.TABLE.equals(group.role) ? first.tableName : Roles.L.equals(group.role) ? first.listName : null;
        group.firstPage = first.page;
        if (Roles.TR.equals(group.role) && first.box != null) {
            group.rowPage = first.page;
            group.rowTop = first.box.ury();
        }
        stack.add(new Scope(group, null));
        opened(group, first);
    }

    private void opened(Group group, Node first) {
        lastClosed = null;
        if (Roles.TABLE.equals(group.role) && pendingCaption != null) {
            // "Table 1: ..." printed above the table is the caption of the table
            pendingCaptionHolder.remove(pendingCaption);
            childrenOn(stack.size() - 1, first.page).add(0, pendingCaption);
        }
        pendingCaption = null;
    }

    /** Index of the innermost group that may still be ended: one that is not below a report frame. */
    private int closableGroupIndex() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Scope scope = stack.get(i);
            if (scope.frame() != null) {
                return -1;
            }
            if (scope.group() != null) {
                return i;
            }
        }
        return -1;
    }

    private Group nearestGroup() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).group() != null) {
                return stack.get(i).group();
            }
        }
        return null;
    }

    private Group nearest(String role) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Group group = stack.get(i).group();
            if (group != null && group.role.equals(role)) {
                return group;
            }
        }
        return null;
    }

    /** Ends the innermost open group with this role, and everything inside it. */
    private void closeNearest(String role) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Scope scope = stack.get(i);
            if (scope.frame() != null) {
                return; // an end mark inside a frame cannot end something outside of it
            }
            if (scope.group() != null && scope.group().role.equals(role)) {
                closeDownTo(i);
                return;
            }
        }
    }

    private void closeDownTo(int depth) {
        while (stack.size() > depth) {
            Scope scope = stack.remove(stack.size() - 1);
            if (scope.group() != null) {
                closed(scope.group());
            }
        }
    }

    private void attach(Node node) {
        List<Node> holder = childrenOn(stack.size() - 1, node.page);
        holder.add(node);
        if (!node.isArtifact()) {
            lastClosed = null;
            boolean caption = Roles.CAPTION.equals(node.role) && !Roles.TABLE.equals(roleOf(nearestGroup()));
            pendingCaption = caption ? node : null;
            pendingCaptionHolder = caption ? holder : null;
        }
    }

    private static String roleOf(Group group) {
        return group == null ? null : group.role;
    }

    /** The child list that receives elements of the given page at the given stack depth. */
    private List<Node> childrenOn(int index, int page) {
        if (index < 0) {
            return pages.get(page - 1);
        }
        Scope scope = stack.get(index);
        if (scope.frame() != null) {
            return scope.frame().children;
        }
        Group group = scope.group();
        for (Node part : group.parts) {
            if (part.page == page) {
                return part.children;
            }
        }
        Node part = new Node();
        part.id = "p" + page + "-group" + (++groupIds) + "-" + group.role;
        part.kind = ElementKind.FRAME;
        part.role = group.role;
        part.page = page;
        part.group = group;
        if (!group.parts.isEmpty()) {
            // the group continues on another page
            group.parts.forEach(earlier -> earlier.structureId = structureIdOf(earlier));
            part.structureId = group.parts.get(0).structureId;
        }
        group.parts.add(part);
        childrenOn(index - 1, page).add(part);
        return part.children;
    }

    // ---- what the groups learn from their content --------------------------------------------

    /** Called for every element and frame with a role, once it is in place. */
    private void placed(Node node) {
        Group parent = parentGroupOf(node);
        if (parent != null && Roles.LI.equals(parent.role)) {
            parent.hasLabel |= Roles.LBL.equals(node.role);
            parent.hasBody |= Roles.LBODY.equals(node.role);
        }
        Group list = nearest(Roles.L);
        if (list != null) {
            if (node.listNumbering != null) {
                list.attributes.putIfAbsent(Attributes.LIST_NUMBERING, node.listNumbering);
            }
            if (Roles.LBL.equals(node.role) && list.firstLabel == null && Roles.LI.equals(roleOf(parent))) {
                list.firstLabel = textOf(node);
            }
        }
        Group table = nearest(Roles.TABLE);
        if (table != null && node.tableSummary != null) {
            table.attributes.putIfAbsent(Attributes.SUMMARY, node.tableSummary);
        }
    }

    /** The group the node was put into: the nearest one below the node's own scope, if it has one. */
    private Group parentGroupOf(Node node) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            Scope scope = stack.get(i);
            if (scope.frame() == node) {
                continue;
            }
            if (scope.group() != null) {
                return scope.group();
            }
        }
        return null;
    }

    private void closed(Group group) {
        lastClosed = group;
        pendingCaption = null;
        if (Roles.TR.equals(group.role)) {
            rowClosed(group);
        } else if (Roles.L.equals(group.role) && group.firstLabel != null) {
            String numbering = numberingOf(group.firstLabel);
            if (numbering != null) {
                group.attributes.putIfAbsent(Attributes.LIST_NUMBERING, numbering);
            }
        }
    }

    /**
     * A finished row: header cells learn their scope, and a header row that the report printed
     * again at the top of a continuation page becomes an artifact.
     */
    private void rowClosed(Group row) {
        if (row.parts.isEmpty()) {
            return;
        }
        Node part = row.parts.get(0);
        List<Node> cells = part.children.stream().filter(node -> Roles.isTableCell(node.role)).toList();
        if (cells.isEmpty()) {
            return;
        }
        boolean headerRow = cells.stream().allMatch(cell -> Roles.TH.equals(cell.role));
        for (Node cell : cells) {
            if (Roles.TH.equals(cell.role)) {
                // PDF/UA-1 7.5: a header cell says whether it heads its column or its row
                cell.attributes.putIfAbsent(Attributes.SCOPE, headerRow ? "Column" : "Row");
            }
        }

        Group table = nearest(Roles.TABLE);
        if (table == null) {
            return;
        }
        List<String> texts = cells.stream().map(LayoutAssembler::textOf).toList();
        if (table.currentPage != part.page) {
            table.currentPage = part.page;
            table.rowsOnCurrentPage = 0;
            table.headerRepetitionOver = false;
        }
        if (part.page == table.firstPage) {
            if (headerRow && !table.dataRowSeen) {
                table.headerRows.add(texts);
            } else {
                table.dataRowSeen = true;
            }
        } else if (!table.headerRepetitionOver) {
            int index = table.rowsOnCurrentPage;
            if (headerRow && index < table.headerRows.size() && table.headerRows.get(index).equals(texts)) {
                part.role = Roles.ARTIFACT;
                part.artifact = REPEATED_HEADER;
            } else {
                table.headerRepetitionOver = true;
            }
        }
        table.rowsOnCurrentPage++;
    }

    private static String textOf(Node node) {
        if (node.children.isEmpty()) {
            return node.text == null ? "" : node.text.strip();
        }
        StringBuilder text = new StringBuilder();
        for (Node child : node.children) {
            String part = textOf(child);
            if (!part.isEmpty()) {
                text.append(text.isEmpty() ? "" : " ").append(part);
            }
        }
        return text.toString();
    }

    /** The ListNumbering value that matches the label of the first item, or {@code null} if it is unclear. */
    static String numberingOf(String label) {
        // "1.", "(a)", "i)" ... without the punctuation around the counter
        String text = label.strip().replaceAll("^[(\\[]|[.)\\]:]+$", "");
        if (text.matches("\\d+")) {
            return "Decimal";
        }
        // the first item of a list numbered with letters is "a", with Roman numerals "i"
        if (text.equals("i")) {
            return "LowerRoman";
        }
        if (text.equals("I")) {
            return "UpperRoman";
        }
        if (text.matches("[a-z]")) {
            return "LowerAlpha";
        }
        if (text.matches("[A-Z]")) {
            return "UpperAlpha";
        }
        return switch (text) {
            case "\u2022", "\u25CF", "\u00B7", "-", "\u2013", "\u2014", "*" -> "Disc";
            case "\u25E6", "\u25CB" -> "Circle";
            case "\u25AA", "\u25A0", "\u25AB", "\u25A1" -> "Square";
            default -> null;
        };
    }
}
