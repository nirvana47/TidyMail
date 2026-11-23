# Testing TidyMail

Use Java 21, Maven, and the native PostgreSQL 16 service from [local setup](DEPLOY.md).
The test role owns `tidymail_test` (Spring integration and journeys) and
`tidymail_test_migrations` (disposable historical migration fixtures). Both use the
real Flyway migrations. Journeys use mocked mail providers or simulated loopback
HTTP responses. Docker is not needed.

If you still have the generated `TIDYMAIL_TEST_DB_PASSWORD` from setup, save it in
a private, Git-ignored file so future shells can load it:

```sh
(umask 077; printf 'export TIDYMAIL_TEST_DB_PASSWORD=%s\n' "$TIDYMAIL_TEST_DB_PASSWORD" > .env.test.local)
```

From the repository root:

```sh
. ./.env.test.local
mvn test                         # functional, safety and migration checks
TIDYMAIL_PERF_ROWS=100000 mvn -Prelease-performance test
mvn -Dtest='DashboardMigrationTest,DashboardJourneyTest,LocalGmailLabelsJourneyTest,PersonalOutlookJourneyTest,GmailBackfillRecoveryJourneyTest' test
```

The `release-performance` profile runs only the existing `performance`-tagged
checks: dashboard queries and confirmed cleanup during concurrent writes at
10k/100k messages, sync commit work at the configured volume, the 115k populated
label cache. These three workloads run separately from `mvn test`. The normal
suite also uses production MVP mode. Set `TIDYMAIL_PERF_ROWS=100000` as above to
exercise 100k sync rows;
the dashboard profile defaults to 100k independently and runs in production MVP
mode. Fixture loading uses bounded batches with production triggers enabled.
Absolute timing budgets are local release targets, not provider throughput
promises. A failed budget needs investigation; do not raise it merely to get a passing run.

For an alternate local service port, export `TIDYMAIL_TEST_DB_PORT` (default
`5433`). The harness fixes the loopback host, database names, and role, pins
Spring and Flyway to the test connections, and checks server identity, role
privileges, and denial of access to the live database before running.
The PostgreSQL permissions also deny the test role a connection to `tidymail`.
The test process does not load the app's `.env`.

Run one suite at a time against the same test databases. The Spring harness
truncates fixture data between tests; standalone migration tests clean their
scratch database to test fresh installs and upgrades at historical versions.
Keep both databases disposable. Functional reports are in `target/surefire-reports/`;
performance reports are in `target/performance-reports/`.
Capture verbose Maven output to an ignored or temporary log; inspect the summary
and failed cases before loading full traces into an agent conversation.
See the [acceptance notes](docs/TESTING.md) for product journeys and live checks.

## What the suite protects

| Risk | Main existing coverage |
|---|---|
| Exact selected mail, confirmation, cancellation and recovery | `DashboardJourneyTest`, `DashboardJobJourneyTest`, `DashboardResilienceTest`, `DashboardResponseCleanupJourneyTest`, `ManualFilingJourneyTest` |
| Sync cursors, partial commits and worker races | `MailboxSyncPersistenceTest`, `MailboxFreshnessConcurrencyTest`, `MailboxFreshnessJourneyTest`, `GmailBackfillRecoveryJourneyTest` |
| Provider requests, tokens and rule changes | Gmail/Outlook contract and journey tests, `OAuthAuthorizedClientRefreshTest`, `AutomationRulesJourneyTest` |
| Data retention, privacy, paid authorization and upgrades | Security, classification/labeling journeys, immutable migration checks and historical upgrade fixtures |

The stricter audit removes suites for the disabled Senders, Unread, Review,
custom-category and analytics experiences, along with their retired query/schema
contracts. Their old tests no longer get a special legacy-mode startup. The
current dashboard migration suite still verifies retained credentials, mail,
corrections, rules, history and unresolved cleanup across upgrades; published
migration checksums remain guarded once.

Shared recovery paths use representative actions for scheduling, reconnect and
rejection states. Action-specific success and ambiguous label verification still
cover Mark as Read, Archive and Trash. Cancellation and stale-worker fencing have
one owner in the existing job journey instead of duplicate matrices. Each actual
Gmail mutation payload is still checked, with rejection/replay coverage retained
for both cleanup and filing entry points.

Metadata privacy, configuration bounds and missing-refresh-token checks are
consolidated into their existing contracts. The old experimental model protocol
suite is removed; the current recommendation protocol and shared text privacy
boundary remain covered. No new test framework or suite was introduced.

Before adding or retaining a case, identify its current call path, concrete user
failure and whether an existing check already catches that failure. A passing
count is not evidence of unique value. After deleting nested suites, clear stale
generated test classes before verification; leave production classes alone if an
app is using them. Browser density checks use visible rows and overflow, not an
absolute pane position that changes when a saved-selection banner appears.

Browser checks validate interaction behavior; the Java journeys validate exact
stored/provider outcomes. Neither simulation proves real Gmail/Outlook consent,
provider throughput or model accuracy. Historical private pilots/probes remain
explicit-only tools, outside both Maven commands; do not bulk-run them or loosen
their replay/schema guards. See `tools/verification/README.md`.

