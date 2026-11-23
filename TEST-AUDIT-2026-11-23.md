# Test accounting — November 23, 2026

## Finding

The suite contains useful protection, but its passing result has been used to
support a much broader claim than its assertions establish. The previous audit
mostly justified retaining individual tests. It did not establish that the
supported desktop workflows work together, recover visibly, or complete promptly.
That distinction matters more than the number of tests removed or retained.

This review uses checkout `f2b6317`. Inventory reconciliation covers every current
Java test method and the existing per-invocation ledger. The detailed failure-path
findings below come from current assertions, harnesses, application call paths and
the relevant fix commits. This is not a mutation score or proof that every retained
assertion catches a unique bug. The existing ledger's “Keep” decisions must not be
read as either of those claims.

## Primary finding: an exposed outcome the automatic pipeline cannot produce

The user supplied a separate chat's live-database finding: every indexed message
had a saved recommendation, processing had no reported errors, and “Consider
Trash” had zero results across three accounts. That database observation was not
independently repeated here. The mechanism is independently confirmed in source:

| Boundary | Current implementation | Existing test behavior |
|---|---|---|
| Visible feature | `dashboard.html` offers `SUGGEST_TRASH` as “Consider Trash”. | Showing/parsing a filter is not proof the pipeline can populate it. |
| Automatic input | `RecommendationService` constructs security/human signals as UNKNOWN. `SemanticLabelComposition.merge` also returns UNKNOWN for both, even with semantic results. | `RecommendationPersistenceTest.localCompositionPreservesSentAndExistingCorrectionsWithoutAdoptingLegacyModelLabels` accepts REVIEW for a MARKETING example. Pipeline cases likewise accept REVIEW for several supported purposes. |
| Engine gate | `MessageRecommendation.evaluate` requires both signals to be ABSENT before returning automatic SUGGEST_TRASH. Other preservation/review branches can stop earlier. | `MessageRecommendationTest` and `RecommendationAttentionTest` manufacture ABSENT signals in their input helpers and successfully exercise the otherwise unreachable engine branch. |

Thus, on the inspected automatic composition path, completing or repeating
evaluation cannot satisfy the Trash gate. A manual correction can explicitly
select an action, which is a separate path. An empty Trash filter is not evidence
that the mailbox contains nothing disposable.

This is more serious than a missing edge case. The component tests demonstrate
capability with unavailable inputs, while integration tests accept conservative
REVIEW results without challenging whether the advertised outcome is achievable.
Together they preserve implementation consistency and miss product usefulness.
The previous audit already noted the adapter limitation but failed to promote it
to a product acceptance failure. Retaining those tests was not a resolution.

The missing acceptance requirement is a representative eligible message traveling
through the actual automatic adapter, stored recommendation and dashboard filter
to a supported result, alongside examples that must remain protected or unresolved.
It must not insert a final recommendation or manually supply the two missing
signals. Failing that, the UI must explain the capability limit. Choosing and
validating a supported disposal policy is separate implementation work; this audit
does not turn UNKNOWN into ABSENT or weaken preservation checks to make a test pass.

## Other reported failures

