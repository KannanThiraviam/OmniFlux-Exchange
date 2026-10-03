package com.omniflux.exchange.security;

import com.omniflux.exchange.config.OmnifluxProperties;
import com.omniflux.exchange.job.ErrorCode;
import com.omniflux.exchange.job.ExportException;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/**
 * HEADER mode for a trusted gateway deployment. The service cannot distinguish
 * a gateway header from a caller-supplied header, so startup requires the
 * explicit non-JWT opt-in and every identity field is validated here.
 */
public final class HeaderPrincipalProvider implements CurrentUserProvider {
    private static final int MAX_VALUE_LENGTH = 1024;
    private final String prefix;

    public HeaderPrincipalProvider(OmnifluxProperties.Security security) {
        this(security.userHeaderPrefix());
    }

    public HeaderPrincipalProvider(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("user header prefix must not be blank");
        }
        this.prefix = prefix;
    }

    @Override
    public Mono<AuthContext> currentAuth(HttpHeaders headers) {
        return Mono.defer(() -> {
            try {
                String issuer = required(headers, "Issuer");
                String subject = required(headers, "Subject");
                String tenant = required(headers, "Tenant");
                String rolesHeader = required(headers, "Roles");
                String authorizationVersion = required(headers, "Authz-Version");

                List<String> roles = Arrays.stream(rolesHeader.split(",", -1))
                        .map(String::trim)
                        .toList();
                roles.forEach(role -> validate(role, "role"));

                return Mono.just(new AuthContext(
                        new PrincipalKey(issuer, subject, tenant), roles, authorizationVersion));
            } catch (ExportException e) {
                return Mono.error(e);
            } catch (RuntimeException e) {
                return Mono.error(unresolved("invalid gateway identity headers", e));
            }
        });
    }

    private String required(HttpHeaders headers, String suffix) {
        String name = prefix + suffix;
        List<String> values = headers.get(name);
        if (values == null || values.size() != 1) {
            throw unresolved("missing or duplicated " + name, null);
        }
        String value = values.getFirst();
        validate(value, name);
        return value.trim();
    }

    private static void validate(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            throw unresolved(name + " is blank or exceeds " + MAX_VALUE_LENGTH + " characters", null);
        }
        if (value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw unresolved(name + " contains a control character", null);
        }
    }

    private static ExportException unresolved(String detail, Throwable cause) {
        return cause == null
                ? new ExportException(ErrorCode.PRINCIPAL_UNRESOLVED, detail)
                : new ExportException(ErrorCode.PRINCIPAL_UNRESOLVED, detail, cause);
    }
}
