# Read-only email evidence probe

The explicit `Phase3JevPilot --paid-heldback-once MANIFEST OUTPUT` mode validates the immutable held-back policy/source hashes and sixteen review IDs, includes approved standard Gmail categories, and refuses replay after a held-back ledger exists. `score_phase3_heldback.py RUN_DIRECTORY` scores per-property known references under those frozen thresholds without treating unresolved predictions or unknown answers as correct. A failed set cannot be tuned and replayed as independent held-back evidence. Future development manifests may opt into `admission_policy: header-context-v1` to defer replies/forwards before paid requests; this post-result policy is prohibited in the frozen held-back mode and is not wired into production classification.

For an explicitly approved matched category comparison, a private pilot manifest sets `comparison` to `GMAIL_CATEGORIES_ON_OFF`. The runner snapshots local labels/presence once, alternates arm order per batch and emits only standard `CATEGORY_*` enums in the included arm. `compare_phase3_categories.py RUN_DIRECTORY` verifies that the paired requests differ only in those labels and scores each arm against the same frozen references. The prior header-only scorer is not used for paired repeated IDs. `Phase3IndexCandidates --heldback-index-only OUTPUT` reads at most 32 local headers without Gmail/model access; `prepare_phase3_heldback.py DIRECTORY` screens and prepares a private four-message replacement review and policy freeze, with no guessed labels. All experiment and review outputs remain private/ignored.

The explicitly authorized Phase 3 paid experiment is separate: `Phase3JevPilot.java --paid-development-only MANIFEST OUTPUT_DIRECTORY`, compiled/run with the current application dependency classpath and development auto-restart disabled. It resolves the configured key internally, sends only allow-listed headers to the fixed TypeSafe Jev/OpenRouter route, caps reported plus reserved exposure at $0.25 and the existing deployment checkpoint, and writes normal job/attempt/cost accounting only. It does not run workers, migrations, Gmail transports or saved classification updates. Keep outputs private and never rerun or change directories to evade an unresolved intent. `Phase3PilotAudit.java --audit-ledger LEDGER` independently verifies settlement read-only; `score_phase3_development.py RUN_DIRECTORY` evaluates only frozen development IDs and thresholds. Neither audit nor scoring calls a model.

Phase 3 reference preparation also has two separate local-only tools. `Phase3IndexCandidates.java` requires explicit `--local-index-only OUTPUT`, uses the existing application classpath/configuration, and exports at most 96 already-indexed headers through a read-only database connection. It has no mail transport, model client, component scan, jobs or migrations and refuses to overwrite its output. `prepare_phase3_reference.py --verification-root docs/verification --output-directory DIRECTORY` combines existing reviewed source files and that candidate export into a private manifest/review sheet. It screens known message/thread/exact-template exposure, preserves unknown reference properties and refuses to overwrite a manifest. Broader template-family review and earlier capability-exposure screening remain necessary; its prospective split is not a qualified benchmark. Keep all outputs in the ignored verification directory and never commit the private pack.

Opt-in feasibility tooling, separate from the application. Reads existing app configuration and encrypted credentials through its crypto classes. Uses a read-only database connection and, if needed, refreshes the Google access token only in memory. Does not start application workers, run migrations, modify mailbox labels, persist bodies, or call any model. Normal app metadata/body protections are untouched.

Requires Java 21, the existing `target/classes` and packaged JAR, the local database, the configured connected account, and a temporary Python environment with `requirements.txt` installed. Configuration is loaded normally; never print or copy secrets into commands/reports.

```sh
python3 -m venv /tmp/tidymail-evidence-venv
/tmp/tidymail-evidence-venv/bin/pip install -r tools/verification/requirements.txt
python3 tools/verification/run_gmail_probe.py --compile-only
/tmp/tidymail-evidence-venv/bin/python -m unittest discover -s tools/verification -p 'test_calendar_probe.py' -v
```

Only when authorized to perform live reads:

```sh
python3 tools/verification/run_gmail_probe.py --live
python3 tools/verification/run_gmail_probe.py --live --cost-check
```

Each invocation caps Gmail GETs at 64, decoded JSON response bytes at roughly 8 MiB total and 1 MiB per response. Requests are sequential, with no automatic retries. Reads calendar parts only under 128 KiB and full-body variants only for selected small messages. MIME field masks inspect six nested levels; deeper structures need another explicitly scoped check. A response exceeding a cap is recorded as incomplete, not accepted as full evidence.

The capability run samples one retained message per named organization, up to four ICS-search candidates and two large-message candidates. The cost run compares identical field masks with/without snippet for the four organizations, plus at most one large message lacking Gmail's attachment search flag. Search results are small/nonrepresentative, not exhaustive.