| Failure | What the tests actually checked | Why the failure escaped | Present evidence / remaining gap |
|---|---|---|---|
| Entering a date filter produces an error | `DashboardJourneyTest.dateSearchSummaryExpansionAndSelectionsUseTheSamePredicate` constructs a complete `DashboardFilter` directly, checks query/details/selection agreement and a DST boundary. | No typing, intermediate date state, dialog feedback or correction/retry occurs in that test. A correct SQL predicate cannot establish a working date control. | Commit `f2b6317` adds browser validation and clearing of stale page errors. The current selection script passes; the same script with only the previous JavaScript substituted in a temporary copy fails because the error stays visible. This demonstrates detection of that repaired regression, not every date-input failure or the user's exact original trigger. API responses in that script are still synthetic. |
| Deleting 100+ messages was slow | Cleanup tests check exact targets, batching, retries and settlement. The 2165-target response-mode case explicitly calls `worker.runOnce()` three times and supplies a provider callback. Scale tests measure local queries and local composition. | Manual worker calls bypass startup delay/poll cadence. Provider doubles omit real authorization, quota waiting and remote latency. No existing browser script measures confirmation-to-completion for 100+ Trash targets. | Large correctness workloads are valuable but do not qualify real cleanup speed. `MailboxSyncPerformanceTest` even allows one minute for a route or 500-row commit; that ceiling is not an interactive responsiveness standard. The historical slowdown's exact cause is not established by this audit. |
| Adding a sender leaves a rule stuck without feedback or usable recovery | Existing successful rule lifecycle tests checked persisted/native state under simulated responses. | A successful update does not exercise rejection, uncertain response, failed read-back, or whether the user can reach recovery controls. | Commit `104a2cd` added four Outlook update outcomes: lost response, rejection, unavailable read-back and not applied. They exercise HTTP/service recovery without replaying the mutation. This is useful retrospective regression coverage. The six browser scripts still do not exercise these four Outlook outcomes through the editor. The drawer's `fetch` also has no request deadline; an HTTP rejection fixture does not model a request that never settles. |

The user reports the rule issue is fixed. This audit does not reopen it or claim
that source fixes have been verified in the currently running application.

## Complete inventory and execution boundaries

| Item | Current accounting | What the number means |
|---|---|---|
| Default Java tests | 326 methods in 53 source suites; 355 expanded invocations | Includes parameter variations, not 355 user workflows. |
| Parameter variations | 46 invocations from 17 methods | Already included in 355; no extra coverage credit. |
| Performance | 3 methods in 3 suites | Separate `release-performance` profile; not run by normal `mvn test`. |
| Explicit Java fixtures | 2 methods | Synthetic browser server and private reviewed-reference fixture; not default tests. |
| Browser scripts | 6 scripts, 744 source lines | Manual Node commands outside Maven. One intercepts every request; five use the synthetic MockMvc bridge, with some additional response interception. |
| Shared Java helpers | 6 files and Spring registration | No independent behavioral passes. |
| Historical Python checks | 6 parser cases | Archived probe parser, outside current Java product regression coverage. |
| Historical experiment tools | Enumerated in the existing ledger | No current release coverage credit; not replayed. |
| Test source size | 11,344 lines under `src/test` | Includes fixtures/helpers/resources. Size is maintenance burden, not a quality score. |

