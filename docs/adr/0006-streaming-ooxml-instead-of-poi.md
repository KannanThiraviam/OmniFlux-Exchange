# ADR-0006: Hand-written streaming OOXML instead of Apache POI

- **Status:** Accepted
- **Detail:** [decision log](../architecture/decision-log.md)

## Context

Apache POI's `SXSSFWorkbook` streams rows but writes temporary XML files to
disk, which [ADR-0001](0001-bounded-streaming-zero-disk.md) forbids. The
non-streaming `XSSFWorkbook` holds the whole workbook in memory.

## Decision

`XlsxStreamWriter` writes the OOXML package directly as a streamed ZIP: static
parts first, then `sheet1.xml` row by row using inline strings (no shared
strings table), with a configurable deflate level. POI is a **test-only**
dependency, used to read our output and verify it.

## Consequences

- Constant memory and zero disk for XLSX.
- One worksheet, capped at 1,000,000 data rows (Excel's physical limit is
  1,048,576 rows including the header). A multi-sheet writer is a separate
  feature.
- Inline strings make files somewhat larger than shared-string workbooks.
- Illegal XML characters are rejected (`CHARACTER_NOT_REPRESENTABLE`), not
  silently stripped.
