# Feature Research: v1.1 Complete Cleanup Workspace

## Shared Workspace

**Table stakes:** Five connected routes, consistent navigation, current account scope, loading/error/empty states, responsive controls, stable filter URLs, and clear queued-action count.

**Differentiators:** Deterministic recommendation explanations, exact impact previews, protected decisions, and a workflow that never mutates a mailbox before the selected execution gate.

## Senders

**Table stakes:** Search, time window, account filter, type/risk/unread filters, sorting, pagination, row and page selection, archive/delete/mark-read/keep/review actions, metrics, and CSV export.

**Differentiators:** Recommendation type, risk level, signal explanation, protected-sender exclusion, queue-state filtering, and exact selected-message counts.

## Unread

**Table stakes:** Message-level unread rows with sender, subject header, age, thread state, category, search, filters, sorting, selection, open-in-provider, archive, delete, mark-read, keep, and review.

**Differentiators:** Deterministic one-to-one versus list classification, waiting-on-me state, reply-required signal, no-response-expected signal, and actionability explanations.

## Unsubscribe

**Table stakes:** Candidate ranking, method detection, monthly-volume estimate, last-seen time, confidence, selected state, save selection, review queue handoff, and manual fallback.

**Differentiators:** Standards-aware RFC 8058 one-click POST, explicit legacy GET option, `mailto:` handoff, account-page inspection, protected sender controls, and completion verification status.

## Review And Execution

**Table stakes:** Grouped staged actions, remove action, exact affected counts, apply selected or all approved actions, execution progress, per-item outcomes, and durable history.

**Differentiators:** Two supported policies: staged review by default and opt-in immediate execution. Both share one action engine, exact scope snapshots, append-only audit data, partial-failure handling, and inverse replay.

## Analytics

**Table stakes:** Date range, bucket grouping, account and action filters, previous-period comparison, headline metrics, received-versus-cleaned trend, top senders, action outcomes, review coverage, and exports.

**Differentiators:** Inbox reduction, cleanup rate, median triage delay, confidence retention, unsubscribe completion, time-to-decision distribution, and drilldowns back to source rows.

## Anti-Features

- No inferred sentiment, urgency, or content meaning from message bodies.
- No silent unsubscribe request or destructive mailbox mutation.
- No analytics values fabricated from current sender totals when historical events are unavailable.
- No share link that exposes mailbox metadata without an explicit access-control model.