Every current Java test method is present in the
[individual case ledger](TESTING.md#individual-case-audit--november-22-2026).
That ledger also retains removed cases for historical reconciliation. Its 371
original rows must not be presented as a verified current or historical executed
baseline: the prior narrative also records a fresh 362 baseline. Current source
and current execution are the authorities for today's accounting.

### What is replaced during tests

`PostgresJourneyTest` uses real Spring handlers and PostgreSQL, but replaces Gmail
and Outlook mail providers, injects an OAuth authentication token into requests,
and disables general scheduling and classification/recommendation workers. Many
tests insert metadata and workflow states with SQL and call processing methods
directly. This is legitimate setup for a particular boundary; it skips the path
by which a real user or provider would create that state.

`DashboardCleanupPollingJourneyTest` is a valuable exception: a real poller finishes
a confirmed synthetic job. It explicitly changes polling to 25 ms and startup
delay to zero. Its one eligible target does not test normal production startup
delay (10 seconds by worker default), polling (one second), or 100+ message speed.
Do not describe all worker tests as proving scheduler wiring.

`DashboardBrowserFixture` serves real rendered templates and handlers over a
loopback bridge, but injects authentication for every request and runs custom
classification/recommendation/cleanup executors. It does not exercise normal
login/session establishment, the deployed web server, real provider consent, or
normal scheduler configuration. The fixture method can pass after its serving
window ends even if no browser visited it; count the script assertions separately.

`dashboard-selection.cjs` serves a transformed raw template and intercepts every
network request. It runs real UI JavaScript and catches selection/rendering bugs,
but its handwritten JSON can disagree with the real controller. It does not
confirm a cleanup job.

No repository CI workflow or Maven step was found that runs the six browser
scripts. No mutation-testing configuration or maintained requirement-to-journey
acceptance matrix was found. An external CI setup has not been inspected.

## Assertion value: specific decisions

| Existing check | Decision | Reason / limitation |
|---|---|---|
| Transaction rollback, exact account/target IDs, no replay after uncertain dispatch, spending settlement and retained-data upgrades | Preserve | They assert consequential state across real persistence boundaries. Mocks at the provider edge do not make these checks worthless. |
| Gmail SDK / Graph transport request checks | Preserve separately from job tests | They catch payload and retry behavior hidden by mail-provider mocks. Their overlap with persistence tests covers different failure boundaries. |
| `MessageRecommendationTest` and `RecommendationAttentionTest` disposal examples | Component-only; challenge further expansion | They directly construct qualified absent security/human signals. Current `RecommendationService` and `SemanticLabelComposition.merge` supply UNKNOWN signals on the inspected composition path. These examples do not prove the app can produce the demonstrated disposal recommendation. Preserve reachable protection/boundary assertions; do not count hypothetical results as product acceptance. |
| `CalendarResponseJourneyTest.calendarFilterChoicesExposeOnlyTheThreeStatusLabels` | Low priority; consolidate when the filter browser journey owns this contract | It only searches rendered HTML for option strings. It cannot detect broken selection, request serialization, results or clearing. The neighboring calendar query/response tests have stronger purposes. |
| `dashboard-selection.cjs` dropdown `appearance`, SVG background and padding assertions | Review styling assertions individually | These prescribe one implementation of a chevron. A working accessible dropdown could fail them. Interaction, readability and overflow checks are stronger evidence. Do not remove the selection journey along with them. |
| `JevClassifierTest` prompt fragments and exact question counts; corresponding semantic-contract wording assertions | Mixed value within otherwise useful cases | Transport schema, privacy and byte limits are real contracts. Matching a few instruction substrings or a fixed count does not demonstrate classification quality or correct prompt semantics. Keep those claims separate. |
| `MailboxSyncPerformanceTest` one-minute route/commit ceilings | Weak performance qualification | It checks a bounded commit and cursor, which are useful. A route can be frustratingly slow and pass. It does not time frontend data loading or a full provider sync. |
| `rule-filing.cjs` completion claim | Submission-only | It checks a POST, a “saved” notice and the Activity destination. It never waits for terminal success or checks every selected message after refresh. |
| `rule-review.cjs` optional existing-mail filing | Submission-only | It checks dialog closure and one job POST, not completed filing. |
| `LabelingPipelineJourneyTest.browserPreviewAndPreparationAreInertOwnedAndProgressDoesNotReloadRequestPayloads` | Preserve API checks; correct interpretation of its name | This is MockMvc HTTP and database coverage, not a browser journey. Buttons, dialogs and JS can be broken while it passes. |
| Historical probe/parser cases and private reference fixture | Historical evidence only | Neither current product acceptance nor independent current model-quality evaluation. |

These are assertion-level distinctions. This audit does not invent a defensible
number of “useless tests” from file names, runtime, or whether a test uses mocks.
Nor does it delete meaningful checks to obtain a smaller count. The previous
inventory's claim that each retained case establishes a distinct boundary was
stronger than the independent fault-detection evidence available.

## Every default suite: protection and limit

Counts below are expanded invocations. “Retain” means preserve the identified
protection; it does not certify independent fault sensitivity or whole-app health.
Detailed method/parameter rows remain in the linked case ledger.

| Suite | Cases | Protection to retain / principal limit |
|---|---:|---|
| AutomationRulesJourneyTest | 20 | Stored/native rule lifecycle and update recovery; browser recovery and real future delivery unproved. |
| CalendarResponseJourneyTest | 7 | Current provider response, ownership and stored filters; HTML-only choices case is weaker. |
| CorrespondentHistoryTest | 3 | Account-local contribution/replay/repair; observed history is not identity proof. |
| DashboardClassificationTest | 20 | Corrections, queue and spending; classifier output and much execution are simulated. |
| DashboardCleanupPollingJourneyTest | 1 | Actual automatic cleanup wiring; altered poll settings and one eligible target. |
| DashboardGmailDispatchTest | 14 | Exact SDK requests and replay limits; loopback provider, no user journey. |
| DashboardIdentityTest | 3 | Ambiguous sender parsing and absent/empty evidence; component inputs. |
| DashboardJobJourneyTest | 17 | Exact durable jobs, cancellation, fencing and ownership; mostly manual worker execution. |
| DashboardJourneyTest | 28 | Query/group/selection/preview agreement; seeded data and no JS date entry. |
| DashboardMigrationTest | 11 | Populated historical upgrades; finite starting datasets, not every retained mailbox. |
| DashboardResilienceTest | 10 | Verified-mode labels, retries and uncertain outcomes; simulated remote state/timing. |
| DashboardResponseCleanupJourneyTest | 10 | Response-mode batching/projections/fencing; direct worker calls do not measure user completion time. |
| DashboardSimplificationTest | 4 | Retired route denial, pending rows/history/logout retention; HTML/API rather than interactive navigation. |
| GmailBudgetAndCredentialsTest | 3 | Quota capacity and managed-token request setup; limited scheduled interleavings. |
| GmailEvidenceReaderTest | 5 | MIME bounds and read accounting; synthetic SDK responses. |
| GmailSynchronizationContractTest | 8 | History ordering/projection, metadata and configuration; provider fixture rather than current Google service. |
| LabelingPipelineJourneyTest | 27 | Reviewed scopes, spending/exposure and recovery; does not establish model quality or all UI controls. |
| LocalGmailLabelsJourneyTest | 3 | Local relabel/cache ownership; supplied metadata and local processing. |
| MailboxFreshnessConcurrencyTest | 6 | Lane ownership, admission and transaction boundaries; selected interleavings. |
| MailboxSyncPersistenceTest | 5 | Atomic commits, replay, revisions and account-local deletes; no browser/provider timing. |
| MailboxSyncStateTest | 2 | Database constraints and provenance; not acquisition/orchestration. |
| ManualFilingJourneyTest | 2 | Exact filing/destination intent and lost-response recovery; mock-provider writes. |
| MessageEvidenceJourneyTest | 6 | Stored evidence, bytes and exact preview; provider acquisition partly mocked. |
| OAuthAuthorizedClientRefreshTest | 6 | Persisted tokens, refresh and revoked grants; not consent in a real browser. |
| OutlookGraphBatchTest | 5 | Per-item transport outcomes and cooldown; no real Microsoft account. |
| PersonalOutlookJourneyTest | 13 | Outlook sync, folders and cleanup recovery; Graph transport simulated. |
| Phase13PostgresMigrationTest | 1 | Immutable SQL bytes for V1–V10 only; not all 50 migrations or upgrade semantics. |
| Phase2IdentificationJourneyTest | 8 | Cached evidence, reviewed reuse and corrected identities; synthetic acquisition/labels. |
| Phase3PersistenceTest | 2 | Encryption/tamper detection and missing refresh-token persistence; not successful sign-in. |
| Phase6SenderAggregationTest | 4 | Active parser examples and rejection cases; limited examples, not sender UX. |
| Phase8DeploymentTest | 1 | Real retention job preserves active/current data; no deployed scheduler proof. |
| Phase9ActionScopeTest | 3 | Canonical hash identity and loaded-summary bounds; component contracts, no UI confirmation. |
| Phase9MetadataIndexTest | 1 | Metadata conversion and defensive copies; narrow mapper boundary. |
| RecommendationDashboardJourneyTest | 5 | Filters/corrections/ownership/durable local processing; API/service journey. |
| RecommendationPersistenceTest | 13 | Staleness, composition, corrections and cache invalidation; seeded semantic facts. |
| RuleDestinationProviderTest | 4 | Native destination compilation and old rule compatibility; no real provider delivery. |
| SecurityConfigIntegrationTest | 4 | Authentication requirement, logout and registration config; no OAuth browser round trip. |
| SyncFailureDiagnosticTest | 1 | Diagnostic redaction; not comprehensive privacy certification. |
| GmailBackfillRecoveryJourneyTest | 4 | Scheduled recovery and SDK-to-DB history; synthetic HTTP responses. |
| MailboxActivityJourneyTest | 6 | Account-local progress, retry and polling fragments; no actual browser polling lifecycle. |
| MailboxFreshnessJourneyTest | 9 | Provider-to-index freshness and progressive import; simulated provider/time control. |
| SecurityConfigTest | 2 | Authorization request parameters; provider acceptance untested. |
| OAuthLoginSuccessListenerTest | 3 | Callback token handoff, queue choice and redirect; mocked collaborators. |
| CalendarEvidenceParserTest | 4 | Temporal shape/size/repetition parsing; no provider association. |
| CalendarReconcilerTest | 9 | Recurrence, timezone and conservative reconciliation; supplied event sets. |
| CalendarResponseFailureTest | 1 | Save failure releases retry guard and sanitizes error; mocked transaction, not real rollback. |
| HeaderSemanticAdmissionTest | 2 | Forward/reply admission; no category-quality proof. |
| JevClassifierTest | 9 | Wire/privacy/pricing/parser failure contracts; prompt text is not accuracy evaluation. |
| MessageRecommendationTest | 4 | Engine precedence/input boundaries; some disposal inputs not produced by inspected app adapters. |
| PositiveRetentionTest | 4 | Positive retention/manual/exclusion precedence; pure component policy. |
| RecommendationAttentionTest | 5 | Age/read/policy boundaries; hypothetical qualified-negative inputs do not prove app disposal. |
| RecommendationSemanticContractTest | 5 | Request/parse/provenance/privacy bounds; self-authored response fixtures do not validate live protocol/model quality. |
| ReviewedPatternPolicyTest | 2 | Reuse exclusions; component judgments, not editor interaction. |

## Browser and performance accounting

| Existing executable | What it establishes | What remains outside it |
|---|---|---|
| dashboard-selection.cjs | Real JS selection, paging, stale-response handling, filter recovery and draft transfer | Entire backend mocked; no cleanup confirmation/completion or real date query. |
| ui-polish.cjs | Rendered desktop navigation, density, filters, review and draft interactions | No executed cleanup, OAuth flow or actual date-range selection results. |
| activity-dashboard.cjs | Activity navigation, cards/chart presence and overflow | No browser-confirmed cleanup completion, numeric chart reconciliation or empty-to-populated polling lifecycle. |
| rule-filing.cjs | Form/destination choices, rejected save recovery and filing submission | No terminal filing result or Outlook uncertain-update editor recovery. |
| rule-edit.cjs | Standalone editing, rejected save, domains/conditions and synthetic enablement | No real provider rule delivery or never-settling request. |
| rule-review.cjs | Rule review/result, add-sender success and optional filing handoff | No four-outcome Outlook recovery through browser or terminal filing assertion. |
| DashboardPerformanceTest | Local 10k/100k browsing during writes and synthetic cleanup | Provider/consent/quota latency and UI confirmation-to-completion. |
| MailboxSyncPerformanceTest | Local routes and one 500-message commit over populated index | Full sync speed and responsive browser loading; one-minute budgets are weak. |
| LabelingScaleJourneyTest | Populated 115k label/recommendation cache during composition | 115k provider acquisitions, paid calls or classification accuracy. |

## What should change in acceptance

Use complete user outcomes to decide which existing checks to strengthen. The
minimum missing evidence is: enter/correct/clear date filters against the rendered
app and verify exact results; confirm a representative 100+ target cleanup and
follow progress through completion, refresh and cancellation; and exercise failed
sender updates through visible recovery controls. Keep independent backend
checks for exact IDs, persistence and no repeated mutations underneath these
journeys. Preserve authentication/permission, empty/error, changed-data and
interrupted-response outcomes where their recovery differs.

For a repaired bug, run the relevant regression check with the defect restored in
an isolated copy when practical. Today's date check demonstrates why this matters.
Do not edit expected behavior merely to agree with the implementation. A test
change can be correct when requirements change, but its reason must be reviewable.

The pasted proposal's blanket two-unit-test cap and mandatory real infrastructure
for every change are not justified by this repository. Encryption, hashes,
parsers and monetary boundaries benefit from focused component tests; simulated
provider failures are needed for outcomes that are difficult to induce live.
The requirement is honest evidence of the relevant user outcome, not a preferred
test count or indiscriminate replacement of mocks.

No tests or production behavior were changed by this audit. It does not claim the
remaining acceptance gaps are implemented, or that the app is ready because an
existing command passes.

## Fresh verification

| Check run in this audit | Result and exact limit |
|---|---|
| Java 21 `mvn -o test` | 355 invocations, zero failures/errors/skips; 4:02 total. XML has 53 nonempty suites plus the empty enclosing scope suite. All 53 suite counts in this report sum to 355. |
| Source-to-ledger reconciliation | All 326 default methods, 3 performance methods and 2 fixture methods are named in the existing ledger. All 17 parameterized methods are included; 46 expanded parameter invocations are part of 355. |
| Current `dashboard-selection.cjs` | Passed, using the current real frontend assets and intercepted API responses. |
| Same selection script with pre-fix `dashboard.js` | Failed at the stale date error notice after correction. Only JavaScript was substituted in a temporary copy; no source rollback or app restart. This validates one concrete regression assertion. |
| `ui-polish.cjs` | Passed against the isolated rendered browser fixture, including desktop density/navigation/review. No actual provider or cleanup completion proof. |
| `rule-filing.cjs` | Passed against that same fixture after the polish script. This verifies its documented form/submission assertions; it still does not assert terminal filing completion. |
| Browser fixture | Completed successfully and stopped. Its one explicit harness invocation is separate from the 355 default assertions. |
| Browser script syntax | All six scripts checked; no syntax failures. The other three rendered scripts were inspected, not executed in this audit. |
| Documentation | Suite table/source/XML counts reconciled, local links checked, `git diff --check` passed. |

Logs are `/tmp/tidymail-test-accounting-20261123.log`,
`/tmp/tidymail-test-accounting-selection-20261123.log`,
`/tmp/tidymail-test-accounting-date-before-20261123.log`,
`/tmp/tidymail-test-accounting-ui-20261123.log`,
`/tmp/tidymail-test-accounting-filing-20261123.log`, and
`/tmp/tidymail-test-accounting-fixture-20261123.log`. The complete default-run XML
was copied to `/tmp/tidymail-test-accounting-20261123-functional-reports` before
starting the explicit browser harness, so its result cannot inflate the default
count. The before-fix browser copy is under
`/tmp/tidymail-date-regression-audit-20261123`.

Initial sandbox attempts failed on Maven cache access and Chromium startup;
successful runs used the same isolated databases/fixtures with process/cache
access. These setup failures were not product assertions. Performance workloads,
private reference data, historical paid/live tools and the retained mailbox were
not run. The separate chat's live-database result remains user-supplied evidence.
