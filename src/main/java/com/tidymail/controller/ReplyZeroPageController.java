package com.tidymail.controller;

import com.tidymail.dto.ReplyZeroItem;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.PageResult;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.ReplyZeroDetector;
import com.tidymail.service.SenderAggregator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class ReplyZeroPageController {

    private final Map<String, MailProvider> mailProviderMap;
    private final UserAccountRepository userAccountRepository;
    private final SenderAggregator senderAggregator;
    private final ReplyZeroDetector replyZeroDetector;

    public ReplyZeroPageController(
            Map<String, MailProvider> mailProviderMap,
            UserAccountRepository userAccountRepository,
            SenderAggregator senderAggregator,
            ReplyZeroDetector replyZeroDetector) {
        this.mailProviderMap = mailProviderMap;
        this.userAccountRepository = userAccountRepository;
        this.senderAggregator = senderAggregator;
        this.replyZeroDetector = replyZeroDetector;
    }

    private MailProvider resolveProvider(UserAccount account) {
        String beanName = switch (account.getProvider()) {
            case "google" -> "gmailMailService";
            case "microsoft" -> "outlookMailService";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown provider");
        };
        MailProvider p = mailProviderMap.get(beanName);
        if (p == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "provider bean not loaded");
        return p;
    }

    private UserAccount currentAccount(OAuth2AuthenticationToken token) {
        OAuth2AuthenticatedPrincipal principal = token.getPrincipal();
        String email = principal.getAttribute("email");
        String provider = token.getAuthorizedClientRegistrationId();
        if (email == null || provider == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return userAccountRepository.findByEmailAndProvider(email, provider)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no account row"));
    }

    @GetMapping("/reply-zero")
    public String replyZeroPage(Model model, OAuth2AuthenticationToken principal) {
        UserAccount account = currentAccount(principal);
        model.addAttribute("email", account.getEmail());
        model.addAttribute("provider", account.getProvider());
        return "reply-zero";
    }
}
