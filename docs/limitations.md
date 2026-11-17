# Limitations

What does not work, what has never been tried, and where the approach itself is fragile.
This is a proof of concept built around **one** three-page document and two small test reports.
Everything below should be assumed until someone shows otherwise.

## Not verified

| What | Why it matters |
|---|---|
| **PAC has not been run.** | PAC is the tool the project was meant to satisfy. veraPDF implements the same machine-checkable conditions, but it is a different program. |
| **The PDF/UA-2 output has been seen by veraPDF and poppler only.** | Whether PAC checks PDF/UA-2 is not known. No reader or screen reader has opened it. Support for PDF 2.0 varies: poppler dropped `Title` and `FENote` with their text until the fallback role map was added, and other software may have gaps the fallback does not cover, such as structure destinations. |
| **No screen reader has read the file.** | "A screen reader should properly read that PDF" was a goal. The tag tree says it should; nobody has listened. This matters most for the table: whether headers are announced while moving through cells can only be heard. |
| **The files have not been opened in Adobe Acrobat.** | The signature fields, the signature, tab order and bookmarks have only been inspected as PDF objects and with poppler. |
| **No signature with a real certificate.** | The demo signs with a self-signed certificate made up during the build. Signing with a qualified certificate, a token or a remote signing service has not been tried. |
| **No human has reviewed the accessibility.** | Reading order, alternate texts, link texts, which cells are headers and the table summary were written by the model that wrote the code. |
| **The GitHub Actions workflow has not run.** | It was written before the first push. The same Maven command passes in a clean Ubuntu container. |
| **Only three templates have been processed.** | Every statement about "what works" is about the demo document and the two tabular test reports. |

## Not supported

**Tables**

- No `THead`, `TBody`, `TFoot` grouping. Header rows are ordinary rows of `TH` cells.
- No `Headers`/`ID` association between cells; header cells are described by `Scope` only. That
  is enough for simple and two-level headers, not for irregular tables.
- No check that a table is regular. A row with a missing cell, or spans that do not add up,
  produce an invalid table; veraPDF reports it, the post-processor does not.
- Tables inside table cells, and cells that are split by a page break, are untested.
- A `columnFooter` of the table component is printed on every page and becomes a row each time.
- Properties written on a cell of the table component are dropped by JasperReports; marks have
  to be on the content of the cell.

**Lists**

- A list written as markup inside one text element (`<ul>`, `<ol>`, `<li>` in styled text or
  HTML) stays one paragraph. Items have to be separate elements.
- `ListNumbering` is derived from the first label only. Whether it is right is not checked.

**Other structure**

- **Table of contents, references, formulas, captions of figures.** Role names are accepted,
  nothing more. A `Caption` is attached to a table that follows it; a caption of a figure stays
  a sibling of the figure.
- **Language inside a paragraph.** A language can be stated for a whole element, not for a few
  words within it.
- **Text elements that span pages.** A text that JasperReports splits at a page break becomes
  two paragraphs. (Tables and lists do continue across pages.)
- **Heading sanity.** The post-processor tags what the template says. It does not check that
  headings start at `H1` or skip no level.

**PDF features**

- **No PDF/A.** The XMP packet has no PDF/A identification, for either part of PDF/UA.
- **PDF/UA-2: only the PDF 1.7 vocabulary in templates.** The PDF 2.0 types that PDF 1.7 lacks
  (`Aside`, `Em`, `Strong`, `Sub`, `DocumentFragment`, `FENote` by that name, headings below
  `H6`) cannot be requested in a template.
- **PDF/UA-2: no citations of notes.** A text that cites a footnote (a reference mark) and the
  `FENote` it cites would need `Ref` entries in both directions; the layout cannot express that.
- **PDF/UA-2: `H` and `TOCI` are refused** with an error, because PDF/UA-2 forbids `H` and
  requires a `Ref` from every `TOCI` to its target.
