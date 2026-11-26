# TidyMail

TidyMail is a desktop-first, self-hosted tool for cleaning Gmail and personal
Outlook mailboxes. Treat it like a searchable spreadsheet of downloaded mail:
find emails, select them, review exactly what will change and confirm the action.
The user controls changes; native rules run only after the user creates/enables them.

## Current product

| Workflow | Application code |
|---|---|
| Recent mail, background download and refresh | MailboxBootstrapService, MailboxBackfillService, MailboxFreshnessService |
| Search, date/unread/label filters, domain/address views | DashboardFilter, DashboardRepository, dashboard.js |
| Exact preview, confirmed background cleanup, cancellation/results | DashboardPreviewService, DashboardJobService, DashboardCleanupWorker |
| Gmail labels and Outlook folders | Manual filing services and provider clients |
| Future-email rule drafts, review, creation and management | com.tidymail.rules, shared rule editor |
| Header/provider labels and invitation checks | LocalMailLabels, LocalLabelService, CalendarResponseService, CalendarLabelRefreshService |
| Download and cleanup progress/history | Activity and account-owned persisted jobs |

A failed or lost reply must leave a visible recoverable result. Normal successful
bulk cleanup uses batch acknowledgements; it does not read every email afterward.
Rules run in Gmail/Outlook while TidyMail is closed. Pausing/removing one affects
future delivery and does not move old mail back. Drafting must not create a new
label/folder before final confirmation.

Manual Combine/Split/Rename/Reset saved sender groups are removed. Automatic
sender/domain and mailing-list browsing remain, using actual downloaded identities
rather than model guesses. Jev/OpenRouter classification, its paid controls,
experiments and automatic Consider Trash recommendations are removed. V51 removes
saved groups; V52 removes internal AI data and rebuilds independent local labels.
Already-applied migrations are immutable. Native labels/folders/rules, downloaded
mail and confirmed cleanup targets must survive these upgrades.

## Privacy and operation

Java 21, Spring Boot, Maven, PostgreSQL and Flyway; Thymeleaf and local JavaScript.
Metadata download does not fetch full bodies or attachment contents. Explicit
calendar/evidence reads have their own permissions. Access/refresh tokens are
encrypted; downloaded sender/subject fields are not separately encrypted. A copied
database privacy requirement remains open, as documented in the workflow review.

Sign-out retains local data. Confirmed local-data deletion is a separate action
and waits for active requests, uncertain cleanup and pending rule operations.
Do not restart the running app without coordinating with the user. Never use live
mailbox actions as unreviewed tests. Do not read `.env` during this work.

## Requirements and verification

[WORKFLOWS.md](WORKFLOWS.md) records the user's expectations in product language.
[WORKFLOW-TEST-REVIEW.md](WORKFLOW-TEST-REVIEW.md) separates actual checks, removed
features and remaining feature/test work. Newest-first/ETA for both providers,
Outlook Sent size review, past-event labels and a shared Activity error/retry view
are recorded requirements, not claims of implementation from this removal.
[TESTING.md](TESTING.md) gives isolated test commands and evidence limits.

Tests must expose a concrete mistake in a supported workflow, use expected results
chosen from requirements, and state which external connections are simulated.
No coverage percentage or passing test count substitutes for that evidence.
Mobile, unsubscribe, full email reading, permanent deletion and automatic learning
are outside this effort. Earlier reports describe historical scope only.
