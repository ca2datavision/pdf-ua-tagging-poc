# PDF/UA compliance map

Which requirement of PDF/UA-1 (ISO 14289-1) is handled where, and what checks it. Read this
together with [verification.md](verification.md): a requirement that is "met" below is met
according to the code and the automated checks, not according to PAC or a screen reader, which
have not been run.

> **About the clause numbers.** Rule ids such as `7.1-3` are taken from the veraPDF report
> (clause of ISO 14289-1:2014 plus the validator's test number). The other clause numbers of the
> PDF/UA-1 part were written from the model's knowledge of the standard and not checked against a
> copy of it. Verify them before quoting them anywhere. The clause numbers of the PDF/UA-2
> section are all taken from the veraPDF PDF/UA-2 profile.

The first part of this document is about PDF/UA-1, the default. The differences for PDF/UA-2
are in [the last section](#pdfua-2).

## Before and after

veraPDF 1.30.4, PDF/UA-1 profile, on the three demo files (the committed report is
[sample-output/04-verapdf-pdfua1-report.txt](sample-output/04-verapdf-pdfua1-report.txt)):

| File | Result | Checks performed |
|---|---|---|
| `01-jasper-raw.pdf` (JasperReports export) | **not compliant**, 8 rules fail | about 2,000 |
| `02-pdfua.pdf` (after post-processing) | **compliant**, 0 rules fail | about 17,600 |
| `03-pdfua-signed.pdf` (one field signed) | **compliant**, 0 rules fail | about 17,800 |

The number of checks rises because a tagged file has far more to check (every structure
element, every marked-content sequence).

The 8 rules the raw file fails, all of which pass afterwards:

| veraPDF rule | Failures | Requirement (validator's wording, shortened) | Fixed by |
|---|---|---|---|
| 6.2-1 | 1 | catalog has `MarkInfo` with `Marked` = true | `PdfUaPostProcessor` |
| 7.1-3 | 930 | content shall be marked as artifact or tagged as real content | `PageTagger` (marked content) |
| 7.1-8 | 1 | catalog has a `Metadata` stream | `XmpMetadata` |
| 7.1-10 | 1 | `ViewerPreferences` has `DisplayDocTitle` = true | `PdfUaPostProcessor` |
| 7.1-11 | 1 | logical structure rooted in `StructTreeRoot` | `StructureBuilder` |
| 7.18.1-2 | 7 | an annotation has `Contents` or an `Alt` in its structure element | `PageTagger` (link description) |
| 7.18.5-1 | 7 | links are tagged with a `Link` element | `PageTagger` + `StructureBuilder` |
| 7.18.5-2 | 7 | links have an alternate description in `Contents` | `PageTagger` (link description) |

Rules the raw file already passes, because JasperReports gets them right when the template is
set up properly: fonts are embedded and have Unicode mappings, the document has a language.

A tagged file can fail rules that an untagged one cannot: a table whose rows have different
numbers of columns, a header cell without scope, a note without ID, a list item outside a list.
veraPDF checks these on the final file, and they pass.

## Requirement by requirement

| Requirement | Clause | How it is met | Checked by |
|---|---|---|---|
| PDF/UA identifier `pdfuaid:part` = 1 in the XMP metadata | 5 | `XmpMetadata` | veraPDF; `documentLevelRequirementsAreMet` |
| File declares itself tagged (`MarkInfo/Marked`) | 6.2 | `PdfUaPostProcessor` | veraPDF 6.2-1; same test |
| All real content is tagged, everything else is an artifact | 7.1 | `PageTagger`: every painting unit gets exactly one of the two | veraPDF 7.1-3; `everyPaintingOperatorIsEitherTaggedOrAnArtifact` |
| Artifacts are not part of the structure, tagged content is not inside artifacts | 7.1 | one flat marked-content sequence per group, never nested | veraPDF; same test (`artifact XOR tagged`) |
| Structure tree in logical reading order | 7.1 | elements are appended in the order of the filled report | `structureTreeFollowsTheReadingOrderOfTheReport` for the demo. *Whether the order is sensible is a human judgement.* |
| Only standard structure types, others role mapped | 7.1 | `Roles`; role map `Title` → `P` | veraPDF; `onlyStandardStructureTypesAreUsedAfterRoleMapping` |
| Structure rooted in `StructTreeRoot`, parent tree | 7.1 | `StructureBuilder` | veraPDF 7.1-11; link and signature tests resolve annotations through the parent tree |
| Metadata stream with `dc:title` | 7.1 | `XmpMetadata` | veraPDF 7.1-8; `documentLevelRequirementsAreMet` |
| Title shown in the title bar (`DisplayDocTitle`) | 7.1 | `PdfUaPostProcessor` | veraPDF 7.1-10; same test |
| Natural language of the text is declared | 7.2 | `/Lang` in the catalog, from the report property; `/Lang` on a structure element whose template element states another language | veraPDF; same test; `paragraphInAnotherLanguageSaysSo`. Whole elements only, not words inside a paragraph. |
| Characters map to Unicode | 7.2, 7.21 | done by JasperReports for extension fonts | veraPDF |
| Figures have alternate text | 7.3 | `pdfua.alt`; the extractor refuses a `Figure` without it | veraPDF; `figuresHaveAlternateTextAndABoundingBox`. *Whether the text is a good description is a human judgement.* |
| Headings are tagged, levels are not skipped | 7.4 | `pdfua.role` in the template | `headingsAreTaggedAndDoNotSkipLevels` for the demo. Not enforced by the post-processor for other templates. |
| Tables: `Table`, `TR`, `TH`, `TD`; header cells have a scope; rows are regular | 7.5 | `LayoutAssembler` builds rows and tables, derives `Scope`, takes over row and column spans; `StructureBuilder` writes the attributes; empty cells are kept | veraPDF (structure, scope, regularity); `TablesAndListsTest`, `TabularReportTest`. No `THead`/`TBody`, no `Headers`/`ID`. |
| Lists: `L`, `LI`, `Lbl`, `LBody` | 7.6 | `LayoutAssembler` builds items and lists, also nested; `ListNumbering` from the first label | veraPDF (structure); `bulletedListHasThreeItemsWithLabelAndBody`, `numberedListContainsANestedList`. *Whether the numbering value is right for an ordered list is a human check.* |
| Mathematical expressions | 7.7 | not present | – |
| Page headers and footers are pagination artifacts | 7.8 | default for the `pageHeader`/`pageFooter` bands | `pageHeaderAndFooterAreArtifactsAndNotInTheStructureTree`, `logoOnFollowingPagesIsAPaginationArtifact` |
| Notes have a unique ID | 7.9 | `StructureBuilder` gives every `Note` an `/ID` and writes the `IDTree` | veraPDF; `noteHasAUniqueIdThatIsInTheIdTree` |
| Optional content, embedded files, article threads | 7.10–7.12 | not present in the output | veraPDF |
| Digital signatures, form fields | 7.13, 7.18.4 | signature field with `/TU`, widget inside a `Form` element; signing keeps both, embeds the font of the visible signature and adds the signed state to `/TU` | veraPDF on the unsigned and the signed file; `signatureFieldsAreAccessible`, `everySignatureFieldIsPrecededByAVisibleLabelSayingWhatIsSigned`, `SignedDocumentTest` |
| XFA, security | 7.15, 7.16 | no XFA; encrypted input is refused | veraPDF |
| Navigation | 7.17 | bookmarks generated from the headings | `documentLevelRequirementsAreMet` (bookmarks exist) |
| Annotations are in the structure tree and have a description | 7.18.1 | link annotations in `Link`, widgets in `Form`; `/Contents` on links | veraPDF 7.18.1-2; `everyLinkAnnotationIsTaggedAsLinkWithADescription` |
| Tab order follows the structure | 7.18.3 | `/Tabs /S` on every page | veraPDF; `documentLevelRequirementsAreMet` |
| Links | 7.18.5 | `Link` element with the link text and an object reference to the annotation | veraPDF 7.18.5-1, 7.18.5-2; `webEmailAndPhoneLinksAreInsideTheirParagraph` |
| XObjects | 7.20 | a form XObject (SVG image) is tagged as a whole through the marked content around its `Do` | veraPDF |
| Fonts are embedded | 7.21 | font extension in the template; precondition check in the post-processor | veraPDF |

## The three goals of the original request

| Goal | Evidence |
|---|---|
| 1. Everything is tagged | veraPDF rule 7.1-3 passes (it failed 930 times before); `everyPaintingOperatorIsEitherTaggedOrAnArtifact` walks every page content stream and fails on any painting operator outside marked content |
| 2. Everything that should be an artifact is one | logo: `Figure` on page 1, `Pagination/Header` artifact on pages 2 and 3, same XObject (`logoIsAFigureOnTheFirstPageOnly`, `logoOnFollowingPagesIsAPaginationArtifact`); every path on every page is an artifact (`linesAndOtherDrawingsAreArtifacts`); header and footer text is absent from the structure tree (`pageHeaderAndFooterAreArtifactsAndNotInTheStructureTree`); the watermark on every page (`watermarkIsAnArtifactOnEveryPage`); table header rows repeated on the next page (`headerRowsRepeatedOnTheNextPageAreArtifacts`) |
| 3. Links, e-mail addresses and phone numbers are marked | all 7 link annotations sit in `Link` elements and have `/Contents`; the five links to the outside (`https:` ×3, `mailto:`, `tel:`) are children of their paragraph with the expected text, and the link inside the document is tagged the same way (`everyLinkAnnotationIsTaggedAsLinkWithADescription`, `webEmailAndPhoneLinksAreInsideTheirParagraph`, `linkToAPlaceInTheDocumentIsTaggedToo`). Contact details that are *not* links produce a warning (`ContactDetailsTest`), and the demo has no warnings. |

## What no validator can tell you

veraPDF and PAC check the conditions a machine can decide. PDF/UA also contains requirements
that need a person, and this project has had none of that review:

- Is the reading order the order a human would read in?
- Are the right cells marked as headers, and is the table summary useful?
- Is `Decimal` (or `Disc`, ...) the right description of how a list is numbered?
- Does every tag fit its content? (A paragraph tagged `H1` passes every validator.)
- Does each alternate text really describe its image?
- Is the link text understandable out of context?
- Is the declared language the language of the text?
- Do the signature descriptions tell the signer what they need to know?
- Colour contrast and other WCAG criteria that are not part of PDF/UA.

## Expected in PAC, but unverified

A few things were done specifically because PAC is known to look at them. This comes from the
model's knowledge of PAC, not from a PAC run:

- every `Figure` has a `Layout` attribute with a bounding box;
- every `TH` has a `Scope`, and every table row has the same number of columns once spans are
  counted;
- link annotations have `/Contents` and widgets have `/TU`;
- the first heading is an `H1`;
- the XMP packet contains the PDF/UA identifier and the title.

Constructions that are unusual and worth watching in PAC:

- the logo is a single form XObject that is tagged content on page 1 and an artifact on the
  other pages;
- the table has one `Table` element with rows on two pages, and the repeated header rows of the
  second page are artifacts of the type `Pagination` without a subtype;
- cells contain a `P` (the table component) or text directly (band-based tables);
- the visible signature is an appearance stream whose content is marked as an artifact.

veraPDF accepts all of them.

## PDF/UA-2

The PDF/UA-2 variant (`--pdfua=2`, `PdfUaPart.PDF_UA_2`) has the same content and structure as
the PDF/UA-1 document; everything above about tagging, artifacts, tables, lists, links and
signature fields applies to it as well. This section covers what ISO 14289-2:2024 asks for in
addition, or differently. How it is implemented: [architecture.md, section 7](architecture.md#7-pdfua-1-and-pdfua-2).

### Before and after

veraPDF 1.30.4 with its PDF/UA-2 profile, which checks the 92 machine-checkable rules of
ISO 14289-2 together with the 1,636 nesting rules of ISO/TS 32005 (the committed report is
[sample-output/04-verapdf-pdfua2-report.txt](sample-output/04-verapdf-pdfua2-report.txt)):

| File | Result | Checks performed |
|---|---|---|
| `01-jasper-raw.pdf` (JasperReports export) | **not compliant**, 7 rules fail | about 2,800 |
| `02-pdfua.pdf` (the PDF/UA-1 output) | **not compliant**, 8 rules fail (see below) | about 31,700 |
| `02-pdfua2.pdf` (after post-processing for PDF/UA-2) | **compliant**, 0 rules fail | about 31,700 |
| `03-pdfua2-signed.pdf` (one field signed) | **compliant**, 0 rules fail | about 31,900 |

The PDF/UA-1 row comes from a manual run during development and is not part of the tests.

### What PDF/UA-1 output lacks for PDF/UA-2

| veraPDF rule | Requirement (validator's wording, shortened) | Handled by |
|---|---|---|
| 5-2, 5-5 | `pdfuaid:part` is 2, `pdfuaid:rev` is 2024 | `XmpMetadata` |
| 6.1-1 | the file version is 2.0 | `PdfUaPostProcessor` (header and catalog) |
| 8.2.5.2-2 | the structure tree root contains one `Document` element, in the PDF 2.0 namespace | `StructureBuilder`, `StructureTypes` |
| 8.2.5.14-1 | no `Note` unless role mapped into the PDF 2.0 namespace | `StructureTypes`: `FENote` |
| 8.8-1 | destinations inside the document are structure destinations (`Dest`) | `StructureDestinations`, bookmarks in `PdfUaPostProcessor` |
| 8.8-2 | the same, for go-to actions | `StructureDestinations` |
| 8.10.2.3-1 | a widget without a label has `Contents` | `SignatureFields`; `PdfSigner` keeps it up to date |

### Other PDF/UA-2 requirements that matter here

| veraPDF rule | Requirement | Status |
|---|---|---|
| 8.2.4-1, -4 | all structure elements belong to, or are role mapped to, the PDF 1.7, PDF 2.0 or MathML namespace | met: PDF 2.0, and PDF 1.7 for `BlockQuote` |
| 8.2.5.12-1 | no `H` | enforced: a template with `H` stops a PDF/UA-2 run |
| 8.2.5.8-1 | every `TOCI` references its target with `Ref` | not supported: a template with `TOCI` stops a PDF/UA-2 run |
| 8.2.5.14-2, -3 | text that cites a footnote and the `FENote` reference each other with `Ref` | met trivially: nothing cites the note. Citations cannot be expressed in the layout |
| 8.2.5.14-4 | `NoteType` is `Footnote`, `Endnote` or `None` | met: not written, default `None` |
| 8.2.5.20-1, -2 | link annotations in `Link` (or `Reference`) elements, one element per target | met, as for PDF/UA-1 |
| 8.2.5.25-1 | a list with `Lbl` elements has a `ListNumbering` other than `None` | met for the demo; a warning if a template's labels cannot be read |
| 8.2.5.26-1 to -6 | regular tables; header cells that can be associated with data cells | met: `Scope` on every `TH` |
| 8.2.5.27-1 | a `Caption` is the first or last child | met |
| 8.2.5.28.2-1 | a `Figure` has `Alt` or `ActualText` | met |
| 8.9.3.3-1 | pages with annotations have `Tabs` `A`, `W` or `S` | met: `S` |
| 8.9.4.2-1 | an annotation's `Contents` equals the `Alt` of its structure element, if both exist | met: the `Link` and `Form` elements have no `Alt` |
| 8.10.1-1, -2 | every widget in a `Form` element, at most one per `Form` | met |
| 8.10.3.5-1 | a graphic in the appearance of a signature has alternative text | met: the visible signature is text, marked as an artifact |
| 8.11.1-1, -2 | metadata stream with `dc:title` | met |
| 8.11.2-1 | `DisplayDocTitle` true | met |
| ISO 32005 | the nesting of structure types (`Form` must not contain `P`, `L` only `LI` and `Caption`, ...) | met: no combination the demo produces is forbidden |

### Not verified for PDF/UA-2

- **PAC.** Whether PAC validates PDF/UA-2 documents at all is not known.
- **Readers and assistive technology.** PDF 2.0 namespaces, `Title`, `FENote` and structure
  destinations are newer than most of the software that would present them. One data point:
  poppler 26.02 did not know `Title` and `FENote` and dropped them with their text, until a
  fallback role map (`Title` → `P`, `FENote` → `Note`) was added for such readers. Whether a
  screen reader announces the `FENote` as a note, or follows a structure destination differently
  from a page destination, has not been tried.
- Everything a machine cannot judge, exactly as for PDF/UA-1.
