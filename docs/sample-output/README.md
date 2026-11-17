# Sample output

The files of one run of the demo, so that they can be opened in a checker without building
anything. They are copied by hand from `target/pdf` (PDF/UA-1) and `target/pdf-ua2` (PDF/UA-2)
after `mvn test`, and can lag behind the code.

| File | What it is |
|---|---|
| [`01-jasper-raw.pdf`](01-jasper-raw.pdf) | the PDF as JasperReports exported it: no tags, no signature fields |
| [`01-jasper-layout.json`](01-jasper-layout.json) | role, position, nesting and anchor of every report element, read from the filled report |
| [`02-pdfua.pdf`](02-pdfua.pdf) | **PDF/UA-1**: the post-processed document; the one to try in PAC |
| [`02-pdfua-structure.txt`](02-pdfua-structure.txt) | its structure tree, bookmarks and artifacts as text |
| [`03-pdfua-signed.pdf`](03-pdfua-signed.pdf) | the PDF/UA-1 document with the customer's field signed (test certificate) |
| [`04-verapdf-pdfua1-report.txt`](04-verapdf-pdfua1-report.txt) | veraPDF, PDF/UA-1 profile, for the raw, the tagged and the signed file |
| [`02-pdfua2.pdf`](02-pdfua2.pdf) | **PDF/UA-2**: the same document made for PDF/UA-2, on PDF 2.0 |
| [`02-pdfua2-structure.txt`](02-pdfua2-structure.txt) | its structure tree, with namespaces and structure destinations, as text |
| [`03-pdfua2-signed.pdf`](03-pdfua2-signed.pdf) | the PDF/UA-2 document with the customer's field signed |
| [`04-verapdf-pdfua2-report.txt`](04-verapdf-pdfua2-report.txt) | veraPDF, PDF/UA-2 profile (with the ISO 32005 nesting rules), for the raw, the tagged and the signed file of the PDF/UA-2 run |

The PDF/UA-2 files come from a run of their own, whose raw export is equivalent to
`01-jasper-raw.pdf` but not byte for byte the same: OpenPDF names font subsets at random, and
the dates differ.

The company, the agreement and all figures are invented. The signatures are made with
throw-away certificates for "Demo Customer" and prove nothing about anyone.
