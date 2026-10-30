# Jev classification

User-authorized scope change, 2026-10-29: optional hosted classification through
OpenRouter is now allowed. This supersedes the earlier non-AI restriction only
for classification and sender organization. It does not authorize automatic
mailbox actions, body/snippet downloads, drafting, or cross-address identity inference.

## Setup and use

Add `OPENROUTER_API_KEY=your-key` to the existing local `.env` (already ignored by
Git), rebuild/restart TidyMail, then choose **Classify mail** and start a run.
A missing key disables starting runs without affecting normal cleanup. The key
is deployment-wide; use a dedicated OpenRouter key with a spending limit.
Do not put it in browser JavaScript, screenshots, tracked configuration or chat.

In OpenRouter's account settings, disable private Input & Output Logging,
OpenRouter Use of Inputs/Outputs (the sharing discount), Broadcast and external
observability destinations. Enable ZDR for **All other models**. These settings
are account-owned: the application cannot inspect or change them. They matter
even when requests require a zero-retention inference provider.

The classifier pins `typesafe/jev-1.14` and provider `typesafe`, with
`provider.zdr=true`, `provider.data_collection=deny`,
`provider.allow_fallbacks=false`, and `X-OpenRouter-Cache: false`. It never uses
an alternate model/provider when that route is unavailable. HTTP redirects are
disabled so a redirect cannot forward credentials elsewhere. No tools, plugins,
trace/session/user identifiers or arbitrary external URLs are submitted.
OpenRouter retains usage metadata and documents legal/CSAM screening exceptions;
ZDR is a published policy, not a claim that no processing or logging ever occurs.

