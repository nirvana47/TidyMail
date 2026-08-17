package com.tidymail.service;

import com.tidymail.dto.AnalyticsSenderScope;
import com.tidymail.dto.SenderQuery;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalyticsSenderScopeResolver {
    private static final String UNAVAILABLE = "Analytics sender drilldown is unavailable";

    private final AnalyticsDrilldownKeyService keys;
    private final OwnedAccountScopeService accounts;
    private final DecisionSurfaceQueryRepository repository;

    public AnalyticsSenderScopeResolver(
            AnalyticsDrilldownKeyService keys,
            OwnedAccountScopeService accounts,
            DecisionSurfaceQueryRepository repository) {
        this.keys = keys;
        this.accounts = accounts;
        this.repository = repository;
    }

    public ResolvedScope resolve(
            OAuth2AuthenticationToken authentication, SenderQuery query) {
        if (query.analyticsKey().isBlank()) {
            throw new IllegalArgumentException("An analytics sender key is required");
        }
        AnalyticsSenderScope scope = keys.resolve(authentication, query.analyticsKey());
        List<Long> accountIds = accounts.resolve(authentication, query.account());
        if (accountIds.size() != 1
                || accountIds.getFirst().longValue() != scope.accountId()
                || !repository.analyticsSenderExists(scope)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, UNAVAILABLE);
        }
        return new ResolvedScope(scope, accountIds);
    }

    public record ResolvedScope(AnalyticsSenderScope scope, List<Long> accountIds) {
        public ResolvedScope {
            accountIds = List.copyOf(accountIds);
        }
    }
}
