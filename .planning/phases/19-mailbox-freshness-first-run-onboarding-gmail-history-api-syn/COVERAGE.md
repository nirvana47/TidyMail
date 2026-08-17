# API Coverage — Gmail API and OAuth

> Full coverage by default. Opt-outs are explicit, reasoned decisions. This matrix covers only the Gmail and OAuth surface touched or deliberately excluded by Phase 19.

| capability | decision | reason |
|---|---|---|
| `users.getProfile` mailbox total and current `historyId` anchor | INTEGRATE | |
| `users.messages.list` recent bootstrap (100–250; Spam/Trash excluded) | INTEGRATE | |
| `users.messages.list` complete paginated backfill with `includeSpamTrash=true` | INTEGRATE | |
| `users.messages.get(METADATA)` with exact existing 24-header whitelist | INTEGRATE | |
| `users.history.list` unfiltered events with complete pagination | INTEGRATE | |
| `History.messagesAdded` event handling | INTEGRATE | |
| `History.messagesDeleted` event handling | INTEGRATE | |
| `History.labelsAdded` event handling | INTEGRATE | |
| `History.labelsRemoved` event handling | INTEGRATE | |
| `users.messages.get(format=METADATA)` HTTP 404 as an idempotent local deletion | INTEGRATE | |
| Expired/invalid `startHistoryId` HTTP 404 recovery | INTEGRATE | |
| `messages.list(q="has:attachment")` full-mailbox membership index | INTEGRATE | |
| Managed OAuth access-token refresh and persistence | INTEGRATE | |
| Gmail message mutation methods | OPT-OUT | Phase 19 is a read/sync phase with no provider mutation; existing reviewed cleanup mutations remain behavior-constant outside this phase. |
| `users.messages.get(format=FULL)` | OPT-OUT | `FULL` requests parsed body content and violates the metadata-only privacy boundary in `docs/DECISIONS.md` §6 and §11. |
| `users.messages.get(format=RAW)` | OPT-OUT | `RAW` requests the complete base64url message body and violates the metadata-only privacy boundary in `docs/DECISIONS.md` §6 and §11. |
| Message body, snippet, or MIME payload-content processing and persistence | OPT-OUT | Phase 19 stores whitelisted headers and provider metadata only, so body-derived text must not be fetched or retained. |
| `users.messages.attachments.get` attachment-content fetch | OPT-OUT | Attachment presence is derived from ID-only `has:attachment` membership; downloading content is unnecessary and prohibited by the metadata-only contract. |
| `users.watch`, `users.stop`, and Google Cloud Pub/Sub push delivery | OPT-OUT | `docs/DECISIONS.md` §7 chooses scheduled polling for this product; push remains the separate `PUSH-01` backlog capability. |
| Gmail settings methods | OPT-OUT | Gmail settings scope expansion is explicitly deferred to Phase 22 (`SCOPE-01`/`RULE-01`), not required for Phase 19 synchronization. |
| Gmail filter methods | OPT-OUT | Creating, listing, or deleting Gmail-side filters is the user-initiated reversible rule capability assigned to Phase 22 `RULE-01`. |
| Gmail label-management methods (`labels.list/get/create/update/patch/delete`) | OPT-OUT | Phase 19 consumes label IDs already present on messages; Gmail-side label management belongs to Phase 22 `RULE-01`. |
| Google Contacts / People API | OPT-OUT | `contacts.readonly` and contact-derived classification signals require the scope expansion assigned to Phase 22 `SCOPE-01`. |
| Gmail drafts methods | OPT-OUT | Draft creation and management do not contribute to mailbox freshness, first-run indexing, metadata extraction, or OAuth refresh. |
| Gmail send methods | OPT-OUT | Sending mail is unrelated to Phase 19’s read-only synchronization lifecycle and remains outside this phase’s requirements. |
| Gmail threads methods | OPT-OUT | Phase 19 synchronizes message-level IDs and labels because unread and history changes are message-specific; thread retrieval is unnecessary. |

Official semantics checked against the current Gmail API references for [`users.getProfile`](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users/getProfile), [`users.messages.list`](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list), [`users.messages.get`](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/get), [`users.history.list`](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.history/list), [`Format`](https://developers.google.com/workspace/gmail/api/reference/rest/v1/Format), [synchronization](https://developers.google.com/workspace/gmail/api/guides/sync), and [search/filtering](https://developers.google.com/workspace/gmail/api/guides/filtering), plus Spring Security's [`OAuth2AuthorizedClientManager`](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/core.html#oauth2Client-authorized-manager-provider) contract.
