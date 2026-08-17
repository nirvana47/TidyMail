package com.tidymail.controller;

import com.tidymail.entity.SenderCategory;
import com.tidymail.entity.SenderTypeOverride.SenderType;
import com.tidymail.service.SenderCategoryService;
import com.tidymail.service.SenderTypeOverrideService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class SenderCategoryController {
    private static final Set<String> CORRECTION_PARAMETERS = Set.of(
        "accountId", "senderAddress", "correctionTarget", "returnTo", "_csrf");
    private static final Map<String, SenderType> SEMANTIC_TARGETS = Map.of(
        "semantic:LIST", SenderType.LIST,
        "semantic:AUTOMATED", SenderType.AUTOMATED,
        "semantic:TRANSACTIONAL", SenderType.TRANSACTIONAL,
        "semantic:PERSONAL", SenderType.PERSONAL);

    private final SenderCategoryService categories;
    private final SenderTypeOverrideService overrides;

    public SenderCategoryController(
            SenderCategoryService categories,
            SenderTypeOverrideService overrides) {
        this.categories = categories;
        this.overrides = overrides;
    }

    @PostMapping("/api/sender-categories/correct")
    public Object correct(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request,
            OAuth2AuthenticationToken authentication,
            Model model) {
        if (request.getQueryString() != null
                || parameters.keySet().stream()
                    .anyMatch(name -> !CORRECTION_PARAMETERS.contains(name))) {
            throw bad("Invalid correction parameters");
        }
        parameters.forEach((name, values) -> {
            if (values == null || values.size() != 1
                    || values.getFirst() == null || values.getFirst().isBlank()) {
                throw bad("Invalid correction parameter: " + name);
            }
        });

        long accountId;
        try {
            accountId = Long.parseLong(required(parameters, "accountId"));
        } catch (NumberFormatException invalid) {
            throw bad("Invalid account");
        }
        String senderAddress = required(parameters, "senderAddress");
        String target = required(parameters, "correctionTarget");
        String returnTo = required(parameters, "returnTo");
        String ownerKey = ActionCommandController.ownerKey(authentication);

        try {
            SenderType semanticType = SEMANTIC_TARGETS.get(target);
            if (semanticType != null) {
                overrides.upsert(ownerKey, accountId, senderAddress, semanticType);
            } else if ("custom:none".equals(target)) {
                categories.unassign(ownerKey, accountId, senderAddress);
            } else if (target.startsWith("custom:")) {
                long categoryId = Long.parseLong(target.substring("custom:".length()));
                categories.assign(ownerKey, accountId, categoryId, List.of(senderAddress));
            } else {
                throw new IllegalArgumentException("Unsupported correction target");
            }
        } catch (IllegalArgumentException invalid) {
            throw bad(invalid.getMessage());
        }

        String path = returnPath(returnTo);
        if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
            return refreshResponse();
        }
        return new RedirectView(path + "?account=" + accountId, true);
    }

    @PostMapping("/api/sender-categories/organize")
    public Object organize(
            @RequestParam long accountId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String categoryTarget,
            @RequestParam(required = false) String categoryName,
            @RequestParam(name = "senderAddress", required = false)
                List<String> senderAddresses,
            @RequestParam String returnTo,
            @RequestParam(required = false) String returnAccount,
            HttpServletRequest request,
            OAuth2AuthenticationToken authentication) {
        try {
            String ownerKey = ActionCommandController.ownerKey(authentication);
            List<String> senders = senderAddresses == null ? List.of() : senderAddresses;
            if (categoryName != null && !categoryName.isBlank()) {
                SenderCategory created = categories.create(ownerKey, accountId, categoryName);
                if (!senders.isEmpty()) {
                    categories.assign(ownerKey, accountId, created.getId(), senders);
                }
            } else {
                String target = normalizedCategoryTarget(categoryTarget, categoryId);
                if (!senders.isEmpty()) {
                    applyCategoryTarget(ownerKey, accountId, senders, target);
                } else if (target != null) {
                    throw new IllegalArgumentException("At least one sender is required");
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
        String path = returnPath(returnTo);
        if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
            return refreshResponse();
        }
        String account = returnAccount == null || returnAccount.isBlank()
            ? String.valueOf(accountId)
            : returnAccount;
        return new RedirectView(path + "?account=" + account, true);
    }

    private void applyCategoryTarget(
            String ownerKey, long accountId, List<String> senders, String target) {
        if (target == null) {
            throw new IllegalArgumentException("A destination category is required");
        }
        SenderType semanticType = SEMANTIC_TARGETS.get(target);
        if (semanticType != null) {
            senders.forEach(sender -> overrides.upsert(ownerKey, accountId, sender, semanticType));
        } else if ("custom:none".equals(target)) {
            senders.forEach(sender -> categories.unassign(ownerKey, accountId, sender));
        } else if (target.startsWith("custom:")) {
            long customCategoryId = Long.parseLong(target.substring("custom:".length()));
            categories.assign(ownerKey, accountId, customCategoryId, senders);
        } else {
            throw new IllegalArgumentException("Unsupported category target");
        }
    }

    private static String normalizedCategoryTarget(String categoryTarget, Long categoryId) {
        if (categoryTarget != null && !categoryTarget.isBlank()) return categoryTarget;
        return categoryId == null ? null : "custom:" + categoryId;
    }

    private static String required(
            MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null || values.size() != 1
                || values.getFirst() == null || values.getFirst().isBlank()) {
            throw bad("Missing correction parameter: " + name);
        }
        return values.getFirst();
    }

    private static String returnPath(String returnTo) {
        return switch (returnTo) {
            case "senders" -> "/senders";
            case "unread" -> "/unread";
            default -> throw bad("Invalid return route");
        };
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseEntity<Void> refreshResponse() {
        return ResponseEntity.noContent().header("HX-Refresh", "true").build();
    }
}
