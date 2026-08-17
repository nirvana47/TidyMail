package com.tidymail.service;

import com.tidymail.dto.DecisionPage;
import com.tidymail.dto.UnsubscribeCandidateRow;
import com.tidymail.dto.UnsubscribeInspection;
import com.tidymail.dto.UnsubscribeQuery;
import com.tidymail.repository.UnsubscribeCandidateRepository;
import com.tidymail.repository.UnsubscribeCandidateRepository.LatestOutcome;
import com.tidymail.repository.UnsubscribeCandidateRepository.SenderIdentity;
import com.tidymail.repository.UnsubscribeCandidateRepository.SenderPageCounts;
import com.tidymail.repository.UnsubscribeCandidateRepository.SenderPageFact;
import com.tidymail.repository.UnsubscribeCandidateRepository.SenderSummary;
import com.tidymail.repository.UnsubscribeCandidateRepository.SourceCandidate;
import com.tidymail.unsubscribe.Rfc2369Parser;
import com.tidymail.unsubscribe.Rfc8058Eligibility;
import com.tidymail.unsubscribe.UnsubscribeCandidateKey;
import com.tidymail.unsubscribe.UnsubscribeMethod;
import com.tidymail.unsubscribe.UnsubscribeUriPolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class UnsubscribeCandidateService {
    private static final int CLASSIFICATION_BATCH_SIZE = 200;
    private static final int SENDER_BATCH_SIZE = 200;
    private final UnsubscribeCandidateRepository repository;
    private final ProviderDeepLinkService deepLinks;
    private final Rfc2369Parser parser = new Rfc2369Parser();
    private final UnsubscribeUriPolicy uriPolicy = new UnsubscribeUriPolicy();
    private final Rfc8058Eligibility eligibility = new Rfc8058Eligibility(uriPolicy);
    private final Clock clock;

    @Autowired
    public UnsubscribeCandidateService(
            UnsubscribeCandidateRepository repository,
            ProviderDeepLinkService deepLinks) {
        this(repository, deepLinks, Clock.systemUTC());
    }

    UnsubscribeCandidateService(UnsubscribeCandidateRepository repository, Clock clock) {
        this(repository, new ProviderDeepLinkService(), clock);
    }

    UnsubscribeCandidateService(
            UnsubscribeCandidateRepository repository,
            ProviderDeepLinkService deepLinks,
            Clock clock) {
        this.repository = repository;
        this.deepLinks = deepLinks;
        this.clock = clock;
    }

    public DecisionPage<UnsubscribeCandidateRow> query(
            Collection<Long> authorizedAccountIds, UnsubscribeQuery query) {
        Instant now = clock.instant();
        List<Long> scope = requireScope(authorizedAccountIds, query.account());
        if (requiresClassificationFallback(query.sort())) {
            return queryWithClassificationSort(scope, query, now);
        }
        SenderPageCounts counts = repository.countSenderPage(scope, query);
        List<SenderPageFact> facts = repository.findSenderPage(scope, query);
        Map<SenderIdentity, ClassifiedCandidate> candidates = candidatesBySender(
            scope, now, facts.stream()
                .map(fact -> new SenderIdentity(
                    fact.summary().accountId(), fact.summary().senderKey()))
                .toList());
        List<UnsubscribeCandidateRow> rows = facts.stream().map(fact -> {
            SenderSummary sender = fact.summary();
            ClassifiedCandidate candidate = candidates.get(
                new SenderIdentity(sender.accountId(), sender.senderKey()));
            if (candidate != null && fact.latestOutcome() != null) {
                candidate = candidate.withOutcome(fact.latestOutcome());
            }
            return senderRow(sender, candidate, fact.cleaned(), query);
        }).toList();
        long executableCount = rows.stream()
            .filter(UnsubscribeCandidateRow::unsubscribeExecutable).count();
        int totalPages = totalPages(counts.senderCount());
        return new DecisionPage<>(
            rows,
            counts.senderCount(),
            new DecisionPage.Metrics(
                counts.senderCount(), executableCount,
                counts.unsubscribedCount(), counts.cleanedCount()),
            query.page(),
            UnsubscribeQuery.PAGE_SIZE,
            totalPages,
            query.page() == 0 ? Map.of()
                : query.withPage(query.page() - 1).canonicalParameters(),
            query.page() + 1 >= totalPages ? Map.of()
                : query.withPage(query.page() + 1).canonicalParameters());
    }

    private DecisionPage<UnsubscribeCandidateRow> queryWithClassificationSort(
            List<Long> scope, UnsubscribeQuery query, Instant now) {
        Map<SenderIdentity, ClassifiedCandidate> candidates = candidatesBySender(scope, now);
        Map<String, LatestOutcome> outcomes = repository.findLatestOutcomes(
            scope, candidates.values().stream().map(ClassifiedCandidate::fingerprint).toList());
        candidates.replaceAll((identity, candidate) ->
            candidate.withOutcome(outcomes.get(candidate.fingerprint())));
        java.util.Set<SenderIdentity> cleaned = repository.findSuccessfulCleanupSenders(
            scope, query.startInclusive(), query.endExclusive());

        Comparator<UnsubscribeCandidateRow> order = exportOrder(query);
        int retainedLimit = Math.toIntExact(query.offset() + UnsubscribeQuery.PAGE_SIZE);
        PriorityQueue<UnsubscribeCandidateRow> retained =
            new PriorityQueue<>(retainedLimit + 1, order.reversed());
        long matchedCount = 0;
        long unsubscribedCount = 0;
        long cleanedCount = 0;
        long executableCount = 0;
        long batchOffset = 0;
        while (true) {
            List<SenderSummary> batch = repository.findSenderBatch(
                scope, query.startInclusive(), query.endExclusive(),
                SENDER_BATCH_SIZE, batchOffset);
            if (batch.isEmpty()) break;
            for (SenderSummary sender : batch) {
                if (query.protection() == UnsubscribeQuery.Protection.EXCLUDE
                        && sender.protectedSender()
                        || query.protection() == UnsubscribeQuery.Protection.ONLY
                            && !sender.protectedSender()) {
                    continue;
                }
                SenderIdentity identity = new SenderIdentity(sender.accountId(), sender.senderKey());
                UnsubscribeCandidateRow row = senderRow(
                    sender, candidates.get(identity), cleaned.contains(identity), query);
                if (!matches(row, query)) continue;
                matchedCount++;
                if ("UNSUBSCRIBED".equals(row.lifecycleCategory())) unsubscribedCount++;
                if ("ARCHIVED_DELETED".equals(row.lifecycleCategory())) cleanedCount++;
                if (row.unsubscribeExecutable()) executableCount++;
                retained.add(row);
                if (retained.size() > retainedLimit) retained.poll();
            }
            batchOffset += batch.size();
            if (batch.size() < SENDER_BATCH_SIZE) break;
        }
        List<UnsubscribeCandidateRow> ordered = retained.stream().sorted(order).toList();
        int from = Math.toIntExact(Math.min(query.offset(), ordered.size()));
        List<UnsubscribeCandidateRow> rows = ordered.subList(from, ordered.size());
        int totalPages = totalPages(matchedCount);
        return new DecisionPage<>(
            rows,
            matchedCount,
            new DecisionPage.Metrics(
                matchedCount, executableCount, unsubscribedCount, cleanedCount),
            query.page(),
            UnsubscribeQuery.PAGE_SIZE,
            totalPages,
            query.page() == 0 ? Map.of()
                : query.withPage(query.page() - 1).canonicalParameters(),
            query.page() + 1 >= totalPages ? Map.of()
                : query.withPage(query.page() + 1).canonicalParameters());
    }

    public Comparator<UnsubscribeCandidateRow> exportOrder(UnsubscribeQuery query) {
        Comparator<UnsubscribeCandidateRow> selected = switch (query.sort()) {
            case VOLUME_DESC -> Comparator.comparingLong(
                UnsubscribeCandidateRow::emailCount).reversed();
            case READ_DESC -> Comparator.comparingInt(
                UnsubscribeCandidateRow::readPercent).reversed();
            case CONFIDENCE_DESC -> Comparator.comparingInt(
                value -> confidenceRank(value.confidence()));
            case METHOD -> Comparator.comparingInt(
                value -> methodRank(value.recommendedMethod()));
            case NEWEST -> Comparator.comparing(
                UnsubscribeCandidateRow::lastReceivedAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
            case OLDEST -> Comparator.comparing(
                UnsubscribeCandidateRow::lastReceivedAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
            case REVIEW -> Comparator.comparing(
                (UnsubscribeCandidateRow value) -> !value.needsReview());
            case SENDER -> Comparator.comparing(
                UnsubscribeCandidateRow::senderAddress, String.CASE_INSENSITIVE_ORDER);
            case STATE -> Comparator.comparing(
                UnsubscribeCandidateRow::latestOutcome, String.CASE_INSENSITIVE_ORDER);
        };
        return selected
            .thenComparing(
                UnsubscribeCandidateRow::senderAddress, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(UnsubscribeCandidateRow::accountId)
            .thenComparing(
                Comparator.comparingLong(UnsubscribeCandidateRow::sourceMetadataId).reversed());
    }

    private Map<SenderIdentity, ClassifiedCandidate> candidatesBySender(
            List<Long> scope, Instant now) {
        LinkedHashMap<SenderIdentity, ClassifiedCandidate> result = new LinkedHashMap<>();
        long offset = 0;
        while (true) {
            List<SourceCandidate> batch = repository.findCandidateBatch(
                scope, now, CLASSIFICATION_BATCH_SIZE, offset);
            for (SourceCandidate source : batch) {
                result.putIfAbsent(
                    new SenderIdentity(source.accountId(), source.senderKey()), classify(source));
            }
            if (batch.size() < CLASSIFICATION_BATCH_SIZE) break;
            offset += batch.size();
        }
        return result;
    }

    private Map<SenderIdentity, ClassifiedCandidate> candidatesBySender(
            List<Long> scope, Instant now, Collection<SenderIdentity> identities) {
        LinkedHashMap<SenderIdentity, ClassifiedCandidate> result = new LinkedHashMap<>();
        for (SourceCandidate source
                : repository.findCandidatesForSenders(scope, identities, now)) {
            result.putIfAbsent(
                new SenderIdentity(source.accountId(), source.senderKey()), classify(source));
        }
        return result;
    }

    private static boolean requiresClassificationFallback(UnsubscribeQuery.Sort sort) {
        return sort == UnsubscribeQuery.Sort.CONFIDENCE_DESC
            || sort == UnsubscribeQuery.Sort.METHOD
            || sort == UnsubscribeQuery.Sort.REVIEW;
    }

    private static UnsubscribeCandidateRow senderRow(
            SenderSummary sender,
            ClassifiedCandidate candidate,
            boolean cleaned,
            UnsubscribeQuery query) {
        UnsubscribeCandidateRow source = candidate == null ? null : candidate.row();
        boolean confirmed = source != null && "CONFIRMED".equals(source.latestOutcome());
        String lifecycle = confirmed ? "UNSUBSCRIBED"
            : cleaned ? "ARCHIVED_DELETED" : "UNHANDLED";
        boolean executable = !sender.protectedSender() && !confirmed && candidate != null
            && candidate.methods().stream().anyMatch(UnsubscribeInspection.MethodFact::eligible);
        int readPercent = sender.emailCount() == 0 ? 0
            : (int) Math.round(sender.readCount() * 100.0 / sender.emailCount());
        String primary = executable ? "Unsubscribe" : query.defaultAction().label();
        return new UnsubscribeCandidateRow(
            source == null ? "" : source.candidateKey(),
            sender.accountId(),
            source == null ? 0 : source.sourceMetadataId(),
            safe(sender.accountLabel(), 320, "Owned account"),
            safe(sender.senderDisplayName(), 200, ""),
            safe(sender.senderKey(), 320, "Unknown sender"),
            safe(sender.senderDomain(), 253, ""),
            source == null ? "No unsubscribe metadata" : source.listIdentity(),
            sender.emailCount(),
            sender.lastReceivedAt(),
            source == null ? List.of() : source.methodLabels(),
            source == null ? "Unavailable" : source.recommendedMethod(),
            source == null ? "REVIEW" : source.confidence(),
            source == null ? "No executable unsubscribe method was detected." : source.reason(),
            source == null || source.needsReview(),
            sender.protectedSender(),
            source == null ? "NOT_ATTEMPTED" : source.latestOutcome(),
            source == null ? null : source.latestOutcomeAt(),
            source == null ? null : source.latestOutcomeMethod(),
            primary,
            DecisionRowKey.sender(sender.accountId(), sender.senderKey()),
            sender.emailCount(),
            sender.readCount(),
            readPercent,
            lifecycle,
            executable,
            primary);
    }

    private static boolean matches(UnsubscribeCandidateRow row, UnsubscribeQuery query) {
        if (query.category() != UnsubscribeQuery.Category.ALL
                && !row.lifecycleCategory().equals(query.category().name())) {
            return false;
        }
        String search = query.search().toLowerCase(Locale.ROOT);
        return search.isEmpty() || String.join(
            " ", row.senderDisplayName(), row.senderAddress(), row.senderDomain())
            .toLowerCase(Locale.ROOT).contains(search);
    }

    public UnsubscribeInspection inspect(
            Collection<Long> authorizedAccountIds, String candidateKey) {
        if (!UnsubscribeCandidateKey.isCanonical(candidateKey)) throw notFound();
        ClassifiedCandidate candidate = find(authorizedAccountIds, candidateKey);
        LatestOutcome outcome = repository.findLatestOutcomes(
            authorizedAccountIds, List.of(candidate.fingerprint()))
            .get(candidate.fingerprint());
        SourceCandidate source = candidate.source();
        return new UnsubscribeInspection(
            candidateKey,
            safe(source.accountLabel(), 320, "Owned account"),
            safe(source.senderDisplayName(), 200, ""),
            safe(source.senderKey(), 320, "Unknown sender"),
            displayListIdentity(source),
            source.receivedAt(),
            candidate.row().confidence(),
            candidate.row().reason(),
            source.protectedSender(),
            !source.listUnsubscribeValues().isEmpty(),
            !source.listUnsubscribePostValues().isEmpty(),
            source.dkimPass(),
            source.dkimCoversListUnsubscribe(),
            source.dkimCoversListUnsubscribePost(),
            outcome == null ? "NOT_ATTEMPTED" : outcome.state(),
            outcome == null ? null : outcome.occurredAt(),
            candidate.methods());
    }

    public java.util.Optional<UnsubscribeCandidateRow> candidateForSender(
            Collection<Long> authorizedAccountIds, String senderRowKey) {
        DecisionRowKey.Locator locator =
            DecisionRowKey.decode(com.tidymail.dto.SelectionSurface.SENDERS, senderRowKey);
        if (!authorizedAccountIds.contains(locator.accountId())) throw notFound();
        Map<SenderIdentity, ClassifiedCandidate> candidates =
            candidatesBySender(List.copyOf(authorizedAccountIds), clock.instant());
        ClassifiedCandidate candidate = candidates.get(
            new SenderIdentity(locator.accountId(), locator.target()));
        if (candidate == null) return java.util.Optional.empty();
        LatestOutcome outcome = repository.findLatestOutcomes(
            authorizedAccountIds, List.of(candidate.fingerprint()))
            .get(candidate.fingerprint());
        return java.util.Optional.of(candidate.withOutcome(outcome).row());
    }

    public ManualNavigation manualNavigation(
            Collection<Long> authorizedAccountIds,
            String candidateKey,
            NavigationKind kind,
            int methodIndex) {
        ClassifiedCandidate candidate = find(authorizedAccountIds, candidateKey);
        if (candidate.source().protectedSender()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Protected sender cannot open unsubscribe navigation");
        }
        if (kind == NavigationKind.PROVIDER) {
            String target = deepLinks.gmail(
                candidate.source().provider(),
                candidate.source().accountLabel(),
                candidate.source().providerThreadId(),
                candidate.source().providerMessageId()).orElseThrow(
                    UnsubscribeCandidateService::notFound);
            return new ManualNavigation(target, true);
        }
        List<UnsubscribeUriPolicy.ValidatedTarget> mailto = candidate.mailtoTargets();
        if (methodIndex < 0 || methodIndex >= mailto.size()) throw notFound();
        return new ManualNavigation(mailto.get(methodIndex).canonicalUri().toASCIIString(), false);
    }

    public ExecutionCandidate executionCandidate(
            Collection<Long> authorizedAccountIds,
            String candidateKey,
            UnsubscribeMethod method) {
        if (method == null) throw unavailable();
        ClassifiedCandidate candidate = find(authorizedAccountIds, candidateKey);
        SourceCandidate source = candidate.source();
        if (source.protectedSender()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Protected sender blocks unsubscribe");
        }

        UnsubscribeUriPolicy.ValidatedTarget target = switch (method) {
            case RFC8058_POST -> candidate.postEligible() ? candidate.postTarget() : null;
            case LEGACY_HTTPS_GET ->
                !candidate.postEligible() && candidate.httpsTargets().size() == 1
                    ? candidate.httpsTargets().getFirst() : null;
            case MAILTO ->
                candidate.mailtoTargets().size() == 1 ? candidate.mailtoTargets().getFirst() : null;
            case ACCOUNT_PAGE -> null;
            case MANUAL -> null;
        };
        URI destination;
        String destinationHost;
        String destinationFingerprint;
        if (method == UnsubscribeMethod.ACCOUNT_PAGE) {
            String deepLink = deepLinks.gmail(
                source.provider(), source.accountLabel(), source.providerThreadId(),
                source.providerMessageId()).orElseThrow(UnsubscribeCandidateService::unavailable);
            destination = URI.create(deepLink);
            destinationHost = "mail.google.com";
            destinationFingerprint = sha256(deepLink);
        } else {
            if (target == null) throw unavailable();
            destination = target.canonicalUri();
            destinationHost = target.kind() == UnsubscribeUriPolicy.TargetKind.HTTPS
                ? target.host() : null;
            destinationFingerprint = target.fingerprint();
        }
        return new ExecutionCandidate(
            candidateKey,
            source.accountId(),
            candidate.fingerprint(),
            sha256(source.senderKey()),
            source.senderKey(),
            method,
            destination,
            destinationHost,
            destinationFingerprint,
            source.providerMessageId(),
            safe(source.senderDisplayName(), 200, source.senderKey()),
            displayListIdentity(source));
    }

    public ExecutionCandidate executionCandidateByFingerprint(
            long accountId,
            String candidateFingerprint,
            UnsubscribeMethod method) {
        if (accountId <= 0 || candidateFingerprint == null
                || !candidateFingerprint.matches("[0-9a-f]{64}") || method == null) {
            throw notFound();
        }
        Instant now = clock.instant();
        long offset = 0;
        while (true) {
            List<SourceCandidate> batch = repository.findCandidateBatch(
                List.of(accountId), now, CLASSIFICATION_BATCH_SIZE, offset);
            for (SourceCandidate source : batch) {
                ClassifiedCandidate candidate = classify(source);
                if (candidate.fingerprint().equals(candidateFingerprint)) {
                    return executionCandidate(
                        List.of(accountId), candidate.row().candidateKey(), method);
                }
            }
            if (batch.size() < CLASSIFICATION_BATCH_SIZE) throw notFound();
            offset += batch.size();
        }
    }

    private ClassifiedCandidate find(
            Collection<Long> authorizedAccountIds, String candidateKey) {
        if (!UnsubscribeCandidateKey.isCanonical(candidateKey)) throw notFound();
        Instant now = clock.instant();
        long offset = 0;
        while (true) {
            List<SourceCandidate> batch = repository.findCandidateBatch(
                authorizedAccountIds, now, CLASSIFICATION_BATCH_SIZE, offset);
            for (SourceCandidate source : batch) {
                ClassifiedCandidate candidate = classify(source);
                if (candidate.row().candidateKey().equals(candidateKey)) return candidate;
            }
            if (batch.size() < CLASSIFICATION_BATCH_SIZE) throw notFound();
            offset += batch.size();
        }
    }

    private ClassifiedCandidate classify(SourceCandidate source) {
        Rfc2369Parser.ParseResult parsed = parser.parse(source.listUnsubscribeValues());
        Rfc8058Eligibility.Result post = eligibility.classify(
            parsed,
            source.listUnsubscribePostValues(),
            source.dkimPass(),
            source.dkimCoversListUnsubscribe(),
            source.dkimCoversListUnsubscribePost());
        String key = UnsubscribeCandidateKey.encode(
            source.accountId(), source.metadataId(), source.senderKey(), keyListIdentity(source));
        ArrayList<UnsubscribeInspection.MethodFact> methods = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<UnsubscribeUriPolicy.ValidatedTarget> httpsTargets = new ArrayList<>();
        ArrayList<UnsubscribeUriPolicy.ValidatedTarget> mailtoTargets = new ArrayList<>();
        boolean unsupported = false;

        if (!parsed.malformed()) {
            for (Rfc2369Parser.UriItem item : parsed.items()) {
                UnsubscribeUriPolicy.Result validated = uriPolicy.validate(item.value());
                if (!validated.valid()) {
                    unsupported = true;
                } else if (validated.target().kind()
                        == UnsubscribeUriPolicy.TargetKind.HTTPS) {
                    httpsTargets.add(validated.target());
                } else {
                    mailtoTargets.add(validated.target());
                }
            }
        }

        if (!source.listUnsubscribePostValues().isEmpty()) {
            UnsubscribeUriPolicy.ValidatedTarget displayedPostTarget = post.target();
            if (displayedPostTarget == null && httpsTargets.size() == 1) {
                displayedPostTarget = httpsTargets.getFirst();
            }
            methods.add(method(
                UnsubscribeMethod.RFC8058_POST,
                "RFC 8058 POST", "Automated request",
                displayedPostTarget, post.eligible() && !source.protectedSender(),
                post.eligible()
                    ? blockedOr(source, "Exact one-click metadata and trusted DKIM coverage detected.")
                    : "Unavailable: exact one-click metadata and trusted DKIM coverage were not established.",
                null, null, null));
            labels.add("RFC 8058 POST");
        }

        for (UnsubscribeUriPolicy.ValidatedTarget target : httpsTargets) {
            boolean legacyEligible = !post.eligible()
                && httpsTargets.size() == 1 && !source.protectedSender();
            methods.add(method(
                UnsubscribeMethod.LEGACY_HTTPS_GET,
                "Legacy HTTPS GET", "Automated request", target,
                legacyEligible,
                post.eligible()
                    ? "Unavailable: this candidate qualifies for RFC 8058 POST."
                    : blockedOr(source, "Requires separate inspection and confirmation."),
                null, null, null));
            addOnce(labels, "Legacy HTTPS GET");
        }
        for (int mailtoIndex = 0; mailtoIndex < mailtoTargets.size(); mailtoIndex++) {
            UnsubscribeUriPolicy.ValidatedTarget target = mailtoTargets.get(mailtoIndex);
            String navigation = source.protectedSender()
                ? null : "/unsubscribe/candidates/" + key + "/mailto/"
                    + mailtoIndex + "?account=" + source.accountId();
            methods.add(method(
                UnsubscribeMethod.MAILTO,
                "Mailto", "Opens another app/page", target,
                mailtoTargets.size() == 1 && !source.protectedSender(),
                blockedOr(source, "The default mail application opens; nothing is auto-sent."),
                navigation, "Open unsubscribe email",
                "Opens your default mail application"));
            addOnce(labels, "Mailto");
        }

        String providerNavigation = deepLinks.gmail(
            source.provider(), source.accountLabel(), source.providerThreadId(),
            source.providerMessageId()).isPresent() && !source.protectedSender()
                ? "/unsubscribe/candidates/" + key + "/provider?account="
                    + source.accountId() : null;
        methods.add(new UnsubscribeInspection.MethodFact(
            UnsubscribeMethod.ACCOUNT_PAGE.name(),
            "Manual/account page",
            "Opens another app/page",
            providerNavigation == null ? "No validated account page" : "mail.google.com",
            providerNavigation == null ? "Destination unavailable" : "/mail/.../source message",
            false,
            providerNavigation != null,
            source.protectedSender()
                ? "Protected sender; external unsubscribe navigation is blocked."
                : providerNavigation == null
                    ? "No supported provider or account-page destination is available."
                    : "Open the exact indexed source message and use the provider controls.",
            providerNavigation,
            providerNavigation == null ? null : "Open source message in Gmail",
            providerNavigation == null ? null : "Opens in a new tab",
            false,
            false,
            null));
        addOnce(labels, "Manual/account page");

        boolean missingListIdentity = isSenderFallback(source);
        boolean ambiguousDestination = httpsTargets.size() > 1;
        boolean malformedPost = !source.listUnsubscribePostValues().isEmpty()
            && (post.reason() == Rfc8058Eligibility.Reason.REQUIRES_ONE_POST_FIELD
                || post.reason() == Rfc8058Eligibility.Reason.INVALID_POST_ARGUMENT);
        boolean needsReview = source.protectedSender() || source.conflictingRecentSource()
            || source.ambiguousListIdentity() || parsed.malformed() || malformedPost
            || ambiguousDestination || missingListIdentity || unsupported;
        String confidence;
        String reason;
        if (source.protectedSender()) {
            confidence = "REVIEW";
            reason = "Protected sender - unsubscribe blocked.";
        } else if (source.conflictingRecentSource()) {
            confidence = "REVIEW";
            reason = "Recent messages advertise conflicting unsubscribe destinations.";
        } else if (source.ambiguousListIdentity() || parsed.malformed() || malformedPost
                || ambiguousDestination || unsupported) {
            confidence = "REVIEW";
            reason = "Metadata is malformed, ambiguous, or uses an unsupported destination.";
        } else if (missingListIdentity) {
            confidence = "REVIEW";
            reason = "List identity is missing; sender fallback requires review.";
        } else if (post.eligible() || !httpsTargets.isEmpty()) {
            confidence = "HIGH";
            reason = post.eligible()
                ? "Trusted one-click metadata and DKIM coverage agree."
                : "A valid HTTPS destination and stable list identity were detected.";
        } else if (!mailtoTargets.isEmpty()) {
            confidence = "MEDIUM";
            reason = "A valid mail recipient was detected; sending remains a manual choice.";
        } else {
            confidence = "REVIEW";
            reason = "No supported unsubscribe destination was detected.";
            needsReview = true;
        }
        UnsubscribeQuery.Method primaryMethod = post.eligible()
            ? UnsubscribeQuery.Method.RFC8058
            : !httpsTargets.isEmpty() ? UnsubscribeQuery.Method.LEGACY_GET
            : !mailtoTargets.isEmpty() ? UnsubscribeQuery.Method.MAILTO
            : UnsubscribeQuery.Method.MANUAL;
        String fingerprint = candidateFingerprint(
            source.accountId(), source.senderKey(), keyListIdentity(source));
        UnsubscribeCandidateRow row = new UnsubscribeCandidateRow(
            key,
            source.accountId(),
            source.metadataId(),
            safe(source.accountLabel(), 320, "Owned account"),
            safe(source.senderDisplayName(), 200, ""),
            safe(source.senderKey(), 320, "Unknown sender"),
            safe(source.senderDomain(), 253, ""),
            displayListIdentity(source),
            source.thirtyDayCount(),
            source.receivedAt(),
            labels,
            methodLabel(primaryMethod),
            confidence,
            reason,
            needsReview,
            source.protectedSender(),
            "NOT_ATTEMPTED",
            null,
            null,
            recommendation(primaryMethod, source.protectedSender()));
        return new ClassifiedCandidate(
            source, row, List.copyOf(methods), List.copyOf(httpsTargets),
            List.copyOf(mailtoTargets), post.target(), post.eligible(), needsReview,
            primaryMethod, fingerprint);
    }

    private static UnsubscribeInspection.MethodFact method(
            UnsubscribeMethod method,
            String label,
            String behavior,
            UnsubscribeUriPolicy.ValidatedTarget target,
            boolean eligible,
            String reason,
            String navigation,
            String navigationLabel,
            String notice) {
        return new UnsubscribeInspection.MethodFact(
            method.name(),
            label,
            behavior,
            target == null ? "No validated destination"
                : target.kind() == UnsubscribeUriPolicy.TargetKind.MAILTO
                    ? target.mailtoRecipient() : target.host(),
            target == null ? "Destination unavailable" : target.redactedPathShape(),
            target != null && target.queryPresent(),
            eligible,
            reason,
            navigation,
            navigationLabel,
            notice,
            target != null && target.mailtoSubjectPresent(),
            target != null && target.mailtoBodyPresent(),
            null);
    }

    private static int confidenceRank(String confidence) {
        return switch (confidence) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    private static int methodRank(String method) {
        return switch (method) {
            case "RFC 8058 POST" -> UnsubscribeQuery.Method.RFC8058.ordinal();
            case "Legacy HTTPS GET" -> UnsubscribeQuery.Method.LEGACY_GET.ordinal();
            case "Mailto" -> UnsubscribeQuery.Method.MAILTO.ordinal();
            default -> UnsubscribeQuery.Method.MANUAL.ordinal();
        };
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

    public static String candidateFingerprint(
            long accountId, String senderKey, String listIdentity) {
        String value = accountId + ":" + senderKey.length() + ":" + senderKey
            + ":" + listIdentity.length() + ":" + listIdentity;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String blockedOr(SourceCandidate source, String availableReason) {
        return source.protectedSender()
            ? "Protected sender; unsubscribe is blocked." : availableReason;
    }

    private static void addOnce(List<String> values, String value) {
        if (!values.contains(value)) values.add(value);
    }

    private static int totalPages(long count) {
        return Math.toIntExact((count + UnsubscribeQuery.PAGE_SIZE - 1)
            / UnsubscribeQuery.PAGE_SIZE);
    }

    private static String methodLabel(UnsubscribeQuery.Method method) {
        return switch (method) {
            case RFC8058 -> "RFC 8058 POST";
            case LEGACY_GET -> "Legacy HTTPS GET";
            case MAILTO -> "Mailto";
            case MANUAL, ALL -> "Manual/account page";
        };
    }

    private static String recommendation(
            UnsubscribeQuery.Method method, boolean protectedSender) {
        if (protectedSender) return "Protected - keep";
        return switch (method) {
            case RFC8058 -> "Inspect one-click destination";
            case LEGACY_GET -> "Review legacy page";
            case MAILTO -> "Open mail client";
            case MANUAL, ALL -> "Open provider message";
        };
    }

    private static String keyListIdentity(SourceCandidate source) {
        String identity = source.listIdentity();
        if (identity == null || identity.isBlank() || identity.length() > 512
                || identity.chars().anyMatch(Character::isISOControl)) {
            return source.senderKey();
        }
        return identity;
    }

    private static boolean isSenderFallback(SourceCandidate source) {
        return keyListIdentity(source).equals(source.senderKey());
    }

    private static String displayListIdentity(SourceCandidate source) {
        return isSenderFallback(source)
            ? "Sender fallback" : safe(keyListIdentity(source), 200, "List identity unavailable");
    }

    private static String safe(String value, int maximum, String fallback) {
        if (value == null || value.isBlank()
                || value.chars().anyMatch(Character::isISOControl)) return fallback;
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Unsubscribe method is unavailable");
    }

    public enum NavigationKind { MAILTO, PROVIDER }

    public record ManualNavigation(String target, boolean browserTab) {
    }

    public record ExecutionCandidate(
        String candidateKey,
        long accountId,
        String candidateFingerprint,
        String senderKeyFingerprint,
        String senderAddress,
        UnsubscribeMethod method,
        URI destination,
        String destinationHost,
        String destinationFingerprint,
        String providerMessageId,
        String safeLabel,
        String listIdentity
    ) {
        public ExecutionCandidate(
                String candidateKey,
                long accountId,
                String candidateFingerprint,
                String senderKeyFingerprint,
                String senderAddress,
                UnsubscribeMethod method,
                URI destination,
                String destinationHost,
                String destinationFingerprint) {
            this(
                candidateKey, accountId, candidateFingerprint, senderKeyFingerprint,
                senderAddress, method, destination, destinationHost,
                destinationFingerprint, "test-provider-message", senderAddress,
                "Test list");
        }
    }

    private record ClassifiedCandidate(
        SourceCandidate source,
        UnsubscribeCandidateRow row,
        List<UnsubscribeInspection.MethodFact> methods,
        List<UnsubscribeUriPolicy.ValidatedTarget> httpsTargets,
        List<UnsubscribeUriPolicy.ValidatedTarget> mailtoTargets,
        UnsubscribeUriPolicy.ValidatedTarget postTarget,
        boolean postEligible,
        boolean needsReview,
        UnsubscribeQuery.Method primaryMethod,
        String fingerprint
    ) {
        ClassifiedCandidate withOutcome(LatestOutcome outcome) {
            if (outcome == null) return this;
            UnsubscribeCandidateRow updated = new UnsubscribeCandidateRow(
                row.candidateKey(), row.accountId(), row.sourceMetadataId(), row.accountLabel(),
                row.senderDisplayName(), row.senderAddress(), row.senderDomain(),
                row.listIdentity(), row.estimatedThirtyDayMessages(), row.lastReceivedAt(),
                row.methodLabels(), row.recommendedMethod(), row.confidence(), row.reason(),
                row.needsReview(), row.protectedSender(), outcome.state(),
                outcome.occurredAt(), outcome.method(), row.recommendation());
            return new ClassifiedCandidate(
                source, updated, methods, httpsTargets, mailtoTargets, postTarget,
                postEligible, needsReview, primaryMethod, fingerprint);
        }
    }
}
