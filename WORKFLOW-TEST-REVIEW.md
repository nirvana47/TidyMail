# What tests check, what was removed and what is still missing

Updated November 26, 2026, after the user's completed review. [WORKFLOWS.md](WORKFLOWS.md)
is the requirements document. This review explains the evidence; it does not turn
an unbuilt feature into a passing test. [The November 23 audit](TEST-AUDIT-2026-11-23.md)
and the historical case ledger in TESTING.md preserve the earlier accounting.
Their old counts and retention decisions describe that checkout, before removals.

## What changed after the review

| Review comments | Decision and observable check |
|---|---|
| Rule wording and destination creation (1–2) | Tasks 10.1–10.3 specify matching/nonmatching mail and zero destination/rule creation during draft/preview. Check outgoing native requests and resulting rule destination after confirmation. |
| Existing rules and unsupported choices (3–5) | Task 11.1 uses “rules created through TidyMail” and “rules already in Gmail/Outlook.” Explicitly choose to edit an existing rule; explain when its conditions cannot be preserved. Reject unsupported new choices before creation. |
| Rule actions, edits and failure recovery (6–8) | Tasks 11.2–11.4 give exact effects, untouched fields and saved-input/lost-reply behavior. Existing rule/provider journeys cover parts; complete browser-to-provider recovery is still a gap. |
| Rule result counts and time windows (9–10) | Task 11.5 gives the 100 old + 3 new example. No invented 7/14-day dashboard. Count tests do not prove the native rule caused every delivery. |
| Large emails and missing metadata (11–12) | Tasks 12.1–12.2 require incoming and Sent for both providers, unknown rather than zero, bounded retry and visible errors. Outlook Sent support and shared retry reporting are recorded feature work. |
| Suggestions and user control (13–14) | Remove automatic Consider Trash entirely. For supported labels, test actual download/header/calendar inputs through visible filters. Browsing/labels must send zero mailbox mutations; explicit cleanup requires confirmation. |
| Calendar invitations (15) | Task 12.4 records past-event filtering independent of RSVP. Existing invitation/response tests do not implement or prove the new past-event label. |
| Paid work and feature removal (16–18) | Remove Jev/OpenRouter, internal model results/labels, automatic recommendations and associated tests/runners. Do not add the unbuilt dedicated failed-email retry screen. Retain native rules, confirmed jobs and independent local/calendar labels. |
| In-app analytics (19) | Task 14 retains Activity and records the shared error/retry view. Tests must check real counts, ownership, partial completion and retry timing, rather than page headings. |

## Current workflow coverage and gaps

| User workflow | Useful existing checks | What they do not prove |
|---|---|---|
| Connect/return to the correct mailbox (1) | Security, OAuth refresh, ownership and retained upgrade journeys | A copied database's mail contents are private; see below. |
| Download recent mail and keep working (2) | GmailBackfillRecoveryJourneyTest, MailboxFreshnessJourneyTest, MailboxSyncPersistenceTest, capacity/concurrency tests | Newest-first Outlook plus an accurate remaining-time estimate for both providers. |
| Find, browse and select mail (3–6) | DashboardJourneyTest, DashboardIdentityTest, fixture selection browser script | Every actual browser request reaches the same real filter. Fixture interception skips the backend. |
| Confirm and finish cleanup (7–8) | DashboardJobJourneyTest, DashboardResilienceTest, DashboardResponseCleanupJourneyTest, DashboardGmailDispatchTest | Real provider throughput or the person's total time for 100+ messages. |
| Label/file selected mail (9) | ManualFilingJourneyTest, RuleDestinationProviderTest, rule-filing browser script | Live native destination creation and final placement without a reviewed provider trial. |
| Draft/create/edit native rules (10–11) | AutomationRulesJourneyTest, AutomationRuleCompilationTest, rule-edit/rule-review browser scripts | A full cross-provider browser journey through every failure/recovery state. |
| Large emails/local labels/calendar (12) | MessageEvidenceJourneyTest, PersonalOutlookJourneyTest, LocalGmailLabelsJourneyTest, CalendarResponseJourneyTest | New Outlook Sent size support and past-event labels. No surviving test should insert a model conclusion to claim pipeline success. |
| Return safely after restart/upgrade (13) | DashboardMigrationTest, DashboardSimplificationTest, SecurityConfigIntegrationTest | Live restart acceptance from isolated tests alone. |
| Activity and errors (14) | MailboxActivityJourneyTest and existing job result checks | The new shared error/retry view and complete visible partial-action accounting. |

## Features and tests removed

Manual saved sender groups and their endpoints/UI are removed; automatic domains,
addresses and native mailing-list identities remain. V51 removes the saved groups.
V52 drops AI job/attempt/budget/exposure/semantic/recommendation/pattern tables and
metadata columns, and recalculates automatic membership without model identities.
It preserves downloaded mail, native label IDs, native rule records and confirmed
cleanup targets. Historical applied migrations remain unchanged so upgrades work.
Local header/provider labels are rebuilt in a separate cache from downloaded
metadata, never copied from AI predictions. Independent calendar checks remain.

