package ro.ca2datavision.pdfua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ro.ca2datavision.pdfua.TaggedPdf.Node;
import ro.ca2datavision.pdfua.TaggedPdf.Painted;

/**
 * The table, the lists and the smaller features of the demo document: caption, note, a
 * paragraph in another language, replacement text.
 */
class TablesAndListsTest {

    private static DemoRun demo;
    private static TaggedPdf tagged;

    @BeforeAll
    static void generate() throws Exception {
        demo = DemoRun.get();
        tagged = demo.tagged;
    }

    private static Node only(String type) {
        List<Node> found = tagged.topLevel().stream().filter(node -> node.type().equals(type)).toList();
        assertEquals(1, found.size(), type + " elements at the top level");
        return found.get(0);
    }

    // ---- table ------------------------------------------------------------------------------------

    @Test
    void tableThatRunsOverTwoPagesIsOneTable() {
        Node table = only("Table");
        Set<Integer> pages = new HashSet<>();
        table.subtree().forEach(node -> pages.add(node.page()));
        pages.remove(0); // grouping elements have no page of their own
        assertEquals(Set.of(2, 3), pages);
        assertEquals(31, table.children("TR").size(), "2 header rows, 28 services, 1 row of totals");
    }

    @Test
    void tableStartsWithItsCaptionAndHasASummary() {
        Node table = only("Table");
        assertEquals("Caption", table.children().get(0).type());
        assertEquals("Table 1: Monthly prices per service", table.children().get(0).text());
        assertTrue(table.attribute("Summary").startsWith("One row per service"));
    }

    @Test
    void headerCellsSayWhatTheyAreHeadersFor() {
        List<Node> rows = only("Table").children("TR");

        // two header rows; the first three columns span both, "Price per month" spans two columns
        Node first = rows.get(0);
        assertEquals(List.of("Service", "Billing unit", "24/7 support", "Price per month in EUR"),
                first.children().stream().map(Node::text).toList());
        assertEquals(List.of(2, 2, 2, 1), first.children().stream().map(Node::rowSpan).toList());
        assertEquals(List.of(1, 1, 1, 2), first.children().stream().map(Node::colSpan).toList());
        assertEquals(List.of("Net", "Gross"), rows.get(1).children().stream().map(Node::text).toList());
        for (Node header : List.of(rows.get(0), rows.get(1))) {
            for (Node cell : header.children()) {
                assertEquals("TH", cell.type());
                assertEquals("Column", cell.attribute("Scope"));
            }
        }

        // every body row starts with a row header: the name of the service
        for (Node row : rows.subList(2, rows.size())) {
            assertEquals(List.of("TH", "TD", "TD", "TD", "TD"), row.children().stream().map(Node::type).toList());
            assertEquals("Row", row.children().get(0).attribute("Scope"));
            assertNull(row.children().get(1).attribute("Scope"), "data cells have no scope");
        }
        assertEquals("Sales: daily report", rows.get(2).children().get(0).text());
    }

    @Test
    void everyRowCoversAllFiveColumns() {
        // PDF/UA wants regular tables: with the spans counted, each row has the same number of columns
        List<Node> rows = only("Table").children("TR");
        int[] carried = new int[5]; // rows still covered, per column, by cells that span rows
        for (Node row : rows) {
            int column = 0;
            for (Node cell : row.children()) {
                while (column < 5 && carried[column] > 0) {
                    column++;
                }
                for (int i = 0; i < cell.colSpan(); i++) {
                    carried[column + i] = cell.rowSpan();
                }
                column += cell.colSpan();
            }
            for (int i = 0; i < 5; i++) {
                assertTrue(carried[i] > 0, "row \"" + row.text() + "\" leaves column " + (i + 1) + " empty");
                carried[i]--;
            }
        }
    }

    @Test
    void emptyCellsAreStillCells() {
        Node totals = only("Table").children("TR").get(30);
        assertEquals("Total for all services", totals.children().get(0).text());
        assertEquals(5, totals.children().size());
        assertEquals("", totals.children().get(1).text());
        assertEquals("", totals.children().get(2).text());
        assertEquals("3,283.00", totals.children().get(3).text());
        assertEquals("3,939.60", totals.children().get(4).text());
    }

