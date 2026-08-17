package com.tidymail.service;

import com.tidymail.dto.SelectionRequest;
import com.tidymail.dto.SelectionScopePreview;
import com.tidymail.repository.DecisionSurfaceQueryRepository.SelectionLocator;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class SelectionMaterializationService {
    private final SelectionPreviewTransactionService transactionService;

    public SelectionMaterializationService(
            SelectionPreviewTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public SelectionScopePreview preview(
            Collection<Long> authorizedAccountIds, SelectionRequest request) {
        if (request == null) throw new IllegalArgumentException("Selection request is required");
        List<Long> accountIds = requireScope(
            authorizedAccountIds, request.accountSelection());
        List<SelectionLocator> locators = decode(request, accountIds);
        try {
            return transactionService.createPreview(accountIds, request, locators);
        } catch (DataIntegrityViolationException race) {
            if (!isScopeHashUniqueViolation(race)) throw race;
            return transactionService.createPreview(accountIds, request, locators);
        }
    }

    private static List<SelectionLocator> decode(
            SelectionRequest request, List<Long> authorizedAccountIds) {
        if (request.mode() != com.tidymail.dto.SelectionMode.INDIVIDUAL) {
            return List.of();
        }
        HashSet<String> distinct = new HashSet<>();
        return request.rowKeys().stream().map(key -> {
            if (!distinct.add(key)) {
                throw new IllegalArgumentException("Duplicate selection row key");
            }
            DecisionRowKey.Locator locator = DecisionRowKey.decode(request.surface(), key);
            if (!authorizedAccountIds.contains(locator.accountId())) {
                throw new IllegalArgumentException("Selection row is outside the authorized scope");
            }
            return new SelectionLocator(locator.accountId(), locator.target());
        }).toList();
    }

    private static List<Long> requireScope(
            Collection<Long> accountIds, String accountSelector) {
        if (accountIds == null || accountIds.isEmpty()
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Authorized account IDs are required");
        }
        List<Long> scope = accountIds.stream().distinct().sorted().toList();
        if ("current".equals(accountSelector) && scope.size() != 1
                || !"current".equals(accountSelector) && !"all".equals(accountSelector)
                    && (scope.size() != 1
                        || scope.getFirst().longValue() != Long.parseLong(accountSelector))) {
            throw new IllegalArgumentException("Authorized account scope does not match query");
        }
        return scope;
    }

    private static boolean isScopeHashUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.contains("uq_action_scopes_account_hash")) {
                return true;
            }
        }
        return false;
    }
}