The dedicated failed-email retry screen was not implemented; there is no screen to
finish or certify here. Existing bounded cleanup recovery and Activity stay. The
requested shared retry/errors area is recorded feature work, not a revival of that
screen. Model dispatch/preparation/replay/scoring runners are also removed.

Removed suites (their only workflow has been removed):

`DashboardClassificationTest`, `LabelingPipelineJourneyTest`, `LabelingScaleJourneyTest`, `Phase2IdentificationJourneyTest`, `RecommendationDashboardJourneyTest`, `RecommendationPersistenceTest`, `HeaderSemanticAdmissionTest`, `JevClassifierTest`, `MessageRecommendationTest`, `PositiveRetentionTest`, `RecommendationAttentionTest`, `RecommendationSemanticContractTest`, `ReviewedPatternPolicyTest`.

Mixed dashboard, calendar, Outlook, sync, migration and browser tests retain their
active workflows; obsolete model fields/assertions are removed. No new suite or
framework is added. The existing migration case now checks AI retirement and
retention of actual mail/native records instead of preservation of model output.

## Rules for keeping or changing tests

An existing or proposed check must name the task above, the mistake a person would
notice, and the observation that exposes it. A small parser/encryption/request
check is justified by that mistake, not by the number of methods it exercises.
If no active workflow needs it, mark it for removal or historical-only retention.

Expected results come from the recorded product decision and independently chosen
sample mail. Specify matching, nonmatching, protected and unchanged emails before
running the implementation. Do not create the expected result by calling the same
matcher or copying the app response. Never quietly change an expectation to make
the code pass. A changed product decision needs a visible reason and source.

Prefer strengthening existing journeys. Exercise the real UI, handlers, database
and provider client together where the task crosses them. Say what is replaced:
a simulated provider can check requested IDs and failure handling, but not actual
provider delivery or latency. A browser fed handcrafted responses cannot prove
real filters work. Real-provider checks use dedicated accounts and separately
reviewed targets; this document authorizes no mailbox action or paid call.

For a fixed defect, demonstrate failure before and success after when practical;
use an isolated copy to restore the defect rather than disrupting the running app.
The prior date regression check demonstrates this. That is evidence about the
specific mistake, not a certificate for all date behavior.

Report the user task, final result, unchanged comparison emails, errors/recovery
and any skipped boundary. Passing counts, coverage percentages, fixtures visited
and screenshots alone never establish the task is complete. Consolidate genuinely
duplicate assertions; retain different failures where the person's recovery differs.

## What happened to the earlier feedback

These are dispositions for the recorded requests, not claims of fresh live
verification. Every row in V1-USER-FEEDBACK.md appears once here.

| Feedback | Plain-language request | Where it goes now |
|---|---|---|
| B01 | Unsubscribe | Declined for this scope; do not rebuild the retired flow. |
| B02 | Rules and labels | Earlier No was superseded by the later explicit rules/filing decisions. Tasks 9–11 use those later decisions. |
| B03 | Multiple mailboxes | Earlier linking/management expansion was declined. Task 1 preserves correct account identity and existing switching; it does not authorize a new linking system. |
| B04 | Richer filters and saved views | Advanced query builder/saved views declined. Existing search/date/unread/label filters remain task 3. |
| B05 | Open email in Gmail | Open one email, not an arbitrary selected set. Task 4.3 includes both supported providers. |
| B06 | Cleanup analytics | In-app analytics/progress/results and shared retry/errors belong in Activity, tasks 8 and 14. |
| B07 | Better sender names | Readable names and addresses in task 5; no logo/enrichment project. |
| B08 | Classification quality evaluation | Declined then; classification/model work is now removed by the current instruction. No new benchmark work here. |
| B09 | Reusable classification rules | Declined; later explicit user-created future-email rules are a different feature, tasks 10–11. |
| B10 | Graceful cleanup failure and retry | Task 8.3 requires honest results and safe next steps. The dedicated failed-email review/retry screen is removed from scope; shared Activity errors are new task 14. |
| B11 | Learn from Spam/Trash for suggestions only | Suggestions never authorize actions. Automatic learning/classification is outside this effort; cleanup-history rule suggestions were later removed. Do not revive them or import more mail for them. |
| B12 | Mobile | Declined/deferred. Desktop keyboard/readability remain task 13.2. |
| B13 | Outlook | Earlier No was superseded by later personal Outlook implementation and rule decisions. Preserve provider differences throughout these tasks. |
| B14 | Automatic data expiry | Declined. Confirmed local-data deletion and upgrade preservation are task 13.1, not a new timed-deletion policy. |
| B15 | Faster sync and useful progress | Task 2: usable first mail, honest progress, background download and cleanup priority. No new push infrastructure or formal speed gate is assumed. |
| B16 | A small message preview | Retain already authorized short previews where available; no full reader or new body download. Task 4 readable details and task 13.3 permission boundaries apply. |
| N01 | Find the remaining mail through a useful overview | Tasks 3–5 preserve finding/filtering/exploring mail. A new analytics/chart redesign is not promised by this document. |
| N02 | Better categories, reusable matches and old invitations | LLM work is removed. Local/provider labels are task 12.3; invitation response and actual past-event timing are task 12.4. Cleanup still needs confirmation. |
| N03 | Group related sender addresses | Task 5 keeps automatic domain and exact-address views. The current review explicitly removes manual saved groups and guessed names. |
| N04 | Mark as read + archive | Included explicitly in task 7 and its final-result checks. |
| N05 | Sort by received date | Task 4.1 includes the existing Latest message choice. A dedicated latest-order test gap is recorded rather than calling the option's presence proof. |
| N06 | Shorter dialogs | Task 13.2 and concise action/rule reviews. Do not turn exact CSS values or a word count into a substitute for a usable dialog. |

