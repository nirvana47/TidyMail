package com.tidymail.service;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;

/** Pure, local recommendation composition. No provider calls, Spring registration or mailbox actions. */
public final class MessageRecommendation {
    public static final String VERSION = "recommendation-v5";
    public static final long LARGE_MESSAGE_BYTES = 1_000_000L;

    public enum Action { KEEP_IN_INBOX, KEEP_STORED, ARCHIVE, SUGGEST_TRASH, REVIEW, NONE }
    public enum Importance { IMPORTANT, ROUTINE, UNKNOWN }
    public enum Purpose {
        PERSONAL, MARKETING, NEWSLETTER, RECEIPT, BILL, STATEMENT,
        SECURITY, NOTIFICATION, SOCIAL, CALENDAR_INVITE, OTHER, UNKNOWN
    }
    public enum Facet {
        HEALTHCARE, SCHOOL, ORDER, SHIPPING, MARKETING, SOCIAL,
        BILL, STATEMENT, SECURITY, HUMAN_CORRESPONDENCE,
        HAS_ATTACHMENTS, CALENDAR_INVITE, SENT, LARGE_MESSAGE, CONTACT
    }
    /** Stable user-facing vocabulary; additional labels may overlap the primary purpose. */
    public static String label(Purpose purpose) { return purpose == Purpose.CALENDAR_INVITE ? "Calendar invite" : title(purpose.name()); }
    public static String label(Facet facet) { return switch (facet) {
        case CONTACT -> "Contact"; case CALENDAR_INVITE -> "Calendar invite";
        case LARGE_MESSAGE -> "Large email"; case HUMAN_CORRESPONDENCE -> "Personal";
        default -> title(facet.name());
    }; }
    private static String title(String value) {
        String lower=value.toLowerCase(java.util.Locale.ROOT).replace('_',' ');
        return Character.toUpperCase(lower.charAt(0))+lower.substring(1);
    }
    public enum Signal { PRESENT, ABSENT, UNKNOWN, CONFLICTING }
    public enum Qualification { QUALIFIED, UNRESOLVED, CONFLICTING, STALE }
    public enum ReadState { READ, UNREAD, UNKNOWN }
    public enum Attention { READ, OLDER_UNREAD, RECENT_UNREAD, UNKNOWN }
    public enum Mailbox { INBOX, ARCHIVED, SENT_ONLY, SENT_AND_INBOX, SENT_AND_ARCHIVED, EXCLUDED, UNKNOWN }
    public enum Reason {
        EXCLUDED_MAIL, UNKNOWN_MAILBOX, MANUAL_DECISION, SENT_COPY,
        EVIDENCE_STALE, EVIDENCE_UNRESOLVED, EVIDENCE_CONFLICT,
        CONTEXT_REQUIRED, PROTECTED_CONTENT, IMPORTANT_MESSAGE,
        CRITICAL_SIGNAL_UNKNOWN, ROUTINE_BULK, STATEMENT_POLICY,
        CALENDAR_REVIEW, NO_DISPOSAL_RULE, RECENT_UNREAD_ACTION,
        UNKNOWN_READ_OR_DATE, READ_OR_OLD_BILL, BILL_RECORD_POLICY,
        PRIOR_SENT_CORRESPONDENCE, OBSERVED_CALENDAR
    }

    /** Qualification is supplied by an evaluated adapter, never inferred from a raw model score here. */
    public record Assessment(Purpose purpose, Importance importance, Set<Facet> facets,
                             Signal security, Signal humanCorrespondence,
                             Qualification qualification, boolean contextRequired) {
        public Assessment {
            Objects.requireNonNull(purpose);
            Objects.requireNonNull(importance);
            facets = Set.copyOf(facets);
            Objects.requireNonNull(security);
            Objects.requireNonNull(humanCorrespondence);
            Objects.requireNonNull(qualification);
            // Derived storage/folder facts must come from this message, never semantic output.
            if (facets.contains(Facet.LARGE_MESSAGE) || facets.contains(Facet.SENT) || facets.contains(Facet.CONTACT))
                throw new IllegalArgumentException("Size and Sent facets are derived from mailbox facts");
        }
    }

