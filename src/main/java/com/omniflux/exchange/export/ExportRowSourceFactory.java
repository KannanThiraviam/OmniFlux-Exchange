package com.omniflux.exchange.export;

import com.omniflux.exchange.adapter.r2dbc.R2dbcRowSource;
import com.omniflux.exchange.adapter.rest.DataApiClient;
import com.omniflux.exchange.adapter.rest.RestRowSource;
import com.omniflux.exchange.adapter.rest.RowJsonMapper;
import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import com.omniflux.exchange.meta.SchemaCatalog;
import com.omniflux.exchange.security.AuthContext;
import com.omniflux.exchange.source.RowSource;
import com.omniflux.exchange.source.SqlDialect;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.Locale;
import java.util.Objects;

/** Creates the configured adapter source, including its filtered high-water query. */
public final class ExportRowSourceFactory {
    private final OmnifluxProperties properties;
    private final SchemaCatalog catalog;
    private final DatabaseClient database;
    private final DataApiClient dataApi;
    private final SqlDialect dialect;

    public ExportRowSourceFactory(OmnifluxProperties properties, SchemaCatalog catalog,
                                  DatabaseClient database, DataApiClient dataApi,
                                  SqlDialect dialect) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.database = Objects.requireNonNull(database, "database");
        this.dataApi = Objects.requireNonNull(dataApi, "dataApi");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    public RowSource create(AuthContext auth) {
        return create(auth, null);
    }

    public RowSource create(AuthContext auth, ExportTiming timing) {
        return create(auth, timing, null);
    }

    /** Creates a source with an optional request-facing page size override. */
    public RowSource create(AuthContext auth, ExportTiming timing, Integer pageSizeOverride) {
        String adapter = properties.source().defaultAdapter().toLowerCase(Locale.ROOT);
        if (adapter.equals("rest")) {
            return new RestRowSource(dataApi, new RowJsonMapper(properties),
                    name -> catalog.describe("rest", name, auth), auth, properties, timing,
                    pageSizeOverride);
        }
        if (adapter.equals("r2dbc")) {
            return new R2dbcRowSource(database, catalog, dialect, properties, timing,
                    pageSizeOverride, auth);
        }
        throw new ExportException(ErrorCode.UPSTREAM_CLIENT_ERROR,
                "unsupported source adapter: " + adapter);
    }
}
