# Real Gmail check — 2026-10-29

The user requested real core-functionality checks before further phase work.
The retained database was backed up privately, restored into a temporary database
to check recoverability, and upgraded from V15 to V18. The current Java 21 app
is running locally with the existing Gmail account and index.

| Actual check | Result |
|---|---|
| Google connection | Existing account authenticated successfully; connection and index survived restart |
| User-selected Move to Trash | Eight exact selected Inbox messages previewed as eight eligible, zero protected/skipped |
| Execution | Completed: eight changed, zero failed/unresolved/cancelled/remaining |
| Independent Gmail observation | All eight selected subjects were visible in the actual Gmail Trash view |
| Indexed reconciliation | All eight exact target rows have TRASH; none remain eligible for either dashboard scope |
| Sender browsing after refresh | Inbox snapshot: 15,693 messages in 1,359 groups; largest group contains 1,003 messages |
| Search and scopes | Sender search returned matching mail; All received included more matching messages than Inbox |

## Live defect and repair

The upgraded index initially showed almost every retained message as a separate
sender: 17,529 identities awaited fresh From-header evidence. A read-only metadata
refresh was requested without deleting the index or changing Gmail messages.
It then exposed a real restart bug: persisted QUEUED sync rows were returned by
`requestLane` without being submitted to the new process's executor. An orphaned
queue could remain stuck indefinitely despite later sync requests.

`IncrementalSyncJob` now dispatches existing queued work as well as newly created
work. Existing lane leases prevent concurrent duplicate execution. The focused
Gmail backfill-recovery journey now starts with a pre-existing queue row and passed;
the app was rebuilt and restarted with the fix. No broad regression suite was run.

Actual retained identities awaiting refresh fell from 17,529 to 84, and real
multi-message sender groups appeared. The scan continues in the background;
remaining missing/ambiguous evidence is not guessed. The sender page currently
needs a reload to reflect background sync changes when no cleanup job changes.
Automatic idle-dashboard refresh is a remaining usability gap.

Only the user's eight selected messages were mutated. Archive, Mark as Read,
large live jobs and live cancellation were not exercised in this check. No
permanent deletion, commit, push or deployment to an external service occurred.
Mailbox addresses, subjects, provider IDs, credentials and private backup locations
are intentionally excluded from this report.
