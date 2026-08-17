# 17-01 Summary - Bulk Unsubscribe Interaction And Stats Tracer

**Status:** Complete, with Docker/Testcontainers performance verification blocked by local Docker availability.

## Implemented

- Loaded the HTMX runtime on `unsubscribe.html` and added local modal behavior for focus entry, close button, Escape close, focus return, and HTMX error feedback into `#unsubscribe-inspection-slot`.
- Wired Bulk Unsubscribe row controls so `View stats` targets `#sender-stats-modal-slot` with loading feedback and native `href` fallback, while primary `Unsubscribe` targets `#unsubscribe-inspection-slot` with visible `Preparing...` pending feedback and accessible failure output.
- Reworked `email-stats-modal-frag.html` into an in-page `.stats-modal-overlay` / `.stats-modal` dialog with `role="dialog"`, `aria-modal="true"`, accessible title, close control, sender context, selected range, modal action row, distribution chart, and compact email metadata table.
- Moved modal styling into `workspace.css`, including responsive 375px/zoom-friendly stacking and internal modal scrolling.
- Replaced the unbounded sender stats path with explicit aggregate count, capped aggregate bucket query, and capped recent-email table query.
- Added `Phase17BulkUnsubscribeTest` coverage for HTMX wiring, modal structure, no inline modal styles, no workspace shell in the fragment, bounded service calls, and repository source contracts.

## Verification

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn clean -Dtest='BulkUnsubscribeContractAlignmentTest,Phase14InteractionFidelityTest,Phase17BulkUnsubscribeTest' test`
  - Passed: 9 tests, 0 failures, 0 errors.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase17BulkUnsubscribeTest,Phase15UnsubscribePerformanceTest' test`
  - Blocked by environment for `Phase15UnsubscribePerformanceTest`: Testcontainers could not find a valid Docker environment (`/var/run/docker.sock` missing; Docker Desktop socket unavailable).
  - `Phase17BulkUnsubscribeTest` passed in this run.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase17*Test,Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase15UnreadSemanticsTest,Phase15StagingTest,BulkUnsubscribeContractAlignmentTest,Phase12ReviewWebTest' test`
  - Passed: 52 tests, 0 failures, 0 errors.

## Deviations

- None from implementation scope.
- Docker/Testcontainers verification could not run because Docker is not available in this environment.
