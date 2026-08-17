package com.tidymail.controller;

import com.tidymail.dto.ReplyZeroPageResponse;
import com.tidymail.dto.SenderPageResponse;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.MailMessage;
import com.tidymail.mail.MailProvider;
import com.tidymail.mail.PageResult;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.service.ReplyZeroDetector;
import com.tidymail.service.SenderAggregator;
import com.tidymail.service.SenderStatsPersistService;
import com.tidymail.util.CursorUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class SenderAggregationController {

    private final Map<String, MailProvider> mailProviderMap;
    private final UserAccountRepository userAccountRepository;
    private final SenderAggregator senderAggregator;
    private final ReplyZeroDetector replyZeroDetector;
    private final SenderStatsPersistService senderStatsPersistService;

    public SenderAggregationController(
            Map<String, MailProvider> mailProviderMap,
            UserAccountRepository userAccountRepository,
            SenderAggregator senderAggregator,
            ReplyZeroDetector replyZeroDetector,
            SenderStatsPersistService senderStatsPersistService) {
        this.mailProviderMap = mailProviderMap;
        this.userAccountRepository = userAccountRepository;
        this.senderAggregator = senderAggregator;
        this.replyZeroDetector = replyZeroDetector;
        this.senderStatsPersistService = senderStatsPersistService;
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

    @GetMapping("/senders")
    public SenderPageResponse getSenders(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "countDesc") String sort,
            OAuth2AuthenticationToken auth) {

        int clampedSize = size;
        if (clampedSize < 1) clampedSize = 20;
        if (clampedSize > 100) clampedSize = 100;

        String googleTok = null;
        long localOff = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                CursorUtil.CursorParts p = CursorUtil.decode(cursor);
                googleTok = p.googlePageToken();
                localOff = p.localOffset();
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad_cursor");
            }
            if (sort != null && !sort.equalsIgnoreCase("countDesc")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sort_only_on_first_page");
            }
        }

        UserAccount account = currentAccount(auth);
        MailProvider provider = resolveProvider(account);

        PageResult<MailMessage> pr = provider.listMessages(account,
                (googleTok == null || googleTok.isBlank()) ? null : googleTok, clampedSize);
        SenderAggregator.AggregationResult agg = senderAggregator.aggregate(pr.items());
        senderAggregator.persistSnapshot(account.getId(), agg);

        String newCursor = pr.nextPageToken() == null ? null
                : CursorUtil.encode(pr.nextPageToken(), localOff + 1, "countDesc");
        return senderAggregator.toPageResponse(agg, newCursor, sort);
    }

    @PostMapping("/senders/_sync")
    public Map<String, Object> runFullSync(OAuth2AuthenticationToken auth) {
        UserAccount account = currentAccount(auth);
        MailProvider provider = resolveProvider(account);
        List<MailMessage> all = new ArrayList<>();
        String pageTok = null;
        int pages = 0;
        do {
            PageResult<MailMessage> pr = provider.listMessages(account, pageTok, 100);
            all.addAll(pr.items());
            pageTok = pr.nextPageToken();
            pages++;
        } while (pageTok != null && pages < 5);
        SenderAggregator.AggregationResult agg = senderAggregator.aggregate(all);
        int n = senderAggregator.persistSnapshot(account.getId(), agg);
        return Map.of("syncedSenders", n, "messagesScanned", agg.totalMessagesScanned(), "pagesFetched", pages);
    }

    @GetMapping("/reply-zero")
    public ReplyZeroPageResponse getReplyZero(
            @RequestParam(name = "olderThanDays", defaultValue = "3") int olderThanDays,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", defaultValue = "20") int size,
            OAuth2AuthenticationToken auth) {
        UserAccount account = currentAccount(auth);
        MailProvider provider = resolveProvider(account);
        String googleTok = "";
        long localOff = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                CursorUtil.CursorParts p = CursorUtil.decode(cursor);
                googleTok = p.googlePageToken();
                localOff = p.localOffset();
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad_cursor");
            }
        }
        PageResult<MailMessage> pr = provider.listMessages(account, googleTok.isBlank() ? null : googleTok, size);
        SenderAggregator.AggregationResult agg = senderAggregator.aggregate(pr.items());
        int clampDays = Math.max(1, olderThanDays);
        var items = replyZeroDetector.analyze(pr.items(), agg.perSender(), account, provider, clampDays, Instant.now());
        String newCursor = pr.nextPageToken() == null ? null : CursorUtil.encode(pr.nextPageToken(), localOff + 1, "countDesc");
        return new ReplyZeroPageResponse(List.copyOf(items), newCursor);
    }
}
