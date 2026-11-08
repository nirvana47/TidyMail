# Legacy action cutover

## Engine removal

The removal build assumes the intake-closure cutover below has already run. It has no startup sweep. Before replacing the binary, inventory the ledger with a read-only connection. Check forward and undo executions separately, including active reservations, unexpired undo windows, and dispatched targets still awaiting verification. If unresolved work remains, review its exact targets and dashboard overlap before rollout. Keep the ledger and protections in place.

Build separately, stop every old instance, and take and verify an encrypted backup before installing the removal build. Retain the previous intake-closed binary for rollback. Use the normal local launcher and check health, dashboard access, and that the legacy ledger and sender protections match the pre-start snapshot. No schema migration or provider reconciliation is required for an empty ledger.

## Earlier intake-closure cutover

The intake-closure binary closes legacy forward intake and approval. Its application-ready listener records `CANCELLED` and an `ACTION_CANCELLED` event with reason `NOT_DISPATCHED` for requests proven undispatched. It retains the reservation, target, outcome and undo records. The sweep is idempotent and needs no schema migration.

1. Before starting the new binary against the working database, stop incoming requests to every old TidyMail instance that uses it. Allow active requests to finish and record their outcomes where possible. Disable any supervisor or launcher that would restart an old instance.
2. Stop all old application processes. For a terminal launch with `tools/run-local.sh`, stop it from its terminal with Ctrl+C. For managed launches, use the supervisor's stop command. Check the launcher or supervisor and the application's listening port to confirm that every old instance has stopped. A lease expiry does not establish that a provider request finished. If a process had to be stopped with an unresolved request, retain its dispatch evidence for reconciliation.
3. Start the updated binary only after those checks. The retirement sweep runs automatically at application readiness, including with MVP enabled. Use the normal launcher for the dashboard's sync and cleanup workers.
4. Check the ledger with a read-only connection. Verify `ACTION_CANCELLED` events with reason `NOT_DISPATCHED`, and retain any remaining `EXECUTING` or verification-pending records and exact targets for review. Review `UNDO` executions independently of the parent action's status. An incomplete ledger is insufficient proof for cancellation.

Keep old binaries stopped after cutover. They lack the new dispatch fence and could renew a saved reservation. Provider reads for reconciliation are a separate operation. No automatic resend is part of cutover.
