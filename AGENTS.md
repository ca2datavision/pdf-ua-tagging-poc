# AGENTS.md

Guide for AI coding agents working in this repository. Humans in a hurry can use it as a cheat
sheet. The long explanations are in [docs/](docs); this file is what you need before you touch
anything.

## What this project is

A proof of concept that turns the untagged PDF of **JasperReports Community Edition** into
**PDF/UA** with a **PDFBox post-processor**: PDF/UA-1 by default, PDF/UA-2 on request
(`PdfUaPart`). JasperReports CE cannot tag PDFs since 7.0.8, and the commercial extensions that
can are deliberately not used. Headings, tables, lists, figures, links, artifacts, signature
fields, and digital signing that keeps the file PDF/UA. One demo template (three pages) and two
small test reports, automated validation with veraPDF. Not a library.

## Commands

```sh
mvn test                          # build, run the flow for both parts, validate. Must pass before you finish.
mvn compile exec:java             # run the flow only, PDF/UA-1; output in target/pdf/
mvn compile exec:java -Dexec.args="--pdfua=2"    # the same for PDF/UA-2; output in target/pdf-ua2/
mvn exec:java@pdf-debugger        # GUI viewer for target/pdf/02-pdfua.pdf (-Dpdf=<file> for another)
pdfinfo -struct-text target/pdf/02-pdfua.pdf    # tag tree, if poppler is installed
pdfsig target/pdf/03-pdfua-signed.pdf           # signature status, if poppler is installed
```

- Java 17 or newer, Maven 3.6.3 or newer (tested: 3.8.4 and 3.9.16).
- `mvn test` takes about one and a half to three minutes once dependencies are cached (veraPDF
  validates fourteen files). It needs no network then. While iterating, run one class:
  `mvn test -Dtest=TablesAndListsTest`.
- The debugger opens a window and blocks until it is closed. Do not start it in CI or when
  nobody is at the screen.
- Output of every run: `target/pdf/01-jasper-raw.pdf`, `01-jasper-layout.json`, `02-pdfua.pdf`,
  `02-pdfua-structure.txt`, `03-pdfua-signed.pdf`, and from the tests
  `04-verapdf-pdfua1-report.txt`. For PDF/UA-2 the same in `target/pdf-ua2/`, with `pdfua2` in
  the names (`02-pdfua2.pdf`, ...).

## Where things are

```
pom.xml                                   versions, exec launchers (demo, pdf-debugger)
src/main/resources/
  jasperreports.properties                silent tagger factory, pdfua.* property transfer, font policy
  reports/accessible-demo.jrxml           THE template; comments explain every hint
  images/                                 logo.svg, process.svg, watermark.svg, availability-chart.png
src/main/java/ro/ca2datavision/pdfua/
  PdfUaPipeline.java, PdfUaDemo.java      the flow and its main()
  DemoData.java                           invented rows for the price table of the demo
  jasper/JasperReportGenerator.java       compile, fill, export (no accessibility knowledge)
  jasper/JasperLayoutExtractor.java       JasperPrint -> DocumentLayout; roles, defaults, attributes live HERE
  jasper/LayoutAssembler.java             nesting: cells -> rows -> tables, items -> lists, across pages
  layout/                                 records + JSON; the only thing both halves share
  postprocess/PdfUaPostProcessor.java     entry point; preconditions; catalog, metadata, bookmarks
  postprocess/PdfUaPart.java              PDF_UA_1 (default) or PDF_UA_2; what differs between them
  postprocess/ContentScanner.java         what does each content stream operator paint, and where
  postprocess/PageTagger.java             content-or-artifact decision, marked content, links, structure
  postprocess/StructureBuilder.java       structure elements, parent tree, role map, namespaces (raw COS)
  postprocess/StructureTypes.java         PDF/UA-2: role -> structure type + namespace (Note -> FENote, ...)
  postprocess/StructureDestinations.java  PDF/UA-2: SD for links and bookmarks inside the document
  postprocess/SignatureFields.java        signature form fields
  postprocess/StructureDump.java          text view of a tagged PDF
  sign/PdfSigner.java                     signs a signature field; incremental update; keeps PDF/UA
  sign/SigningIdentity.java               key + certificates; can make a throw-away test identity
src/test/java/ro/ca2datavision/pdfua/
  DemoRun.java                            ONE run of the demo flow per part, shared by the test classes
  PdfUaPipelineTest.java                  flow, PDF/UA basics, artifacts, links, signature fields
  PdfUa2Test.java                         the PDF/UA-2 output, compared with the PDF/UA-1 output
  TablesAndListsTest.java                 the table, the lists, note, language, replacement text
  SignedDocumentTest.java                 the signed file
  TabularReportTest.java                  band-based tables, marked in two ways, as PDF/UA-1 and -2
  jasper/LayoutAssemblerTest.java         nesting rules on hand-made elements
  TaggedPdf.java                          reads a tagged PDF back, independent of the writer
  VeraPdf.java                            veraPDF as a library
src/test/resources/reports/               the two tabular test reports
tools/MakeChart.java                      draws the PNG of the demo; not part of the build
docs/                                     architecture, template guide, compliance map, verification, limitations, process
docs/sample-output/                       committed copy of target/pdf (and the pdfua2 files of target/pdf-ua2)
```

