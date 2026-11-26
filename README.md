# TidyMail

TidyMail helps people clean Gmail and personal Outlook mailboxes. Download email
metadata, browse by domain or sender address, filter, select exact emails and
confirm cleanup. Apply Gmail labels, move Outlook emails into folders, or create
native rules for future emails. Activity shows download and cleanup progress.

Use [local setup](DEPLOY.md), [product behavior](PROJECT.md),
[workflows](WORKFLOWS.md), [test accounting](WORKFLOW-TEST-REVIEW.md) and
[test commands](TESTING.md).

Manual saved sender groups, Jev/OpenRouter classification and automatic cleanup
recommendations are removed. Local header/provider labels and calendar response
checks remain. No mailbox action is authorized by a label alone.

Start an installed package with `./tools/run-local.sh`. It enables background
sync, cleanup and local labels, validates migrations, and keeps a build-specific
JAR under `.local-runtime/`. A running app needs a coordinated restart to use a
new package. Ordinary logout retains downloaded mail; local-data deletion is a
separate confirmation. Removing local data does not remove native mailbox rules.

Downloaded subjects and addresses are currently readable database fields. Token
encryption does not protect them. See the [privacy gap](WORKFLOW-TEST-REVIEW.md#downloaded-mail-privacy).
Implementation and simulated tests are not proof of live provider behavior.
Historical reports under `docs/` describe their dates, not current feature scope.

Licensed under [GPL v3](LICENSE).