Reports contain sizes/timings/types/presence and conservative calendar facts, no message IDs, subjects, addresses, filenames, body text, calendar titles or tokens. Original reports are not overwritten on subsequent runs. The parser deliberately leaves recurrence/update reconciliation, floating times and unknown all-day timezone boundaries unresolved; it is a feasibility helper, not a complete calendar cleanup engine. Production library/runtime selection remains a separate decision.

`Phase3SentCoverage.java --local-coverage-only` prints account IDs and aggregate retained-index Sent coverage only. It uses a read-only connection without component scanning, migrations, workers, mail transports or model clients. It does not print addresses/subjects, inspect Contacts, establish provider-side completeness, or write to the retained database. The relationship-index implementation and rollout boundary are recorded in `docs/PHASE-3-CORRESPONDENT-HISTORY.md`.

The correspondence comparison uses `Phase3CorrespondenceSnapshot --local-snapshot-only INPUT OUTPUT` for read-only account-scoped pre-message summaries through the shared production extractor. `prepare_phase3_correspondence.py SNAPSHOT OUTPUT` freezes 10 reviewed real development examples and 16 separately identified synthetic count-sensitivity controls. `Phase3JevPilot --prepare-correspondence-only MANIFEST OUTPUT` runs fully offline, producing exact matched requests without configuration loading, database access, credentials or transport. `compare_phase3_correspondence.py RUN` audits paired equality except relationship fields and scores real/synthetic cases separately. The first proposed paid correspondence run was rejected by automatic approval review; the user then explicitly approved the exact payload, destination and $0.25 shared experiment allowance. The approved 18-request run completed, its costs settled, and the scorer found no purpose-label changes or demonstrated quality gain. This does not authorize automatic replay or production adoption. The frozen trial has 26 messages per arm and 18 requests, preserves unknown references, excludes future exchanges and leaves production adoption disabled.
# Recommendation snippet-scope preparation

`RecommendationPilotPreparation.java --select-local-only <reference-manifest> <new-output-directory>` prepares an exact-message development scope using only the retained local metadata index. Compile it against the current application dependencies (including ical4j), rather than a stale packaged-JAR classpath. Disable development auto-restart. It asserts a read-only connection and does not component-scan, migrate, fetch Gmail content, submit to Jev or write the database.

The current review packet is `docs/verification/2026-11-02/recommendation-snippet-pilot-scope-02/REVIEW.md`. Scope-01 is superseded. Scope-02 has eleven exact messages, one documented recent-unread-bill sampling gap, and twenty-two matched single-message request drafts with UNKNOWN snippets. The proposed incremental cap is $0.20 inside the existing shared $0.25 experiment limit; a thirteen-question request reserves up to $0.143130624 at the previously pinned upper input price. Verify pricing, pending/unknown liabilities and checkpoint capacity before any future submission. Do not weaken reservations to fit three-message batching or the earlier $0.05 proposal.

The preparer intentionally has no paid-dispatch flag. The user's required scope review, bounded snippet acquisition, final payload freeze, guarded dispatch/settlement and independent audit/scoring remain separate work. No new route is qualified for production by these offline artifacts. Read/age policy implementation and the 55-check checkpoint are documented in `docs/verification/2026-11-02/RECOMMENDATION-SNIPPET-AND-ATTENTION.md`.

The separately approved one-shot runner is `RecommendationSnippetPilot.java`, compiled alongside the unchanged `Phase3JevPilot.java` accounting/HTTP helper. Its modes separate offline checks, reviewed snippet collection, reviewed paid dispatch and read-only audit. Do not replay an existing paid run or exceed the reviewed request count to tune results. The completed run is documented in `docs/verification/2026-11-02/RECOMMENDATION-SNIPPET-PILOT-RESULT.md`; all 22 charges matched and all diagnostic actions remained REVIEW. `score_recommendation_snippets.py` verifies paired inputs and scores only prior known references. Replaying composition locally is distinct from another paid semantic run.

The November 3 correction uses `ObservedRetentionSnapshot` for a bounded read-only retained Sent-metadata snapshot and `ReplayRecommendationDecisions` for provider-free replay. These are local tools, not new paid modes. The original trial source was archived before changing retention logic. Results and the 45-check boundary are documented in `docs/verification/2026-11-03/POSITIVE-RETENTION-CORRECTION.md`. Do not present new local correspondence observations as part of the original snippet/model input or claim the replay is an independent accuracy gate.

# Recommendation dashboard verification

The local dashboard/worker slice through V36 is recorded in
`docs/verification/2026-11-03/RECOMMENDATION-DASHBOARD.md`. The new
`RecommendationDashboardJourneyTest` covers opt-in processing, account/CSRF
boundaries, exact filtered selection/bytes, stale-preview rejection, per-message
correction conflicts, durable pause/restart/lease expiry and account failure
recovery. `DashboardPerformanceTest` now measures cached recommendation filtering
alongside its existing 10k/50k concurrent workflows. `DashboardBrowserFixture`
remains an explicit-only synthetic mailbox harness, including a mocked Sent
storage candidate and local recommendation processing. It is never a retained-app
startup or permission for Gmail/model work.

