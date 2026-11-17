package ro.ca2datavision.pdfua.jasper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ro.ca2datavision.pdfua.jasper.LayoutAssembler.Node;
import ro.ca2datavision.pdfua.layout.Box;
import ro.ca2datavision.pdfua.layout.ElementKind;
import ro.ca2datavision.pdfua.layout.LayoutElement;

/** The nesting rules of the assembler, on hand-made elements. */
class LayoutAssemblerTest {

    private int ids;

    /** A text element with the given role in a row at the given height. */
    private Node text(String role, float top, String content) {
        Node node = new Node();
        node.id = "e" + (++ids);
        node.kind = ElementKind.TEXT;
        node.role = role;
        node.text = content;
        node.page = 1;
        node.box = new Box(ids * 10f, top - 10, ids * 10f + 8, top);
        return node;
    }

    private static List<LayoutElement> assemble(Node... nodes) {
        LayoutAssembler assembler = new LayoutAssembler();
        assembler.addPage(new ArrayList<>(List.of(nodes)));
        return assembler.finish().get(0);
    }

    /** "Table(TR(TH TH) TR(TD TD)) P": the nesting as text. */
    private static String shape(List<LayoutElement> elements) {
        StringBuilder text = new StringBuilder();
        for (LayoutElement element : elements) {
            text.append(text.isEmpty() ? "" : " ").append(element.role());
            if (!element.children().isEmpty()) {
                text.append('(').append(shape(element.children())).append(')');
            }
        }
        return text.toString();
    }

    @Test
    void cellsAtTheSameHeightAreARowAndConsecutiveRowsATable() {
        assertEquals("P Table(TR(TH TH) TR(TD TD) TR(TD TD)) P", shape(assemble(
                text("P", 700, "before"),
                text("TH", 680, "a"), text("TH", 680, "b"),
                text("TD", 660, "1"), text("TD", 660, "2"),
                text("TD", 640, "3"), text("TD", 640, "4"),
                text("P", 620, "after"))));
    }

    @Test
    void artifactsBetweenRowsDoNotEndTheTable() {
        // a rule drawn under the first row: it stays where it is (inside the row that is still
        // open) and is ignored when the structure is built
        Node line = text("Artifact", 670, null);
        assertEquals("Table(TR(TD TD Artifact) TR(TD TD))", shape(assemble(
                text("TD", 680, "1"), text("TD", 680, "2"), line, text("TD", 660, "3"), text("TD", 660, "4"))));
    }

    @Test
    void differentTableNamesKeepAdjacentTablesApart() {
        Node[] cells = {text("TD", 680, "1"), text("TD", 680, "2"), text("TD", 660, "3"), text("TD", 660, "4")};
        assertEquals("Table(TR(TD TD) TR(TD TD))", shape(assemble(cells)));

        cells = new Node[] {text("TD", 680, "1"), text("TD", 680, "2"), text("TD", 660, "3"), text("TD", 660, "4")};
        cells[0].tableName = cells[1].tableName = "first";
        cells[2].tableName = cells[3].tableName = "second";
        assertEquals("Table(TR(TD TD)) Table(TR(TD TD))", shape(assemble(cells)));
    }

    @Test
    void headerCellsGetTheirScopeFromTheRow() {
        List<LayoutElement> table = assemble(
                text("TH", 680, "Name"), text("TH", 680, "Amount"),
                text("TH", 660, "Rent"), text("TD", 660, "500")).get(0).children();
        assertEquals("Column", table.get(0).children().get(0).attributes().get("Scope"));
        assertEquals("Row", table.get(1).children().get(0).attributes().get("Scope"));
        assertNull(table.get(1).children().get(1).attributes().get("Scope"));
    }

    @Test
    void labelsAndBodiesBecomeItemsOfAList() {
        List<LayoutElement> result = assemble(
                text("Lbl", 700, "1."), text("LBody", 700, "one"),
                text("LBody", 680, "an item without a label"),
                text("Lbl", 660, "3."), text("LBody", 660, "three"),
                text("P", 640, "after"));
        assertEquals("L(LI(Lbl LBody) LI(LBody) LI(Lbl LBody)) P", shape(result));
        assertEquals("Decimal", result.get(0).attributes().get("ListNumbering"));
    }

    @Test
    void startAndEndMarksOpenAndCloseGroups() {
        // the JasperReports way: table and row are marked on the first and last cell
        Node first = text("TH", 700, "a");
        first.opens.addAll(List.of("Table", "TR"));
        Node last = text("TH", 700, "b");
        last.closes.add("TR");
        Node cell = text("TD", 680, "1");
        cell.opens.add("TR");
        cell.closes.addAll(List.of("TR", "Table"));
        assertEquals("Table(TR(TH TH) TR(TD)) P", shape(assemble(first, last, cell, text("P", 660, "after"))));
    }

    @Test
    void frameWithARoleIsAGroupAndGetsItsMissingParent() {
        // a row stated explicitly, as a frame around its cells: only the table has to be added
        Node row = new Node();
        row.id = "row";
        row.kind = ElementKind.FRAME;
        row.role = "TR";
        row.page = 1;
        row.box = new Box(0, 670, 100, 680);
        row.children.add(text("TD", 680, "1"));
        row.children.add(text("TD", 680, "2"));
        assertEquals("Table(TR(TD TD)) P", shape(assemble(row, text("P", 660, "after"))));
    }

    @Test
    void captionBeforeATableBelongsToTheTable() {
        assertEquals("P Table(Caption TR(TD))", shape(assemble(
                text("P", 700, "text"), text("Caption", 690, "Table 1"), text("TD", 680, "1"))));
    }

    @Test
    void listNumberingIsReadFromTheFirstLabel() {
        assertEquals("Decimal", LayoutAssembler.numberingOf("1."));
        assertEquals("Decimal", LayoutAssembler.numberingOf("(1)"));
        assertEquals("LowerAlpha", LayoutAssembler.numberingOf("a)"));
        assertEquals("UpperAlpha", LayoutAssembler.numberingOf("A."));
        assertEquals("LowerRoman", LayoutAssembler.numberingOf("i."));
        assertEquals("UpperRoman", LayoutAssembler.numberingOf("I."));
        assertEquals("Disc", LayoutAssembler.numberingOf("•"));
        assertEquals("Disc", LayoutAssembler.numberingOf("–"));
        assertEquals("Square", LayoutAssembler.numberingOf("▪"));
        assertNull(LayoutAssembler.numberingOf("→"));
    }
}
