# Verification

What has been checked, how, and what is still open. The short version:

- **Automated:** veraPDF passes the tagged and the signed document, in both variants: the
  PDF/UA-1 output with the PDF/UA-1 profile, the PDF/UA-2 output with the PDF/UA-2 profile.
  79 unit tests pass.
- **Manual:** nothing has been recorded yet. PAC, a screen reader and Adobe Acrobat still have
  to be tried by a person. The procedures are below, with a table for the results.

## Results log

Add a row when you check something. The filled rows are from the sessions that built the
project.

| # | Date | Tool | File | Result | Notes |
|---|---|---|---|---|---|
| 1 | 2026-11-17 | veraPDF 1.30.4, PDF/UA-1 profile (library, in `mvn test`) | `02-pdfua.pdf` | compliant | about 17,600 checks; the raw file fails 8 rules |
| 2 | 2026-11-17 | same | `03-pdfua-signed.pdf` | compliant | about 17,800 checks |
| 3 | 2026-11-17 | veraPDF 1.30.4, PDF/UA-2 profile with the ISO 32005 rules (library, in `mvn test`) | `02-pdfua2.pdf` | compliant | about 31,700 checks; the raw file fails 7 rules, the PDF/UA-1 output 8 |
| 4 | 2026-11-17 | same | `03-pdfua2-signed.pdf` | compliant | about 31,900 checks |
| 5 | 2026-11-17 | 79 JUnit tests | whole flow, both parts | pass | macOS, Java 21 and Java 17, Maven 3.8.4 |
| 6 | 2026-11-17 | same 79 tests in a clean container | whole flow, both parts | pass | Ubuntu 24.04, Temurin 17, Maven 3.9.16, empty Maven repository, no custom settings; the structure dumps of both parts are identical to the committed samples |
| 7 | 2026-11-17 | poppler 26.02 `pdfinfo -struct-text` | `02-pdfua.pdf` | tag tree read correctly | independent parser; shows `Title` as `P` through the role map |
| 8 | 2026-11-17 | poppler 26.02 `pdfsig` | `03-pdfua-signed.pdf` | "Signature is Valid", "Total document signed" | independent of the code that signed; certificate issuer unknown, as expected for a self-signed test certificate; second field reported as not signed |
| 8a | 2026-11-17 | poppler 26.02 `pdfinfo -struct-text` | `02-pdfua2.pdf` | first version: `Title` and `FENote` **dropped** with their text ("StructElem object is wrong type"); with the fallback role map: the same tree as from `02-pdfua.pdf` | the reason for the fallback role map, see [architecture.md](architecture.md#structure-types-and-namespaces) |
| 8b | 2026-11-17 | poppler 26.02 `pdfsig` | `03-pdfua2-signed.pdf` | "Signature is Valid", "Total document signed" | as row 8 |
| 9 | 2026-11-17 | poppler 26.02 `pdftoppm`, raw against tagged file | `01-jasper-raw.pdf`, `02-pdfua.pdf` | pages identical | done for the first version of the demo (two pages); the test with the PDFBox renderer covers the current one |
| 10 | 2026-11-17 | PDFBox PDFDebugger 3.0.8 | `02-pdfua.pdf` | launcher starts without error | the window itself was not seen by the session |
| 11 | | **PAC** | `02-pdfua.pdf`, `03-pdfua-signed.pdf` | *open* | |
| 12 | | **PAC**, if it supports PDF/UA-2 | `02-pdfua2.pdf`, `03-pdfua2-signed.pdf` | *open* | find out first whether PAC checks PDF/UA-2 |
| 13 | | **NVDA or JAWS** with Adobe Acrobat (Reader) | `02-pdfua.pdf` | *open* | |
| 14 | | **VoiceOver** (macOS) | `02-pdfua.pdf` | *open* | |
| 15 | | **Adobe Acrobat**: open, tab through, look at the signature, sign the second field | `03-pdfua-signed.pdf` | *open* | |
| 16 | | GitHub Actions workflow | repository | *open* | written before the first push |

## What the automated checks prove

### veraPDF

[veraPDF](https://verapdf.org/) is the open source validator for PDF/A and PDF/UA. Its PDF/UA-1
profile implements the machine-checkable conditions of ISO 14289-1; its PDF/UA-2 profile those
of ISO 14289-2 (92 rules) plus the 1,636 nesting rules of ISO/TS 32005. `VeraPdf.java` (test
code) runs it as a library on the raw, the tagged and the signed file of each part and writes
`target/pdf/04-verapdf-pdfua1-report.txt` and `target/pdf-ua2/04-verapdf-pdfua2-report.txt`.

Because the first tagged file passed on the first run, the session checked that the validator
was really exercising it: the same validator fails the raw file on 8 rules, and the number of
checks is many times higher for the tagged file. One test asserts both.

What veraPDF does **not** establish:

- that PAC agrees. Both implement the same failure conditions, but they are different programs
  with different interpretations in edge cases;
- anything that needs human judgement (see [pdfua-compliance.md](pdfua-compliance.md));
- WCAG conformance. The veraPDF WCAG profiles could not be loaded with the library setup used
  here (`PDFAFlavour wcag2m is not supported by this directory`) and were not pursued.

### The unit tests

The tests do not trust the code that wrote the PDF. `TaggedPdf.java` reads the result back with
plain PDFBox: the structure tree in reading order with its attributes and namespaces, and every
painting operator of every page with the marked content around it. The demo flow runs once per
part (`DemoRun.java`) and is shared by the test classes that look at the demo.

`PdfUaPipelineTest` (24 tests): the flow, the PDF/UA basics, artifacts, links, signature fields.

| Test | Proves |
|---|---|
| `intermediateFilesAreKept` | all five files of the flow exist and are not empty |
| `jasperExportIsNotTaggedAndFailsPdfUaValidation` | the raw file has no structure tree, no form, and fails veraPDF: the baseline |
| `postProcessingReportsNoProblems` | the post-processor produced no warnings |
| `documentHasAtLeastTwoPages` | 3 pages, same count before and after |
| `postProcessingDoesNotChangeTheAppearance` | every page of the raw and of the tagged file renders to exactly the same pixels (PDFBox renderer, 96 dpi) |
| `postProcessedDocumentPassesPdfUa1Validation` | veraPDF: compliant, no failed rule, more than 1,000 checks |
| `documentLevelRequirementsAreMet` | `Marked`, language `en-GB`, title, `DisplayDocTitle`, PDF/UA identifier and title in the XMP, bookmarks, `/Tabs /S` on every page |
| `everyPaintingOperatorIsEitherTaggedOrAnArtifact` | each text, image, path and shading operator is inside exactly one of: tagged content, artifact |
| `structureTreeFollowsTheReadingOrderOfTheReport` | the exact sequence of the 38 top-level structure elements |
| `onlyStandardStructureTypesAreUsedAfterRoleMapping` | every type is standard, `Title` is mapped to `P` |
| `headingsAreTaggedAndDoNotSkipLevels` | the 11 headings, their levels and three of their texts |
| `paragraphTextKeepsItsWordSpacesAcrossLineBreaks` | the full text of a three-line paragraph, with spaces at the wraps |
| `figuresHaveAlternateTextAndABoundingBox` | 3 figures, each with `Alt` and a `BBox` |
| `logoIsAFigureOnTheFirstPageOnly` | exactly one element with the logo's alternate text, on page 1, first in the reading order |
| `logoOnFollowingPagesIsAPaginationArtifact` | the same XObject is tagged `Figure` on page 1 and is a `Pagination/Header` artifact on pages 2 and 3 |
| `watermarkIsAnArtifactOnEveryPage` | the background image is a `Pagination/Watermark` artifact on all pages, never part of a figure |
| `linesAndOtherDrawingsAreArtifacts` | every path painting operator is an artifact |
| `pageHeaderAndFooterAreArtifactsAndNotInTheStructureTree` | header, footer and page number text is not in the structure; the kinds of pagination artifact that occur |
| `layoutMarksTheRepeatedLogoAsArtifact` | the layout file itself has `Figure` on page 1 and `Artifact` on page 2 |
| `everyLinkAnnotationIsTaggedAsLinkWithADescription` | all 7 link annotations resolve through the parent tree to a `Link` element and have `/Contents` |
| `webEmailAndPhoneLinksAreInsideTheirParagraph` | the 5 links to the outside with their URIs and texts, each a child of a `P`; the wrapped link is one `Link` with two annotations |
| `linkToAPlaceInTheDocumentIsTaggedToo` | the link to section 4.1 is a `Link` inside a list item body; its description is the link text |
| `signatureFieldsAreAccessible` | 2 empty signature fields with name and description; widget in a `Form` element, printable, visible size, on page 3 |
| `everySignatureFieldIsPrecededByAVisibleLabelSayingWhatIsSigned` | the element before each `Form` is a `P` with the statement of what is signed |

`TablesAndListsTest` (11 tests): the table component, the lists and the smaller features.

| Test | Proves |
|---|---|
| `tableThatRunsOverTwoPagesIsOneTable` | one `Table` with content on pages 2 and 3 and 31 rows |
| `tableStartsWithItsCaptionAndHasASummary` | the `Caption` is the first child; the `Summary` attribute is set |
| `headerCellsSayWhatTheyAreHeadersFor` | two header rows with the expected texts, row and column spans, `Scope` = `Column`; every body row starts with a `TH` with `Scope` = `Row` |
| `everyRowCoversAllFiveColumns` | with the spans counted, every row fills all five columns: the table is regular |
| `emptyCellsAreStillCells` | the totals row has five cells, two of them empty |
| `headerRowsRepeatedOnTheNextPageAreArtifacts` | header texts occur once in the structure; on page 3 they are pagination artifacts |
| `symbolsHaveReplacementText` | the tick and the dash carry `ActualText` |
| `bulletedListHasThreeItemsWithLabelAndBody` | `L` with `ListNumbering` = `Disc`, three `LI`, each `Lbl` + `LBody` |
| `numberedListContainsANestedList` | `Decimal` list; the body of its second item holds a `P` and an `L` |
| `noteHasAUniqueIdThatIsInTheIdTree` | the `Note` has an `ID`, and the `IDTree` leads from it to the note |
| `paragraphInAnotherLanguageSaysSo` | exactly one element has `Lang` = `ro-RO`; the others rely on the document language |

`TabularReportTest` (4 runs of one test): a report with a column header band and 90 detail
records over three pages, once marked with `pdfua.role` on the cells only, once with the
`start`/`end`/`full` properties of JasperReports, each made into PDF/UA-1 and PDF/UA-2. All four
must give: veraPDF compliant for their part, no warnings, heading + **one** table + the two
paragraphs after it, 91 rows, header cells with `Scope` = `Column`, the header repeated on
pages 2 and 3 as artifacts, nothing unmarked. The last paragraph links to the first page; for
PDF/UA-2 its structure destination must be the heading at the top of page 1, which tests the
fallback for links that point to a page instead of an anchor.

`PdfUa2Test` (13 tests): the PDF/UA-2 output of the demo, compared with the PDF/UA-1 output.

| Test | Proves |
|---|---|
| `taggedAndSignedDocumentsPassPdfUa2Validation` | veraPDF PDF/UA-2: compliant, no failed rule, more than 10,000 checks, also for the signed file; the raw export is not compliant |
| `postProcessingReportsNoProblems` | no warnings; 12 structure destinations (11 bookmarks, 1 link) |
| `declaresPdfUa2OnPdf20` | header `%PDF-2.0`, catalog version 2.0, `pdfuaid:part` 2 and `pdfuaid:rev` 2024; the PDF/UA-1 output still says part 1, PDF 1.7, no revision |
| `sameContentInTheSameOrderAsPdfUa1` | the same structure elements with the same text in the same order as the PDF/UA-1 output, `FENote` for `Note` |
| `everyElementSaysWhichNamespaceItBelongsTo` | `Document` in the PDF 2.0 namespace; every element has a namespace; only `BlockQuote` is in the PDF 1.7 namespace; the root lists both namespaces |
| `aReaderWithoutPdf20SupportStillKnowsEveryType` | a reader that ignores namespaces and follows the root role map finds only PDF 1.7 types: `Title` → `P`, `FENote` → `Note`, nothing else mapped |
| `titleIsAPdf20TitleAndTheNoteAnFENote` | `Title` in the PDF 2.0 namespace; one `FENote` with its `ID`, no `NoteType`; no `Note` left |
| `bookmarksLeadToTheStructureElementsOfTheirHeadings` | all 11 bookmarks: a go-to action, no `Dest`; `SD` names the same heading as `SE`; `D` still a page destination with the same coordinates |
| `linkInsideTheDocumentLeadsToTheHeadingOfSection41` | the link's `SD` is the `H2` "4.1 Signatures"; `D` kept; the PDF/UA-1 output has no `SD` and keeps `Dest` on bookmarks |
| `signatureWidgetsAreDescribedInContentsToo` | widget `Contents` = `TU`; after signing both mention the signer; the PDF/UA-1 widgets have no `Contents` |
| `signingOnlyAppendsToTheFile` | the unsigned PDF/UA-2 file is a prefix of the signed one |
| `everyPaintingOperatorIsEitherTaggedOrAnArtifact` | as for PDF/UA-1, with the same number of painting operators |
| `postProcessingDoesNotChangeTheAppearance` | the PDF/UA-2 file renders to the same pixels as the raw export |

`SignedDocumentTest` (7 tests): the demo after signing.

| Test | Proves |
|---|---|
| `signedDocumentStillPassesPdfUa1Validation` | veraPDF: compliant |
| `signingOnlyAppendsToTheFile` | the unsigned file is a prefix of the signed file, byte for byte |
| `signatureIsValidAndCoversTheWholeFile` | signer name and reason; the signed byte range ends at the end of the file; the CMS signature verifies against these bytes and its certificate (Bouncy Castle) |
| `signedFieldKeepsItsPlaceInTheStructureAndSaysThatItIsSigned` | the widget is still in its `Form` element; the description now ends with "Signed by ..."; the tag tree is unchanged |
| `visibleSignatureUsesAnEmbeddedFont` | the appearance of the signed field uses one font, and it is embedded |
| `otherFieldStaysEmptyAndCanBeSignedAfterwards` | the provider's field is empty; signing it afterwards leaves the first signature's bytes untouched and the file compliant |
| `fieldCannotBeSignedTwice` | signing a signed field, or a field that does not exist, is refused |

Other tests:

| Test | Proves |
|---|---|
| `LayoutAssemblerTest` (9) | the nesting rules on hand-made elements: rows by height, tables end at a paragraph, artifacts do not end a table, table names, header scope, list items with and without label, start and end marks, a row given as a frame, caption, list numbering |
| `SilentTaggerTest` | a report that asks JasperReports for `tagged=true` exports without exception and still ends up compliant |
| `PostProcessorFromFilesTest.rawPdfPlusLayoutFileGiveAPdfUaDocument` | the post-processor works from the two intermediate files alone |
| `PostProcessorFromFilesTest.everythingInsideAnArtifactFrameIsAnArtifact` | an artifact frame hides its children; the result stays compliant and fully marked |
| `PostProcessorFromFilesTest.structureDestinationsFollowTheAnchorsOfTheLayout` | with the anchor moved to another element in the layout, the PDF/UA-2 link follows it: anchors are resolved through the layout, not by position |
| `PostProcessorFromFilesTest.pdfUa2WarnsAboutAListWithLabelsButNoNumbering` | a labelled list without `ListNumbering` gives a warning for PDF/UA-2, none for PDF/UA-1 |
| `ContactDetailsTest` (2) | plain-text e-mail addresses, web addresses and phone numbers are detected; ordinary numbers and dates are not |
| `LinkDescriptionTest` (4) | how the `/Contents` text of a link is built |

What the tests do **not** cover: templates other than the demo and the two small tabular
reports; error paths (missing font, missing title, unknown role, `H` or `TOCI` in a PDF/UA-2
run); the PDF/UA-1 bookmarks beyond their existence; whether a certificate is trusted, valid or
revoked (the test certificate is none of that); the appearance of the signed page.

## Manual checks

### PAC

[PAC](https://pac.pdf-accessibility.org/) (PDF Accessibility Checker) is free and runs on
Windows only.

1. Run `mvn test`, or take [sample-output/02-pdfua.pdf](sample-output/02-pdfua.pdf).
2. Open the file in PAC.
3. **PDF/UA tab:** expected is no error in any checkpoint. Note warnings too.
4. **Screen reader preview:** expected order is: logo, "Service Agreement Summary", the
   introduction, then headings and paragraphs as on the pages. On page 2 two lists, the second
   with a list inside its second item, and a table that is introduced by its caption. The table
   must appear once, with its header rows once, although it ends on page 3. Links are announced
   as links. On page 3, each signature statement is followed by its form field. Header text,
   footer text, page numbers and the watermark must not appear, and the logo only once.
5. **Logical structure:** compare with `02-pdfua-structure.txt`. Select the logo on page 2, the
   lines, the watermark and the header rows of the table on page 3: they must be shown as
   artifacts. Select a header cell: PAC shows its scope.
6. Repeat the PDF/UA check with [sample-output/03-pdfua-signed.pdf](sample-output/03-pdfua-signed.pdf).
7. If PAC offers a WCAG tab, record its findings as well; nothing was checked against WCAG.

On macOS or Linux, PAC needs a Windows virtual machine. [axesCheck](https://check.axes4.com/)
is a web version from the same vendor; it means uploading the PDF to their service, which is
fine for this demo file and worth a thought for real documents.

For the PDF/UA-2 files, find out first whether the PAC version at hand checks PDF/UA-2; the
sessions that built the project could not confirm it. If it does, run the same steps on
[sample-output/02-pdfua2.pdf](sample-output/02-pdfua2.pdf) and its signed version, and also
follow a bookmark and the link "Sign the agreement in section 4.1": both carry a structure
destination. If PAC does not check PDF/UA-2, veraPDF's PDF/UA-2 profile is the only automated
check so far; its command line or its desktop application can be run on the file directly.

### Screen reader

Windows, NVDA or JAWS, with Adobe Acrobat or Acrobat Reader:

1. Read the document from the top (NVDA: `NVDA+Down arrow`). Listen for: the logo announced
   once as a graphic with its alternate text; headings announced with their level; lists
   announced as lists with their number of items; no header, footer or watermark text; words
   not run together at line ends; the Romanian sentence in a Romanian voice, if one is installed.
2. List the headings (`NVDA+F7`): 11 headings, levels 1 and 2.
3. List the links: 6 links. The e-mail and phone links should be recognisable as such by their
   text.
4. Move into the table (`T` jumps to the next table) and through its cells with
   `Ctrl+Alt+arrow keys`. Listen for: 31 rows and 5 columns announced; column headers read when
   moving sideways ("Net", "Gross" under "Price per month in EUR"); the service name read as row
   header when moving up and down; "included" and "not included" instead of the tick and the
   dash; no header rows in the middle of the table where page 3 starts.
5. Press `Tab` through page 3. On each signature field, the description should be read
   ("Customer signature. By signing here you confirm ..."), and in the signed file also
   "Signed by Demo Customer on ...".

macOS: VoiceOver with Adobe Acrobat. Preview's support for tagged PDF is limited, so a bad
result in Preview says little about the file.

### Adobe Acrobat

- *Tags* panel and *Reading Order* tool: the same tree as `02-pdfua-structure.txt`.
- *Accessibility Check* (Acrobat Pro): record the result.
- Open `03-pdfua-signed.pdf`: the signature panel should show one signature that is intact
  ("document has not been modified since") from a signer whose identity is unknown, which is
  right for a test certificate. Sign the second field with a test identity and check the file
  again.

### Without Windows: looking at the tags

These do not replace PAC, but they show what is in the file:

```sh
cat target/pdf/02-pdfua-structure.txt                # written by the flow: tree, attributes, artifacts
pdfinfo -struct-text target/pdf/02-pdfua.pdf         # poppler: tree with text, no artifacts
pdfsig target/pdf/03-pdfua-signed.pdf                # poppler: signature status
mvn exec:java@pdf-debugger                           # PDFBox PDFDebugger
```

In PDFDebugger: `Root > StructTreeRoot` for the structure elements (`K`), the `ParentTree`, the
`RoleMap` and the `IDTree`; `Root > Pages > Kids > [n] > Contents` for the content stream with
the `BDC ... EMC` sequences. [PDFix Desktop Lite](https://pdfix.net/) is a free viewer with a
tags panel that runs on macOS; it was not tried here.

## When a check fails

1. Record it in the results log, with the tool version and the exact message.
2. Find the object: PAC and veraPDF name the page and the structure element or content item.
   `02-pdfua-structure.txt` and PDFDebugger show the same object from this project's side.
3. Decide where the problem belongs:
   - wrong or missing meaning (role, alternate text, order, a cell that should be a header):
     the template, see [template-guide.md](template-guide.md);
   - wrong nesting (rows, tables, lists): the layout. Look at `01-jasper-layout.json` first; if
     the groups are wrong there, the cause is in `LayoutAssembler` or in the marks of the
     template;
   - wrong PDF construction (attributes, parent tree, marked content): the post-processor,
     see [architecture.md](architecture.md);
   - a kind of content the project does not handle: [limitations.md](limitations.md).
4. Add a unit test that fails for the same reason before fixing it.
