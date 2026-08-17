# Phase 17 Verification

Generated: 2026-08-13T14:18:10Z
Status: complete — automated verification passed and UI accepted 2026-08-13

## Automated Verification

| Gate | Command | Result |
|---|---|---|
| Focused Phase 17 and inherited safety suite | `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn -Dtest='Phase17*Test,Phase16WorkspaceFidelityTest,Phase15WorkspaceWebTest,Phase15UnreadSemanticsTest,Phase15StagingTest,BulkUnsubscribeContractAlignmentTest,Phase12ReviewWebTest,Phase11UnsubscribeWebTest' test` | PASS: 74 tests, 0 failures, 0 errors, 0 skipped |
| Docker/Testcontainers full Maven suite | `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH mvn test` | PASS: 423 tests, 0 failures, 0 errors, 0 skipped |
| Docker discovery | `docker info`; full suite logs | PASS: Docker context `colima`; Testcontainers resolved `dockerHost=unix:///Users/bytedance/.colima/default/docker.sock` |
| Forbidden UI source scan | `rg -n -i "export|csv|snapshot|download|share|public tunnel|unpkg.com|cdn.jsdelivr|Select senders first, then choose where they belong|workspace-download|analytics-download" src/main/resources/templates src/main/resources/static/css src/main/resources/static/js` | PASS with one privacy-copy exception: login text says TidyMail never downloads email bodies |

## Requirement Evidence

| Requirement | Evidence |
|---|---|
| UX-09 | `Phase17RailChromeTest`, `Phase16WorkspaceFidelityTest`, and authenticated UAT confirm visually text-only rail labels, clickable brand chrome, utility alignment, and collapse affordance. |
| UX-10 | `Phase17UnreadOnlyTest`, `Phase15UnreadSemanticsTest`, and source scan confirm page-level `Inbox unread`, Inbox-only predicates, and stale note removal. |
| UX-11 | `Phase17CleanupCategoryTest`, `Phase17UnreadOnlyTest`, and `Phase15StagingTest` confirm exact-ID staging and no provider mutation before Review approval. |
| UX-12 | `Phase17UnreadOnlyTest` and `Phase15UnreadSemanticsTest` confirm archived All Mail unread rows are excluded from Unread Only totals/groups. |
| UX-13 | `Phase17BulkUnsubscribeTest`, `Phase17ReviewHistoryTest`, `Phase17RailChromeTest`, and responsive/source assertions cover density, local feedback, and compact history. |
| UX-14 | `Phase17CleanupCategoryTest` confirms user-created category controls remain reachable and `User generated` marking is retained. |
| UX-15 | Focused suite and browser UAT cover latest feedback; unresolved authenticated rows remain in `17-UAT.md`. |

## Notes

- The first full-suite run failed because Testcontainers did not infer Colima's Docker socket from the Docker CLI context. Rerunning with `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` passed.
- A stale external HTMX CDN reference was removed by introducing local `src/main/resources/static/js/htmx-lite.js` and updating authenticated templates/tests to use it.
- A browser console issue from `htmx.ajax(..., { target: element })` was fixed by allowing the local shim to accept either selector strings or Element targets.
- No credentials, tokens, provider IDs, message bodies, attachment content, or raw sensitive screenshots were stored in this artifact.
- Subsequent authenticated walkthroughs verified the sender stats modal and its
  graph/table refinements. The user accepted the current UI and closed further
  Phase 15/16 visual work on 2026-08-13.
- Functionality and data-quality issues are intentionally deferred to a new
  phase and do not keep this UI verification open.