- **PDF/UA-2: nothing else of PDF 2.0.** The file is PDF 2.0 in its version and in what PDF/UA-2
  checks. Other PDF 2.0 recommendations, such as leaving out the document information
  dictionary, associated files, or the newer `ListNumbering` values (`Ordered`, `Unordered`),
  are not used.
- **Encrypted PDFs** are refused.
- **Form fields other than signatures.** JasperReports can create text fields and check boxes;
  such widgets are not put into the structure tree (they produce a warning). Untested; their
  appearances probably use a font that is not embedded, which PDF/UA does not allow.
- **Annotations other than links.** Same.
- **Already tagged input.** The post-processor expects a PDF without marked content and replaces
  any existing structure tree. Running it twice on the same file is not supported; existing
  marked content produces a warning.

**Signing**

- The signature format is the classic `adbe.pkcs7.detached` with SHA-256 and RSA. No PAdES
  baseline profile is claimed.
- No time stamp, no embedded revocation information (no long-term validation), no
  certification level (DocMDP), no field locks.
- The visible signature is a few lines of text. No image, no layout options.
- The post-processor must run before signing: it rewrites the file, which would destroy an
  existing signature.

**Links**

- **Plain-text contact details are not converted.** An e-mail address, web address or
  international phone number that is not a hyperlink is reported as a warning, nothing more.
  National phone formats (no `+` or `00` prefix) are not even detected.
- **Links in page headers or footers** end up as a `Link` element without text, plus a warning.
- **Links into the document** work for whole elements only, because JasperReports does not
  support them inside styled text. Remote links to other files are untested.
- **Images with a hyperlink** are untested. They would be tagged `Figure > Link`; `Link > Figure`
  is the better structure.

## Untested JasperReports features

None of these appears in the templates. Some will work, some will not; nobody knows which.

- subreports, crosstabs, the list component
- charts, barcodes, maps and other generic or custom elements (content of an element type the
  extractor does not know becomes an artifact, with a warning for its text and images)
- `markup="html"` and `markup="rtf"` (only `markup="styled"` is used)
- rotated text
- right-to-left and complex scripts, and JasperReports' glyph renderer for them
- multiple columns, groups, group headers that repeat on every page
- batch export of several reports, report books and parts
- page offset and page size export options (`...export.pdf.odd.page.offset.x`,
  `...size.page.to.content`): they shift content relative to the layout coordinates
- large reports: the post-processor keeps the whole document in memory, and performance has not
  been measured. The demo (3 pages, about 420 marked-content sequences) takes a few seconds.

## Fragile by design

The approach has assumptions built in. They hold for the templates here and are easy to break.

**Matching by position.** Page content is assigned to the layout element whose box contains
the centre of what was painted. This fails when two text elements overlap, when content is
drawn outside its element box, or when the export moves content (offsets, scaling). The failure
is partly visible: text that no element accounts for produces a warning. Text that lands in the
*wrong* element produces none.

**Dependence on how OpenPDF writes text.** The inline link tagging relies on each styled chunk
of text being its own `Tj` operator, so that a chunk is either inside a link or not. A different
PDF writer, or a JasperReports setting that merges chunks, would need a text operator to be
split, which the post-processor cannot do.

**Rows are recognised by height.** When the template does not mark rows, cells that start at
the same height on the same page are one row. Cells of one row that are not aligned are split
into several rows; two rows printed at the same height (two tables side by side) would be
merged.

**A table ends when something else follows.** Without an end mark, a table or list ends at the
first element that cannot be part of it. Two tables directly after each other, with nothing in
between, become one unless their cells carry different `pdfua.table` names.

**Repeated header rows are recognised by their text.** The first rows on a continuation page
are compared with the header rows at the start of the table. A header that is printed with
different text on later pages ("continued") is kept as a row in the middle of the table. A data
row that happens to equal the header would be dropped as a repetition if it is the first row on
a page.

**Split frames are joined by their origin.** A frame that follows the frame closed last, comes
from the same template element and sits on a later page with only artifacts in between is taken
as its continuation. Two separate tables from the same template element (a table component in a
repeating band) that happen to meet at a page break would be joined.

