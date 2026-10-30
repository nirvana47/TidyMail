# Classification improvement implementation — 2026-10-30

All four stages are implemented and the approved rollout is running at localhost:8080.
Real Jev measurements are recorded in CLASSIFICATION-BENCHMARK.md. Automatic
incoming processing is enabled and shares the same durable allowance. Ordinary
paid dispatch was held during both explicit benchmark rounds.
Reusable rules, regex discovery and learning remain deferred.

## Verification

Isolated PostgreSQL fixtures cover recurring checkpoint overshoot, idempotent
continuation, concurrent and duplicate response accounting, late cancellation costs,
unknown/late charges, acknowledgement ownership, zero costs, metadata invalidation,
automatic deduplication/switch/pause/cancellation, arrival/backlog fairness, exact
address/category pagination, category-preview invalidation, and retained V21
classification/override/historical-cost preservation across V22/V23.

Java 21 checks: 55 dashboard/classification/migration/job checks passed, then 93
expanded checks had one timing-sensitive cleanup Retry-After failure under load.
That parameterized check passed all three cases on rerun. The final changed
classification/HTTP-boundary checks passed 28/28. Migration checks passed 4/4.
No Gmail cleanup mutation was performed; mail providers in these tests are mocks.

Synthetic desktop checks verified partial Jev/Unclassified rows during progress,
exact-address Messages navigation, pagination reset and next page, return to group,
independent selection, All received + Category filtering, and keyboard tab changes.
The selected message remained selected during ordinary batches; meaningful final
regrouping cleared the draft as intended. A screenshot is saved at
/tmp/tidymail-classification-desktop.png. No live desktop classification UI state
has yet been claimed as verified.

## Recoverable backup and startup

The app was not listening on localhost:8080 when checked before the first startup.
A later coordinated restart applied the measured transport setting and bounded
grouping updates. Repackaging began shortly before the shutdown hook fully exited,
which caused a shutdown-only class-loading warning; the process and all database
connections were confirmed closed before replacement startup. The user explicitly approved the coordinated restart after checks.
An encrypted V21 dump and previous JAR were saved under
.local-backups/classification-20261030 (ignored by Git, directory permissions 700).
A separate PostgreSQL container restored the encrypted dump successfully: all
110,574 metadata rows, 59,120 saved classifications, zero overrides, and the
classification/override fingerprint matched. Historical reported job cost was
$2.00742499 and is not charged against the new initial allowance.

The agent did not read .env. Tests disable dotenv loading; the normal app loads its
own configuration during startup. No commit or push is implied or performed.

## First live measurements

The approved startup applied V22/V23 and returned HTTP 200 at /login. Five live
40-message variants completed without failures, followed by 40 newly eligible
messages saved end to end. Newly reported ledger spend was $0.00790209; no
continuation was performed. Old 59,120-classification fingerprints still matched
the restored backup exactly. Automatic incoming processing was enabled and its
outbox was empty after processing. Production selected batches of 10 with one
request in flight; original semantic prompts/version remain unchanged.

The first full-storage sample took 17.95 seconds; grouping, rather than provider
latency, dominated that small sample. This motivated bounded 500-row grouping
updates and avoiding writes of unchanged labels/membership before a final rerun.

## Final verification and operating configuration

The final changed core checks passed 39/39 on Java 21, including the automatic-off
manual-catch-up edge case. Scale checks passed at 10k and 50k messages, including
concurrent metadata writes and simulated cleanup. The 50k category-filter p95 was
103.60 ms and concurrent summary-page p95 was 314.06 ms. An earlier update's 80k
reference was incorrect; 80k was not rerun in this task. Manual-correction checks
caught a parameter-binding mistake while developing the bulk update; that change
was corrected before rollout and the final grouping/core checks passed.

The repeated live experiment selected ten messages per request with one request
in flight. Baseline and batch-10 variant throughput were 959.0 and 1982.8 messages
per minute respectively (provider + ledger path, same 40 samples within the round).
Original instructions, threshold and metadata-v1 semantics remain unchanged;
compact prompts and production parallel dispatch were not adopted. The labeled
fixture had 9/12 accepted category+kind answers and three cautious abstentions
for the adopted configuration; no wrong accepted answer appeared in those 12
synthetic cases. This small fixture is not a real-mail accuracy guarantee.

The second end-to-end check saved 40 newly eligible retained messages, zero
skipped, in 2.63 seconds. The first 17.95-second check also reconciled previously
saved grouping evidence, and the two checks used different newly eligible mail;
they are not a controlled before/after grouping speedup measurement. Across both
rounds: 400 comparison classifications, 80 newly saved classifications, zero
request failures, no retries, total reported spend $0.01580061, checkpoint $1,
continuation generation 0, and no DISPATCHED/UNKNOWN attempts remaining. The old
59,120-classification fingerprint still matches the backup; total classified
count is 59,200. No encoded Google Voice unit has a classified-domain automatic
key. The automatic outbox and active classification-job queue were empty at the
final check, and /login returned HTTP 200.

New arrival controls and exact-address UI behavior were verified with isolated
fixtures. Live backfill remains active; a transient SYNC_UNIT_FAILED warning was
observed while lifecycle remained BACKFILLING/RUNNING with no stored error code.
No live eligible incoming-mail classification or broader human-labeled real-mail
quality measurement is claimed from the empty outbox alone.

The encrypted backup and both earlier JARs remain under the ignored backup
directory. The separate restoration container was used only for verification.
No .env read, Gmail cleanup mutation, commit or push was performed. For ordinary
future starts, omit the opt-in benchmark flag so experiments do not repeat.