    /** Positive account-local observations justify retention, not a Personal label or sender trust. */
    public record RetentionEvidence(long sentToExactSender,boolean calendarPartObserved) {
        public RetentionEvidence { if(sentToExactSender<0)throw new IllegalArgumentException("Observed count cannot be negative"); }
        public static RetentionEvidence none() { return new RetentionEvidence(0,false); }
    }

    public record ManualDecision(Action action, Importance importance) {
        public ManualDecision {
            Objects.requireNonNull(action);
            Objects.requireNonNull(importance);
            if (action == Action.NONE) throw new IllegalArgumentException("Manual decision must be actionable");
        }
    }

    /** A revision binds the message, evidence and any correction used by the eventual persistence adapter. */
    public record Input(UUID message, String evidenceRevision, Mailbox mailbox, Long sizeEstimate,
                        Assessment assessment, ManualDecision manualDecision,
                        ReadState readState, Instant receivedAt, Instant evaluatedAt,RetentionEvidence retention) {
        public Input(UUID message,String evidenceRevision,Mailbox mailbox,Long sizeEstimate,
                     Assessment assessment,ManualDecision manualDecision,ReadState readState,Instant receivedAt,Instant evaluatedAt) {
            this(message,evidenceRevision,mailbox,sizeEstimate,assessment,manualDecision,readState,receivedAt,evaluatedAt,RetentionEvidence.none());
        }
        public Input(UUID message,String evidenceRevision,Mailbox mailbox,Long sizeEstimate,
                     Assessment assessment,ManualDecision manualDecision) {
            this(message,evidenceRevision,mailbox,sizeEstimate,assessment,manualDecision,ReadState.UNKNOWN,null,Instant.EPOCH);
        }
        public Input {
            Objects.requireNonNull(message);
            if (evidenceRevision == null || evidenceRevision.isBlank())
                throw new IllegalArgumentException("Evidence revision required");
            Objects.requireNonNull(mailbox);
            Objects.requireNonNull(assessment);
            Objects.requireNonNull(readState);
            Objects.requireNonNull(evaluatedAt);
            Objects.requireNonNull(retention);
            if (sizeEstimate != null && sizeEstimate < 0)
                throw new IllegalArgumentException("Size estimate cannot be negative");
        }
    }

    /** Policy values are explicit so a preview can pin the exact policy revision. */
    public record Policy(String revision, boolean trashStatements, Set<Purpose> disposableBulk,
                         int attentionDays, boolean trashReadOrOldBills) {
        public Policy(String revision,boolean trashStatements,Set<Purpose> disposableBulk) {
            this(revision,trashStatements,disposableBulk,60,true);
        }
        public Policy {
            if (revision == null || revision.isBlank()) throw new IllegalArgumentException("Policy revision required");
            disposableBulk = Set.copyOf(disposableBulk);
            if(attentionDays<1 || attentionDays>365) throw new IllegalArgumentException("Attention window must be 1–365 days");
            if (!Set.of(Purpose.MARKETING, Purpose.NEWSLETTER, Purpose.SOCIAL).containsAll(disposableBulk))
                throw new IllegalArgumentException("Bulk policy cannot dispose of protected purposes");
        }
        public static Policy initial() {
            return new Policy("user-retention-2026-11-02-v2", true,
                    Set.of(Purpose.MARKETING, Purpose.NEWSLETTER, Purpose.SOCIAL));
        }
    }

    public record Result(UUID message, String evidenceRevision, String policyRevision, String engineVersion,
                         Purpose purpose, Importance importance, Set<Facet> facets, Long sizeEstimate,
                         Action action, Reason reason, Attention attention, Instant nextEvaluationAt) {
        public Result { facets = Set.copyOf(facets); }
    }