## How it works, in seven steps

1. JasperReports fills the template and exports a PDF without tags.
2. `JasperLayoutExtractor` reads role, position and attributes of every element from the filled
   report; `LayoutAssembler` nests them (rows, tables, list items, lists), also across pages.
3. `ContentScanner` finds every *painting unit* of a page (text run, image, form XObject, path)
   and its bounding box.
4. `PageTagger` assigns each unit to the layout element at its centre: content of an element
   with a role, otherwise artifact. It rewrites the content stream with `BDC ... EMC`.
5. `PageTagger` + `StructureBuilder` create the structure elements in layout order, put link
   annotations into `Link` elements and signature widgets into `Form` elements.
6. `PdfUaPostProcessor` writes the structure tree, parent tree, XMP, language, title, tab order
   and bookmarks. For PDF/UA-2, `StructureBuilder` writes types and namespaces as
   `StructureTypes` says, and `StructureDestinations` adds structure destinations.
7. `PdfSigner` (optional, separate) signs a field as an incremental update.

Details and diagrams: [docs/architecture.md](docs/architecture.md).

## Invariants. Do not break these.

1. **Nothing unmarked.** Every painting operator on every page is inside exactly one
   marked-content sequence: tagged content (has an MCID) or `/Artifact`. Never both, never
   neither. Test: `everyPaintingOperatorIsEitherTaggedOrAnArtifact`.
2. **Appearance is untouched.** Post-processing must not change a single pixel. Test:
   `postProcessingDoesNotChangeTheAppearance`.
3. **The halves stay apart.** `postprocess`, `layout` and `sign` never import
   `net.sf.jasperreports`; `jasper` and `layout` never import `org.apache.pdfbox`; `sign` imports
   nothing from the rest of the project. The layout is the whole contract.
4. **The post-processor does not guess meaning.** Roles, nesting, attributes, alternate texts
   and reading order come from the layout. If something is missing, add a hint to the template
   and the layout; do not add a heuristic to the post-processor. Guesses about a report (cells
   at the same height are a row) belong in `LayoutAssembler`, where their result is written into
   the layout and can be read.
5. **No JasperReports Professional extensions**, and the tagger factory stays
   `SilentPdfTaggerFactory`. Do not "fix" tagging inside JasperReports.
6. **Fail fast on what cannot be repaired** (fonts not embedded, no title, no language, a
   `Figure` without alternate text). **Warn on what looks like a mistake** (content no element
   accounts for, plain-text contact details). Never drop content silently.
7. **veraPDF must report the tagged and the signed file compliant**, with the profile of their
   part (PDF/UA-1 or PDF/UA-2), and the demo must produce no warnings in either part.
8. **Tables stay regular.** Every row covers every column once spans are counted; empty cells
   are cells. Test: `everyRowCoversAllFiveColumns`.
9. **Signing only appends.** The signed file starts with the unsigned file, byte for byte.
   Never run the post-processor on a signed file. Test: `signingOnlyAppendsToTheFile`.
10. **Do not claim more than was checked.** PAC and screen readers have not been run. Do not
   write "PAC compliant" or "accessible" anywhere. Results of manual checks go into the log in
   [docs/verification.md](docs/verification.md), with date and tool version.
11. **PDF/UA-1 is the default, and its output does not change by accident.** The developer chose
   it. Work on PDF/UA-2 must leave the PDF/UA-1 tag tree as it is: compare
   `target/pdf/02-pdfua-structure.txt` with the committed sample.
12. **One layout for both parts.** The layout and the templates speak PDF 1.7 types (plus
   `Title`). Translation to PDF 2.0 happens only in `StructureTypes`; do not add part-specific
   information to the layout. Test: `sameContentInTheSameOrderAsPdfUa1`.

