# Template guide

How to write a JasperReports template (JRXML, version 7 syntax) that this flow can turn into
PDF/UA. The reference example is
[`src/main/resources/reports/accessible-demo.jrxml`](../src/main/resources/reports/accessible-demo.jrxml);
every recipe below is taken from it, or from the two small reports in
[`src/test/resources/reports`](../src/test/resources/reports).

The post-processor cannot invent meaning. Whatever a screen reader should know (this is a
heading, this image shows that, these cells are a table, this box is where you sign and here is
what you sign) has to be said in the template.

- [Checklist](#checklist)
- [Report properties](#report-properties)
- [Element properties](#element-properties)
- [Recipes](#recipes): [headings](#title-and-headings), [images](#images),
  [header, footer, watermark](#page-header-and-footer-content-that-is-real-only-once),
  [links](#links-e-mail-addresses-phone-numbers), [tables](#tables), [lists](#lists),
  [notes, language, replacement text](#notes-another-language-replacement-text),
  [signature fields](#signature-fields)
- [Reading order](#reading-order)
- [PDF/UA-1 or PDF/UA-2](#pdfua-1-or-pdfua-2)
- [Fonts](#fonts)
- [Pitfalls](#pitfalls-met-while-building-the-demo)
- [Using the flow with your own report](#using-the-flow-with-your-own-report)

## Checklist

- [ ] The report has a title and a language (report properties).
- [ ] Every font comes from a font extension, so that it is embedded.
- [ ] Every heading has `pdfua.role` = `H1`..`H6`, starting with `H1` and without skipping levels.
- [ ] Every meaningful image has `pdfua.alt`. Images without it are treated as decoration.
- [ ] Content in the page header or footer that must be read has an explicit `pdfua.role`.
- [ ] Web addresses, e-mail addresses and phone numbers are hyperlinks (`https:`, `mailto:`, `tel:`).
- [ ] Table cells are marked `TH` or `TD` (or the table component is set to accessible), every
      row has the same number of columns, and header cells are `TH`.
- [ ] List items consist of a label (`Lbl`) and a body (`LBody`).
- [ ] Text in another language than the document says so.
- [ ] Every signature placeholder has a name and a description, and a visible text before it that
      says what is being signed.
- [ ] Elements appear in the JRXML in the order in which they should be read.
- [ ] The `PostProcessingReport` has no warnings, and `02-pdfua-structure.txt` reads like the
      document.

## Report properties

```xml
<property name="net.sf.jasperreports.export.pdf.metadata.title" value="Service Agreement Summary SA-2026-0042"/>
<property name="net.sf.jasperreports.export.pdf.metadata.author" value="Aurora Data Services SRL"/>
<property name="net.sf.jasperreports.export.pdf.metadata.subject" value="..."/>
<property name="net.sf.jasperreports.export.pdf.tag.language" value="en-GB"/>
<property name="net.sf.jasperreports.components.table.accessible" value="true"/>
```

| Property | Required | Effect |
|---|---|---|
| `net.sf.jasperreports.export.pdf.metadata.title` | yes | document title: information dictionary, XMP `dc:title`, window title bar |
| `net.sf.jasperreports.export.pdf.tag.language` | yes (falls back to the locale of the report) | `/Lang` of the document, a BCP 47 tag such as `en-GB` or `ro-RO` |
| `net.sf.jasperreports.export.pdf.metadata.author` | no | author |
| `net.sf.jasperreports.export.pdf.metadata.subject` | no | subject / description |
| `net.sf.jasperreports.components.table.accessible` | when the table component is used | the component marks table, rows, cells and spans itself |

These are standard JasperReports properties, nothing specific to this project. Without a title
or a language the post-processor stops with an error.

## Element properties

Set with `<property name="..." value="..."/>` inside an element, or with
`<propertyExpression name="...">` when the value depends on data.

**Properties of this project**

| Property | Applies to | Values | Effect |
|---|---|---|---|
| `pdfua.role` | text, image, frame | a structure type (below) or `Artifact` | the tag of the element |
| `pdfua.alt` | image | text | alternate text; makes the image a `Figure` |
| `pdfua.scope` | header cell | `Column`, `Row`, `Both` | what the header cell is a header for; normally worked out from the row |
| `pdfua.colspan`, `pdfua.rowspan` | cell | number | columns or rows the cell spans |
| `pdfua.table` | cell, row, table frame | any name | keeps two tables apart that follow each other directly |
| `pdfua.table.summary` | table frame, or any cell | text | summary of the table |
| `pdfua.list` | label, body | any name | keeps two lists apart that follow each other directly |
| `pdfua.list.numbering` | label, body | `Disc`, `Circle`, `Square`, `Decimal`, `UpperRoman`, `LowerRoman`, `UpperAlpha`, `LowerAlpha`, `None` | how the list is numbered; normally read from the first label |
| `pdfua.signature.name` | any element, normally a rectangle | field name, unique in the document | a signature field is created on the element's area |
| `pdfua.signature.description` | same element | text | what the signer confirms; mandatory with `pdfua.signature.name` |
| `pdfua.artifact.type` | artifacts | `Pagination`, `Layout`, `Page`, `Background` | overrides the artifact type |
| `pdfua.artifact.subtype` | artifacts | `Header`, `Footer`, `Watermark` | subtype, with `pdfua.artifact.type` |

**Properties of JasperReports that are understood**

| Property | Values | Effect |
|---|---|---|
| `net.sf.jasperreports.export.pdf.tag.language` (on an element) | language tag | language of that element |
| `net.sf.jasperreports.export.pdf.tag.attribute.ActualText` | text | read instead of the content of the element |
| `net.sf.jasperreports.export.accessibility.tag` | `h1`..`h6`, `artifact`, `table`, `columnheader`, `rowheader`, `note`, `reference` | role, when `pdfua.role` is absent |
| `net.sf.jasperreports.export.pdf.tag.h1` .. `.h6`, `.table`, `.tr`, `.th`, `.td`, `.l`, `.li`, `.lbl`, `.lbody`, `.note`, `.reference` | `start`, `end`, `full` | a group of that type starts or ends with the element; `full`: the element is the group |
| `net.sf.jasperreports.export.pdf.tag.colspan`, `.rowspan` | number | span of a cell |

When an element has both, `pdfua.role` decides what the element itself is.

Accepted structure types are the standard types of PDF 1.7 (`Sect`, `Div`, `BlockQuote`,
`Caption`, `P`, `H1`..`H6`, `L`, `LI`, `Lbl`, `LBody`, `Table`, `TR`, `TH`, `TD`, `Span`, `Quote`,
`Note`, `Code`, `Figure`, `Formula`, ...; the full list is in `layout/Roles.java`) plus `Title`,
which is mapped to `P` in the role map of the PDF. An unknown value stops the generation with
an error. Tested are the types the demo uses: `Title`, `H1`, `H2`, `P`, `Figure`, `BlockQuote`,
`Caption`, `Note`, `Table`, `TR`, `TH`, `TD`, `L`, `LI`, `Lbl`, `LBody`.

### Defaults

| Element | Without `pdfua.role` |
|---|---|
| static text, text field in title, detail, group, summary bands | `P` |
| image with alternate text, outside page header/footer/background | `Figure` |
| image without alternate text | artifact, **silently** |
| anything in `pageHeader`, `pageFooter`, `background` of the report | artifact: `Pagination/Header`, `Pagination/Footer`, `Background` |
| line, rectangle, ellipse | artifact (`Layout`, or the pagination type of its band) |
| borders, backgrounds, underlines of any element | artifact |
| frame | no tag of its own; its children are handled one by one |

Errors that stop the generation: unknown role; `Figure` without alternate text; signature
placeholder without description; duplicate signature field name.

### Nesting

Tables and lists need parents that a report does not have: a cell belongs to a row, a row to a
table. They are worked out from the roles:

| You mark | Added for you |
|---|---|
| cells: `TH`, `TD` | the row (`TR`): cells that start at the same height on the same page; the `Table`: consecutive rows |
| list parts: `Lbl`, `LBody` | the item (`LI`): a label and the body after it; the list (`L`): consecutive items |

A table or list ends by itself when something follows that cannot be part of it, usually the
next paragraph. Lines and other artifacts in between do not end it. A page break does not end
it either: the table continues on the next page as the same table.

Frames can state the nesting explicitly instead (a frame with `pdfua.role` = `TR` around the
cells of a row), and so can the `start`/`end` marks of JasperReports.

## Recipes

### Title and headings

```xml
<element kind="staticText" x="0" y="6" width="495" height="32" style="Title">
    <text><![CDATA[Service Agreement Summary]]></text>
    <property name="pdfua.role" value="Title"/>
</element>

<element kind="staticText" x="0" y="4" width="495" height="22" style="H1">
    <text><![CDATA[1. About this document]]></text>
    <property name="pdfua.role" value="H1"/>
</element>
```

The `style` only changes the look. The tag comes from `pdfua.role` alone: a big bold text
without it is a paragraph. Headings also become bookmarks.

In JasperReports the `title` band is printed *above* the page header on the first page. The
demo wants the header at the top of every page, so its title is the first element of the first
detail band instead.

### Paragraphs

Any text without a role is a `P`. One text element is one paragraph, also when it contains
line breaks.

### Images

```xml
<element kind="image" x="0" y="62" width="300" height="138">
    <expression><![CDATA["images/availability-chart.png"]]></expression>
    <property name="pdfua.alt" value="Bar chart of monthly availability in 2026: January 99.20 percent, ..."/>
</element>
```

Write the alternate text for someone who cannot see the image: say what the image *tells*, not
what it is. An image without `pdfua.alt` is treated as decoration and hidden from assistive
technology without any warning, so forgetting it is easy to miss.

Raster images (PNG, JPEG) and SVG both work. JasperReports converts SVG text to shapes, so SVG
needs no font embedding.

### Page header and footer; content that is real only once

Everything in the page header and footer is an artifact by default, which is right for running
titles and page numbers. To have the logo read once, give it a role on the first page only:

```xml
<pageHeader height="60">
    <element kind="image" x="0" y="0" width="132" height="40">
        <expression><![CDATA["images/logo.svg"]]></expression>
        <property name="pdfua.alt" value="Aurora Data Services logo"/>
        <propertyExpression name="pdfua.role"><![CDATA[$V{PAGE_NUMBER} == 1 ? "Figure" : "Artifact"]]></propertyExpression>
    </element>
    ...
</pageHeader>
```

Do not put hyperlinks into header or footer text. PDF/UA wants every link annotation inside a
`Link` structure element, and an artifact has none; the post-processor then creates a `Link`
without text and reports a warning.

**Watermark.** Whatever is in the `background` band is an artifact of the type `Background`.
The demo states that its image is a watermark:

```xml
<background height="770">
    <element kind="image" x="0" y="0" width="495" height="770">
        <expression><![CDATA["images/watermark.svg"]]></expression>
        <property name="pdfua.artifact.type" value="Pagination"/>
        <property name="pdfua.artifact.subtype" value="Watermark"/>
    </element>
</background>
```

### Lines and boxes

Nothing to do: lines, rectangles, ellipses, borders and backgrounds are always artifacts.

```xml
<element kind="line" x="144" y="2" width="1" height="36">   <!-- vertical: width 1 -->
    <pen lineWidth="1.0" lineColor="#7A7A7A"/>
</element>
```

### Links, e-mail addresses, phone numbers

On a whole element:

```xml
<element kind="textField" x="0" y="106" width="495" height="16" forecolor="#0645AD" underline="true" linkType="Reference">
    <expression><![CDATA["Read the full terms and conditions of the service"]]></expression>
    <hyperlinkReferenceExpression><![CDATA["https://www.example.com/terms"]]></hyperlinkReferenceExpression>
</element>
```

Inside a paragraph, with `markup="styled"`:

```xml
<element kind="staticText" x="0" y="56" width="495" height="46" markup="styled">
    <text><![CDATA[Open a ticket on <a type="Reference" href="https://www.example.com/support"><style forecolor="#0645AD" isUnderline="true">www.example.com/support</style></a>,
write to <a type="Reference" href="mailto:support@example.com"><style forecolor="#0645AD" isUnderline="true">support@example.com</style></a>
or call <a type="Reference" href="tel:+442079460142"><style forecolor="#0645AD" isUnderline="true">+44 20 7946 0142</style></a>.]]></text>
</element>
```

The text is wrapped here for readability. In the JRXML keep it on one line: a line break inside
the text is a line break on the page.

- The URI scheme is what marks the kind of link: `https:`, `mailto:`, `tel:`. Write phone
  numbers in international format without spaces in the `href`.
- Make the link text meaningful on its own ("terms and conditions", not "click here").
- The description of the link annotation is generated: the link text, plus the target in
  parentheses if the text does not already show it.
- A web address, e-mail address or international phone number that is *not* a hyperlink is
  reported as a warning. Only `markup="styled"` is tested; HTML markup is not.

**A link to a place in the same document** works on a whole element only. JasperReports reads
`<a>` in styled text as a link to the outside, whatever its `type` says.

```xml
<element kind="textField" x="24" y="234" width="471" height="16" forecolor="#0645AD" underline="true" linkType="LocalAnchor">
    <expression><![CDATA["Sign the agreement in section 4.1"]]></expression>
    <hyperlinkAnchorExpression><![CDATA["signatures"]]></hyperlinkAnchorExpression>
</element>
...
<!-- the target: a text field (a static text cannot have an anchor) -->
<element kind="textField" x="0" y="74" width="495" height="18" style="H2">
    <expression><![CDATA["4.1 Signatures"]]></expression>
    <anchorNameExpression><![CDATA["signatures"]]></anchorNameExpression>
    <property name="pdfua.role" value="H2"/>
</element>
```

### Tables

A table for a screen reader is: rows of cells, every row with the same number of columns, and
header cells that say whether they head a column or a row. Three ways to get there.

**1. The table component.** Set `net.sf.jasperreports.components.table.accessible` on the
report. JasperReports then marks the table, its rows and cells, column headers and the spans of
grouped headers by itself. This is how the price table of the demo is built; what the template
adds by hand is small:

```xml
<element kind="component" x="0" y="0" width="495" height="60">
    <property name="pdfua.table.summary" value="One row per service with its billing unit, ..."/>
    <component kind="table">
        <datasetRun subDataset="Prices">...</datasetRun>
        <column kind="single" width="195">
            <columnHeader height="34" rowSpan="2" style="TableHeader">...Service...</columnHeader>
            <tableFooter height="18" style="TableHeader">...Total for all services...</tableFooter>
            <detailCell height="16" style="TableCell">
                <element kind="textField" x="4" y="0" width="187" height="16">
                    <expression><![CDATA[$F{service}]]></expression>
                    <!-- the service name is the header of its row -->
                    <property name="pdfua.role" value="TH"/>
                </element>
            </detailCell>
        </column>
        ...
        <column kind="group" width="150">          <!-- a header cell that spans two columns -->
            <columnHeader height="17" style="TableHeader">...Price per month in EUR...</columnHeader>
            <column kind="single" width="75">...Net...</column>
            <column kind="single" width="75">...Gross...</column>
        </column>
    </component>
</element>
```

- **Row headers.** The component marks every body cell as a data cell. Put `pdfua.role` = `TH`
  on the text inside the cell to make the cell a header; it becomes a header of its row.
  (Properties written on the `detailCell` itself do not reach the filled report.)
- **Totals.** Use `tableFooter` for a row that should appear once at the end. A `columnFooter`
  is printed at the bottom of *every* page of the table, with the values so far, and each of
  those becomes a row.
- **Empty cells** must exist: `<tableFooter height="18"/>` for a column without a total. A row
  with a missing cell makes the table irregular.
- **Long tables** just continue. The header rows that JasperReports repeats on the following
  pages are recognised and become artifacts.

**2. Bands, with roles on the cells.** The classic report: a column header band and one detail
band per record. Mark the cells, nothing else
([`tabular-roles.jrxml`](../src/test/resources/reports/tabular-roles.jrxml)):

```xml
<columnHeader height="22">
    <element kind="staticText" x="0" y="0" width="60" height="18" bold="true">
        <text><![CDATA[No.]]></text>
        <property name="pdfua.role" value="TH"/>
    </element>
    ...
</columnHeader>
<detail>
    <band height="18">
        <element kind="textField" x="0" y="0" width="60" height="16">
            <expression><![CDATA[String.valueOf($V{REPORT_COUNT})]]></expression>
            <property name="pdfua.role" value="TD"/>
        </element>
        ...
    </band>
</detail>
```

Cells of a row must start at the same height (same `y` in the band).

**3. Bands, with the JasperReports marks.** For templates that already have them
([`tabular-jasper-tags.jrxml`](../src/test/resources/reports/tabular-jasper-tags.jrxml)): the
first header cell carries `...pdf.tag.table=start`, `...tag.tr=start`, `...tag.th=full`, the
last cell of each row `...tag.tr=end`, and so on. An end mark for the table is not needed.

**Caption.** A text with `pdfua.role` = `Caption` directly before the table becomes the caption
of the table:

```xml
<element kind="staticText" x="0" y="62" width="495" height="14" style="Small">
    <text><![CDATA[Table 1: Monthly prices per service]]></text>
    <property name="pdfua.role" value="Caption"/>
</element>
```

**Two tables in a row** with nothing between them would be read as one. Give their cells
different `pdfua.table` names.

### Lists

Every item is two elements, a label and a body:

```xml
<element kind="staticText" x="8" y="76" width="14" height="16">
    <text><![CDATA[•]]></text>
    <property name="pdfua.role" value="Lbl"/>
</element>
<element kind="staticText" x="24" y="76" width="471" height="16">
    <text><![CDATA[nightly collection of your operational data]]></text>
    <property name="pdfua.role" value="LBody"/>
</element>
```

The numbering style is read from the first label (`•` gives `Disc`, `1.` gives `Decimal`,
`a)` gives `LowerAlpha`); set `pdfua.list.numbering` to state it yourself. An item may consist
of a body only.

**A list inside a list item.** Make the body a frame; what is in it is the content of the item:

```xml
<element kind="staticText" x="8" y="178" width="14" height="16">
    <text><![CDATA[2.]]></text>
    <property name="pdfua.role" value="Lbl"/>
</element>
<element kind="frame" x="24" y="178" width="471" height="52">
    <property name="pdfua.role" value="LBody"/>
    <element kind="staticText" x="0" y="0" width="471" height="16">
        <text><![CDATA[Tell us how you want to receive the reports:]]></text>
    </element>
    <element kind="staticText" x="6" y="18" width="12" height="16">
        <text><![CDATA[–]]></text>
        <property name="pdfua.role" value="Lbl"/>
    </element>
    <element kind="staticText" x="20" y="18" width="451" height="16">
        <text><![CDATA[by e-mail, as an accessible PDF]]></text>
        <property name="pdfua.role" value="LBody"/>
    </element>
    ...
</element>
```

A list written as markup inside one text element (`<ul><li>` in styled text or HTML) is drawn
correctly by JasperReports but stays a single paragraph in the tag tree.

### Notes, another language, replacement text

```xml
<!-- a note or footnote; it gets the unique ID that PDF/UA asks for -->
<element kind="staticText" x="0" y="6" width="495" height="14" style="Small">
    <text><![CDATA[Note: gross prices include value added tax at 20 percent.]]></text>
    <property name="pdfua.role" value="Note"/>
</element>

<!-- a paragraph in Romanian in an English document: a screen reader switches its voice -->
<element kind="staticText" x="0" y="128" width="495" height="16" italic="true">
    <text><![CDATA[Echipa noastră de asistență vă răspunde și în limba română.]]></text>
    <property name="net.sf.jasperreports.export.pdf.tag.language" value="ro-RO"/>
</element>

<!-- a symbol that means nothing when read aloud -->
<element kind="textField" x="2" y="0" width="56" height="16" hTextAlign="Center">
    <expression><![CDATA[$F{support} ? "✓" : "–"]]></expression>
    <propertyExpression name="net.sf.jasperreports.export.pdf.tag.attribute.ActualText"><![CDATA[$F{support} ? "included" : "not included"]]></propertyExpression>
</element>
```

The language applies to a whole element. A few foreign words inside a paragraph cannot be
marked.

### Grouping: frames with a role

```xml
<element kind="frame" x="316" y="62" width="179" height="138">
    <property name="pdfua.role" value="BlockQuote"/>
    <element kind="line" x="0" y="0" width="1" height="138">...</element>          <!-- artifact -->
    <element kind="staticText" x="14" y="4" width="165" height="130">...</element> <!-- P inside BlockQuote -->
</element>
```

A frame with a role becomes a grouping element around its children. A frame with
`pdfua.role` = `Artifact` hides everything inside it. Keep the content of one group in one
frame: a frame without a role that holds table cells *and* other content confuses the nesting.

### Signature fields

```xml
<!-- 1. the visible statement of what is signed, immediately before the field -->
<element kind="staticText" x="0" y="100" width="235" height="62">
    <text><![CDATA[Customer signature. By signing in the field below you confirm that you have read this summary and that you accept Service Agreement SA-2026-0042.]]></text>
</element>
<!-- 2. the placeholder: its rectangle is drawn, its area becomes the signature field -->
<element kind="rectangle" x="0" y="166" width="235" height="60" backcolor="#F4F7FA">
    <pen lineWidth="0.75" lineColor="#555555"/>
    <property name="pdfua.signature.name" value="CustomerSignature"/>
    <property name="pdfua.signature.description" value="Customer signature. By signing here you confirm that you have read this summary and that you accept Service Agreement SA-2026-0042."/>
</element>
```

- The **visible text** is read in the normal reading flow and is there for everyone.
- The **description** is announced when the field gets focus. A user who tabs straight to the
  field hears nothing else, so the description must be complete on its own: who signs, and what
  the signature confirms.
- Order matters: the statement must come directly before the placeholder in the JRXML.

**Signing** such a field in code:

```java
SigningIdentity signer = ...;   // private key + certificate chain; SigningIdentity.selfSigned("...") for tests
PdfSigner.sign(Path.of("out/pdfua.pdf"), Path.of("out/signed.pdf"), "CustomerSignature", signer,
        new PdfSigner.Wording(
                "I have read the summary and accept Service Agreement SA-2026-0042",          // reason
                List.of("Signed by " + signer.name(), "on 17 November 2026"),                  // shown in the field
                "Signed by " + signer.name() + " on 17 November 2026."));                      // added to the description
```

The signed file is still PDF/UA. The last text is what makes a screen reader say that the
field is signed; write it in the language of the document.

### Page breaks

```xml
<element kind="break" x="0" y="0" width="495" height="1"/>
```

## Reading order

The reading order is the order of the elements in the filled report: band after band, and
inside a band the order in which the elements are written in the JRXML. It is **not** derived
from positions on the page. Two columns side by side are read correctly only if all elements
of the left column come first in the JRXML, as in the signature section of the demo.

Check it in `02-pdfua-structure.txt`: the file lists the content exactly in the order a screen
reader presents it.

## PDF/UA-1 or PDF/UA-2

A template does not say which part of PDF/UA it is made for; the same template gives either
document (`--pdfua=2` on the demo, `PdfUaPart.PDF_UA_2` in code). Roles stay in the vocabulary
of this guide, PDF 1.7 plus `Title`; the post-processor writes them as PDF 2.0 types where needed
(`Note` becomes `FENote`, for example). What to keep in mind when a template should also work
for PDF/UA-2:

- **No `H`, no `TOCI`.** A PDF/UA-2 run stops with an error when a template uses the unnumbered
  heading `H` (PDF/UA-2 forbids it) or `TOCI` (PDF/UA-2 requires a link from every entry to its
  target, which cannot be expressed). Use `H1`..`H6`.
- **Lists with labels need a numbering.** PDF/UA-2 requires `ListNumbering` on every list that
  has labels. It is read from the first label (`•`, `1.`, `a)`, ...). For a label it cannot
  read, set `pdfua.list.numbering`, otherwise veraPDF rejects the file.
- **Links inside the document are best made with anchors.** PDF/UA-2 makes such a link lead to a
  structure element. For a link to an `anchorNameExpression` that element is known exactly; for
  a link to a page (`linkType="LocalPage"`) it is the first element at the top of that page.
- **Footnotes that are cited in the text** (a reference mark that points to the note) cannot be
  expressed: PDF/UA-2 wants a two-way `Ref` between the mark and the note. A `Note` that nothing
  cites, like the one under the demo table, is fine.

## Fonts

PDF/UA requires embedded fonts. JasperReports embeds a font only if it comes from a *font
extension*; the demo uses DejaVu Sans from `jasperreports-fonts`. Two safety nets catch other
fonts:

- a font that is neither in an extension nor installed on the machine fails at fill time
  (`net.sf.jasperreports.awt.ignore.missing.font=false`);
- a font that is installed but has no extension passes JasperReports and ends up as a standard
  font that is not embedded. The post-processor rejects such a PDF and names the font.

## Pitfalls met while building the demo

- **Text that does not fit is cut off silently.** A static text has a fixed height; what does not
  fit is not printed and therefore not tagged. The layout file still shows the full text. Look at
  the rendered page.
- **Properties need the `pdfua.` prefix registration.** `jasperreports.properties` must contain
  `net.sf.jasperreports.print.transfer.pdfua=pdfua.`, otherwise the properties never reach the
  filled report and every element silently gets its default role.
- **Overlapping elements confuse the matching.** Content is assigned by position; if two text
  elements overlap, text can end up in the wrong tag. Keep elements apart. (An image behind
  everything, like the watermark, is fine.)
- **A text split over two pages becomes two paragraphs**, one per page.
- **Line wraps.** JasperReports drops the space at the end of a wrapped line; the post-processor
  puts it back. A line that ends with a hyphen is treated as a word that continues.
- **Table component: `columnFooter` repeats on every page**, and properties on a cell element
  are dropped. See the table recipe.
- **Styled text cannot link into the document.** Use a hyperlink on the whole element.
- **Rows are recognised by height.** In a band-based table, cells of one row that do not start
  at the same `y` end up in different rows. Wrap the row in a frame with `pdfua.role` = `TR` if
  the cells cannot be aligned.

## Using the flow with your own report

`PdfUaPipeline` runs the demo template. For a real report, call the steps yourself:

```java
JasperPrint print = JasperFillManager.fillReport(report, parameters, dataSource);   // your own fill

Path raw = Path.of("out/raw.pdf");
JasperReportGenerator.exportPdf(print, raw);                    // or your own JRPdfExporter setup

DocumentLayout layout = JasperLayoutExtractor.extract(print);   // roles, positions, nesting
LayoutJson.write(layout, Path.of("out/layout.json"));           // optional: keep it

PostProcessingReport result = PdfUaPostProcessor.process(raw, layout, Path.of("out/pdfua.pdf"));
if (!result.warnings().isEmpty()) {
    // something in the template needs attention
}
```

Your application needs the two settings from `jasperreports.properties` (silent tagger
factory, `pdfua.` transfer prefix), and the export must not encrypt the PDF.