    public Result evaluate(Input input, Policy policy) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(policy);
        Assessment a = input.assessment();
        var facets = EnumSet.noneOf(Facet.class);
        facets.addAll(a.facets());
        if(input.retention().sentToExactSender()>0)facets.add(Facet.CONTACT);
        if(input.retention().calendarPartObserved())facets.add(Facet.CALENDAR_INVITE);
        if (input.sizeEstimate() != null && input.sizeEstimate() > LARGE_MESSAGE_BYTES)
            facets.add(Facet.LARGE_MESSAGE);
        if (input.mailbox() == Mailbox.SENT_ONLY || input.mailbox() == Mailbox.SENT_AND_INBOX
                || input.mailbox() == Mailbox.SENT_AND_ARCHIVED)
            facets.add(Facet.SENT);

        if (input.mailbox() == Mailbox.EXCLUDED)
            return result(input, policy, facets, Action.NONE, Reason.EXCLUDED_MAIL, a.importance());
        if (input.mailbox() == Mailbox.UNKNOWN)
            return result(input, policy, facets, Action.REVIEW, Reason.UNKNOWN_MAILBOX, a.importance());
        if (input.manualDecision() != null)
            return result(input, policy, facets, input.manualDecision().action(), Reason.MANUAL_DECISION,
                    input.manualDecision().importance());
        if (input.mailbox() == Mailbox.SENT_ONLY)
            return result(input, policy, facets, Action.KEEP_STORED, Reason.SENT_COPY, a.importance());

        if(input.retention().sentToExactSender()>0)
            return result(input,policy,facets,preserve(input),Reason.PRIOR_SENT_CORRESPONDENCE,a.importance());
        if(input.retention().calendarPartObserved())
            return result(input,policy,facets,preserve(input),Reason.OBSERVED_CALENDAR,a.importance());

        // A supported reason to preserve mail does not require a complete semantic assessment.
        if(a.qualification()==Qualification.QUALIFIED) {
            if(a.security()==Signal.PRESENT || a.humanCorrespondence()==Signal.PRESENT
                    || Set.of(Purpose.PERSONAL,Purpose.RECEIPT,Purpose.SECURITY,Purpose.CALENDAR_INVITE).contains(a.purpose())
                    || !java.util.Collections.disjoint(facets,Set.of(Facet.HEALTHCARE,Facet.SCHOOL,Facet.ORDER,Facet.SECURITY,Facet.HUMAN_CORRESPONDENCE,Facet.CALENDAR_INVITE)))
                return result(input,policy,facets,preserve(input),Reason.PROTECTED_CONTENT,a.importance());
            if(a.importance()==Importance.IMPORTANT)
                return result(input,policy,facets,preserve(input),Reason.IMPORTANT_MESSAGE,a.importance());
            if((a.purpose()==Purpose.BILL || a.purpose()==Purpose.STATEMENT || facets.contains(Facet.BILL)
                    || facets.contains(Facet.STATEMENT)) && attention(input,policy)==Attention.RECENT_UNREAD)
                return result(input,policy,facets,preserve(input),Reason.RECENT_UNREAD_ACTION,a.importance());
        }
        if (a.contextRequired() && a.qualification() != Qualification.STALE && a.qualification() != Qualification.CONFLICTING)
            return result(input, policy, facets, Action.REVIEW, Reason.CONTEXT_REQUIRED, a.importance());
        if (a.qualification() != Qualification.QUALIFIED) {
            Reason reason = switch (a.qualification()) {
                case STALE -> Reason.EVIDENCE_STALE;
                case CONFLICTING -> Reason.EVIDENCE_CONFLICT;
                default -> Reason.EVIDENCE_UNRESOLVED;
            };
            return result(input, policy, facets, Action.REVIEW, reason, a.importance());
        }
        if (a.security() == Signal.CONFLICTING || a.humanCorrespondence() == Signal.CONFLICTING)
            return result(input, policy, facets, Action.REVIEW, Reason.EVIDENCE_CONFLICT, a.importance());

