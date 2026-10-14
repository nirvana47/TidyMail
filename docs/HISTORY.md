# Documentation history

On 2026-10-14 the older planning material was preserved at Git checkpoint **`ea36399`** before removal from the working tree. It was already tracked; the checkpoint is an explicit snapshot and does not include the local login-page redesign.

Removed material: `docs/DECISIONS.md`, `docs/InboxTriage Master Plan.md`, `docs/Progress Check - mid-PRD.md`, and the 241 tracked artifacts under `.planning/` (plans, phase summaries, requirements, research, verification and planning configuration). All are available in that checkpoint; no history was rewritten. Design references under `tidymail-ux-rehaul*` remain because they are useful visual artifacts.

## Read historical documents

```sh
git show 'ea36399:docs/DECISIONS.md'
git show 'ea36399:docs/InboxTriage Master Plan.md'
git ls-tree -r --name-only ea36399 .planning
```

## Recover a document without restoring stale active documentation

```sh
git show 'ea36399:docs/Progress Check - mid-PRD.md' > /tmp/tidymail-old-progress.md
git show 'ea36399:.planning/ROADMAP.md' > /tmp/tidymail-old-roadmap.md
```

Historical test evidence is in `.planning/phases/19-mailbox-freshness-first-run-onboarding-gmail-history-api-syn/19-VALIDATION.md` and `19-VERIFICATION.md` at the checkpoint. The former records 447 passing tests; the latter records the targeted 53-test verification and missing controlled live Gmail acceptance. [Status](STATUS.md) carries forward those distinctions and the unfinished work.

The new [codebase map](CODEBASE.md), [project description](../PROJECT.md), [deployment guide](../DEPLOY.md), [testing guide](TESTING.md) and [status](STATUS.md) replace the active planning documents. Some legacy source comments still name removed requirement/decision documents; use Git for that context rather than treating those comments as current contracts.