## Recipes

**Change the demo template.** The tests assert the exact structure of the demo: the sequence of
top-level tags, the headings, the links and link annotations, the text of one paragraph
(`PdfUaPipelineTest`); the rows, spans and totals of the table and the items of the lists
(`TablesAndListsTest`). Update those expectations deliberately, then refresh the committed
samples:

```sh
mvn test
rm -f docs/sample-output/*.pdf docs/sample-output/*.txt docs/sample-output/*.json
cp target/pdf/* docs/sample-output/
cp target/pdf-ua2/02-pdfua2* target/pdf-ua2/03-pdfua2* target/pdf-ua2/04-* docs/sample-output/
pdftoppm -r 110 -png target/pdf/03-pdfua-signed.pdf docs/images/page    # page-1.png ... page-3.png
```

`docs/architecture.md` quotes object numbers and coordinates from the sample; they change with
the template.

**Add a template hint.** The property name must start with `pdfua.` (only that prefix is
transferred to the filled report). Read it in `JasperLayoutExtractor`, carry it in
`LayoutElement` (a record: the JSON changes with it), use it in `PageTagger`, and document it in
[docs/template-guide.md](docs/template-guide.md).

**Add a structure type.** All PDF 1.7 standard types are already accepted (`Roles.STANDARD`). A
non-standard type goes into `Roles.CUSTOM` with the standard type it maps to; the role map is
written automatically.

**Add an attribute to structure elements.** Add a key to `layout/Attributes.java`, set it in
`JasperLayoutExtractor.readAttributes` (or in `LayoutAssembler` when it is derived, like the
scope of a header cell), and write it in `StructureBuilder.applyAttributes`.

**Change something about PDF/UA-2 only.** Type and namespace: `StructureTypes.forPdfUa2`.
Destinations: `StructureDestinations`. Anything else: look for `PdfUaPart.PDF_UA_2` in
`postprocess`. Test it in `PdfUa2Test` and check that the PDF/UA-1 tag tree did not change. The
rules are in the veraPDF jar
(`org/verapdf/pdfa/validation/PDFUA-2-ISO32005.xml` in `core-1.30.4.jar`); read the rule before
changing what it checks.

**Change how tables or lists are put together.** `LayoutAssembler` is a stack machine over the
elements of all pages: `CHILDREN` says what a group may contain, `PARENTS` what a type needs
around it, `endsBefore` when an automatic row or list item is complete. Try the rule in
`LayoutAssemblerTest` first; it runs in a second.

**Find out why something is tagged wrongly.**

1. `target/pdf/02-pdfua-structure.txt`: what is in the tree, in which order; artifacts per page.
2. The warnings of the `PostProcessingReport` (printed by `PdfUaDemo`).
3. `target/pdf/01-jasper-layout.json`: does the element have the role and box you expect? If the
   role is wrong, the cause is in the template or in `JasperLayoutExtractor`, not in the tagger.
4. The PDF itself. It is saved without object streams, so `grep -a "/S /Link" 02-pdfua.pdf`
   works, and PDFDebugger shows everything.

**Find out why a table or list is nested wrongly.** Look at `01-jasper-layout.json`: the groups
(`"kind" : "FRAME"` with a role and an id like `p2-group12-TR`) are what the assembler built.
If they are right there and wrong in the PDF, the problem is in the post-processor; otherwise
in the marks of the template or in the assembler.

## Things that are not obvious

- **JasperReports drops the space at a line wrap.** `PageTagger.restoreWordSpaces` adds a space
  glyph at line ends. If text in the tag tree runs together, look there first.
- **Element properties only survive filling if their prefix is registered**
  (`net.sf.jasperreports.print.transfer.pdfua=pdfua.`). An unregistered property is silently
  absent from the `JasperPrint`, and the element gets its default role.
- **The title band is printed above the page header on page 1.** That is why the demo's title is
  in the first detail band.
- **A static text that does not fit its box is cut off without any error.** Render the page and
  look (`pdftoppm`) after changing texts or fonts.
- **JasperReports writes one `Tj` per styled chunk and line, and one link annotation per chunk.**
  The inline link tagging depends on it.
- **SVG images arrive as form XObjects**, raster images as image XObjects. Both are one painting
  unit each.