        boolean bill=a.purpose()==Purpose.BILL || facets.contains(Facet.BILL);
        boolean statement=a.purpose()==Purpose.STATEMENT || facets.contains(Facet.STATEMENT);
        if(bill || statement) {
            if(attention(input,policy)==Attention.RECENT_UNREAD)
                return result(input,policy,facets,preserve(input),Reason.RECENT_UNREAD_ACTION,a.importance());
            if(attention(input,policy)==Attention.UNKNOWN)
                return result(input,policy,facets,Action.REVIEW,Reason.UNKNOWN_READ_OR_DATE,a.importance());
        }
        if(bill && !policy.trashReadOrOldBills())
            return result(input,policy,facets,preserve(input),Reason.BILL_RECORD_POLICY,a.importance());

        boolean disposable = policy.disposableBulk().contains(a.purpose())
                || (statement && policy.trashStatements()) || (bill && policy.trashReadOrOldBills());
        if (disposable) {
            if (a.security() != Signal.ABSENT || a.humanCorrespondence() != Signal.ABSENT)
                return result(input, policy, facets, Action.REVIEW, Reason.CRITICAL_SIGNAL_UNKNOWN, a.importance());
            if (facets.contains(Facet.CALENDAR_INVITE) || a.purpose() == Purpose.CALENDAR_INVITE)
                return result(input, policy, facets, Action.REVIEW, Reason.CALENDAR_REVIEW, a.importance());
            return result(input, policy, facets, Action.SUGGEST_TRASH,
                    bill ? Reason.READ_OR_OLD_BILL : statement ? Reason.STATEMENT_POLICY : Reason.ROUTINE_BULK, a.importance());
        }
        return result(input, policy, facets, Action.REVIEW,
                a.purpose() == Purpose.CALENDAR_INVITE || facets.contains(Facet.CALENDAR_INVITE)
                        ? Reason.CALENDAR_REVIEW : Reason.NO_DISPOSAL_RULE, a.importance());
    }

    /** Preserving archived mail never re-adds it to the inbox. */
    private static Action preserve(Input input) {
        return input.mailbox() == Mailbox.ARCHIVED || input.mailbox() == Mailbox.SENT_AND_ARCHIVED
                ? Action.KEEP_STORED : Action.KEEP_IN_INBOX;
    }

    /** Read status/elapsed days affect attention and financial disposal, not personal/record protections. */
    public static Attention attention(Input input,Policy policy) {
        if(input.readState()==ReadState.READ) return Attention.READ;
        if(input.readState()==ReadState.UNKNOWN || input.receivedAt()==null || input.receivedAt().isAfter(input.evaluatedAt()))
            return Attention.UNKNOWN;
        return input.evaluatedAt().isAfter(input.receivedAt().plus(Duration.ofDays(policy.attentionDays())))
                ? Attention.OLDER_UNREAD : Attention.RECENT_UNREAD;
    }

    public static Instant nextEvaluation(Input input,Policy policy) {
        if(input.readState()!=ReadState.UNREAD || input.receivedAt()==null) return null;
        if(input.receivedAt().isAfter(input.evaluatedAt())) return input.receivedAt();
        return attention(input,policy)==Attention.RECENT_UNREAD
                ? input.receivedAt().plus(Duration.ofDays(policy.attentionDays())).plusNanos(1000) : null;
    }

    private static Result result(Input input, Policy policy, Set<Facet> facets, Action action,
                                 Reason reason, Importance importance) {
        return new Result(input.message(), input.evidenceRevision(), policy.revision(), VERSION,
                input.assessment().purpose(), importance, facets, input.sizeEstimate(), action, reason,
                attention(input,policy),nextEvaluation(input,policy));
    }
}