    @Test
    void headerRowsRepeatedOnTheNextPageAreArtifacts() throws Exception {
        // in the structure the header texts occur once ...
        String tableText = only("Table").subtree().stream().filter(node -> node.children().isEmpty())
                .map(Node::text).reduce("", (a, b) -> a + "|" + b);
        assertEquals(1, count(tableText, "Billing unit"));
        assertEquals(1, count(tableText, "Price per month in EUR"));

        // ... although JasperReports prints them on both pages: on page 3 they are artifacts
        List<Painted> headerArtifacts = tagged.painted().stream()
                .filter(operator -> operator.page() == 3 && operator.artifact() && !operator.isPath()
                        && "Pagination".equals(operator.properties().getNameAsString(COSName.TYPE))
                        && operator.properties().getNameAsString(COSName.SUBTYPE) == null)
                .toList();
        assertEquals(7, headerArtifacts.size(), "the text of 6 header cells; \"24/7 support\" takes two lines");
    }

    private static int count(String text, String part) {
        return (text.length() - text.replace(part, "").length()) / part.length();
    }

    @Test
    void symbolsHaveReplacementText() {
        List<String> replacements = only("Table").subtree().stream()
                .filter(node -> node.entry("ActualText") != null)
                .map(node -> node.text() + "=" + node.entry("ActualText")).distinct().sorted().toList();
        assertEquals(List.of("–=not included", "✓=included"), replacements);
    }

    // ---- lists ------------------------------------------------------------------------------------

    @Test
    void bulletedListHasThreeItemsWithLabelAndBody() {
        Node list = tagged.topLevel().stream().filter(node -> node.type().equals("L")).findFirst().orElseThrow();
        assertEquals("Disc", list.attribute("ListNumbering"));
        assertEquals(3, list.children().size());
        for (Node item : list.children()) {
            assertEquals("LI", item.type());
            assertEquals(List.of("Lbl", "LBody"), item.children().stream().map(Node::type).toList());
            assertEquals("•", item.children().get(0).text());
        }
        assertEquals("a report on every working day before 08:00", list.children().get(1).children().get(1).text());
    }

    @Test
    void numberedListContainsANestedList() {
        Node list = tagged.topLevel().stream().filter(node -> node.type().equals("L")).skip(1).findFirst().orElseThrow();
        assertEquals("Decimal", list.attribute("ListNumbering"));
        assertEquals(List.of("1.", "2.", "3."), list.children().stream().map(item -> item.children().get(0).text()).toList());

        // the body of the second item holds a paragraph and a list of its own
        Node body = list.children().get(1).children().get(1);
        assertEquals(List.of("P", "L"), body.children().stream().map(Node::type).toList());
        Node nested = body.children().get(1);
        assertEquals("Disc", nested.attribute("ListNumbering"));
        assertEquals(List.of("by e-mail, as an accessible PDF", "in the reporting portal"),
                nested.children().stream().map(item -> item.children().get(1).text()).toList());
    }

    // ---- note, language ---------------------------------------------------------------------------

    @Test
    void noteHasAUniqueIdThatIsInTheIdTree() {
        Node note = only("Note");
        assertEquals("Note: gross prices include value added tax at 20 percent.", note.text());
        String id = note.entry("ID");
        assertNotNull(id, "PDF/UA-1 7.9: a Note has an ID");

        COSDictionary idTree = demo.pdfUa.getDocumentCatalog().getStructureTreeRoot().getCOSObject()
                .getCOSDictionary(COSName.getPDFName("IDTree"));
        assertNotNull(idTree, "ID tree");
        assertEquals(id, idTree.getCOSArray(COSName.NAMES).getString(0));
        assertTrue(idTree.getCOSArray(COSName.NAMES).getObject(1) == note.element(), "the ID leads to the Note");
    }

    @Test
    void paragraphInAnotherLanguageSaysSo() {
        List<Node> romanian = tagged.readingOrder().stream().filter(node -> "ro-RO".equals(node.entry("Lang"))).toList();
        assertEquals(1, romanian.size());
        assertEquals("P", romanian.get(0).type());
        assertTrue(romanian.get(0).text().startsWith("Echipa noastră"));
        // everything else relies on the document language
        assertFalse(tagged.topLevel().get(2).element().containsKey(COSName.LANG));
    }
}