# Scoped background labeling

The [allowance update](../../docs/verification/2026-11-04/LABELING-ALLOWANCE.md)
adds an owner-only, CSRF-protected review and explicit confirmation for increasing
the same saved batch's total cap and the shared labeling allowance. No increase
resumes work or resubmits completed messages. Financial revisions preserve the
exact request list and append approval history to the manifest, changing its hash.
Current spending and funding blockers are returned by progress; unfundable Resume
is disabled, with saved-response recovery retained. Actual production limits were
left unchanged during installation. New batch preparation accepts cent amounts
through $100, subject to the independently approved shared/deployment limits.

The [installed labeling controls](../../docs/verification/2026-11-04/LABELING-CONTROLS.md)
now expose missing/changed, Other/Unknown and selected-message candidate review
in the app. `POST /dashboard/api/labeling/preview` is local-only;
`POST .../labeling/prepare` creates an inert exact batch of up to 1,000 messages.
`POST .../scopes/{id}/start` requires its owned reviewed hash and explicitly runs
only that scope, without enabling general scheduled jobs. Preparation is not
authorization. `GET .../scopes` lists owned recent batches, and `GET
.../scopes/{id}/progress` excludes the large frozen request payload. The original
spending checkpoints remain unchanged. This does not approve or perform a
mailbox-wide model run.

The November 4 [recovery checkpoint](../../docs/verification/2026-11-04/LABELING-RECOVERY.md)
adds authenticated `GET .../scopes/{id}` for exact persisted review/progress and
reported/reserved spending, plus CSRF-protected `POST .../{id}/resume` and
`POST .../{id}/retry-mime`, each with the reviewed `hash`. MIME retry does not
resume the scope or replenish its caps, and cannot retry paid exposures. MIME
reservations now run at the actual uncached read boundary. The retained app was
not restarted or replaced by those isolated checks.

`LabelingPipeline` and V37 connect MIME discovery and durable Jev label results to
local composition. The worker defaults off. Existing local Build recommendations
does not authorize or enable Gmail/Jev work. Authenticated, CSRF-protected
`POST /dashboard/api/labeling/scopes` prepares 1–1,000 exact message IDs with `kind`
MIME/JEV, `variant` HEADERS/SNIPPET and `maxSpend` (zero for MIME, at most $0.20
for Jev). A separate cumulative new-route $0.20 allowance survives scopes and restarts. It returns an inert exact manifest and hash. The separate
`.../scopes/{id}/authorize` accepts that reviewed hash; `.../{id}/pause` stops
future claims. Authorized work requires deliberate worker enablement. Java service
methods also support reviewed resume and explicit MIME-only retries; there is no
unknown-charge paid retry shortcut.

`LabelingTrialPreparation --prepare-local-only NEW_DIRECTORY` freezes at most twelve
exact local samples and headers-only request drafts with a read-only connection,
no component scan, migrations, Gmail/Jev client or retained writes. Compile against
current application classes/dependencies. The concrete packet is
`docs/verification/2026-11-03/labeling-trial-scope-05/REVIEW.md`; provider/model
execution remains separately approved work. Do not overwrite frozen pilot files.

`LabelingPipelineJourneyTest` covers isolated provider-to-filter behavior and
recovery with mock transports and real temporary Postgres. `LabelingScaleJourneyTest`
uses a fully populated synthetic 115k label/recommendation cache; it measures
browsing during local composition, not model accuracy or live acquisition rates.
Implementation boundaries: `docs/verification/2026-11-03/LABELING-PIPELINE.md`.

`LabelingTrialExecution` separates `--offline-check`, `--preflight-local-only`,
`--prepare-runtime`, `--execute-reviewed` and `--audit-read-only`. Every mode takes
the frozen scope JSON and private run directory. Execution is one-shot, uses the
production queue with unrelated scheduling disabled, verifies exact persisted
payloads and shared liabilities, and refuses replay after an execution intent.
The approved scope-05 run is complete; do not invoke its execution mode again.
See `docs/verification/2026-11-03/LABELING-LIVE-TRIAL.md` for results and rollout.

Confirmed dashboard cleanup has its own bounded poller. It normally follows
`spring.task.scheduling.enabled`, but `app.cleanup.worker.enabled=true` explicitly
keeps cleanup running when global scheduling is disabled for local labeling
controls. This does not enable sync, legacy classification, or paid labeling.
`DashboardCleanupPollingJourneyTest` verifies automatic completion of confirmed
indexed-mail cleanup with global scheduling disabled and excluded targets skipped.
