package com.tidymail.controller;

import com.tidymail.dto.ActionScopePreview;
import com.tidymail.dto.ActionScopePresentation;
import com.tidymail.dto.ActionScopeRiskSummary;
import com.tidymail.dto.ActionScopeTargetSummary;
import com.tidymail.action.ActionType;
import com.tidymail.action.DecisionSource;
import com.tidymail.entity.MessageMetadata;
import com.tidymail.entity.UserAccount;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.ActionScopeService;
import com.tidymail.service.ActionConfirmationIntentService;
import com.tidymail.service.MessageMetadataQueryService;
import com.tidymail.util.Rfc5322Util;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/api/actions")
public class BulkActionApiController {

    private final UserAccountRepository userAccountRepository;
    private final MessageMetadataQueryService metadataQueryService;
    private final ActionScopeService actionScopeService;
    private final ActionConfirmationIntentService confirmationIntents;

    public BulkActionApiController(
            UserAccountRepository userAccountRepository,
            MessageMetadataQueryService metadataQueryService,
            ActionScopeService actionScopeService,
            ActionConfirmationIntentService confirmationIntents) {
        this.userAccountRepository = userAccountRepository;
        this.metadataQueryService = metadataQueryService;
        this.actionScopeService = actionScopeService;
        this.confirmationIntents = confirmationIntents;
    }

    private UserAccount currentAccount(OAuth2AuthenticationToken token) {
        OAuth2AuthenticatedPrincipal principal = token.getPrincipal();
        String email = principal.getAttribute("email");
        String provider = token.getAuthorizedClientRegistrationId();
        if (email == null || provider == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return userAccountRepository.findByEmailAndProvider(email, provider)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no account row"));
    }

    @PostMapping("/bulk-by-sender/{fromAddress}")
    public String bulkBySenderFragment(
            @PathVariable String fromAddress,
            @RequestParam("action") ActionType action,
            OAuth2AuthenticationToken principal,
            Model model) {
        UserAccount account = currentAccount(principal);
        if (action != ActionType.ARCHIVE
                && action != ActionType.TRASH
                && action != ActionType.MARK_READ) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "unsupported_preview_action");
        }
        String normalizedAddress = fromAddress.strip().toLowerCase(Locale.ROOT);
        List<MessageMetadata> selected = new ArrayList<>();
        for (int pageNumber = 0; selected.size() < 1000; pageNumber++) {
            Page<MessageMetadata> page = metadataQueryService.bySender(
                List.of(account.getId()), normalizedAddress, pageNumber, 100);
            selected.addAll(page.getContent());
            if (!page.hasNext()) {
                break;
            }
        }
        List<MessageMetadata> capped = selected.stream().limit(1000).toList();
        List<String> messageIds = capped.stream()
            .map(MessageMetadata::getProviderMessageId)
            .toList();
        int protectedCount = (int) capped.stream()
            .filter(row -> row.getLabels().contains("STARRED")
                || row.getLabels().contains("IMPORTANT"))
            .count();
        int highRiskCount = action == ActionType.TRASH ? protectedCount : 0;
        List<String> warnings = highRiskCount > 0
            ? List.of("PROTECTED_MESSAGES") : List.of();
        ActionScopePreview preview = actionScopeService.createOrGet(
            account.getId(),
            action.name(),
            "SENDER_ADDRESS",
            messageIds,
            new ActionScopeTargetSummary(
                normalizedAddress,
                normalizedAddress,
                Rfc5322Util.extractDomain(normalizedAddress),
                1),
            new ActionScopeRiskSummary(protectedCount, highRiskCount, warnings));
        model.addAttribute("preview", ActionScopePresentation.from(preview));
        model.addAttribute("confirmationIntent", confirmationIntents.issue(
            ActionCommandController.ownerKey(principal),
            action,
            DecisionSource.SENDERS,
            List.of(new ActionConfirmationIntentService.ScopeBinding(
                account.getId(), preview.scopeHash()))));
        return "fragments/action-scope-preview-frag";
    }

    @PostMapping("/scopes/{scopeId}/queue")
    public String queueScope(
            @PathVariable Long scopeId,
            OAuth2AuthenticationToken principal,
            Model model) {
        currentAccount(principal);
        throw new ResponseStatusException(
            HttpStatus.GONE, "numeric_scope_queue_removed");
    }

}