- **The logo is the same XObject on all pages**, tagged on page 1 and an artifact afterwards.
- **The table component builds an inner report.** Its cells are frames (or the single text of
  the cell, when that fills the cell and the cell has no box or background). Properties on a
  cell in the JRXML are dropped. Its `columnHeader` and `columnFooter` are printed on every page;
  `tableHeader` and `tableFooter` once. Elements of its footers have a *page footer* origin,
  which is why `JasperLayoutExtractor.bandArtifact` ignores bands of inner reports.
- **`<a>` in styled text always links to the outside**, whatever `type` it has. Links into the
  document need `linkType="LocalAnchor"` on the element.
- **Print elements of one template element share `getSourceElementId()`.** The assembler uses it
  to join a frame that was split at a page break. It cannot tell a split text from the same
  text field printed for the next record, which is why paragraphs are not joined across pages.
- **PDFBox subsets a font only in a full save.** For the incremental save of a signature the
  visible signature is built in a separate small PDF (`PdfSigner.visibleSignature`).
- **Bouncy Castle refuses trailing bytes** when a signature is parsed from a byte array; the
  space reserved for a signature is padded with zeros. Parse it from a stream.
- **Images are matched by overlap, everything else by the smallest element** at the point. A
  full-page background image is why.
- **veraPDF and structure destinations** (from its source): a go-to action passes rule 8.8-2 as
  soon as it has an `SD`; a plain `Dest` passes 8.8-1 only if it is itself a structure
  destination (first element a structure element). That is why bookmarks and links get a go-to
  action with `D` and `SD` instead of a `Dest`.
- **JasperReports links to an anchor** point with `/D 64 0 R` to the very array that the named
  destination tree lists under the anchor name. `StructureDestinations` finds the name by
  identity of that array, then the element through the layout's `anchor`.
- **The veraPDF PDF/UA-2 profile includes ISO 32005**, 1,636 rules about which structure type
  may contain which. A new nesting (for example a `Form` element with a `P` in it) can fail there.
- **`Map.copyOf` and `Set.of` iterate in an order that changes with every JVM start.** Anything
  that ends up in the PDF or the JSON in iteration order must use an ordered map
  (`LayoutElement` does).
- **The PDF/UA-2 output has a role map although PDF 2.0 does not need one.** `Title` → `P` and
  `FENote` → `Note` in the root role map are a fallback for readers that ignore namespaces;
  poppler 26.02 drops both types without it. PDF 2.0 processors (veraPDF included) do not apply
  the root role map to namespaced elements. Test: `aReaderWithoutPdf20SupportStillKnowsEveryType`.
- **PDFBox has no `COSName` constants for some PDF 2.0 and action names** (`Action`, `XYZ`,
  `NS`, `SD`); they are created with `COSName.getPDFName`.
- **`tagged=true` in a template is harmless** (silent tagger), but then the raw file declares
  itself tagged, with an empty structure tree and a metadata stream written by JasperReports.
  The post-processor replaces both.
- **Reading a PDF with PDFBox can change it in memory.** `getAcroForm()` adds default resources
  (Helvetica) to a form that has none. This shows up as a log line about a fallback font in the
  tests; it is not in the file.
- **The stock OpenPDF warning** ("Stock OpenPDF detected ...") is silenced in
  `JasperReportGenerator` on purpose.
- **`exec-maven-plugin` is pinned to 3.5.1** because newer versions need Maven 3.9.
- **veraPDF rule ids** in the report look like `7.1-3`: clause 7.1 of ISO 14289-1, test 3.

## When you change behaviour, update the documents

| Change | Update |
|---|---|
| a hint property, a default role, a nesting rule | [docs/template-guide.md](docs/template-guide.md) |
| what the PDF/UA-2 output contains | [docs/architecture.md](docs/architecture.md) section 7, [docs/pdfua-compliance.md](docs/pdfua-compliance.md) last section |
| the matching or tagging algorithm, what is written to the PDF | [docs/architecture.md](docs/architecture.md) |
| which requirement is met, a new or removed test | [docs/pdfua-compliance.md](docs/pdfua-compliance.md), [docs/verification.md](docs/verification.md) |
| something starts or stops working | [docs/limitations.md](docs/limitations.md), README status table |
| the demo output | `docs/sample-output/`, `docs/images/` (see recipe above) |

## Style

Java 17, records for data, no Lombok, no new dependency without a reason. Comments explain why
something is done, with the PDF or PDF/UA clause where one applies. Keep the JRXML comments: the
template is documentation too.

## Background

The project was written by an AI model in one session;
[docs/development-process.md](docs/development-process.md) has the prompts and the history.