**The JasperReports marks are implemented from their documentation.** The `start`/`end`/`full`
protocol and the properties the table component writes were read from the JasperReports sources.
The commercial tagger that normally consumes them is not available, so identical behaviour in
every corner case cannot be claimed.

**Glyph boxes are approximate.** The vertical extent of text is assumed to be 0.8 of the font
size above and 0.2 below the baseline. Only the centre of the box is used for matching, with a
tolerance of 1.5 pt, so this is uncritical for normal text.

**Word space restoration is a heuristic.**

- A line ending with a hyphen is treated as a broken word and gets no space. A dash at the end
  of a line would be wrongly glued to the next word.
- Languages written without spaces (Chinese, Japanese, Thai) would get spaces they should not
  have.
- The space is taken from the same font on the same page. If that font never shows a space on
  the page, the space is not restored and a warning is produced.
- Artifacts are left alone: the repeated header "24/7 support" reads "24/7support" in the
  artifact listing, which no reader sees.

**Reading order is template order.** The structure follows the order of the elements in the
filled report. Nothing checks it against the visual layout.

**Artifacts are the fallback.** Anything that is not recognised as content becomes an artifact.
That keeps the file valid, and it means a mistake in a template (a forgotten alternate text, a
role property that never reached the filled report) hides content from assistive technology or
demotes it to a plain paragraph instead of failing loudly. The warnings catch some of these
cases, not all: an image without alternate text is hidden silently.

**One XObject, two meanings.** The logo is one form XObject, tagged as a `Figure` on page 1 and
marked as an artifact on the other pages. veraPDF accepts this; PAC's verdict is unknown.

**Structure destinations of links to a page are a guess.** For PDF/UA-2, a link to an anchor
leads to the element with that anchor, exactly. A link to a page (`LocalPage`) leads to the
first structure element in reading order that starts at or below the top of that page, which
is usually, not necessarily, what a reader expects.

## Project limits

- **Not a library.** No published artifact, no stable API, no command line tool for the
  post-processor or the signer alone (`PdfUaDemo` runs the demo flow; other use is through the
  Java classes).
- **Jackson is an undeclared dependency.** `LayoutJson` uses Jackson, which arrives through
  `jasperreports`. A separate post-processor module would have to declare it.
- **The signer takes its font from `jasperreports-fonts`.** `PdfSigner` loads DejaVu Sans from
  the classpath location of that jar. Without the jar, another TrueType font has to be supplied
  in the code.
- **Stock OpenPDF instead of the build JasperReports asks for.** The POM excludes
  `openpdf 1.3.43.jaspersoft.2` (not on Maven Central) and uses the stock 1.3.43. JasperReports
  supports this; one logger that complains about it on every start is silenced in code.
- **`exec-maven-plugin` is held at 3.5.1** because newer versions need Maven 3.9 and the
  development machine has 3.8.4.
- **Error handling is minimal.** Problems with the input are `IllegalStateException` or
  `IllegalArgumentException` with a message; there is no error model.
- **The test suite is slow for its size** (about one and a half to three minutes, depending on
  the machine), because veraPDF validates fourteen files in it, five of them with the large
  PDF/UA-2 profile.
- **The sample files in `docs/sample-output` are a copy.** They are regenerated by hand
  (`mvn test`, then copy from `target/pdf` and `target/pdf-ua2`) and can lag behind the code.

## What would come next

In rough order of value:

1. Run PAC and a screen reader on the demo, unsigned and signed, and record the results; for the
   PDF/UA-2 variant, find a second validator besides veraPDF.
2. A second and third document template, with data, a subreport, a band-based table that has
   group headers, and a page-spanning text.
3. `THead`/`TBody` and `Headers`/`ID` for tables that `Scope` cannot describe.
4. Lists from styled-text or HTML markup.
5. Signing for real: a certificate from a trust service, a time stamp, PAdES.
6. Interactive form fields (text, check box) with embedded fonts and descriptions.
7. Evaluate the alternative design: an own implementation of JasperReports' `PdfTagger`
   interface, which would mark content during export and remove the positional matching.