The frontend selection journey runs in Chromium with fixture responses only:
`node tools/verification/dashboard-selection.cjs` (requires Playwright and its
Chromium browser, with Playwright available on Node's module path). It checks
address/message checkbox synchronization, selection within one parent group,
exact Trash preview IDs, unread scope, and delayed-response handling. It does
not connect to the live mailbox or perform cleanup.

The desktop polish journey also checks filter chips, settings/tool navigation,
pane dismissal and unsaved-rule protection. Set `TIDYMAIL_UI_SCREENSHOTS` to an
existing output directory to capture its fixture mailbox and cleanup dialog.
`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` may point to an installed Chromium binary.

For screenshots and browser checks against actual server-rendered templates,
start the explicit synthetic harness in one terminal (with Java 21 and the test
password loaded as above):

```sh
mvn -Dtest=DashboardBrowserFixture -Ddashboard.browser.minutes=10 test
```

Use the loopback URL printed after `DASHBOARD_BROWSER_FIXTURE` in another terminal:

```sh
node tools/verification/ui-polish.cjs http://127.0.0.1:PORT/dashboard docs/verification/ui-polish
```

The script requires the synthetic-fixture marker before any form submission. It
checks the rendered mailbox, filters, selection, exact review, rule draft/preview,
rule-removal cancellation/confirmation, Rules/editor, sync, sign-in and desktop
layout/zoom, and captures PNGs. It never enables a provider rule or executes
mailbox cleanup. Stop the fixture early with `touch /tmp/tidymail-phase3-browser-done`.

Activity dashboard verification uses the same isolated browser fixture:

```sh
node tools/verification/activity-dashboard.cjs http://127.0.0.1:PORT/dashboard
```

It checks standalone navigation, sidebar highlighting, summary cards, the chart,
sync navigation and absence of a modal or desktop overflow. The focused Java
journeys are `MailboxActivityJourneyTest` and
`DashboardJobJourneyTest#activityDashboardPagesAllJobsAndCountsOnlyConfirmedChanges`;
they cover template rendering, populated pagination and account isolation.

Rule and manual-filing desktop journeys use the same synthetic fixture:
`node tools/verification/rule-filing.cjs http://127.0.0.1:PORT/dashboard /tmp/tidymail-filing-screenshots`.
It checks multiple phrases, explicit duplicate destination selection, retained
choices after a permission error, deferred creation, manual filing review and
confirmation, Activity names, both selection menus, and desktop layout. It saves a
draft and confirms one synthetic filing job; it never enables a native rule.

`AutomationRulesJourneyTest` also follows the Outlook sender picker, review and
update HTTP requests with simulated provider rejection, response loss and failed
read-back. It checks recovery without another mailbox mutation, retry after a
confirmed rejection, and checking an old pending update that left the original
rule unchanged. `RuleDestinationProviderTest` checks that comparing Outlook rule
behavior preserves the provider snapshot and that sender updates omit unused
predicates while explicitly clearing a predicate when switching matching modes.

## Individual case audit — November 22, 2026

The checkout was clean at entry (no staged or unstaged work). The baseline was
**371 functional invocations**, verified by a fresh Java 21 Maven run and its XML,
not inferred from old reports. The inventory has 347 Java test methods: 342 default
functional methods expand to 371 invocations, plus 3 performance methods and 2
explicit fixtures. After removing 8 obsolete cases and consolidating 1 duplicate,
333 default methods expand to **362 functional invocations**. Parameterized values
are separate rows below; ordinary loops remain visible as assertion variations,
not extra Surefire cases. No case was moved behind a flag or into an archive.

The single table accounts for the baseline and retained Java cases, six browser
scripts, six historical Python parser cases, shared helpers and archived tools.
Each bold suite row supplies the **current call path, concrete keep rationale and related overlap inherited
by its following case rows**. Each case states the specific asserted failure that justifies its disposition. “Keep” means the named user failure has a distinct
asserted boundary on that path; overlap across transport, persistence and browser
layers is intentional. The three scale rows are workloads, not 371 extra examples
or claims of live throughput. The private fixture and archived runners have no
current release coverage claim and were not executed.

Whole-suite decisions: remove the four-case offline
`RecommendationDecisionQualificationTest`; remove the three frozen `diagnose`
cases/helper from `RecommendationSemanticContractTest` while retaining its five
production request/parse/provenance cases. `LabelingPipeline.settle` calls
`SemanticLabelComposition.merge`, never either diagnostic policy. Remove the
standalone strict-mapper case from `Phase9ActionScopeTest`; current previews create
summary JSON server-side, and that mapper was not an application input path.
Keep canonical hash compatibility, identity boundaries and loaded-summary bounds.
Keep reviewed-pattern checks: owned preview/apply endpoints remain reachable and
retained labels still render, although the toolbar no longer offers pattern creation.
Keep old-named `Phase6SenderAggregationTest` and `Phase8DeploymentTest`: the mapper
still calls both address/domain parsers and the nightly purge still runs.

Consolidation also removes obsolete experimental protocol assertions from the
mixed correspondence case and tests the shared header-admission string/reply API
directly. Large-size invariance shrinks from 36 purpose/mailbox pairs to three
actual outcomes, retaining null/exact-threshold boundaries. Positive Sent
retention keeps 1/3000 and the zero control; 4/5 exercised no distinct threshold.
Other pure matrices stay: protection precedence, absent/unknown/conflicting
signals, date-only versus instant/DST recurrence, malformed typed response shapes,
and each provider mutation payload exercise genuinely different branches.

| Case / executable | Current user failure caught (or explicit historical limit) | Overlap | Disposition and concrete reason |
|---|---|---|---|
| [**AutomationRulesJourneyTest** (Functional)](src/test/java/com/tidymail/AutomationRulesJourneyTest.java) | **Path:** AutomationRuleController → AutomationRuleService → RuleStore/providers | **Overlap:** RuleDestinationProviderTest / browser rule journeys | Keep: persisted review/version/native behavior differs from payload-only or DOM checks. |
| `browserDraftPreviewEnablePauseResumeAndRemoveHaveRealPersistence` | Draft save accidentally enables mail rules, or lifecycle changes fail to persist.  | Suite overlap; distinct boundary stated here | Keep |
| `reviewReplacesEditorAndSuccessfulRuleOffersExactlyScopedOptionalFiling` | Creating a future rule silently files historical mail or widens the optional Inbox/All selection.  | Suite overlap; distinct boundary stated here | Keep |
| `inboxSenderAdditionPreservesSettingsAndRecoversLostGmailReplacementReceipt` | Gmail sender replacement loses settings, duplicates filters after response loss, or edits paused rules remotely.  | Suite overlap; distinct boundary stated here | Keep |
| `deleteOnlyConfirmedEligibleDraftsInOwnedSelectedMailbox` | Draft deletion widens a confirmed snapshot to new, changed, attached or foreign rules.  | Suite overlap; distinct boundary stated here | Keep |
| `gmailDomainRuleRoundTripsAndPreviewsChangingAddressesWithExceptionsAndProtections` | Gmail domain matching loses future prefixes, exceptions or newly added protection.  | Suite overlap; distinct boundary stated here | Keep |
| `outlookDomainAndAddressUseOneOrListAndPreserveDomainsThroughPauseAddAndResume` | Outlook OR domains become native AND conditions, or pause/update/resume loses action settings.  | Suite overlap; distinct boundary stated here | Keep |
| `overlapIdentifiesOnlySharedAddressesAndBatchChecksFullFilterWithoutMovingMail` | Overlap review uses substring sender matches, partial searches or a changed native fingerprint.  | Suite overlap; distinct boundary stated here | Keep |
| `newRuleFromFortyTwoSendersRetainsEveryAddressThroughHttpPreviewAndProviderPayloads` | The sender picker truncates 42 addresses before saved/native rule creation.  | Suite overlap; distinct boundary stated here | Keep |
| `multipleSubjectPhrasesKeepSenderScopeAndAnyExceptionWinsAcrossPreviewAndNativePayloads` | Multiple subjects widen sender scope or ignore any exception in SQL/native translations.  | Suite overlap; distinct boundary stated here | Keep |
| `existingGmailDeletionRuleCanBeConvertedThroughEditorAndRetainsResultsAfterMailMoves` | Adopted Gmail deletion rule cannot be converted; observed results disappear after messages move.  | Suite overlap; distinct boundary stated here | Keep |
| `pausedNewLabelRuleReopensWithResolvedDestinationAndCanSaveSkipInbox` | Paused new-label rules recreate destinations or lose edited Remove from Inbox settings.  | Suite overlap; distinct boundary stated here | Keep |
| `failedDestinationPreparationNeverDispatchesRuleAndLostNativeResponseStillReconciles` | Uncertain destination creation dispatches/repeats a native rule before the destination is known.  | Suite overlap; distinct boundary stated here | Keep |
| `lostCreationResponseRecoversWithoutDuplicatesAndExternalChangesAreNotOverwritten` | Lost Gmail creation receipt duplicates rules or overwrites external changes.  | Suite overlap; distinct boundary stated here | Keep |
| `previewUsesAvailableEvidenceAndChangedProtectionsBlockActivation` | Rule activation accepts a preview after protection changes.  | Suite overlap; distinct boundary stated here | Keep |
| `outlookSenderUpdateRecoversWithoutSendingAnotherMutation[lost-response]` | Outlook update confuses rejection, lost response, unavailable read-back and confirmed unchanged native state. Applied update; immediate read-back settles. | Suite overlap; distinct boundary stated here | Keep |
| `outlookSenderUpdateRecoversWithoutSendingAnotherMutation[rejected]` | Outlook update confuses rejection, lost response, unavailable read-back and confirmed unchanged native state. HTTP 400 plus unchanged read-back permits reviewed retry. | Suite overlap; distinct boundary stated here | Keep |
| `outlookSenderUpdateRecoversWithoutSendingAnotherMutation[unavailable-readback]` | Outlook update confuses rejection, lost response, unavailable read-back and confirmed unchanged native state. Applied update; pending until later successful check. | Suite overlap; distinct boundary stated here | Keep |
| `outlookSenderUpdateRecoversWithoutSendingAnotherMutation[not-applied]` | Outlook update confuses rejection, lost response, unavailable read-back and confirmed unchanged native state. Unchanged after ambiguous dispatch; old pending timeout requires fresh review. | Suite overlap; distinct boundary stated here | Keep |
| `outlookOrganizeUsesNativeFolderAndReportsObservedMailWithoutChangingOldMessages` | Outlook destination creation or observed-result counts change historical mail or lose settings on resume.  | Suite overlap; distinct boundary stated here | Keep |
| `existingOutlookReadArchiveRuleCanBeAdoptedPausedEditedAndReenabledWithoutLosingNativeSettings` | Adopted Outlook rule loses consent scope, archive/read settings or native sequence during edit.  | Suite overlap; distinct boundary stated here | Keep |
| [**CalendarResponseJourneyTest** (Functional)](src/test/java/com/tidymail/CalendarResponseJourneyTest.java) | **Path:** CalendarResponseService → GmailEvidenceReader / GoogleCalendarReader / OutlookCalendarReader | **Overlap:** CalendarReconcilerTest / CalendarResponseFailureTest | Keep: real transport projection, stored status and current filter integration. |
| `exactOccurrenceRsvpSurvivesCancelledSubjectAndAppearsInOwnedDashboard` | An occurrence RSVP is confused with a cancelled subject or another occurrence; private identifiers persist.  | Suite overlap; distinct boundary stated here | Keep |
| `ordinaryOccurrenceIsExpandedAndAmbiguousOrExpiredEvidenceNeverQualifies` | Expanded recurring occurrence is unresolved, or duplicate/expired checks still qualify a filter.  | Suite overlap; distinct boundary stated here | Keep |
| `currentResponseAndCalendarCancellationComeFromCalendarNotSentMail` | Sent-mail guesses or stale status override current calendar response/cancellation.  | Suite overlap; distinct boundary stated here | Keep |
| `calendarFilterChoicesExposeOnlyTheThreeStatusLabels` | Dashboard exposes retired invitation/past-event choices instead of three supported statuses.  | Suite overlap; distinct boundary stated here | Keep |
| `backgroundRefreshChecksOnlyKnownInvitesAndResumesWithoutReclassifyingMail` | Background refresh reads ordinary mail, ignores backoff/permissions or reclassifies messages.  | Suite overlap; distinct boundary stated here | Keep |
| `ownershipAndCsrfAreCheckedBeforeAnyProviderRead` | Foreign/no-CSRF calendar request performs a provider read.  | Suite overlap; distinct boundary stated here | Keep |
| `outlookFollowsAssociatedEventAndPersistsItsCurrentResponse` | Outlook association uses the wrong event endpoint or retains status after permission removal.  | Suite overlap; distinct boundary stated here | Keep |
| [**CorrespondentHistoryTest** (Functional)](src/test/java/com/tidymail/CorrespondentHistoryTest.java) | **Path:** CorrespondentHistory → metadata contributions/summary | **Overlap:** RecommendationPersistenceTest positive retention | Keep: index mutation and repair precede composition; one exact exchange is not identity proof. |
| `exactRecipientsRepliesIsolationAndUnknownCoverageSurviveUpdatesDeletesAndReplay` | Recipient parsing/deduplication, self/spam/draft exclusions or replay corrupt account-local exchange counts.  | Suite overlap; distinct boundary stated here | Keep |
| `lockedHistoryIsDeferredAndDeletingLatestExchangeRepairsDates` | Locked history is lost or deleting the latest exchange leaves false dates/reply counts.  | Suite overlap; distinct boundary stated here | Keep |
| `boundedRestartableBackfillAndCandidateAttachmentDoNotRelaxForwardAdmission` → `boundedRestartableBackfillAndAliasChangesRebuildObservedCounts` | Bounded history/alias rebuild loses observed counts or account deletion fails to cascade.  | As above; different layer/transition | Consolidate: keep current index/alias/cascade checks; remove obsolete experiment request/hash/admission assertions. |
| [**DashboardBrowserFixture** (Harness)](src/test/java/com/tidymail/DashboardBrowserFixture.java) | **Path:** Explicit synthetic MockMvc loopback bridge; no default Maven discovery | **Overlap:** Six browser scripts | Keep: helper only, not one behavioral pass; synthetic markers/provider stubs bound all submissions. |
| `serveSyntheticDashboardForBrowserAcceptance` | Browser scripts accidentally exercise retained/live application instead of isolated server-rendered templates.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardClassificationTest** (Functional)](src/test/java/com/tidymail/DashboardClassificationTest.java) | **Path:** DashboardClassificationService → ClassificationBudget / grouping | **Overlap:** LabelingPipelineJourneyTest (separate queue); JevClassifierTest (wire) | Keep: current classification queue, account corrections and deployment spending ledger. |
| `explicitPersonalIdentitySurvivesLaterAutomatedClassificationRegrouping` | Explicit personal identity is swallowed by later automated organization regrouping.  | Suite overlap; distinct boundary stated here | Keep |
| `socialCategoryCanBeCorrectedAndFilteredWithoutSwallowingMarketplaceReceipts` | Correcting Social swallows Marketplace receipts or selects the wrong category IDs.  | Suite overlap; distinct boundary stated here | Keep |
| `confirmedCleanupTargetsStayFixedWhenClassificationChangesGroupsAndCategories` | Classification regrouping changes already confirmed cleanup IDs.  | Suite overlap; distinct boundary stated here | Keep |
| `metadataIsSentOutsideTransactionsAndUnchangedMailIsCached` | Classification holds a DB transaction over I/O or rebills unchanged metadata.  | Suite overlap; distinct boundary stated here | Keep |
| `manualGroupingAndCategoryCorrectionsWinAndStaleInputsAreReclassified` | Manual grouping/category corrections lose to model results or stale input is treated as current.  | Suite overlap; distinct boundary stated here | Keep |
| `cancellationDuringRemoteCallDiscardsLateAnswers` | Cancelled classification stores a late model result.  | As above; same cancellation scenario owned by retained billing case | Consolidate → lateCancelledResponsesStillChargeExactlyOnce: same cancel-in-response setup; retained SQL no-adoption/state assertions plus exact billing. |
| `changedMetadataDuringRequestIsSkippedAndRetriedOnlyOnNextRun` | Metadata changed during a request is committed instead of skipped until a new run.  | Suite overlap; distinct boundary stated here | Keep |
| `unknownBillingPausesInsteadOfBlindlyRetrying` | Unknown billing is retried or treated as a harmless free failure.  | Suite overlap; distinct boundary stated here | Keep |
| `expiredLeasesRecoverAndDeletedTargetsDoNotBlockProgress` | Expired classification lease/deleted targets strand a job.  | Suite overlap; distinct boundary stated here | Keep |
| `routesEnforceOwnershipCsrfAndDoNotExposeKey` | Classification routes bypass account/CSRF boundaries or expose the key.  | Suite overlap; distinct boundary stated here | Keep |
| `recurringCheckpointSurvivesNewJobsAndContinuationIsIdempotent` | A new job resets shared checkpoint spending or duplicate continuation grants extra allowance.  | Suite overlap; distinct boundary stated here | Keep |
| `lateCancelledResponsesStillChargeExactlyOnce` | Late cancelled responses are uncharged, double charged or adopted as classification.  | Suite overlap; distinct boundary stated here | Keep |
| `expiredDispatchRequiresExplicitBillingReviewAndOwnerContinuation` | Expired dispatch resumes spending without owner acknowledgement of unknown charges.  | Suite overlap; distinct boundary stated here | Keep |
| `inFlightBlocksContinuationAndZeroCostsAreValid` | In-flight spending permits continuation or legitimate zero cost becomes unknown.  | Suite overlap; distinct boundary stated here | Keep |
| `automaticIncomingDeduplicatesAndRespectsSwitchAndSharedPause` | Automatic incoming work duplicates requests or bypasses disabled/shared-pause settings.  | Suite overlap; distinct boundary stated here | Keep |
| `exactAddressDetailsRetainCategoryFilterAndIndependentPagination` | Address drilldown ignores category or uses sender pagination for message pagination.  | Suite overlap; distinct boundary stated here | Keep |
| `batchProgressWithoutIdentityChangesLeavesDraftStableAndInvalidatesCategoryPreviews` | Classification progress unnecessarily invalidates a draft or leaves a category preview usable.  | Suite overlap; distinct boundary stated here | Keep |
| `concurrentResponseReplaysCannotLoseOrDoubleCountSpend` | Concurrent cost reports lose or double-count money.  | As above; different layer/transition | Keep: exact two-attempt money/replay invariant. Uncertain: executor submission does not force every report interleaving. |
| `automaticArrivalsGetFourSlotsAndHistoricalBacklogKeepsOne` | Incoming arrivals starve historical classification or cannot get four reserved batch slots.  | Suite overlap; distinct boundary stated here | Keep |
| `cancellingAutomaticWorkDoesNotImmediatelyRecreateIt` | Cancelled automatic work recreates itself immediately.  | Suite overlap; distinct boundary stated here | Keep |
| `disablingAutomaticLetsManualCatchupFinishAndRetainsDeferredArrivals` | Disabling automatic work loses pending arrivals or interrupts manual catchup.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardCleanupPollingJourneyTest** (Functional)](src/test/java/com/tidymail/DashboardCleanupPollingJourneyTest.java) | **Path:** DashboardCleanupWorker.startPolling → tick → confirmed jobs | **Overlap:** DashboardJobJourneyTest (manual runOnce) | Keep: actual poller wiring is absent from manual worker tests. |
| `confirmedIndexedMailCleanupRunsWithoutTheGlobalScheduler` | Confirmed indexed cleanup never runs with global scheduling disabled, or sends excluded mail.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardGmailDispatchTest** (Functional)](src/test/java/com/tidymail/DashboardGmailDispatchTest.java) | **Path:** GmailMailService.cleanupOnce/fileOnce/getCleanupMetadata → Gmail SDK loopback | **Overlap:** DashboardResilienceTest / ManualFilingJourneyTest (mock provider) | Keep: actual SDK mutation payload/retry behavior is not exercised by mock-provider jobs. |
| `exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries[200,MARK_READ]` | Wrong action/filing label payload or automatic SDK replay mutates exact IDs twice. Wire case 200,MARK_READ. | Suite overlap; distinct boundary stated here | Keep |
| `exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries[200,ARCHIVE]` | Wrong action/filing label payload or automatic SDK replay mutates exact IDs twice. Wire case 200,ARCHIVE. | Suite overlap; distinct boundary stated here | Keep |
| `exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries[200,TRASH]` | Wrong action/filing label payload or automatic SDK replay mutates exact IDs twice. Wire case 200,TRASH. | Suite overlap; distinct boundary stated here | Keep |
| `exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries[200,MARK_READ_ARCHIVE]` | Wrong action/filing label payload or automatic SDK replay mutates exact IDs twice. Wire case 200,MARK_READ_ARCHIVE. | Suite overlap; distinct boundary stated here | Keep |
| `exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries[200,FILING]` | Wrong action/filing label payload or automatic SDK replay mutates exact IDs twice. Wire case 200,FILING. | Suite overlap; distinct boundary stated here | Keep |
| `exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries[200,FILING_ARCHIVE]` | Wrong action/filing label payload or automatic SDK replay mutates exact IDs twice. Wire case 200,FILING_ARCHIVE. | Suite overlap; distinct boundary stated here | Keep |
| `exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries[401,ARCHIVE]` | Wrong action/filing label payload or automatic SDK replay mutates exact IDs twice. Wire case 401,ARCHIVE. | Suite overlap; distinct boundary stated here | Keep |
| `exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries[503,ARCHIVE]` | Wrong action/filing label payload or automatic SDK replay mutates exact IDs twice. Wire case 503,ARCHIVE. | Suite overlap; distinct boundary stated here | Keep |
| `exactCleanupRequestDisablesAutomaticReplayEvenWhenInitializerAllowsRetries[503,FILING]` | Wrong action/filing label payload or automatic SDK replay mutates exact IDs twice. Wire case 503,FILING. | Suite overlap; distinct boundary stated here | Keep |
| `cleanupMetadataHasOneAttemptAndPropagatesRetryGuidanceWithoutSleeping[401]` | Cleanup metadata SDK retries/sleeps or loses original HTTP status and Retry-After. Wire case 401. | Suite overlap; distinct boundary stated here | Keep |
| `cleanupMetadataHasOneAttemptAndPropagatesRetryGuidanceWithoutSleeping[403]` | Cleanup metadata SDK retries/sleeps or loses original HTTP status and Retry-After. Wire case 403. | Suite overlap; distinct boundary stated here | Keep |
| `cleanupMetadataHasOneAttemptAndPropagatesRetryGuidanceWithoutSleeping[429]` | Cleanup metadata SDK retries/sleeps or loses original HTTP status and Retry-After. Wire case 429. | Suite overlap; distinct boundary stated here | Keep |
| `cleanupMetadataHasOneAttemptAndPropagatesRetryGuidanceWithoutSleeping[503]` | Cleanup metadata SDK retries/sleeps or loses original HTTP status and Retry-After. Wire case 503. | Suite overlap; distinct boundary stated here | Keep |
| `cleanupSharesBackgroundCapacityWhileFreshnessRemainsAvailable` | Cleanup consumes reserved freshness capacity instead of sharing background capacity.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardIdentityTest** (Functional)](src/test/java/com/tidymail/DashboardIdentityTest.java) | **Path:** DashboardIdentity / GmailMailService.toMailMessage → MessageMetadataMapper | **Overlap:** DashboardJourneyTest grouping; GmailSynchronizationContractTest conversion | Keep: parser/evidence edge branch does not follow from seeded SQL journeys. |
| `displayNameBracketsNeverBecomeMailboxDelimiters` | Quoted angle brackets select a display-name address instead of the actual sender.  | Suite overlap; distinct boundary stated here | Keep |
| `providerAndMapperPreserveMissingVersusObservedEmptyLabels` | Missing labels become observed-empty and falsely qualify received mail.  | Suite overlap; distinct boundary stated here | Keep |
| `conservativeIdentityPreservesLocalPartAndRejectsAmbiguity` | Address parsing folds local-part case, ignores IDN or accepts ambiguous/injected identities.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardJobJourneyTest** (Functional)](src/test/java/com/tidymail/DashboardJobJourneyTest.java) | **Path:** DashboardJobService → DashboardCleanupWorker (VERIFIED) | **Overlap:** DashboardResilienceTest / ResponseCleanup (different completion modes) | Keep: exact durable target states and recovery transitions are independently observable. |
| `activityDashboardPagesAllJobsAndCountsOnlyConfirmedChanges` | Activity pagination drops jobs or counts unconfirmed/foreign changes.  | Suite overlap; distinct boundary stated here | Keep |
| `concurrentDuplicateConfirmationReturnsOneJobAndOtherOverlappingPreviewConflicts` | Concurrent confirmations create duplicate jobs or overlapping previews bypass active-target holds.  | As above; different layer/transition | Keep: DB identity/overlap-hold assertions. Uncertain: submitted tasks can serialize before entering confirm; not a forced race. |
| `cancelBeforeStartAndDuplicateCancelPreventAllDispatch` | Cancel-before-start or duplicate cancel still dispatches mail.  | Suite overlap; distinct boundary stated here | Keep |
| `cancellationDuringProviderCallLetsClaimedBatchFinishButClaimsNoMore` | Cancel during I/O loses the claimed result or dispatches the next batch.  | Suite overlap; distinct boundary stated here | Keep |
| `cancellationAfterPreflightBeforeIntentStopsTheBatch` | Cancel after preflight but before dispatch intent still sends the batch.  | Suite overlap; distinct boundary stated here | Keep |
| `timeoutAfterSendUsesReadOnlyVerificationEvenAfterCancellation` | A partial timeout is replayed instead of read-only recovery after cancellation.  | Suite overlap; distinct boundary stated here | Keep |
| `exhaustedTrashVerificationFinishesWithUnknownOutcomesAndNormalizesSavedJobs` | Exhausted verification remains active forever or rewrites retained outcomes/holds.  | Suite overlap; distinct boundary stated here | Keep |
| `exhaustedTargetsDoNotHideOtherPendingVerificationOrLoseTheLimitReason` | One exhausted target hides another retryable target or erases terminal guidance.  | Suite overlap; distinct boundary stated here | Keep |
| `restartRecoversQueuedWorkButNeverReplaysAnExpiredDispatchAndFencesLateResults` | An expired dispatch is replayed or a late worker overwrites its successor.  | Suite overlap; distinct boundary stated here | Keep |
| `eligibilityAndProtectionAreRecheckedAndKnownNoOpsStayInFixedTotal` | Changed eligibility/protection and provider no-ops shrink the frozen total or mutate excluded targets.  | Suite overlap; distinct boundary stated here | Keep |
| `allReceivedDispatchRetainsArchivedDeliveredSentMailButExcludesSentOnlyMail` | Delivered Sent mail is dropped or Sent-only mail is treated as received at dispatch.  | Suite overlap; distinct boundary stated here | Keep |
| `protectionAddedAfterPreflightPreventsDispatchAndKeepsAllTargets` | Protection added after preflight does not prevent dispatch.  | Suite overlap; distinct boundary stated here | Keep |
| `readFailureDefersWithoutMutationAndCancellationBypassesTheDelay` | Preflight read failure sends mail or cancellation waits for its retry delay.  | Suite overlap; distinct boundary stated here | Keep |
| `ownershipCsrfExpiredPreviewUnsupportedActionsAndReloadAreEnforced` | Job HTTP endpoints allow foreign access/no-CSRF or expired confirmation and lose reload history.  | Suite overlap; distinct boundary stated here | Keep |
| `deletionCancelsWorkAndWaitsForActiveRequestsButAllowsExplicitPurgeAfterLeasesSettle` | Data deletion purges an active request or cannot explicitly purge settled unresolved history.  | Suite overlap; distinct boundary stated here | Keep |
| `reconciliationRejectsStaleReadsAndBlocksOlderSyncSnapshots` | Recovery read/stale sync overwrites a newer provider revision.  | Suite overlap; distinct boundary stated here | Keep |
| `confirmedTargetAndJobIdentitiesCannotBeChangedInTheDatabase` | SQL permits changing frozen target identity or selected count.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardJourneyTest** (Functional)](src/test/java/com/tidymail/DashboardJourneyTest.java) | **Path:** DashboardService → DashboardRepository / DashboardGroupingService / PreviewService | **Overlap:** Phase2IdentificationJourneyTest; RecommendationDashboardJourneyTest | Keep: current query/selection predicates or correction state; provider rows use different basic SQL plans. |
| `senderPageSizesCoverEverySenderWithoutOverlap[10]` | Sender paging skips/duplicates groups or accepts an invalid page size. Accepted page size and boundary pagination. | Suite overlap; distinct boundary stated here | Keep |
| `senderPageSizesCoverEverySenderWithoutOverlap[20]` | Sender paging skips/duplicates groups or accepts an invalid page size. Accepted page size and boundary pagination. | Suite overlap; distinct boundary stated here | Keep |
| `senderPageSizesCoverEverySenderWithoutOverlap[50]` | Sender paging skips/duplicates groups or accepts an invalid page size. Accepted page size and boundary pagination. | Suite overlap; distinct boundary stated here | Keep |
| `dashboardReportsInitialImportFailureAndAccountScopedRecovery` | Dashboard hides first-import failure or shows another account recovery state.  | Suite overlap; distinct boundary stated here | Keep |
| `gmailDetailsLinkToTheExactMessageInTheConnectedAccount` | Gmail link opens a thread or wrong connected account instead of exact message.  | Suite overlap; distinct boundary stated here | Keep |
| `receivedEligibilityIsPerMessageAndExclusionsOverrideInbox` | Received selection includes Sent-only/unknown/excluded mail or ignores verified delivered aliases.  | Suite overlap; distinct boundary stated here | Keep |
| `dashboardNamesUseSavedFromWithoutChangingGroupingOrSelection[google]` | Saved provider names change grouping/selection IDs or overwrite explicit merged labels. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `dashboardNamesUseSavedFromWithoutChangingGroupingOrSelection[microsoft]` | Saved provider names change grouping/selection IDs or overwrite explicit merged labels. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `senderAddressViewDrillsDownAndPreviewsExactMessagesWithoutChangingManualGroups[google]` | Address view loses exact selection, pagination or chart drilldown after manual grouping. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `senderAddressViewDrillsDownAndPreviewsExactMessagesWithoutChangingManualGroups[microsoft]` | Address view loses exact selection, pagination or chart drilldown after manual grouping. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `domainEvidenceIdentifiesTheBrandAmongDifferentSavedNames[google]` | Domain branding disappears among differing saved names or filtered message display is wrong. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `domainEvidenceIdentifiesTheBrandAmongDifferentSavedNames[microsoft]` | Domain branding disappears among differing saved names or filtered message display is wrong. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `splitsTwoAddressesSharingOneListAndLeavesNewAddressesAutomatic[google]` | Splitting two addresses changes original membership, mishandles new arrivals or cannot reset. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `splitsTwoAddressesSharingOneListAndLeavesNewAddressesAutomatic[microsoft]` | Splitting two addresses changes original membership, mishandles new arrivals or cannot reset. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `mailingListNeverPicksOneBrandWhenFiltersHideConflictingNames[google]` | Filtered subset invents a brand despite conflicting all-mail saved names. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `mailingListNeverPicksOneBrandWhenFiltersHideConflictingNames[microsoft]` | Filtered subset invents a brand despite conflicting all-mail saved names. Provider-specific basic SQL plan. | Suite overlap; distinct boundary stated here | Keep |
| `deterministicGroupingPreservesLocalCaseListsAndSharedDomainExceptions` | Grouping folds local case, merges shared domains or permits ambiguous sender correction.  | Suite overlap; distinct boundary stated here | Keep |
| `organizationGroupsCombineChaseSubdomainsAndKeepExactSelectionsAndCorrections` | Organization drilldown/API selects the wrong addresses or correction leaves preview valid.  | Suite overlap; distinct boundary stated here | Keep |
| `organizationBoundariesRespectPublicPrivateWildcardExceptionAndSharedDomains` | Public/private/wildcard/exception/shared suffix handling merges unrelated senders.  | Suite overlap; distinct boundary stated here | Keep |
| `dateSearchSummaryExpansionAndSelectionsUseTheSamePredicate` | Dates, literal search, summary, detail and selection use different predicates or wrong DST boundary.  | Suite overlap; distinct boundary stated here | Keep |
| `labelExpressionsSelectionAndChartUseTheSameFilteredMessagesAndSavedGroups` | Any/all labels or composition chart diverge from selected IDs and saved merge/split groups.  | Suite overlap; distinct boundary stated here | Keep |
| `correctionsRejectTransitiveConflictsAndResetOnlyTheChosenCorrection` | Transitive grouping conflicts apply or Reset removes unrelated corrections.  | Suite overlap; distinct boundary stated here | Keep |
| `exactPreviewDeduplicatesKeepsNoOpsAndSurvivesNewMailButInvalidatesOnCorrection` | Preview dedupe/no-ops drift with new mail or lose frozen subject/IDs after correction.  | Suite overlap; distinct boundary stated here | Keep |
| `previewsSkipChangedEligibilityProtectedAndNoOpArchiveWithoutSubstitution` | Preview substitutes changed/protected/archive no-op targets or cannot invalidate.  | Suite overlap; distinct boundary stated here | Keep |
| `routesRequireOwnershipCsrfAndRenderTheApprovedDashboard` | Dashboard/preview routes bypass ownership/CSRF or lose saved zone.  | Suite overlap; distinct boundary stated here | Keep |
| `senderPagesAndMessagePagesHaveIndependentTenRowBoundaries` | Sender paging caps exact message selection at visible detail rows.  | Suite overlap; distinct boundary stated here | Keep |
| `senderUnreadViewAndSelectionExcludeReadMessagesAcrossPages` | Unread selection includes read/outside/other-sender messages or exposes select-all beyond groups.  | Suite overlap; distinct boundary stated here | Keep |
| `unreadSortOrdersAllSendersBeforePagingAndUsesMatchingMessages` | Unread ordering sorts only the current page or counts archived messages in Inbox.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardMigrationTest** (Functional)](src/test/java/com/tidymail/DashboardMigrationTest.java) | **Path:** Flyway historical SQL → latest schema (scratch DB) | **Overlap:** Phase13PostgresMigrationTest checksums; runtime persistence journeys | Keep: each populated historical boundary has different data repair/retention requirements. |
| `upgradeFromV49DropsRetiredRuleSuggestionStorageAndPreservesRulesAndMail` | V49→V50 retirement drops saved rules/mail as well as suggestion storage.  | Suite overlap; distinct boundary stated here | Keep |
| `gmailScopeUpgradeDiscardsListTokensAndPreservesIndexHistoryAndCleanup` | Gmail scope upgrade reuses incompatible listing tokens or changes Outlook/index/OAuth state.  | Suite overlap; distinct boundary stated here | Keep |
| `retainedV32RecommendationsAddOnlyEmptyLocalStructuresAndPreserveExistingData` | V32 upgrade rewrites evidence/classification or prepopulates paid/local work.  | Suite overlap; distinct boundary stated here | Keep |
| `retainedV28PersonalCorrectionsRepairCompanyGroupingWithoutChangingManualMembership` | V28 personal identity repair destroys explicit group separation or message corrections.  | Suite overlap; distinct boundary stated here | Keep |
| `retainedV29MarketplaceRelaysRepairWithoutReclassifyingMessagesOrOverridingCorrections` | V29 marketplace repair merges sellers or changes categories/manual membership.  | Suite overlap; distinct boundary stated here | Keep |
| `retainedV24OrganizationRepairPreservesExplicitSeparationAndMessageCategories` | V24 organization repair swallows manual separation/security categories.  | Suite overlap; distinct boundary stated here | Keep |
| `freshAndRetainedUpgradePreserveCredentialsCursorsMetadataAndHistory` | Fresh install/V15→latest loses encrypted tokens, cursors, 10k metadata, history or old preview identity.  | Suite overlap; distinct boundary stated here | Keep |
| `organizationUpgradeRegroupsRetainedAddressesAndPreservesManualMembership` | V19 organization migration changes message/unit IDs or overrides an explicit separation.  | Suite overlap; distinct boundary stated here | Keep |
| `retainedV17DispatchKeepsItsIdentityAndPreconditionForVerificationAfterUpgrade` | V17 dispatch upgrade loses precondition, generation, identity or active overlap hold.  | Suite overlap; distinct boundary stated here | Keep |
| `retainedClassificationsOverridesAndHistoricalSpendingSurviveNewAllowance` | Allowance migration resets historical classifications/costs or falsely enrolls incoming work.  | Suite overlap; distinct boundary stated here | Keep |
| `finalMigrationSetPreservesUnresolvedDashboardTargetsAndTheirOverlapHolds` | V38→latest loses unresolved targets, spent verification attempts or overlap holds.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardPerformanceTest** (Performance)](src/test/java/com/tidymail/DashboardPerformanceTest.java) | **Path:** DashboardRepository + recommendation/history workers + cleanup | **Overlap:** MailboxSyncPerformanceTest (render/commit); LabelingScaleJourneyTest (full cache) | Keep: 10k/100k idle/concurrent plans, 501/5000 previews and a 5000-target active cleanup; timing is local. |
| `realDashboardPathsMeetPhaseTwoBudgetsDuringMetadataWrites` | Dashboard/recommendation/selection reads stall under writes/history/composition/confirmed cleanup; fixed totals fail.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardResilienceTest** (Functional)](src/test/java/com/tidymail/DashboardResilienceTest.java) | **Path:** CleanupWorker → JobService (VERIFIED, 501 targets) | **Overlap:** DashboardJobJourneyTest cancellation/fencing; ResponseCleanup has no preflight reads | Keep: three action-specific desired-state predicates; shared scheduling/rejection paths use Archive once. |
| `multiBatchSuccessReconcilesScopesAndPreservesOtherLabels[MARK_READ]` | Multi-batch action changes wrong scopes/labels, exceeds 50 targets or leaves active holds. Action MARK_READ desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| `multiBatchSuccessReconcilesScopesAndPreservesOtherLabels[ARCHIVE]` | Multi-batch action changes wrong scopes/labels, exceeds 50 targets or leaves active holds. Action ARCHIVE desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| `multiBatchSuccessReconcilesScopesAndPreservesOtherLabels[TRASH]` | Multi-batch action changes wrong scopes/labels, exceeds 50 targets or leaves active holds. Action TRASH desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| `partialTimeoutIsVerifiedWithoutReplayingAndExhaustionStaysUnresolved[MARK_READ]` | Partially applied timeout is replayed or unresolved outcomes vanish at verification exhaustion. Action MARK_READ desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| `partialTimeoutIsVerifiedWithoutReplayingAndExhaustionStaysUnresolved[ARCHIVE]` | Partially applied timeout is replayed or unresolved outcomes vanish at verification exhaustion. Action ARCHIVE desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| `partialTimeoutIsVerifiedWithoutReplayingAndExhaustionStaysUnresolved[TRASH]` | Partially applied timeout is replayed or unresolved outcomes vanish at verification exhaustion. Action TRASH desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| `throttledReadHonorsRetryAfterThenRecoversAndCancelledWaitDoesNotSend[ARCHIVE]` | Read throttle ignores Retry-After, cannot recover or sends cancelled queued work. Action ARCHIVE desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| `reconnectBeforeDispatchCanRecoverAndRepeatedReadFailuresHaveBoundedBudget[ARCHIVE]` | Reconnect/read failures send before auth recovery or exceed the pre-dispatch retry budget. Action ARCHIVE desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| `explicitProviderRejectionProducesFailuresWithoutGuessedChanges[ARCHIVE]` | Explicit provider rejection invents confirmed changes or becomes ambiguous verification. Action ARCHIVE desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| `verificationOutagePreservesRetryGuidanceAndCancelledWorkOnlyVerifiesAfterReconnect[ARCHIVE]` | Post-send read outage loses unresolved state/guidance or repeats a mutation after cancel/reconnect. Action ARCHIVE desired-label/read predicate. | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardResponseCleanupJourneyTest** (Functional)](src/test/java/com/tidymail/DashboardResponseCleanupJourneyTest.java) | **Path:** CleanupWorker → JobService.completeAcknowledged (RESPONSE) | **Overlap:** DashboardResilienceTest (VERIFIED); GmailDispatchTest (wire) | Keep: response mode skips preflight/verification-on-success and applies action-specific local projections. |
| `twoThousandOneHundredSixtyFiveTargetsNeedThreeRequestsAndNoVerificationReads[MARK_READ]` | 2165 exact targets are duplicated/lost across 1000/1000/165 requests or success performs verification reads. Action MARK_READ acknowledgement/local projection. | Suite overlap; distinct boundary stated here | Keep |
| `twoThousandOneHundredSixtyFiveTargetsNeedThreeRequestsAndNoVerificationReads[ARCHIVE]` | 2165 exact targets are duplicated/lost across 1000/1000/165 requests or success performs verification reads. Action ARCHIVE acknowledgement/local projection. | Suite overlap; distinct boundary stated here | Keep |
| `twoThousandOneHundredSixtyFiveTargetsNeedThreeRequestsAndNoVerificationReads[TRASH]` | 2165 exact targets are duplicated/lost across 1000/1000/165 requests or success performs verification reads. Action TRASH acknowledgement/local projection. | Suite overlap; distinct boundary stated here | Keep |
| `twoThousandOneHundredSixtyFiveTargetsNeedThreeRequestsAndNoVerificationReads[MARK_READ_ARCHIVE]` | 2165 exact targets are duplicated/lost across 1000/1000/165 requests or success performs verification reads. Action MARK_READ_ARCHIVE acknowledgement/local projection. | Suite overlap; distinct boundary stated here | Keep |
| `combinedActionKeepsPartiallySatisfiedTargetsAndVerifiesBothLabelsAfterTimeout` | Combined read+archive drops partially satisfied targets or accepts only one label after timeout.  | Suite overlap; distinct boundary stated here | Keep |
| `localProtectionAndEligibilityAreRecheckedWithoutProviderReads` | Response mode sends locally protected/ineligible mail instead of exact surviving target.  | Suite overlap; distinct boundary stated here | Keep |
| `cancellationAfterClaimKeepsThatBatchButStopsTheNextOne` | Cancellation loses acknowledged batch or dispatches the next batch.  | Suite overlap; distinct boundary stated here | Keep |
| `explicitThrottlingCanRetryButAnAmbiguousDispatchCanOnlyBeRead` | Explicit 429 is not retryable, or ambiguous dispatch repeats rather than only reading.  | Suite overlap; distinct boundary stated here | Keep |
| `lostWorkerNeverReplaysAndLateAcknowledgementIsFenced` | Expired response-mode worker replays mutation or its late acknowledgement is accepted.  | Suite overlap; distinct boundary stated here | Keep |
| `staleSyncCannotUndoAcknowledgedLabelsButLaterGmailChangesRemainAuthoritative` | Stale sync undoes acknowledged labels or blocks later authoritative Gmail changes.  | Suite overlap; distinct boundary stated here | Keep |
| [**DashboardSimplificationTest** (Functional)](src/test/java/com/tidymail/DashboardSimplificationTest.java) | **Path:** SecurityConfig / DashboardRepository / JobService / logout | **Overlap:** DashboardJobJourneyTest Activity and cancellation | Keep: current MVP route denial, pending projection and history endpoint differ from worker state. |
| `removedPagesAndEveryLegacyMutationFamilyAreBlocked` | Retired route/mutation families remain reachable in supported MVP mode.  | Suite overlap; distinct boundary stated here | Keep |
| `pendingRowsRestoreAndClearAfterCancellationAndStayAccountScoped` | Pending markers survive cancel incorrectly, disappear for unresolved work or cross accounts.  | Suite overlap; distinct boundary stated here | Keep |
| `historyPagesAllResultsAndNeverDropsActiveJobs` | Jobs/history APIs drop active jobs, omit page 2 or accept invalid page.  | Suite overlap; distinct boundary stated here | Keep |
| `syncDetailsHaveNoLegacyNavigationAndSignOutKeepsIndex` | Sync navigation exposes retired UI or ordinary sign-out deletes retained index.  | Suite overlap; distinct boundary stated here | Keep |
| [**GmailBudgetAndCredentialsTest** (Functional)](src/test/java/com/tidymail/GmailBudgetAndCredentialsTest.java) | **Path:** GmailRateLimiter / GmailClientFactory → managed credentials | **Overlap:** GmailSynchronizationContractTest concurrency; OAuthAuthorizedClientRefreshTest persisted refresh | Keep: account quota/transport initialization differs from durable credential refresh. |
| `sharedCooldownHonorsRetryAfterAndDoesNotBlockAnotherAccount` | One account ignores shared throttle or blocks another account.  | Suite overlap; distinct boundary stated here | Keep |
| `backgroundBudgetLeavesForegroundCapacityAndWaitingNeverHoldsTheMonitor` | Background quota consumes foreground budget or waits holding the shared monitor.  | Suite overlap; distinct boundary stated here | Keep |
| `managedTokenNearExpiryDoesNotTriggerAnUnsupportedSecondRefresh` | A near-expiry managed token causes a second unsupported SDK refresh or transport/retry settings drift.  | Suite overlap; distinct boundary stated here | Keep |
| [**GmailEvidenceReaderTest** (Functional)](src/test/java/com/tidymail/GmailEvidenceReaderTest.java) | **Path:** GmailEvidenceReader → SDK loopback / CalendarEvidenceParser | **Overlap:** MessageEvidenceJourneyTest cache/persistence; CalendarResponseJourneyTest event association | Keep: actual MIME projection, response bounds and attachment read accounting. |
| `selectiveCalendarReadsRetainPartProvenanceAndNeverDownloadPdfOrProse` | Calendar discovery downloads prose/PDF, duplicates attachment reads or persists raw private event data.  | Suite overlap; distinct boundary stated here | Keep |
| `largeInlineContentNeedsNoAttachmentToRemainInspectable` | Large inline content falsely requires an attachment or missing snippet becomes known text.  | Suite overlap; distinct boundary stated here | Keep |
| `wrongIdentityContentAndOversizedResponsesAreRejectedWithoutRetries` | Wrong-ID/raw/oversized/HTTP-error evidence is accepted or automatically retried.  | Suite overlap; distinct boundary stated here | Keep |
| `snippetsRespectUtf8BytesWithoutSplittingCharacters` | UTF-8 snippet byte limit splits a character.  | Suite overlap; distinct boundary stated here | Keep |
| `backgroundDiscoveryFetchesOnlyOneMimeProjectionWithoutSnippetOrCalendarBytes` | Background presence reads snippets/calendar content or more than one projected structure.  | Suite overlap; distinct boundary stated here | Keep |
| [**GmailSynchronizationContractTest** (Functional)](src/test/java/com/tidymail/GmailSynchronizationContractTest.java) | **Path:** GmailMailService → SDK fixture / mapper / rate limiter | **Overlap:** GmailBackfillRecoveryJourneyTest HTTP-to-DB recovery | Keep: provider-specific projections, ordered history and config branches. |
| `providerProfilesBootstrapsAndBackfillsMetadataOnly` | Profile/backfill loses unsigned cursor, fetches nonmetadata content or includes Spam/Trash.  | Suite overlap; distinct boundary stated here | Keep |
| `providerConsumesOnlySpecificUnfilteredHistoryEventsAcrossPages` | History consumes generic messages or loses specific changes/deletes/pagination boundary.  | Suite overlap; distinct boundary stated here | Keep |
| `completeHistoryWindowKeepsOrderedLabelDeltasAndCoalescesDeletions` | Full history window loses ordered label changes or deleted-message coalescing.  | Suite overlap; distinct boundary stated here | Keep |
| `boundedHistoryWindowFallsBackInsteadOfApplyingAnIncompleteLabelHistory` | Bounded/incomplete history applies partial label chains instead of metadata fallback.  | Suite overlap; distinct boundary stated here | Keep |
| `providerClassifiesMetadataDeletionAndExpiredHistoryWithoutReplacingCursor` | Deleted metadata or expired history is confused with a successful replacement cursor.  | Suite overlap; distinct boundary stated here | Keep |
| `providerReconcilesAttachmentPresenceFromIdMembershipOnly` | Attachment membership fetches content or loses ID-only pagination.  | Suite overlap; distinct boundary stated here | Keep |
| `gmailMetadataMapsLabelsSizeAndUnsignedRevisionWithoutSnippetOrMimeInference` | Gmail conversion guesses MIME attachments, loses revision/size or accepts body/raw data.  | Suite overlap; distinct boundary stated here | Keep |
| `configurationDefaultsToProgressiveUnlimitedSyncAndReservesFreshnessCapacity` | Sync defaults/config bounds remove progressive import or reserved freshness permits.  | Suite overlap; distinct boundary stated here | Keep |
| [**LabelingPipelineJourneyTest** (Functional)](src/test/java/com/tidymail/LabelingPipelineJourneyTest.java) | **Path:** DashboardController/LabelingWorker → LabelingPipeline → budget, semantic labels, recommendations | **Overlap:** JevClassifierTest/RecommendationSemanticContractTest wire parsing; RecommendationPersistenceTest local composition | Keep: reviewed exact scopes, durable acquisition/recovery and money accounting are absent from pure composition tests. |
| `gmailDeletionOfLabeledMailPreservesCleanupHistoryAndPaidAccounting` | Provider deletion of labeled mail deletes cleanup history/exposure/spending or leaves invalidation debris.  | Suite overlap; distinct boundary stated here | Keep |
| `removingOnlySemanticLabelsStillInvalidatesTheSurvivingMessage` | Deleting only semantic labels fails to invalidate a surviving message.  | Suite overlap; distinct boundary stated here | Keep |
| `scopesAreInertExactAndAccountOwnedAndDoNotQueueEveryReview` | Inert scope preparation authorizes work, accepts foreign/wrong-hash/duplicate/Sent targets or queues all Review mail.  | Suite overlap; distinct boundary stated here | Keep |
| `backgroundPresenceAddsCalendarAlongsideContactWithoutSnippetOrCalendarContentReads` | Presence discovery overwrites saved snippets, rereads cached MIME or loses overlapping Contact/calendar labels.  | Suite overlap; distinct boundary stated here | Keep |
| `labelsAccumulateAcrossKeepReviewAndManualActionsAndFeedFilters` | Independent labels disappear under Keep/Review/manual actions or fail to feed SQL filters (nine fixture combinations).  | Suite overlap; distinct boundary stated here | Keep |
| `unknownPropertiesAndForwardsStillEarnIndependentLabelsButNeverDisposal` | Unknown properties or forwarded content suppress independent labels or authorize disposal.  | Suite overlap; distinct boundary stated here | Keep |
| `staleBeforeDispatchDoesNotChargeAndStaleResponseIsAccountedButHidden` | Stale pre-dispatch inputs charge money or late stale answers remain visible without being billed.  | Suite overlap; distinct boundary stated here | Keep |
| `policyAndCorrectionChangesReusePaidLabelsAndUnchangedReviewIsNotResubmitted` | Policy/correction/read changes repay unchanged labels or changed content remains current.  | Suite overlap; distinct boundary stated here | Keep |
| `recoveryNeverRetriesUnknownChargesAndRespectsReservationsAndMimeRetryLimits` | Unknown charges replay, reservations vanish, or MIME retry bypasses reviewed read caps.  | Suite overlap; distinct boundary stated here | Keep |
| `persistedResponseRecoversWithoutAnotherTransportCallAndInvalidTypedAnswersAreBilled` | Stored response recovery calls transport again or invalid typed results escape billing.  | Suite overlap; distinct boundary stated here | Keep |
| `financialHoldAndLabelFilteredPreviewStayIndependentOfDisposalQualification` | Financial unread hold disappears or stale label-filtered preview remains confirmable.  | Suite overlap; distinct boundary stated here | Keep |
| `duplicateScopesExpiredLeasesAndAllowanceExhaustionDoNotDuplicateExposure` | Duplicate scopes/expired leases create duplicate exposure or shared allowance exhaustion still sends.  | Suite overlap; distinct boundary stated here | Keep |
| `missingPricingAndTruncatedMimeRemainExplicitAndWorkCanResumeAfterRestart` | Missing pricing dispatches money or truncated MIME invents calendar evidence.  | Suite overlap; distinct boundary stated here | Keep |
| `labelingEndpointsRequireAuthenticationCsrfAndTheReviewedAccountHash` | Scope API skips CSRF/ownership review or preparing an exact batch starts it.  | Suite overlap; distinct boundary stated here | Keep |
| `contradictingPurposeDoesNotEraseAnIndependentShippingLabel` | Conflicting purpose erases independent Shipping or invents a disposal decision.  | Suite overlap; distinct boundary stated here | Keep |
| `coverageSeparatesApprovedWorkFromDraftsPausesFailuresAndCompletedScopes` | Coverage confuses drafts, paused, leased, failed, completed and foreign work.  | Suite overlap; distinct boundary stated here | Keep |
| `successfulMimeRetryClearsItsErrorAndAllAttemptsStillConsumeTheReviewedCap` | Successful MIME retry keeps stale error or retry attempts exceed reviewed request/byte caps.  | Suite overlap; distinct boundary stated here | Keep |
| `persistedScopeCanBeReopenedAndRecoveredThroughOwnedHttpControlsWithoutResettingCaps` | Owned HTTP recovery resets caps, allows foreign/wrong-hash/no-CSRF controls or silently resumes MIME retry.  | Suite overlap; distinct boundary stated here | Keep |
| `candidatePreviewFindsMissingAndOtherMailIndependentlyOfKeepAndNeverBlindlyRepaysUnknown` | Missing/Other candidate review includes excluded or previously paid unchanged mail.  | Suite overlap; distinct boundary stated here | Keep |
| `exactInAppStartWorksWithGlobalSchedulingOffAndCannotDispatchAnotherScope` | Explicit in-app Start dispatches another authorized scope or replays completed requests.  | Suite overlap; distinct boundary stated here | Keep |
| `reviewSeparatesMailboxCoverageFromAnExactThousandMessageBatchWithoutStartingIt` | 1005-mail coverage is confused with the exact inert 1000-message prepared batch.  | Suite overlap; distinct boundary stated here | Keep |
| `browserPreviewAndPreparationAreInertOwnedAndProgressDoesNotReloadRequestPayloads` | Preview/preparation starts work or progress repeatedly returns the large request manifest.  | Suite overlap; distinct boundary stated here | Keep |
| `explicitBatchPauseAndResumeReuseSavedWorkWithoutReplayingCompletedMessages` | Explicit pause/resume replays the completed message.  | Suite overlap; distinct boundary stated here | Keep |
| `reviewedAllowanceIncreaseRaisesBothLimitsPreservesCompletedWorkAndDoesNotResume` | Reviewed allowance increase resets exact requests/completed work, resumes automatically or grants duplicate approval.  | Suite overlap; distinct boundary stated here | Keep |
| `allowanceReviewsEnforceOwnershipOutstandingChargesAndFreshBudgetSnapshots` | Allowance review accepts foreign owner, outstanding unknown charges or stale financial snapshot.  | Suite overlap; distinct boundary stated here | Keep |
| `allowanceHttpReviewIsInertAndConfirmationRequiresCsrfAndTheExactReviewHash` | Allowance HTTP review changes limits before exact CSRF-protected confirmation.  | Suite overlap; distinct boundary stated here | Keep |
| `fundingPauseDoesNotPreventRecoveringAnAlreadyStoredResponseWithoutAnotherPaidCall` | Funding pause blocks settlement of an already stored response or pays again to recover.  | Suite overlap; distinct boundary stated here | Keep |
| [**LabelingScaleJourneyTest** (Performance)](src/test/java/com/tidymail/LabelingScaleJourneyTest.java) | **Path:** Full semantic/recommendation cache → DashboardRepository during local composition | **Overlap:** DashboardPerformanceTest partial cache; LabelingPipelineJourneyTest small real pipeline | Keep: full 115k JSON/exposure/cache join selectivity; synthetic timing is not quality/provider throughput. |
| `full115kLabelCacheRemainsBrowsableDuringLocalComposition` | Full populated 115k cache cannot filter by School/action during local recomposition within local budget.  | Suite overlap; distinct boundary stated here | Keep |
| [**LocalGmailLabelsJourneyTest** (Functional)](src/test/java/com/tidymail/LocalGmailLabelsJourneyTest.java) | **Path:** RecommendationProcessingService → RecommendationService local labels; dashboard label cache | **Overlap:** PersonalOutlookJourneyTest native calendar; CalendarResponseJourneyTest current RSVP | Keep: local version rebuild/cache invalidation differs from provider RSVP reads. |
| `existingOrganizerInvitesAreRelabeledWithoutMimeReads` | Retained Google invitation headers need paid/MIME reads or fail to relabel after rule-version upgrade.  | Suite overlap; distinct boundary stated here | Keep |
| `storedCalendarTimingDoesNotProduceRetiredPastEventLabels` | Stored old calendar timing invents retired Past Event labels or accepts retired filter.  | Suite overlap; distinct boundary stated here | Keep |
| `gmailLabelNamesAreAccountScopedAndFetchedOnceForAnOpenDashboard` | Gmail label-name cache leaks accounts or fetches again for the same open dashboard.  | Suite overlap; distinct boundary stated here | Keep |
| [**MailboxFreshnessConcurrencyTest** (Functional)](src/test/java/com/tidymail/MailboxFreshnessConcurrencyTest.java) | **Path:** MailboxSyncStateRepository / executors / IncrementalSyncJob / FreshnessService | **Overlap:** MailboxSyncPersistenceTest commit; GmailBackfillRecoveryJourneyTest scheduler/SDK | Keep: lane ownership, executor admission and I/O transaction boundaries differ from cursor persistence. |
| `twoApplicationInstancesCannotOwnOneLaneButSeparateLanesCanOverlap` | Two instances own one lane or separate freshness/backfill lanes cannot overlap.  | Suite overlap; distinct boundary stated here | Keep |
| `freshnessStartsAndFinishesWhileBackfillExecutorIsSaturated` | Saturated backfill executor prevents freshness execution.  | Suite overlap; distinct boundary stated here | Keep |
| `freshnessProviderRunsOutsideTransactionsAndCommitsNoChangeAtomically` | Freshness holds transaction over provider I/O or fails to commit no-change cursor.  | Suite overlap; distinct boundary stated here | Keep |
| `staleLeaseRevisionCannotTakeOwnershipAfterNewerRevisionWins` | Stale CAS lease version acquires ownership or expired lease cannot transfer.  | Suite overlap; distinct boundary stated here | Keep |
| `returningAccountWithoutHistoryCursorQueuesBootstrapBeforeFreshness` | Returning account without cursor queues freshness instead of bootstrap.  | Suite overlap; distinct boundary stated here | Keep |
| `rejectedFreshnessSubmissionRecordsTerminalFailureAndCanBeRetried` | Rejected executor submission strands a queued lane instead of terminal failure and retry.  | Suite overlap; distinct boundary stated here | Keep |
| [**MailboxSyncPerformanceTest** (Performance)](src/test/java/com/tidymail/MailboxSyncPerformanceTest.java) | **Path:** SyntheticMailboxGenerator → rendered routes + SyncCommitService | **Overlap:** DashboardPerformanceTest query latency | Keep: 100k index plus bounded 500-message real commit/cursor; route ceiling alone is weak and documented. |
| `mailboxSyncUsesBoundedWorkAtRealisticVolume` | At 100k retained rows, route rendering or 500-row metadata/cursor commit stalls or loses bounded rows.  | Suite overlap; distinct boundary stated here | Keep |
| [**MailboxSyncPersistenceTest** (Functional)](src/test/java/com/tidymail/MailboxSyncPersistenceTest.java) | **Path:** MailboxSyncCommitService → state + MessageMetadataSyncRepository | **Overlap:** MailboxFreshnessJourneyTest orchestration; state schema tests | Keep: real transaction rollback/idempotence/account isolation, not mock service return values. |
| `failedCursorWriteRollsBackMetadataDeletesAndActivityTogether` | Cursor write failure leaves metadata deletes/updates/activity partially committed.  | Suite overlap; distinct boundary stated here | Keep |
| `replayIsIdempotentAndLowerRevisionCannotOverwriteNewerFacts` | Replayed unit changes row identity/first-seen or lower revision overwrites newer facts.  | Suite overlap; distinct boundary stated here | Keep |
| `providerDeletesAreIdempotentAndConfinedToTheTargetAccount` | Provider deletion affects another account or replay deletes/counts twice.  | Suite overlap; distinct boundary stated here | Keep |
| `noChangeProviderCheckAdvancesFreshnessWithoutCompletingBackfill` | No-change cursor advances but incorrectly completes historical backfill or loses activity snapshot.  | Suite overlap; distinct boundary stated here | Keep |
| `completedRescanSweepsOnlyOldRowsOutsideTheGeneration` | Rescan sweep deletes post-start freshness rows or current-generation mail.  | Suite overlap; distinct boundary stated here | Keep |
| [**MailboxSyncStateTest** (Functional)](src/test/java/com/tidymail/MailboxSyncStateTest.java) | **Path:** Flyway constraints → mailbox_sync_state/message_metadata | **Overlap:** MailboxSyncPersistenceTest uses valid states only | Keep: direct DB rejection proves invariants survive writers outside service validation. |
| `accountConnectsAndReceivesIndependentDurableMailboxLaneState` | Lane/default/version/lease constraints accept impossible state or account deletion leaves sync state.  | Suite overlap; distinct boundary stated here | Keep |
| `metadataPersistsUnsignedRevisionAndTriStateProvenance` | Unsigned revision overflows or tri-state attachment provenance can become internally contradictory.  | Suite overlap; distinct boundary stated here | Keep |
| [**ManualFilingJourneyTest** (Functional)](src/test/java/com/tidymail/ManualFilingJourneyTest.java) | **Path:** Preview/Job/Worker → MailDestinationService → Gmail fileOnce | **Overlap:** GmailDispatchTest filing wire; PersonalOutlookJourneyTest folder filing | Keep: destination intent is an additional mutation/recovery boundary. |
| `exactPreviewCancellationAndConfirmedFilingRetainOtherLabelsAndDoNotCreateRules` | Review/cancel creates destination or files extra/protected/no-op IDs; filing creates a rule or removes unrelated labels.  | Suite overlap; distinct boundary stated here | Keep |
| `lostMutationResponseIsVerifiedAfterLeaseRecoveryWithoutReplayingAndDestinationIntentNeverRepeats` | Lost filing response replays the write, or abandoned destination intent creates a duplicate label.  | Suite overlap; distinct boundary stated here | Keep |
| [**MessageEvidenceJourneyTest** (Functional)](src/test/java/com/tidymail/MessageEvidenceJourneyTest.java) | **Path:** Dashboard/Preview/Job + MessageEvidenceService → mapper/sync | **Overlap:** GmailEvidenceReaderTest wire; Phase2IdentificationJourneyTest related calendar history | Keep: saved evidence and exact selection/bytes are separate from transport parsing. |
| `largeSuggestionsUseWholeMessageSizeAndExactFrozenSelectionBytes` | Large-mail SQL/preview selects wrong boundary/Sent/excluded IDs or counts unfrozen bytes.  | Suite overlap; distinct boundary stated here | Keep |
| `checkingOneOwnedEmailIsReadOnlyCachedAndSurvivesMetadataSync` | Owned cached inspection rereads provider, leaks ownership/CSRF or sync erases saved evidence.  | Suite overlap; distinct boundary stated here | Keep |
| `reviewedOrganizationMergesListVariantsButPreservesCorrectionsAndSecurityTypes` | Reviewed organization grouping loses category or ignores explicit separation on new mail.  | Suite overlap; distinct boundary stated here | Keep |
| `scopedReadPermissionIsCheckedOnlyAtTheActualUncachedRead` | Uncached presence read bypasses reservation while cached read unnecessarily consumes cap.  | Suite overlap; distinct boundary stated here | Keep |
| `previewReportsUnknownSizesWithoutCountingThemAsZeroCoverage` | Unknown selected size is reported as zero known coverage.  | Suite overlap; distinct boundary stated here | Keep |
| `personalCorrespondenceAtAReviewedCompanyKeepsItsOwnIdentity` | A personal correspondent at a reviewed company merges with automated organization mail.  | Suite overlap; distinct boundary stated here | Keep |
| [**OAuthAuthorizedClientRefreshTest** (Functional)](src/test/java/com/tidymail/OAuthAuthorizedClientRefreshTest.java) | **Path:** JpaOAuth2AuthorizedClientService / ManagedGoogleCredentialService → token loopback | **Overlap:** Phase3PersistenceTest encryption; OAuthLoginSuccessListenerTest callback; GmailBudgetAndCredentialsTest SDK | Keep: persisted revocation/refresh/reauth and concurrent lane behavior. |
| `userLosesAuthorizationWithoutLosingIndexedMailboxData` | Removing authorization deletes retained index/state or invalidates another account.  | Suite overlap; distinct boundary stated here | Keep |
| `userReauthorizesWithoutReplacingRefreshToken` | Reauthorization without returned refresh token destroys the stored refresh token.  | Suite overlap; distinct boundary stated here | Keep |
| `userWithUnknownTokenTimesKeepsThemUnknownWhenLoaded` | Loading credentials invents unknown token issue/expiry times.  | Suite overlap; distinct boundary stated here | Keep |
| `userRefreshesOnlyAtOrInsideTheConfiguredExpirySkew` | Expiry skew refreshes too early/late or refresh persistence loses token.  | Suite overlap; distinct boundary stated here | Keep |
| `parallelMailboxLanesBuildClientsFromOnePersistedRefresh` | Parallel mailbox lanes refresh twice or build clients with stale bearer token.  | As above; different layer/transition | Keep: shared persisted refresh/bearer protection. Uncertain: second caller is submitted before release but not barrier-proven to overlap. |
| `userWithRevokedGrantStopsBeforeAnyGmailRequestOrRefreshLoop` | Revoked grant loops refresh/provider calls, leaks provider detail or deletes retained mail.  | Suite overlap; distinct boundary stated here | Keep |
| [**OutlookGraphBatchTest** (Functional)](src/test/java/com/tidymail/OutlookGraphBatchTest.java) | **Path:** SecurityConfig token clients → OutlookGraph RestClient | **Overlap:** OAuthAuthorizedClientRefreshTest Google manager; PersonalOutlookJourneyTest mock Graph | Keep: actual refresh vs auth-code transports and Graph HTTP response handling. |
| `stalledTokenEndpointTimesOutBeforeMutationAndAllowsRecovery[true]` | Stalled token endpoint permits mailbox mutation or cannot recover after timeout (refresh/code clients differ). Refresh-token transport before Graph mutation. | Suite overlap; distinct boundary stated here | Keep |
| `stalledTokenEndpointTimesOutBeforeMutationAndAllowsRecovery[false]` | Stalled token endpoint permits mailbox mutation or cannot recover after timeout (refresh/code clients differ). Authorization-code transport during sign-in. | Suite overlap; distinct boundary stated here | Keep |
| `folderAndRuleCreationReturnTheirResponseIdentities` | Graph 201 creation discards returned folder/rule identity.  | Suite overlap; distinct boundary stated here | Keep |
| `transientAuthorizationFailureCanRetryBecauseNoGraphRequestWasSent` | Transient authorization is confused with post-dispatch uncertainty although no Graph request was sent.  | Suite overlap; distinct boundary stated here | Keep |
| `batchTransportReadsIndividualStatusesAndSharesRetryAfterWithSync` | Graph ignores per-item throttle/Retry-After and lets sync bypass shared cooldown.  | Suite overlap; distinct boundary stated here | Keep |
| [**PersonalOutlookJourneyTest** (Functional)](src/test/java/com/tidymail/PersonalOutlookJourneyTest.java) | **Path:** AccountSession/OutlookSyncService → OutlookMailService → JobService | **Overlap:** Gmail journeys have different delta/folder/batch semantics; GraphBatchTest wire | Keep: Outlook-specific identity, delta cursors, move/read steps and per-item results. |
| `consumerAuthorizationAndRetainedAccountSwitchUseStableIdentity` | Consumer consent/PKCE or retained mailbox switch uses email instead of stable owned subject.  | Suite overlap; distinct boundary stated here | Keep |
| `resumesAnInterruptedBaselineAndReconcilesMovesDeletesAndFolderChanges` | Interrupted baseline/delta/folder inventory loses cursor, stable IDs, moves/deletes or renamed nested paths.  | Suite overlap; distinct boundary stated here | Keep |
| `sizesResumeSeparatelyAndDriveTheExactLargeMailPreview` | Size scan failure blocks freshness, loses separate cursor or widens large-mail preview.  | Suite overlap; distinct boundary stated here | Keep |
| `calendarFilterMatchesNativeMeetingsAndGoogleInvites` | Native meeting/cancellation and Google header invite filtering incorrectly includes accepted-response messages.  | Suite overlap; distinct boundary stated here | Keep |
| `partialAndAmbiguousCombinedActionsReconcileWithoutRepeatingTheMove` | Partial read+move or lost move receipt repeats the move or loses partial-read state.  | Suite overlap; distinct boundary stated here | Keep |
| `exactConfirmedActionsRespectProtectionAndMoveToProviderFolders` | Exact confirmed read/archive/trash ignores protection or uses wrong provider folder.  | Suite overlap; distinct boundary stated here | Keep |
| `manualFolderFilingUsesReviewedNestedDestinationAndReconcilesLostAcknowledgement` | Nested-folder filing widens reviewed targets, changes unread or replays missing acknowledgement.  | Suite overlap; distinct boundary stated here | Keep |
| `bulkAcknowledgementsUseTwentyItemBatchesWithoutReadsAndRetainCancellation` | Bulk Graph jobs exceed 20-item batches, read after success or ignore cancellation.  | Suite overlap; distinct boundary stated here | Keep |
| `mixedBatchResultsRetryOnlyThrottledTargetsAndSyncSettlesMissingAcknowledgements` | Mixed batch retries failed/accepted targets or loses sync reconciliation for omitted results.  | Suite overlap; distinct boundary stated here | Keep |
| `uncertainOutcomesDoNotDelayTheRemainingQueuedMessages` | One unresolved result stalls remaining queued messages or becomes a repeated move.  | Suite overlap; distinct boundary stated here | Keep |
| `anInflightSyncObservationCannotUndoAnAcknowledgedMove` | In-flight stale sync observation undoes acknowledged read/archive.  | Suite overlap; distinct boundary stated here | Keep |
| `lostWholeBatchResponseReconcilesWithoutRepeatingAnyMoves` | Lost whole Graph batch response causes any move replay.  | Suite overlap; distinct boundary stated here | Keep |
| `tickDrainsMoreThanOneBatchAndRechecksLocalProtections` | Worker tick only processes one batch or ignores protection added before the next job.  | Suite overlap; distinct boundary stated here | Keep |
| [**Phase13PostgresMigrationTest** (Functional)](src/test/java/com/tidymail/Phase13PostgresMigrationTest.java) | **Path:** Published V1–V10 SQL bytes → pinned SHA-256 | **Overlap:** DashboardMigrationTest executes upgrades | Keep: historical DB cannot detect accidental edits to old migrations from a freshly created test DB. |
| `publishedMigrationSqlHasNotChanged` | Published V1–V10 migrations are edited and retained Flyway installations later reject checksums.  | Suite overlap; distinct boundary stated here | Keep |
| [**Phase2IdentificationJourneyTest** (Functional)](src/test/java/com/tidymail/Phase2IdentificationJourneyTest.java) | **Path:** MessageEvidenceService / ReviewedPatternService / DashboardRepository | **Overlap:** CalendarReconcilerTest pure timing; ReviewedPatternPolicyTest admission; DashboardJourneyTest grouping | Keep: retained evidence/review invalidation and SQL grouping add persistence integration. |
| `cachedInspectionReconcilesNewUpdatesWithoutReadingOldMessageAgainAndExcludesOtherAccounts` | New event update does not reconcile cached old inspection, or foreign account reads it.  | Suite overlap; distinct boundary stated here | Keep |
| `stableNamesReadAllReceivedEvidenceAndOtherFilterIncludesUncertainty` | Filtered subset changes saved name or Other drops stored Uncertain/exact sender IDs.  | Suite overlap; distinct boundary stated here | Keep |
| `reviewedReuseIsScopedExplicitInspectableAndInvalidatedByChangedEvidence` | Pattern reuse is implicit, crosses sender/unit/accounts or survives source/calendar/content changes.  | Suite overlap; distinct boundary stated here | Keep |
| `changedPreviewAndConflictingReviewedExamplesCannotApply` | Changed preview or conflicting reviewed sources can still apply inherited label.  | Suite overlap; distinct boundary stated here | Keep |
| `allDayUpdatesStayDateOnlyAndOlderRelatedEvidenceMustBeExplicitlyRefreshed` | Old-version related evidence yields dates without explicit refresh or all-day dates gain invented timezone.  | Suite overlap; distinct boundary stated here | Keep |
| `reviewExplainsReplyAndChangedTemplateExclusionsAndOldPolicyLabelsDisappear` | Pattern review hides reply/template exclusion or old policy labels stay visible.  | Suite overlap; distinct boundary stated here | Keep |
| `personalCompanyAndPersonalMailboxIdentitiesSurviveMetadataRefreshAndSavedMerges` | Personal company/home mail merges by name/employer or explicit user merge is lost on refresh.  | Suite overlap; distinct boundary stated here | Keep |
| `marketplaceSellerRelaysNeverJoinAutomatedAmazonOrInheritItsPatterns` | Marketplace seller relays merge with automated Amazon or inherit its reviewed pattern.  | Suite overlap; distinct boundary stated here | Keep |
| [**Phase2ReviewedReferenceFixture** (Archive)](src/test/java/com/tidymail/Phase2ReviewedReferenceFixture.java) | **Path:** Explicit private fixture → ReviewedPatternService on test DB | **Overlap:** Current synthetic pattern tests | Keep: archived experiment reproducibility only; not current release coverage and not run. |
| `replayFrozenReviewedHeadersWithoutChangingRetainedMailbox` | No default user-flow protection; private frozen review replay must preserve account/sender/source association.  | Suite overlap; distinct boundary stated here | Keep |
| [**Phase3PersistenceTest** (Functional)](src/test/java/com/tidymail/Phase3PersistenceTest.java) | **Path:** CryptoUtil + JpaOAuth2AuthorizedClientService on Postgres | **Overlap:** OAuthAuthorizedClientRefreshTest lifecycle | Keep: stored-token cryptography and initial no-refresh representation differ from reauth. |
| `aes256gcmRoundtripAndRandomIv` | Token encryption reuses IV, cannot round-trip or accepts tampering.  | Suite overlap; distinct boundary stated here | Keep |
| `oauthSaveRepresentsMissingInitialRefreshTokenAsEncryptedEmptyValue` | First access-only OAuth save/load invents a refresh token or cannot represent absence.  | Suite overlap; distinct boundary stated here | Keep |
| [**Phase6SenderAggregationTest** (Functional)](src/test/java/com/tidymail/Phase6SenderAggregationTest.java) | **Path:** Rfc5322Util → MessageMetadataMapper.fromAddress/fromDomain | **Overlap:** DashboardIdentityTest uses different conservative parser; Phase9MetadataIndexTest only one formatted header | Keep: existing mapper still calls both parsers; independent normal/invalid branches. |
| `T6_a_01_extractAddrSpecHappyPaths` | Formatted/commented/bare addresses map to wrong lowercased addr-spec (four input shapes).  | Suite overlap; distinct boundary stated here | Keep |
| `T6_a_02_extractAddrSpecGuards` | Null/blank/malformed addr-spec input becomes an address; whitespace handling breaks.  | Suite overlap; distinct boundary stated here | Keep |
| `T6_a_03_extractDomainHappyPaths` | Formatted/bare mixed-case input produces wrong stored sender domain.  | Suite overlap; distinct boundary stated here | Keep |
| `T6_a_04_extractDomainGuards` | Malformed domain input yields a usable sender domain instead of null.  | Suite overlap; distinct boundary stated here | Keep |
| [**Phase8DeploymentTest** (Functional)](src/test/java/com/tidymail/Phase8DeploymentTest.java) | **Path:** Scheduled SenderStatsVacuumJob → account/stats/progress repositories | **Overlap:** DashboardMigrationTest retention applies during upgrades, not nightly job | Keep: scheduled job still runs in MVP and could delete current derived history or retained mail. |
| `vacuumDeletesOnlyInactiveAccountsDerivedData` | Nightly stale-account purge touches fresh/never-login accounts or deletes retained account/mail data.  | Suite overlap; distinct boundary stated here | Keep |
| [**Phase9ActionScopeTest** (Functional)](src/test/java/com/tidymail/Phase9ActionScopeTest.java) | **Path:** ActionScopeHasher → DashboardPreviewService / ActionScopeService; summary DTOs | **Overlap:** DashboardJourneyTest exact scope integration | Keep: canonical hash compatibility/collision boundaries and loaded-summary bounds matter for persisted scopes. |
| `knownHashIsVersionedDeterministicAndDeduplicated` | Canonical hash changes across release/order/case/deduplication and breaks retained exact scope identity.  | Suite overlap; distinct boundary stated here | Keep |
| `everyIdentityFieldChangesHashAndLengthPrefixesPreventAmbiguity` | Account/action/kind/ID boundaries collide or malformed Unicode produces an ambiguous scope.  | Suite overlap; distinct boundary stated here | Keep |
| `rejectsInvalidIdentityAndBoundsTypedSummaries` | Invalid exact identity or impossible loaded summary bounds are accepted.  | Suite overlap; distinct boundary stated here | Keep |
| `summaryJsonRejectsUnknownAndNestedCallerValues` | A standalone strict ObjectMapper rejects caller JSON or makes DTO lists immutable.  | As above; current pipeline covers app behavior | Remove: standalone strict mapper does not exercise app input/JPA mapper; summaries are generated server-side; constructor bounds remain. |
| [**Phase9MetadataIndexTest** (Functional)](src/test/java/com/tidymail/Phase9MetadataIndexTest.java) | **Path:** MessageMetadataMapper.apply → stored metadata | **Overlap:** GmailSynchronizationContractTest wire conversion; FreshnessJourneyTest SQL visibility | Keep: direct mapper field/header-copy contract; rename to match actual assertions, remove unasserted sentinel. |
| `mapperCopiesOnlyApprovedMetadataAndDefensivelyCopiesCollections` → `mapperMapsSupportedFieldsAndDefensivelyCopiesHeaderCollections` | Supported metadata fields or defensive header copies change; no generic unapproved-header exclusion was asserted.  | As above; different layer/transition | Keep: mapper/header-copy assertions; rename accurately and delete unasserted private-header and pre-copied-label setup. |
| [**RecommendationDashboardJourneyTest** (Functional)](src/test/java/com/tidymail/RecommendationDashboardJourneyTest.java) | **Path:** DashboardController → RecommendationService/RecommendationProcessingService → DashboardRepository/Preview/Job | **Overlap:** RecommendationPersistenceTest engine cache; LabelingPipelineJourneyTest acquisition | Keep: current HTTP/UI query integration; local processing never authorizes model or cleanup work. |
| `allIndexedMailIncludesUnassessedButExcludesSpamAndTrashWithoutExpandingCleanup` | Indexed browsing hides unassessed mail or expands cleanup eligibility to Sent/draft/unknown/excluded messages.  | Suite overlap; distinct boundary stated here | Keep |
| `browsingIsReadOnlyAndAutomaticProcessingSupportsPagedFilteredReviewAndExactBytes` | Read-only browsing mutates mail or local processing loses pagination/filter/known-byte alignment.  | Suite overlap; distinct boundary stated here | Keep |
| `evidenceChangesImmediatelyRemoveFilteredRecommendationsAndInvalidateAReviewedPreview` | Changed evidence remains in filtered selection or a reviewed cleanup preview stays valid.  | Suite overlap; distinct boundary stated here | Keep |
| `correctionsArePerMessageAccountScopedCsrfProtectedAndRejectLostUpdates` | Per-message correction loses ownership/CSRF/optimistic revision or changes another message.  | Suite overlap; distinct boundary stated here | Keep |
| `pauseResumeAndExpiredWorkerLeaseRetainDurableProgress` | Pause/restart/expired processing lease loses progress or account failure cannot be cleared and retried.  | Suite overlap; distinct boundary stated here | Keep |
| [**RecommendationPersistenceTest** (Functional)](src/test/java/com/tidymail/RecommendationPersistenceTest.java) | **Path:** RecommendationService/Sql + processing queues/triggers | **Overlap:** MessageRecommendationTest pure decisions; RecommendationDashboardJourneyTest HTTP filter integration | Keep: persisted invalidation, lock/deletion/backfill and evidence provenance differ from pure decisions. |
| `localCompositionPreservesSentAndExistingCorrectionsWithoutAdoptingLegacyModelLabels` | Local composition adopts legacy labels as disposal authority, changes Sent storage or rewrites identical saved rows.  | Suite overlap; distinct boundary stated here | Keep |
| `metadataReadStateAndEvidenceChangesHideCachedActionsButSyncTimeDoesNot` | Read/size/MIME/facts changes leave cached actions current; sync timestamp alone creates false invalidation.  | Suite overlap; distinct boundary stated here | Keep |
| `actionAndImportanceCorrectionsRemainIndependentAcrossPolicyChangesAndClearing` | Importance/action corrections overwrite each other or policy/clear/replay loses exact correction.  | Suite overlap; distinct boundary stated here | Keep |
| `ownershipBoundsExclusionsAndDeletionAreEnforced` | Foreign/excluded/deleted messages retain usable actions or bounds/cascade cleanup fail.  | Suite overlap; distinct boundary stated here | Keep |
| `relationshipAndAliasRepairsInvalidateOnlyRelevantContextWithoutSchedulingPaidWork` | History/alias/account repairs fail to invalidate relevant context or schedule paid work.  | Suite overlap; distinct boundary stated here | Keep |
| `boundedBackfillMakesProgressBesideDirtyWorkAndDefersLockedMessages` | Dirty work starves history, locked rows lose work or a no-op batch rewrites completed results.  | Suite overlap; distinct boundary stated here | Keep |
| `historicalBuildNeedsNoDirtyQueueAndCoverageChangesAreReconsidered` | Retained mail without dirty queue never builds or completeness changes are ignored.  | Suite overlap; distinct boundary stated here | Keep |
| `busyAccountDefersWithABoundedWaitAndRetainsAllWork` | Account lock blocks worker indefinitely or causes pending work loss.  | Suite overlap; distinct boundary stated here | Keep |
| `semanticPreparationReadsOnlyOwnedSavedEvidenceAndCannotEnableModelWork` | Semantic preparation includes foreign/Sent/Trash/private-label evidence or creates paid/local work.  | Suite overlap; distinct boundary stated here | Keep |
| `changedCompositionVersionRestartsLocalRecompositionWithoutChangingEvidenceOrPayingAgain` | Engine-version change leaves old cache usable or rebills instead of locally recomposing.  | Suite overlap; distinct boundary stated here | Keep |
| `clockBoundaryInvalidatesAndRecomposesWithoutNewMailOrAnotherModelCall` | Clock-only attention boundary never invalidates/recomposes without new mail.  | Suite overlap; distinct boundary stated here | Keep |
| `realSavedCorrespondenceAndCalendarObservationsPreserveUnclearMailWithoutModelWork` | Observed exact Sent/calendar retention does not preserve unclear mail or leaks across accounts.  | Suite overlap; distinct boundary stated here | Keep |
| `freshSupportedLegacyProtectionCanKeepButCannotAuthorizeTrashOrSurviveStaleEvidence` | Supported old protections authorize Trash or survive stale classification evidence.  | Suite overlap; distinct boundary stated here | Keep |
| [**RuleDestinationProviderTest** (Functional)](src/test/java/com/tidymail/RuleDestinationProviderTest.java) | **Path:** GmailRuleProvider / OutlookRuleProvider compile/prepare/translate | **Overlap:** AutomationRulesJourneyTest persisted rule lifecycle | Keep: low-level native payload and translation branches are masked by journey transport mocks. |
| `outlookSenderUpdateDoesNotMutateReadBackOrAddUnusedPredicates` | Outlook behavior comparison mutates provider snapshot; sender update adds unused predicates or fails to clear previous mode.  | Suite overlap; distinct boundary stated here | Keep |
| `inboxFilterCheckKeepsSenderScopeAndAllConditionsAndRejectsPartialTranslation` | Gmail interaction search drops original conditions, broadens sender scope or accepts untranslatable/injected query.  | Suite overlap; distinct boundary stated here | Keep |
| `gmailCompilesWithoutMutationThenCreatesOrReusesLabelAndReadsMessageCount` | Compile mutates provider, destination is created twice, or native criteria/message count/import compatibility are wrong.  | Suite overlap; distinct boundary stated here | Keep |
| `existingRuleDefinitionsStillLoadWithoutDestinationFields` | Existing saved rule JSON without destination fields no longer loads.  | Suite overlap; distinct boundary stated here | Keep |
| [**SecurityConfigIntegrationTest** (Functional)](src/test/java/com/tidymail/SecurityConfigIntegrationTest.java) | **Path:** Spring Security filter chain → login/logout/client registration | **Overlap:** SecurityConfigTest consent parameters; PersonalOutlookJourneyTest actual auth redirect | Keep: configured filter/render/session behavior versus pure request customization. |
| `loginPageLoadsUnauthenticated` | Unauthenticated login cannot render its sign-in page.  | Suite overlap; distinct boundary stated here | Keep |
| `dashboardRequiresAuthentication` | Dashboard becomes accessible without authentication.  | Suite overlap; distinct boundary stated here | Keep |
| `rootUnauthenticatedRedirectsToLogin` | Root entry no longer redirects an unauthenticated visitor to sign-in.  | Suite overlap; distinct boundary stated here | Keep |
| `logoutInvalidatesSessionAndRedirects` | Logout does not invalidate session or returns to wrong page.  | Suite overlap; distinct boundary stated here | Keep |
| `unconfiguredProvidersRenderWarningNotButtons` | Unconfigured OAuth providers appear as usable sign-in buttons.  | Suite overlap; distinct boundary stated here | Keep |
| `configuredMicrosoftClientRegistrationHasCorrectScopesAndIssuer` | Configured Microsoft issuer/scopes or Google read-only Calendar/Gmail scope contract drifts.  | Suite overlap; distinct boundary stated here | Keep |
| [**SyncFailureDiagnosticTest** (Functional)](src/test/java/com/tidymail/job/SyncFailureDiagnosticTest.java) | **Path:** FailureDiagnostics ← IncrementalSyncJob/cleanup logs | **Overlap:** JevClassifierTest sanitized user error (different producer) | Keep: nested provider status/location is useful only when secret payload/message stays excluded. |
| `reportsStatusAndLocationsWithoutProviderPayloads` | Failure logging loses status/location or emits private provider/token messages.  | Suite overlap; distinct boundary stated here | Keep |
| [**GmailBackfillRecoveryJourneyTest** (Functional)](src/test/java/com/tidymail/journey/GmailBackfillRecoveryJourneyTest.java) | **Path:** Real Gmail SDK loopback → IncrementalSyncJob → sync services/Postgres/dashboard | **Overlap:** GmailSynchronizationContractTest mocks; MailboxFreshnessJourneyTest mock provider | Keep: HTTP callback retry, scheduled queue/heartbeat and persisted cursor integration. |
| `scheduledSyncRecoversAfter200AndRetriesOnlyThrottledMetadata` | Import stops after first 200, loses resumed Inbox→All order or retries successful metadata with throttled items.  | Suite overlap; distinct boundary stated here | Keep |
| `permanentForbiddenMetadataFailsWithoutRetryingOrAdvancingState` | Permanent forbidden metadata retries or advances state without a committed page.  | Suite overlap; distinct boundary stated here | Keep |
| `historyAndInterruptedRescanHideMovesRestoreRowsAndDeleteOnlyConfirmedMissing` | Spam/Trash moves/restores or interrupted rescan lose IDs, delete unverified missing mail or sweep after read failure.  | Suite overlap; distinct boundary stated here | Keep |
| `historyDeletionWinsOverOlderMetadataFromAnInFlightPage` | Old in-flight backfill resurrects a message already deleted by history.  | Suite overlap; distinct boundary stated here | Keep |
| [**MailboxActivityJourneyTest** (Functional)](src/test/java/com/tidymail/journey/MailboxActivityJourneyTest.java) | **Path:** ActivityController/ActivityDashboardController/templates → state/progress and job history | **Overlap:** DashboardJobJourneyTest populated cleanup history; browser activity-dashboard | Keep: server-visible active mailbox/progress/CSRF and estimate branches. |
| `cleanupDashboardIsAStandalonePageWithAccountScopedEmptyHistory` | Standalone Activity embeds retired modal/workspace or mishandles empty history/invalid page.  | Suite overlap; distinct boundary stated here | Keep |
| `activityShowsOnlyTheActiveMailboxDespiteLegacyAccountParameters` | Legacy account parameter reads foreign sync state or exposes raw cursor.  | Suite overlap; distinct boundary stated here | Keep |
| `largeGmailIndexUsesChangingScopedEstimateAndNeverTheOldProfileTotal` | Large-index progress trusts obsolete profile total or shows 100% before scoped listing completes.  | Suite overlap; distinct boundary stated here | Keep |
| `failedFirstImportShowsRetryAndKeepsPollingUntilRecovery` | Failed first import has no usable retry/polling recovery state.  | Suite overlap; distinct boundary stated here | Keep |
| `retrySyncRequiresCsrfAndReturnsToActivity` | Retry sync accepts no-CSRF request or fails to return to Activity.  | Suite overlap; distinct boundary stated here | Keep |
| `pollingStopsAtServerReportedCompletionWithoutExposingCursor` | Activity polling never stops on completed state or exposes server-only cursor.  | Suite overlap; distinct boundary stated here | Keep |
| [**MailboxFreshnessJourneyTest** (Functional)](src/test/java/com/tidymail/journey/MailboxFreshnessJourneyTest.java) | **Path:** MailboxBootstrap/Backfill/Freshness/RescanService → SyncCommitService | **Overlap:** GmailBackfillRecoveryJourneyTest wire; MailboxSyncPersistenceTest transaction isolation | Keep: orchestration/fallback/visible metadata is distinct from direct commit contract. |
| `bulkLabelHistoryNeedsReadsOnlyForNewOrInsufficientlyCachedMessages` | 1201 cached label changes refetch metadata or lose preserved headers/categories/deletion/cursor.  | Suite overlap; distinct boundary stated here | Keep |
| `completeLabelWindowPreservesAcknowledgedCleanupAndSkipsNewerCachedRevisions` | Ordered label window undoes acknowledged cleanup or lower history overwrites newer revision.  | Suite overlap; distinct boundary stated here | Keep |
| `cleanupAfterHistoryObservationRejectsAndRollsBackTheLabelCommitWithoutAdvancingCursor` | Cleanup racing history commits stale base labels or advances cursor after rollback.  | Suite overlap; distinct boundary stated here | Keep |
| `interruptedLabelWindowReplaysCommittedChunksWithoutSkippingTheSuffixOrReadingMetadata` | Interrupted 600-message label window skips uncommitted suffix or rereads already cached metadata.  | Suite overlap; distinct boundary stated here | Keep |
| `newGmailMessageAppearsWhileHistoricalBackfillIsStillRunning` | New message cannot appear while historical backfill is running.  | Suite overlap; distinct boundary stated here | Keep |
| `newUserSeesFirstBatchBeforeFullMailboxBackfillCompletes` | First 200-message batch is unusable until full import or configured bounded import overfetches unique rows.  | Suite overlap; distinct boundary stated here | Keep |
| `expiredGmailHistoryTriggersBoundedRepairAndProgressiveRescan` | Expired history replaces cursor before repair or incomplete rescan sweeps newer/fresh rows.  | Suite overlap; distinct boundary stated here | Keep |
| `emptyMailboxRemainsEligibleForFreshness` | Empty mailbox never reaches future freshness processing.  | Suite overlap; distinct boundary stated here | Keep |
| `gmailMetadataFlowsFromProviderThroughStorageToVisibleWorkspace` | Provider labels/size/attachment provenance do not reach stored and visible message details.  | Suite overlap; distinct boundary stated here | Keep |
| [**SecurityConfigTest** (Functional)](src/test/java/com/tidymail/security/SecurityConfigTest.java) | **Path:** SecurityConfig.customizeAuthorizationRequest | **Overlap:** OAuthLoginSuccessListenerTest token persistence (later boundary) | Keep: provider-specific consent parameters at actual authorization builder. |
| `googleAuthorizationRequestsRequireOfflineConsent` | Google authorization omits offline access/consent and cannot acquire refresh credentials.  | Suite overlap; distinct boundary stated here | Keep |
| `nonGoogleAuthorizationRequestsAreUnchanged` | Google-only consent parameters alter a non-Google authorization request.  | Suite overlap; distinct boundary stated here | Keep |
| [**OAuthLoginSuccessListenerTest** (Functional)](src/test/java/com/tidymail/security/oauth2/OAuthLoginSuccessListenerTest.java) | **Path:** OAuthLoginSuccessListener / AccountLoginSuccessHandler → save/session/sync | **Overlap:** OAuthAuthorizedClientRefreshTest persisted clients | Keep: callback ordering and first-vs-returning bootstrap routing precede stored-client reuse. |
| `persistsTokensFromSuccessfulCallbackWithoutLoadingStoredClientFirst` | First OAuth callback loads nonexistent stored client before saving token or queues wrong lane.  | Suite overlap; distinct boundary stated here | Keep |
| `returningGoogleAccountQueuesFreshnessInsteadOfBootstrap` | Returning Google login restarts bootstrap instead of freshness.  | Suite overlap; distinct boundary stated here | Keep |
| `pendingBootstrapRedirectsToActivityWithoutWaitingForWorker` | Login waits for bootstrap instead of redirecting immediately to Activity.  | Suite overlap; distinct boundary stated here | Keep |
| [**CalendarEvidenceParserTest** (Functional)](src/test/java/com/tidymail/service/CalendarEvidenceParserTest.java) | **Path:** CalendarEvidenceParser ← GmailEvidenceReader | **Overlap:** CalendarReconcilerTest consumes parsed facts | Keep: bounded private-safe parser projection differs from multi-message recurrence resolution. |
| `localFactsRetainRecurrenceInstanceAndCancellationWithoutClaimingExpired` | Calendar parser drops cancellation/instance/sequence/timezone or exposes raw UID.  | Suite overlap; distinct boundary stated here | Keep |
| `allDayFloatingAndOldDatesRemainUnreconciled` | Parser claims an old/all-day/floating event is globally expired before reconciliation.  | Suite overlap; distinct boundary stated here | Keep |
| `malformedOrOversizedContentCannotBecomeKnownEvent` | Malformed/oversized/duplicate-start calendar becomes known event.  | Suite overlap; distinct boundary stated here | Keep |
| `repeatedRecurrencePropertiesKeepTheirOwnTimezonesAndInvalidTypesStayExplicit` | RDATE/EXDATE lose per-property timezone or invalid/ranged types are silently accepted.  | Suite overlap; distinct boundary stated here | Keep |
| [**CalendarReconcilerTest** (Functional)](src/test/java/com/tidymail/service/CalendarReconcilerTest.java) | **Path:** CalendarReconciler ← MessageEvidenceService cached related events | **Overlap:** CalendarResponseJourneyTest provider RSVP, not recurrence reduction | Keep: distinct recurrence/date/DST and unsupported-form recovery; no global history completeness claim. |
| `sequenceWinsOverReceiptOrderAndReschedulePreventsPastConclusion` | Receipt order beats event sequence/reschedule and falsely marks current event ended.  | Suite overlap; distinct boundary stated here | Keep |
| `finiteRecurrenceUsesDetachedExceptionAndCancellation` | Detached move/cancel or missing master produces wrong last occurrence/series status.  | Suite overlap; distinct boundary stated here | Keep |
| `conflictingVersionsIncompleteDiscoveryAndUnsupportedRecurrenceRemainUnknown` | Conflicting/incomplete/unsupported/over-cap recurrence is treated as known.  | Suite overlap; distinct boundary stated here | Keep |
| `timezoneDstAndUntilAreBoundedWhileFloatingAllDayAndAmbiguousTimesStayUnknown` | DST/UNTIL or floating/ambiguous local start invents wrong end instant.  | Suite overlap; distinct boundary stated here | Keep |
| `allDayEventsKeepExclusiveDateEndsWithoutInventingTimezoneOrPastClaims` | All-day events invent timezone/past claim or ignore exclusive date ends/invalid duration.  | Suite overlap; distinct boundary stated here | Keep |
| `nominalDaysAndExactHoursDifferAcrossDstAndApplyPerInstance` | Nominal P1D and exact PT24H become identical across DST or lose per-instance duration.  | Suite overlap; distinct boundary stated here | Keep |
| `monthlyYearlyAndWeekdayRecurrencesAreBoundedByTheirCompleteSet` | Monthly/yearly/leap/weekday/BYSETPOS recurrence omits valid set members or exceeds horizon.  | Suite overlap; distinct boundary stated here | Keep |
| `additionalAndExcludedDatesRetainTimezoneAndDateInstanceIdentity` | Additional/excluded dates lose timezone identity/deduplication or detached date-only update.  | Suite overlap; distinct boundary stated here | Keep |
| `unsupportedTimezoneRangedAndPeriodFormsNeverProducePastConclusions` | Custom timezone/ranged update/period form yields a definite end despite unsupported semantics.  | Suite overlap; distinct boundary stated here | Keep |
| [**CalendarResponseFailureTest** (Functional)](src/test/java/com/tidymail/service/CalendarResponseFailureTest.java) | **Path:** CalendarResponseService.inspect with failed save transaction | **Overlap:** CalendarResponseJourneyTest happy provider persistence | Keep: local storage failure must release account admission; provider failure recovery differs. |
| `saveFailureIsLocalAndReleasesAccountForRetry` | Local calendar save is mislabeled as provider error, leaks detail or permanently blocks retry; coding error is swallowed.  | Suite overlap; distinct boundary stated here | Keep |
| [**HeaderSemanticAdmissionTest** (Functional)](src/test/java/com/tidymail/service/HeaderSemanticAdmissionTest.java) | **Path:** HeaderSemanticAdmission.inspect → RecommendationService.load | **Overlap:** RecommendationPersistenceTest one forward; experimental contract overload | Keep: current shared prefix/reply predicate has positive/negative boundary coverage. |
| `forwardedAccessAndShippingContentCannotBecomeHeaderOnlyResolvedJudgments` | Reply/forward wrappers or reply headers qualify as direct-header context (re/fw/fwd/spacing plus reply flag).  | As above; different layer/transition | Consolidate: exercise current string/reply API directly; remove obsolete experiment Evidence fixture, retain both outcomes. |
| `directReceiptsAndAccessNoticesRemainCandidatesWithoutAssertingTheirCategory` | Direct headers containing ordinary words such as Forward are incorrectly excluded.  | As above; different layer/transition | Consolidate: exercise current string/reply API directly; remove obsolete experiment Evidence fixture, retain both outcomes. |
| [**JevClassifierTest** (Functional)](src/test/java/com/tidymail/service/JevClassifierTest.java) | **Path:** JevClassifier → mock RestClient / static typed parse | **Overlap:** DashboardClassificationTest queue; RecommendationSemanticContractTest independent-label protocol | Keep: current classification transport, privacy routing, billing and pinned pricing. |
| `requestPinsPrivacyRouteAndSendsOnlyBoundedMetadata` | Model request leaks unsupported metadata or changes privacy/provider/no-fallback route.  | Suite overlap; distinct boundary stated here | Keep |
| `uncertaintyIsExplicitAndCategoriesAreNotInvented` | Weak category result invents certainty or arbitrary category name is accepted.  | Suite overlap; distinct boundary stated here | Keep |
| `highReportedConfidenceCannotOverrideWeakProbability` | Self-reported confidence overrides weaker distribution probability.  | Suite overlap; distinct boundary stated here | Keep |
| `malformedMissingAndWrongProviderAnswersFailClosed` | Foreign-provider/missing/out-of-range response is accepted.  | Suite overlap; distinct boundary stated here | Keep |
| `providerErrorsDoNotExposeRemoteContentOrKeysAndDoNotFallback` | Provider error leaks content/key or triggers fallback.  | Suite overlap; distinct boundary stated here | Keep |
| `noKeyDoesNotMakeAnyRequest` | Missing key still issues a transport request.  | Suite overlap; distinct boundary stated here | Keep |
| `rejectedDecisionsRetainExactReportedCostAndMissingBillingNeverBecomesZero` | Invalid response drops exact reported cost or missing billing is silently zeroed.  | Suite overlap; distinct boundary stated here | Keep |
| `largerTransportBatchKeepsAllThreeDimensionsAndPrivacyRoute` | Ten-item batch loses per-message kind/category/stream association.  | Suite overlap; distinct boundary stated here | Keep |
| `labelingPricingUsesPinnedPublicMetadataAndRejectsPriceOrOutputChanges` | Pricing check sends key or accepts increased input/nonzero output prices.  | Suite overlap; distinct boundary stated here | Keep |
| [**MessageRecommendationTest** (Functional)](src/test/java/com/tidymail/service/MessageRecommendationTest.java) | **Path:** MessageRecommendation.evaluate ← RecommendationService composition | **Overlap:** RecommendationAttentionTest / PositiveRetentionTest; pipeline integration | Keep: pure branch precedence is required by current composition; no provider accuracy claim. |
| `readFinancialMailIsDisposableWhileReceiptsAndOtherRecordsAreProtected` | Read financial disposal incorrectly deletes receipt/personal/security records or returns archived mail to Inbox.  | Suite overlap; distinct boundary stated here | Keep |
| `protectedContentWinsOverBulkAndStatementPolicies` | Bulk/statement disposal overrides protected facets or unresolved/conflicting critical signals.  | Suite overlap; distinct boundary stated here | Keep |
| `contextStalenessAndConflictsCannotBecomeResolvedDisposal` | Stale/conflicting/unresolved qualification or forwarding context becomes disposal permission.  | Suite overlap; distinct boundary stated here | Keep |
| `largeSizeAddsAFacetWithoutChangingTheRetentionDecision` | Large-size facet changes retention/importance or appears at unknown/exact threshold.  | As above; different layer/transition | Consolidate: 36 purpose×mailbox pairs → 3 outcomes (bulk Inbox, protected Inbox, Sent stored); same size branch precedes decisions; keep null/exact-byte boundary. |
| `sentOnlyMailIsPreservedButMixedSentInboxMailStillNeedsJudgment` | Sent-only/excluded/unknown/mixed Sent+Inbox mail is treated as ordinary received bulk.  | Suite overlap; distinct boundary stated here | Keep |
| `explicitManualDecisionsWinWithoutMakingTheModelMoreCertain` | Explicit manual action/importance loses to stale model judgment.  | Suite overlap; distinct boundary stated here | Keep |
| `calendarAndGeneralUpdatesNeedASeparatePolicyAndEvidence` | Unqualified notification/unknown mail gains disposal or calendar/importance protection is lost.  | Suite overlap; distinct boundary stated here | Keep |
| `inputCannotForgeStorageFactsAndPolicyCannotDisposeOfProtectedPurposes` | Semantic input forges derived size facts or policy permits disposing protected purposes.  | Suite overlap; distinct boundary stated here | Keep |
| [**PositiveRetentionTest** (Functional)](src/test/java/com/tidymail/service/PositiveRetentionTest.java) | **Path:** MessageRecommendation.evaluate retention evidence | **Overlap:** RecommendationPersistenceTest SQL evidence adaptation | Keep: current positive-retention precedence on incomplete/stale semantics. |
| `oneObservedSentExchangeIsEnoughToPreserveAnUnclearForwardWithoutInventingPersonalOrImportance` | One observed exchange fails retention or invents purpose/importance; zero and high count boundaries differ.  | As above; different layer/transition | Consolidate: counts 1/4/5/3000 → 1/3000 plus existing zero control; implementation only checks >0, no 4/5 threshold. |
| `observedCalendarPartPreservesTheMessageWithoutKnowingDatesRecurrenceOrSemanticContext` | Calendar presence requires invented temporal/semantic certainty or loses overlapping archived Contact/size facets.  | Suite overlap; distinct boundary stated here | Keep |
| `currentLocalRetentionEvidenceSurvivesStaleSemanticGuessesButRespectsManualDecisionsAndExclusions` | Stale semantics defeat current retention, or retention defeats manual choice/exclusion.  | Suite overlap; distinct boundary stated here | Keep |
| `supportedReceiptAndSecurityPreservationDoNotNeedTheGenericContextAnswer` | Supported record purpose requires unrelated generic-context certainty or ambiguous bulk earns disposal.  | Suite overlap; distinct boundary stated here | Keep |
| [**RecommendationAttentionTest** (Functional)](src/test/java/com/tidymail/service/RecommendationAttentionTest.java) | **Path:** MessageRecommendation.attention/nextEvaluation/evaluate | **Overlap:** MessageRecommendationTest disposal baseline; PersistenceTest clock-only invalidation | Keep: age/read boundaries and scheduling differ from general purpose/facet branches. |
| `onlyRecentUnreadConsequentialMailIsHeldWhileReadOrOldBillsAreEligibleForTrash` | Recent unread financial mail loses hold or read/over-60-day bills cannot become eligible.  | Suite overlap; distinct boundary stated here | Keep |
| `olderOrReadDoesNotEraseIndependentRecordAndPersonalProtections` | Age/read status overrides permanent record/personal/facet protection.  | Suite overlap; distinct boundary stated here | Keep |
| `missingFutureAndUnknownReadEvidenceCannotManufactureAnOldBill` | Missing/future date or unknown read status invents an old bill.  | Suite overlap; distinct boundary stated here | Keep |
| `nextEvaluationFollowsAnExactElapsedDayBoundaryWithoutChangingImportance` | Next evaluation uses calendar-day rounding, misses exact elapsed boundary or changes importance.  | Suite overlap; distinct boundary stated here | Keep |
| `explicitRecordPolicyAndManualChoiceStillWinWithoutAutomaticallyArchivingAnything` | Explicit record policy/manual choice loses or archived mail returns to Inbox.  | Suite overlap; distinct boundary stated here | Keep |
| **RecommendationDecisionQualificationTest** (Functional) — deleted | **Path:** RecommendationDecisionQualification.diagnose → tools/verification/ReplayRecommendationDecisions only | **Overlap:** Current LabelingPipeline/SemanticLabelComposition + PositiveRetention/Attention tests | Remove: whole suite covers frozen offline experiment; no production caller; preserve replay tool/history. |
| `directReceiptCanBeKeptWithoutGlobalContextCertaintyOrResolvedUnrelatedQuestions` | No current user path; frozen replay keeps direct receipt despite unrelated unknown questions.  | As above; current pipeline covers app behavior | Remove: frozen offline diagnostic policy; current pipeline calls parse → SemanticLabelComposition, never diagnose. Archived replay sources stay unchanged. |
| `genericContextUncertaintyDoesNotBlockDirectBulkButPermanentProtectionUncertaintyDoes` | No current user path; frozen replay treats generic context and permanent protection uncertainty differently.  | As above; current pipeline covers app behavior | Remove: frozen offline diagnostic policy; current pipeline calls parse → SemanticLabelComposition, never diagnose. Archived replay sources stay unchanged. |
| `recentUnreadConsequentialUncertaintyIsStillHeldAndPositiveFinancialEvidenceKeeps` | No current user path; frozen replay holds uncertain unread financial mail.  | As above; current pipeline covers app behavior | Remove: frozen offline diagnostic policy; current pipeline calls parse → SemanticLabelComposition, never diagnose. Archived replay sources stay unchanged. |
| `ambiguousForwardCannotEarnTrashButSupportedHumanCorrespondenceCanKeepIt` | No current user path; frozen replay distinguishes ambiguous forward from supported human retention.  | As above; current pipeline covers app behavior | Remove: frozen offline diagnostic policy; current pipeline calls parse → SemanticLabelComposition, never diagnose. Archived replay sources stay unchanged. |
| [**RecommendationSemanticContractTest** (Functional)](src/test/java/com/tidymail/service/RecommendationSemanticContractTest.java) | **Path:** RecommendationSemanticContract.request/parse/evidenceHash → LabelingPipeline | **Overlap:** JevClassifierTest uses different 3-question contract; LabelingPipelineJourneyTest integration | Keep: current 12-question independent-label wire/provenance, not frozen diagnostic disposal. |
| `matchedArmsDifferOnlyBySnippetAndDoNotDiscloseLocalProvenanceOrCleanupPreferences` | Matched header/snippet requests differ in other fields or disclose local provenance/cleanup policy.  | Suite overlap; distinct boundary stated here | Keep |
| `utf8LimitsAndExplicitSnippetStatesBoundEveryInputWithoutFullBodyFallback` | UTF-8 input bounds/snippet states leak unbounded text, private labels or duplicate message association.  | Suite overlap; distinct boundary stated here | Keep |
| `responsePreservesAllDistributionsCostsAndMessageAssociationsWithoutAdoptingAnything` | Typed response loses probability distributions/exact cost/message identity.  | Suite overlap; distinct boundary stated here | Keep |
| `malformedForeignMissingAndOverconfidentPrimitiveShapesFailClosed` | Malformed/foreign/missing/overconfident primitive response is accepted (nine independent mutations).  | Suite overlap; distinct boundary stated here | Keep |
| `readStatementsAndBillsAreDisposableWhileReceiptsRemainProtected` | No current user path; frozen diagnostic financial-disposal policy differs from label persistence.  | As above; current pipeline covers app behavior | Remove: frozen offline diagnostic policy; current pipeline calls parse → SemanticLabelComposition, never diagnose. Archived replay sources stay unchanged. |
| `everyUnresolvedProtectedCheckBlocksBulkTrashWhilePositiveChecksProtectMixedContent` | No current user path; frozen diagnostic protection matrix is mistaken for production qualification.  | As above; current pipeline covers app behavior | Remove: frozen offline diagnostic policy; current pipeline calls parse → SemanticLabelComposition, never diagnose. Archived replay sources stay unchanged. |
| `forwardedHeaderContentCannotSelfDeclareEnoughContextAndContradictoryAnswersAreDeferred` | No current user path; frozen diagnostic forwarding policy is mistaken for production composition.  | As above; current pipeline covers app behavior | Remove: frozen offline diagnostic policy; current pipeline calls parse → SemanticLabelComposition, never diagnose. Archived replay sources stay unchanged. |
| `revisionChangesInvalidateProvenanceWithoutInventingNewPaidEvidence` | Source revision/hash fails invalidation or text beyond actual submitted bound triggers fresh paid evidence.  | Suite overlap; distinct boundary stated here | Consolidate: retain submitted-text/hash boundaries; remove stale-source assertion against frozen diagnose API. |
| [**ReviewedPatternPolicyTest** (Functional)](src/test/java/com/tidymail/service/ReviewedPatternPolicyTest.java) | **Path:** ReviewedPatternService.assess ← current preview/reuse | **Overlap:** Phase2IdentificationJourneyTest persisted preview/apply | Keep: independent admission reasons are not model-accuracy examples. |
| `categoryDoesNotDetermineImportanceAndSensitiveWordingDoesNotProveSecurity` | Sensitive wording is mistaken for reusable security category or category implies importance.  | Suite overlap; distinct boundary stated here | Keep |
| `independentSecurityHumanReplyAndCalendarEvidencePreventInheritance` | Known security/person/relay/reply/calendar evidence is allowed to inherit a reviewed pattern.  | Suite overlap; distinct boundary stated here | Keep |
| [**SyntheticMailboxPiiGuardTest** (Functional)](src/test/java/com/tidymail/support/SyntheticMailboxPiiGuardTest.java) | **Path:** SyntheticMailboxGenerator.seed → isolated SQL inspection | **Overlap:** Scale workloads consume generator; no live data scanned | Keep: validates fixture fidelity/privacy used by retained scale claims; is not a repository PII audit. |
| `syntheticMailboxContainsRealisticVolumeWithoutRealPii` | 10k fixtures contain non-synthetic identities or incoherent threads/Sent/bulk distribution, invalidating scale evidence.  | Suite overlap; distinct boundary stated here | Keep |
| **Browser scripts — existing sequential journeys, outside Maven counts** | Actual dashboard.js/inbox-rules.js/rules templates; six scripts, no mobile checks. | Java asserts SQL/provider outcomes; browser asserts DOM/event/focus behavior. | Keep: intercepted responses or explicit marked synthetic fixture only; no retained app. |
| `dashboard-selection.cjs` | 20/10/50 paging; exact additive address/message/unread selection; parent change/clear/filter/lost-response guards; rule transfer/draft; import/retry/details recovery; links, overlays, focus/scroll and desktop bounds. | DashboardJourney exact predicate; rule-review sender transfer; ui-polish rendering. | Keep: sole delayed-selection/details event-race owner; full interception, 42-address group. Remove duplicate arrow assertions already inside checkDropdowns (same element, same state). |
| `ui-polish.cjs` | Server-rendered 1440/1280 density, filter-chip removal, exact review/back, draft save, draft-delete cancel/confirm, navigation and 200% desktop zoom. | selection script fake-rendered HTML; Java deletion snapshot. | Keep: actual rendered template/DOM wiring; assertions protect visible rows/overflow, not one fixed pane position. |
| `activity-dashboard.cjs` | Standalone Activity/sidebar state, four cards, 14 chart columns, Sync round trip, no modal/overflow/browser errors. | MailboxActivityJourney; JobJourney populated pages. | Keep: real browser navigation/chart wiring; server tests cannot establish it. |
| `rule-filing.cjs` | Two subject/exception phrases; duplicate destination selection; permission error preserves edits; deferred new label; filing review cancel/confirm; Activity destination; both menus; 1280/1440 layout. | ManualFilingJourney and AutomationRulesJourney persistence. | Keep: DOM form serialization/recovery and destination controls; one synthetic filing confirmation only. |
| `rule-edit.cjs` | Standalone save/pause/edit; existing destination/skip-Inbox retention after 403; domain-only versus mixed OR/subject-exception preview and activation. | AutomationRulesJourney paused-label fix; rule-filing inline editor. | Keep: standalone edit form path differs from inline setup; native rules are synthetic. |
| `rule-review.cjs` | Setup/review/result separation; linked 3/10 samples; edit preservation; optional historical filing cancel/confirm; add-sender-only filing; Inbox/All counts; 42-address overlap review; desktop controls. | AutomationRulesJourney data; other browser scripts setup/editor. | Keep: review/result transitions and exact existing-mail handoff; optional Dark Reader check is pre-existing, not run without local script. |
| **Shared test helpers — no executable cases** | These files support existing assertions; do not count lifecycle hooks or seed rows as tests. | Used across retained Java suites. | Keep: six Java helper files plus Spring registration; no new harness introduced. |
| `support/NativeTestPostgres.java` | Wrong/live DB or privileged role accepted before cleanup/migrations. | All PostgreSQL journeys; generator PII guard. | Keep: Pins host/role/database/server identity and checks denial of live DB access. |
| `support/PostgresTestContextCustomizerFactory.java` | Spring/Flyway inherits exported live datasource or scheduling. | All PostgreSQL journeys; generator PII guard. | Keep: Test-only registration pins real migrations, schema validation and bounded pool. |
| `support/PostgresDatabaseCleanupTestExecutionListener.java` | One test inherits another account/ledger or truncates unverified DB. | All PostgreSQL journeys; generator PII guard. | Keep: Verifies identity before account CASCADE cleanup and reinitializes budget singleton. |
| `support/PostgresJourneyTest.java` | Journeys accidentally use actual mail providers/OAuth or inconsistent account session. | All PostgreSQL journeys; generator PII guard. | Keep: Shared test context/provider mocks and request auth; abstract, zero tests. |
| `support/SyntheticMailboxGenerator.java` | Scale seed uses one giant write or unrealistic identities/threads/Sent mix. | All PostgreSQL journeys; generator PII guard. | Keep: Bounded 1000-row batches with triggers and deterministic distribution; functional PII guard asserts fidelity. |
| `support/UserAccountFixtures.java` | Fixtures create real credentials or nonowned/unstable account identities. | All PostgreSQL journeys; generator PII guard. | Keep: Test-only synthetic token bytes and stable provider/owner key; no transports. |
| `src/test/resources/META-INF/spring.factories` | Isolated datasource/cleanup customizers do not load. | NativeTestPostgres and cleanup listener. | Keep: existing Spring registration, no executable cases. |
| **Historical Python parser checks — six offline cases, outside Maven counts** | `parse_calendar_probe.facts` used only by archived capability probe, not Java production Calendar code. | Java CalendarEvidenceParser/Reconciler tests are different runtime. | Keep: frozen probe evidence reproducibility; no claim about current product decision accuracy. |
| `test_calendar_probe.py#test_utc_event_end_is_past` | UTC end does not yield past-with-reconciliation-needed. | Archived probe parser only; not Maven. | Keep (historical): Timestamp comparison plus explicit incomplete-series warning. |
| `test_calendar_probe.py#test_future_event_not_past` | Future UTC end is called past. | Archived probe parser only; not Maven. | Keep (historical): Opposite temporal branch. |
| `test_calendar_probe.py#test_recurring_series_not_expired_from_first_end` | Single past occurrence is mistaken for expired series. | Archived probe parser only; not Maven. | Keep (historical): Recurrence overrides naive end comparison. |
| `test_calendar_probe.py#test_all_day_needs_timezone_for_boundary` | All-day date invents UTC/past boundary. | Archived probe parser only; not Maven. | Keep (historical): Date-only path and inferred exclusive end. |
| `test_calendar_probe.py#test_floating_time_not_assumed_utc` | Floating datetime invents timezone/past conclusion. | Archived probe parser only; not Maven. | Keep (historical): Timezone-free datetime differs from date-only event. |
| `test_calendar_probe.py#test_invalid_content_does_not_become_event` | Non-ICS text is accepted as parsed event. | Archived probe parser only; not Maven. | Keep (historical): Parser failure path. |
| **Archived experiment tools — explicit only; not release cases and not replayed** | No current automated user-flow protection; entrypoint/guard/dependency review only. Private historical inputs/results are outside this audit. | Current synthetic Java covers released behavior. | Keep: original reproducibility/one-shot/schema/liability boundaries; no new flags or moved tests. |
| `tools/verification/GmailEvidenceProbe.java` (--read-only-sample / --read-only-cost-check) | No current release-case claim. Bounded provider read/byte accounting; preserved historical capability evidence. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/run_gmail_probe.py` (default/--compile-only / --live [--cost-check]) | No current release-case claim. Compiles/launches the archived probe; default does not authorize live reads. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/parse_calendar_probe.py` (stdin JSON parser / facts helper) | No current release-case claim. Original Python calendar facts for archived probe; six local tests above. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/Phase3JevPilot.java` (--prepare-correspondence-only / --paid-development-only / --paid-heldback-once) | No current release-case claim. Frozen payload/source hashes, one-shot replay and outstanding-liability guards; experimental SemanticQualificationContract only. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/Phase3PilotAudit.java` (--audit-ledger) | No current release-case claim. Read-only historical attempt/cost settlement verification; no independent quality claim. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/Phase3IndexCandidates.java` (--local-index-only / --heldback-index-only) | No current release-case claim. Bounded retained-header export with read-only database and no-overwrite intent. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/prepare_phase3_reference.py` (--verification-root / --output-directory) | No current release-case claim. Exposure-screened private reference preparation; unresolved references are not invented. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/prepare_phase3_heldback.py` (offline frozen-pack preparation) | No current release-case claim. Frozen IDs/policy/source screening; no reuse as independent held-back evidence. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/score_phase3_development.py` (offline development scorer) | No current release-case claim. Known-reference-only historical scoring under frozen thresholds. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/score_phase3_heldback.py` (offline held-back scorer) | No current release-case claim. Independent held-back membership/reference scoring; do not tune/replay. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/compare_phase3_categories.py` (offline matched-category comparison) | No current release-case claim. Paired input equality and frozen-reference scores; not production label quality. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/Phase3SentCoverage.java` (--local-coverage-only) | No current release-case claim. Aggregate retained Sent-index completeness observation; not provider completeness. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/Phase3CorrespondenceSnapshot.java` (--local-snapshot-only) | No current release-case claim. Account-scoped pre-message retained correspondence export. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/prepare_phase3_correspondence.py` (offline correspondence manifest preparation) | No current release-case claim. Real/synthetic sensitivity controls kept distinct. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/compare_phase3_correspondence.py` (offline paired-request comparison) | No current release-case claim. Only relationship fields may differ; quality gain cannot be inferred from synthetic controls. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/RecommendationPilotPreparation.java` (--select-local-only) | No current release-case claim. Exact-message development scope preparation from retained metadata, no paid dispatch mode. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/RecommendationSnippetPilot.java` (--offline-check / --collect-reviewed / --paid-reviewed / --audit-read-only) | No current release-case claim. Frozen one-shot headers/snippet experiment acquisition, accounting and read-only audit. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/score_recommendation_snippets.py` (offline matched snippet scorer) | No current release-case claim. Paired equality and prior known references; not a current quality gate. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/ObservedRetentionSnapshot.java` (--local-sent-only) | No current release-case claim. Bounded retained Sent evidence snapshot; not original model input. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/ReplayRecommendationDecisions.java` (offline saved-judgment replay) | No current release-case claim. Only caller of retired decision-specific diagnostic policy; preserve historical reproduction, remove its default suite. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/LabelingTrialPreparation.java` (--prepare-local-only) | No current release-case claim. Private exact local trial request preparation; no automatic approval. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/LabelingTrialExecution.java` (--offline-check / --preflight-local-only / --prepare-runtime / --execute-reviewed / --audit-read-only) | No current release-case claim. Frozen V37 experiment; schema/replay intent and paid-liability guards remain intact. | Historical experiments; private fixture above. | Keep archived: do not execute retained/live/paid modes in this audit; future replay requires separate exact authorization. |
| `tools/verification/requirements.txt`, `README.md`, `.gitignore` | Historical runtime pin/instructions/private outputs; not executable cases. | Archived tools and six parser cases. | Keep: dependency/documentation/artifact boundaries, no test-count contribution. |

## Audit validation and remaining limits

Verified commands used Java 21, `.env.test.local` only, the pinned loopback test
role/databases, serial database suites and full Maven output redirected to `/tmp`.
Only generated `target/test-classes` and test reports were cleared; no production
classes, application configuration or retained mailbox were deleted/read/restarted.
No live provider or paid request was made. No production code changed.

Baseline: `mvn test` → 371/371 passed, zero failures/errors/skips
(`/tmp/tidymail-audit-baseline.log`). **Final retained: `mvn test` → 362/362 passed, zero
failures/errors/skips** (`/tmp/tidymail-audit-final.log`), reconciled per suite
against the final source inventory and fresh XML. Test/script edits delete 162
lines and add 16: **146 net lines removed**, separately from this requested audit
accounting document. The source inventory was reconciled per suite against all baseline
XML invocations, including every Value/Csv/EnumSource expansion; every retained
method has an explicit table row. The two fixtures are excluded by the existing
Maven include pattern, not newly disabled.

`TIDYMAIL_PERF_ROWS=100000 mvn -Prelease-performance test` → 3/3 passed,
zero failures/errors/skips (`/tmp/tidymail-audit-performance.log`). Dashboard:
10k/100k idle and concurrent query/read/preview work; at 100k, concurrent summary
p95 607.10 ms (2000 ms budget), 5000-target preview 348.58 ms. Its confirmed
5000-target job completed 1000 changes, skipped 2500 no-ops and cancelled the
remaining 1500; this is **not** a claim that all 5000 were mutated. Full 2165-target
response-mode and 501-target verified completion remain separate functional cases.
Sync: 100k seeded rows, 500-row commit/cursor update in 319 ms. The populated 115k
cache passed both action+School and School-only filters during recomposition at
1566.14/1813.08 ms p95 (2000 ms budgets). These are local synthetic observations;
budgets were not changed.

All six existing browser scripts passed in headless Chromium. Selection used only
intercepted fixtures; each other script used a fresh marked
`DashboardBrowserFixture` and stopped it before the next database user. Browser
logs are `/tmp/tidymail-audit-selection.log` and
`/tmp/tidymail-audit-browser-{ui-polish,activity-dashboard,rule-filing,rule-edit,rule-review}.log`;
fixture logs have `/tmp/tidymail-audit-fixture-` prefixes. The six historical Python
calendar parser cases also passed with the existing pinned `icalendar==7.3.0`
dependency in a temporary environment (`/tmp/tidymail-audit-python.log`). No new
suite, runner, framework or replacement workflow was committed.

Remaining uncertainty is bounded, not converted into a pass claim. The three
executor-based confirmation/token/report cases identify real invariants but do
not force every interleaving; their table rows say so. Published SQL byte hashes
cover only V1–V10; fresh migrations and eleven populated upgrade cases do not
prove immutable bytes for V11–V50. The 70,029-row Activity estimate fixture disables
triggers inside its transaction and validates display logic, not ingestion speed.
Pure recommendation matrices use constructed assessments to check component
precedence, including qualified negative signals that the current independent-label
adapter deliberately does not supply. Their allowed-disposal examples do not
establish an enabled automatic-disposal route; pipeline tests retain Review for
unqualified labels. Browser DOM/provider simulations do not prove real consent, remote delivery,
provider throughput, model accuracy or every mailbox distribution. Archived
paid/live/private experiments (including the original V37-only trial) were reviewed
as historical tools, not executed or certified against today's schema. The
optional Dark Reader branch was not run. These limits are retained follow-ups,
not reasons to add another framework or silently bulk-run experiments.

Final checks: `git diff --check` passed; all 59 retained suite/fixture source links
resolve. Fresh XML contains exactly the 362 expected functional invocations and
three performance workloads, with no stale deleted test class or fixture included
in the functional total. All retained methods and all original parameter values
have table dispositions; no unresolved disposition is hidden in the passing count.