## Downloaded-mail privacy

The current source encrypts Gmail/Outlook access and refresh tokens using AES-GCM.
The key is supplied to the app through configuration. Subjects, sender/recipient
addresses and other downloaded mail details are ordinary database fields; token
encryption does not encrypt them. Copying the token key along with the database
also defeats the benefit of encrypting the tokens.

The earlier review recorded owner-only permissions for local configuration and
the PostgreSQL directory. Those permissions limit other local accounts; they do not stop someone
running as the logged-in owner or administrator. FileVault can protect a locked,
powered-off Mac's disk, but its present state could not be confirmed in this
session (`fdesetup status` could not identify the volume). An earlier recorded
FileVault-on observation is not treated as current verification.

Requirement 1.4 is therefore an open protection requirement. A design that protects
a copied database must encrypt mail details too and keep its unlock secret out of
that copy/backup. Mac Keychain or a separate unlock can be considered, with an
explicit locked/unlocked behavior. Neither can promise secrecy from malware
controlling the unlocked app or reading its memory. No encryption migration or
key change was performed as part of removing saved sender groups.

## Remaining feature work

| Requirement | Status |
|---|---|
| Copied database does not reveal mail or usable tokens | Open security design; encrypted tokens alone do not meet it. |
| Newest-first download and honest ETA, both providers | Recorded; existing progress tests are insufficient evidence. |
| Outlook incoming + Sent large-email review | Recorded functionality change; not implemented by this cleanup. |
| Past-event label from actual event end/occurrence | Recorded functionality change; no new age-based shortcut. |
| Shared Activity retry/error area | Recorded functionality change; show stopped retries and useful next actions. |
| 7/14-day rule reporting | No agreed new screen/filter; remove ambiguous promise. |
| Real confirmation-to-completion performance | Measure when investigating user-visible speed; no invented deadline. |

## Verification of this removal

| Check | Observed result and limit |
|---|---|
| Clean isolated package and surviving functional suite | 242 invocations passed, no failures/errors/skips. This is evidence for their assertions, not complete product acceptance. |
| Populated upgrades | Existing cases preserve credentials, mail, native label IDs, native rules, confirmed targets and unresolved work. The AI-removal case seeds an old saved recommendation and checks its storage is removed. |
| Local labels after removal of obsolete correction handling | Existing three local-label journeys passed again; no model output is used. |
| Selection browser journey | Passed with synthetic intercepted responses: dates/error recovery, address/email selection, delayed requests, exact preview and rule handoff. Backend/provider behavior is not proved by this script. |
| Rendered desktop browser journey | Passed on isolated real templates/handlers: filter chips, selection/review, rule drafting/removal, desktop navigation/layout. Providers and authentication are simulated. |
| Rendered rule-review browser journey | Passed on a fresh synthetic mailbox: draft/review/create, sender additions, destination choices, separate old-mail filing and Inbox/All counts. No real provider activation/delivery claim. |
| Stored-function check | Upgraded disposable schema has no public functions referencing removed classification, recommendation or semantic-label data. |
| Source hygiene | JS syntax and git whitespace checks pass. V1–V50 are unchanged. Removed model workers/client/tools are absent from active source. |

The first removal runs exposed a SQL spacing error, stale model-only fixtures and
an incorrect change that combined distinct mailing lists. Those were corrected.
A scratch database was reset after the in-development V52 checksum changed; no
published migration was edited. Browser checks caught an obsolete model-tools
listener that prevented startup; it was removed before the passing checks.

No paid request, native mailbox change, live database upgrade or running-app restart
was performed. V51/V52 still need a coordinated restart to update the active app
and remove its internal AI data. The new Sent/calendar/Activity features above
remain requirements. Their tests must be written around the actual implemented
workflow, not fabricated final labels or headings.
