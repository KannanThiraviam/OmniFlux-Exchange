package com.omniflux.exchange.source;

/**
 * A predicate applied to one column of a scan. Named after PostgREST's own
 * operator vocabulary (spec §5.1: `id=gt.41999&id=lte.99999`), since the
 * primary adapter (`RestRowSource`) speaks it directly and the R2DBC
 * adapter (`PgDialect`) translates it to bound SQL.
 *
 * <p>NOTE: neither the plan (Task 2, Step 3) nor the spec (§4.4) gives this type
 * a body — Task 2's own snippet says only "as in the spec §4.4", but §4.4
 * defines RowSource/ExportScan/RowRecord/RowWriter/UploadSession and does not
 * mention FilterSpec/FilterOperator/ExportRequest at all. The only concrete
 * evidence is Task 10's `FilterSpec.eq("country", "IN")` — this enum and
 * FilterSpec are sized to that evidence plus the wire operators the spec
 * shows, not invented beyond it.
 */
public enum FilterOperator {
    EQ, NE, GT, GTE, LT, LTE, IN
}