Sources verified 2026-10-29:
[Decisions API](https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-request),
[Jev](https://openrouter.ai/docs/guides/community/jev),
[ZDR](https://openrouter.ai/docs/guides/features/zdr),
[data collection](https://openrouter.ai/docs/guides/privacy/data-collection),
[response cache controls](https://openrouter.ai/docs/guides/features/response-caching).
No live key or private mailbox data was required to build the integration.

## What is classified

An explicit run snapshots existing eligible received-message IDs across Inbox and
archived mail, excluding Spam, Trash, drafts, sent-only mail, unknown label
eligibility, and ambiguous From identities. It ignores dashboard search/date/category
filters, and the dialog states that scope. It sends at most five messages per
request, each with the exact parsed sender address, sender display name (120
characters), subject (300 characters), five standard Gmail category hints,
boolean mailing-list/unsubscribe evidence and boolean References/In-Reply-To
evidence. Internal message/account IDs, recipients, custom labels, OAuth tokens,
bodies, snippets and attachments are not sent. Reply-header evidence does not
prove that the account owner has replied to or knows the sender.

Each email gets three independent, fixed-choice questions:

| Dimension | Stored values |
|---|---|
| Message purpose | PERSONAL, MARKETING, NEWSLETTER, RECEIPT, SECURITY, NOTIFICATION, OTHER, UNCERTAIN |
| Sender kind | PERSON, AUTOMATED, RELAY, UNKNOWN |
| Service stream | GENERAL, VOICE, AI_PRODUCT, DEVICE_SUPPORT, SECURITY, BANKING, SHOPPING, TRAVEL, SOCIAL, WORK |

Categories have stable application IDs. Jev selects them; it cannot create a
category named after a person or invent spelling variants. Different display
names or addresses never implicitly merge personal identities.

For the three-message example:

| Message | Sender identity | Possible category |
|---|---|---|
| Shivani at gene.com | Exact work address | PERSONAL |
| Amazon | Exact address within an optional automated Amazon group | RECEIPT or MARKETING, depending on this message's metadata |
| Shivani at gmail.com | Exact Gmail address, independent of the work address | PERSONAL |

`shivani.tewari20166@gmail.com` is another independent address. A shared name,
nearby spelling, or matching category is not identity evidence. A user can still
explicitly merge or separate groups using the existing correction flow.

## Architecture

```mermaid
flowchart LR
  M[Indexed metadata] --> Q[Persisted run and message targets]
  Q --> J[Jev via OpenRouter: five messages per request]
  J --> V[Validate provider, model, choices and confidence]
  V --> S[Save labels and evidence fingerprint]
  S --> F[Shared dashboard category filters]
  S --> G[Rebuild automatic groups, then replay manual corrections]
  F --> P[User selection and exact action preview]
```

Remote I/O runs outside database transactions and independently of cleanup/sync.
A persisted lease token fences each batch; interrupted runs can resume after
restart. Cancel prevents further application of results, including late replies,
although an already-sent request may finish and be billed. Existing saved
categories remain. Network/rate/provider failures retry at most three times per
batch with a 30-second delay; authentication, credit and malformed-output errors
stop with a generic actionable message. Expired leases count toward the same
attempt limit. Failed/cancelled runs can be restarted; unchanged saved results
are reused. Requests interrupted after dispatch can be charged more than once
on recovery; this integration does not claim exactly-once billing.

Results are matched to message IDs locally. A fingerprint covers exactly the
bounded metadata and normalized hints used by this classifier version; a trigger
refreshes it on metadata writes. Changed inputs are skipped if they change in
flight, and display as Unclassified rather than reusing stale categories. A later
explicit run handles new or changed messages. The pinned model and question-set
version are also part of cache validity. No automatic mailbox-wide reprocessing
runs just because the key is configured. Manual per-message category overrides
survive new classification and can be reset separately.

Both reported confidence and the chosen option's probability must reach 0.85.
Otherwise purpose becomes UNCERTAIN, kind UNKNOWN, or stream GENERAL. This is an
initial conservative operating threshold, not a measured 85% accuracy guarantee.
Malformed distributions, unknown values, unexpected provider/model and incomplete
responses are rejected; raw remote bodies and exceptions are never shown/logged.
Only normalized labels, conservative confidence, model/version, timestamp,
fingerprint, run counts and provider-reported cost are retained locally.

After a successful run, ADDRESS units can group by registrable domain plus
service stream only when all their messages have fresh, confident, consistently
automated classifications. People, relays, mixed/uncertain senders and shared
provider domains retain exact addresses. Google Voice encoded addresses are
explicitly excluded from organization merging even if the model guesses wrong.
LIST boundaries remain intact. Saved manual grouping events are replayed over
the new baseline; a conflict rolls back that grouping transaction instead of
dropping a correction. Confirmed cleanup targets never change. Grouping/category
revision changes invalidate drafts and unconfirmed previews.

All dashboard counts, paging, address expansion, selection and action eligibility
use the same category predicate. A category can therefore isolate Amazon offers
without including its receipts. Categories never choose or execute cleanup actions.

## Accuracy and limitations

Metadata alone cannot reliably distinguish every receipt, promotion, work message
or personal conversation. Missing metadata, misleading display names, mixed-purpose
senders, forwarded mail, unsupported languages and new services can produce errors.
Gmail's categories are hints rather than truth. Incoming email fields are untrusted;
question instructions explicitly prohibit obeying embedded instructions. This
reduces instruction injection risk but is not a model accuracy guarantee.

The next meaningful evaluation is a small user-labeled, representative sample:
people's work/personal addresses, Chase variants, Google service streams, receipts,
promotions, newsletters and uncertain cases. Compare false merges and wrong
categories for both accepted and abstained decisions before lowering thresholds.
Prefer saved corrections and improvements to the shared question set over lists
of special rules for individual people. Richer content, contact access, historical
reply analysis or body/snippet processing requires a separate scope decision.

## Validation — 2026-10-30

The Java 21 package build passed with 72 focused tests (zero failures/errors/skips)
covering classification, identity, dashboard journeys, V15/V17/V19 upgrades,
cleanup jobs, resilience and performance. Classified-category browsing passed at
10k/50k messages (p95 41.86/101.59 ms in this run); ordinary browsing also passed
with concurrent metadata writes and cleanup. JavaScript syntax and diff checks passed.

The isolated desktop fixture classified 364 synthetic messages with simulated Jev
responses. UI checks covered start/progress across reload, completion, work/Gmail
addresses staying separate under Personal, Amazon Marketing excluding receipts,
and a selected-message category override. These verify application behavior, not
Jev's real-world categorization accuracy. No live Jev call, retained-database
migration, running-app restart, commit, push or deployment was performed here.
A representative user-labeled live sample remains the next accuracy check after
local key and account privacy settings are configured. The full test suite was
not run.
