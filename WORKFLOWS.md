# What people should be able to do in TidyMail

TidyMail helps people find and clean emails in Gmail or Outlook. This document
collects the decisions already made about that work. It describes what should
happen, not a claim that every task has been proved to work.
Requirements collected November 23–26, 2026.

Read the tasks here. The [test review](WORKFLOW-TEST-REVIEW.md) shows where each
expectation came from, what existing tests check, and what they still miss.
The numbers let us refer to an expectation without repeating the whole paragraph.

## 1. Connect the right mailbox

**1.1** Connect Gmail or a personal Outlook account and see which email account is
open. Browsing, selections, rules and results belong to that account. Switching
accounts must not carry an action or private information into another mailbox.
**1.2** Returning to TidyMail should reuse the downloaded mail and check for
changes, rather than start the whole download again.
**1.3** If sign-in or permission expires, explain what needs reconnecting. Keep
downloaded mail and completed work. Request extra rule or calendar permission
when that task needs it; do not lose a saved rule draft along the way.
**1.4** Protect downloaded mail and account access. Copying the database alone
should not reveal readable mail or usable access tokens. This protection is not
fully in place: mail details are not separately encrypted today. An attacker
controlling an unlocked laptop can also use data while the app is using it. The
[test review](WORKFLOW-TEST-REVIEW.md#downloaded-mail-privacy) explains the current limits.

## 2. See recent mail while the rest downloads

**2.1** For both Gmail and Outlook, download recent Inbox emails first and show
them as soon as a batch is ready. Keep downloading the rest in the background;
do not make the person wait for the entire mailbox before using TidyMail.
**2.2** Show downloaded and remaining emails, a progress bar, and an approximate
time remaining. Use totals for the same mailbox/folders being downloaded, the
observed download rate, and provider rate limits or waiting periods. Update the
estimate as these change. If there is not enough information, say “Calculating”
or “Estimate unavailable” rather than invent a finish time.
**2.3** New mail and changes made in Gmail or Outlook should appear without
waiting for all older mail. A failed download can continue from saved progress.
**2.4** Give user-requested cleanup actions priority over background downloading.
Downloading older mail must not use all available provider capacity and leave a
person waiting to mark, archive, delete or file the emails they selected.

## 3. Find the emails you want to clean

**3.1** Start with Inbox. All mail includes archived received emails and excludes
Spam/Junk, Trash/Deleted Items and drafts. Ordinary received-mail views exclude
Sent-only mail; the large-email review has the separate scope described in task 12.
**3.2** Search, dates, unread and available label filters must narrow the actual
emails. Counts, sender details, selections and action review must agree about
which emails match. Dates mean the selected calendar days in the user's timezone.
**3.3** Show active filters and let people remove one or clear them. An invalid
date or failed request should explain the problem where the person is working.
Correcting it should restore results and clear the old error. An unavailable
result must not look like a genuine zero matches.

## 4. Browse and open an email

**4.1** Sort senders by message count, unread count or latest message, then page
through every matching sender. Apply the sort before dividing results into pages.
Changing page size must not make senders unreachable or repeat them across pages.
**4.2** Open a sender, its addresses and its emails without selecting them for an
action. Sender pages and email pages move independently. Keep the detail pane
usable, with readable subjects, addresses and dates.
**4.3** Open an individual email in the connected Gmail or Outlook account.
Loading failures should offer a way to try again. A late response for a previous
sender must not replace the sender currently being viewed.

## 5. View senders by domain or email address

**5.1** Show the sender name supplied with the email by Gmail or Outlook. If no
name is available, show the email address. Do not invent a company name or infer
a person's identity from similar wording. For a domain row containing different
people, the domain itself is a clear label; opening it shows each sender's name
and address.
**5.2** Keep the automatic domain view. For example, emails from different people
at `getrocket.ai` can appear under that domain. Use the sender's actual email
address/domain, not similarity between names. Do not put unrelated Gmail or
Outlook users together just because their addresses end in the same public email
service. Existing automatic mailing-list handling uses the List-ID header carried
by the email, not a user-created saved group.
**5.3** Keep the Sender address view so a person can inspect and select one
specific sender. Remove manually combined, split or renamed saved groups and
their controls, application code and tests. A domain row is an automatic way to
browse mail, not a saved group or a rule in Gmail/Outlook. Future-email rules
continue to use explicitly chosen addresses or domains.

## 6. Select the exact emails

**6.1** Tick a domain row, a sender address, or individual emails and show how
many emails are selected. If only 3 of a sender's 10 matching emails are selected,
show a dash in that address's checkbox in Sender addresses, rather than a full
tick. Unticking an email must update that checkbox and the selected count.
**6.2** Use one shared action toolbar for parent sender rows, child addresses and
individual emails. Review their combined selection, counting overlapping emails
once. Clear selection clears all selected rows and messages.
The left/right arrows only change the page being viewed. They do not
select emails. Tick the rows or emails you want; ticking a second sender address
adds its matching emails to the first selection. The checkbox above the sender
list selects the sender rows currently shown. “Select all matching senders” is a
separate choice for all result pages. These are existing checkbox controls, not
a feature for selecting page numbers.
**6.3** Suppose you selected 20 emails. If 5 new emails arrive or a slow request
finishes afterward, the selected count must not quietly become 25. You choose
whether to add them. Opening rule setup and returning to the mailbox should keep
the same selected emails and filters. Starting a new selection or clearing it
must not change any mail.

## 7. Review before changing mail

**7.1** Offer Mark as read, Archive, Mark as read + archive, Move to Trash or
Deleted Items, and Apply a label or Move to a folder. Choosing an action only
opens review; it does not execute it.
**7.2** Review shows the account, action, exact selected emails, affected count,
protected emails and emails that already satisfy the action. Inspect the target
list before confirming. Explain which emails will be skipped and why. Archive
does not free storage; moving to Trash is not permanent deletion.
**7.3** Back or close changes nothing. Confirm starts only the reviewed action
on those emails. New arrivals cannot join it. Relevant changes to protections,
filters or the review require a fresh review. Repeated confirmation must not
start the same work twice. There is no TidyMail Undo promise.

## 8. Follow cleanup through to its result

**8.1** After confirmation, keep the app usable and show real progress through
completion, including selections of 100+ emails. Reloading or reopening the app
must find the same job and preserve completed work.
**8.2** Cancel stops work that has not been sent. An already sent batch may finish;
say so. Cancellation must not reverse completed changes. Closing a progress
panel is not cancellation.
**8.3** Show changed, skipped, failed, cancelled and still-uncertain results in
Activity. Retry temporary failures only a limited number of times, with a delay
when the provider asks for one. After that, stop retrying and explain what did
not finish and what the person can do next. Never leave an endless retry/spinner.
The proposed dedicated failed-email retry screen is removed from scope. Task 14 records the requested shared Activity error view.
**8.4** Keep the normal success path fast: send the batch action and use Gmail's
or Outlook's successful response. Do not add a read-after-write check for every
email. Only when the response is missing or unclear, check the affected emails
before deciding whether another action is needed. These recovery checks also
have a limit; unresolved emails must be reported instead of checked forever.

## 9. Put selected emails in a label or folder

**9.1** For Gmail, apply a label and leave the emails in Inbox unless Remove from
Inbox was chosen. For Outlook, move emails into the chosen folder. Show the
destination name and explain whether the emails leave Inbox.
**9.2** Choose an existing destination explicitly or give a new one a name. Show
Outlook folder paths. If a name already exists, ask the person to select that
existing destination. Saving, previewing or backing out must not create a label
or folder. Creation happens only after final confirmation.
**9.3** File only the reviewed emails; keep unrelated labels and unselected mail
untouched. Filing existing mail does not create a future-email rule. For example,
if Gmail creates “Offers” but its reply never reaches TidyMail, do not create a
second “Offers” label automatically. If Outlook may already have moved an email,
check its folder before sending the move again. If the result cannot be confirmed,
show what is unknown and the next step.

## 10. Create a rule for future emails

**10.1** Start from selected emails, senders or New rule. Choose sender addresses
or domains, optional subject phrases and exceptions. The review must say which
emails will match. If Gmail or Outlook cannot apply those choices as shown, stop
before creating anything and explain which choice needs changing. Do not silently
create a broader rule. Check this with matching and nonmatching example emails,
including an excluded sender or subject, through the actual rule-building code.

**10.2** Choose an existing Gmail label or Outlook folder, or enter a new name.
Saving a draft, viewing examples or returning to edit must not create the label,
folder or active rule. Only the final Create rule action may create them. A test
should follow these steps and check that no provider creation request was sent
before confirmation, then check the created rule's actual destination.

**10.3** Show the rule in a plain sentence before creation. Returning to edit keeps
all choices. Changing a choice requires a new review. Create rule affects future
emails. Filing emails already downloaded is a separate selection, review and
confirmation; creating a rule alone must not move them.

## 11. Edit, stop or remove rules

**11.1** Show rules created through TidyMail and rules already found in Gmail or
Outlook. Say where each came from and whether it is running. Before changing a
rule created elsewhere, ask the person to explicitly choose to edit it in TidyMail.
If TidyMail cannot read and preserve all of that rule's conditions/actions, explain
that it must be edited in Gmail or Outlook. This is different from rejecting a
new unsupported rule in task 10.1; a settings link is optional, not a requirement.

**11.2** Make each action's effect clear. Edit changes the chosen fields. Pause
stops future matching emails being filed; Resume starts that again. Remove stops
and removes the future-email rule. Delete draft removes an uncreated draft only.
None of these actions moves previously filed emails back or deletes their label
or folder. Tests check the saved/native rule and future handling, rather than
merely checking that explanatory text exists.

**11.3** Adding an address keeps the existing sender choices and adds that address.
Keep the rule name, subject phrases, exceptions, destination, Remove from Inbox
and Mark as read choices unchanged. Editing any one field must preserve every
field the person did not change. Test using a rule with those other fields filled
in, save the edit, then read the resulting provider rule and compare the fields.

**11.4** When saving fails, keep the person's entered changes available to edit or
retry. Keep showing the last rule Gmail/Outlook confirmed, alongside the failed
change; do not pretend the change succeeded. If the reply was lost, check the
provider before repeating creation or an update. Show what is being checked and
what the person can do if it cannot be resolved. Tests simulate rejection and a
lost reply, then check retained inputs, rule state and absence of duplicate rules.

**11.5** Keep rule results understandable. A destination containing 100 old emails
and 3 newly observed matching emails may show 103 emails there and 3 newly observed
matches. Do not call all 103 emails “moved by this rule”: another rule or the person
may also put mail there. Test those counts with a destination that already contains
mail. Rules run inside Gmail/Outlook even when TidyMail is closed. Pausing/removing
a rule stops future work; tests must also check that old filed mail stays put.
No new 7-day/14-day report or date selector is specified here. Its layout and exact
meaning have not been agreed; earlier mentions must not become invented tests.

## 12. Find large emails and calendar invitations

**12.1** Large-email review means emails larger than 1,000,000 bytes. Include
incoming, archived and Sent emails for both Gmail and Outlook. Exclude Spam/Junk,
Trash/Deleted Items and drafts, and count an email only once if it appears in more
than one folder/view. Show this scope beside the filter. Check incoming and Sent
examples for both providers, the exact size boundary, selected bytes and duplicate
membership. Outlook Sent support is a feature requirement, not a completed check.

**12.2** Mail is downloaded as metadata, not full bodies and attachment files.
Size can be missing because it has not been fetched yet, a request failed, or the
provider did not supply it. Outlook's separate size lookup can also be pending.
Show unknown size rather than zero. Retry temporary lookup failures in the
background a limited number of times. Report pending, retrying and final failures
in Activity (task 14). Do not promise a size when the provider cannot supply one.

**12.3** Keep useful local and provider labels available for filtering. Different
labels may apply to the same email. Opening a view, downloading data or assigning
an internal label must send no mailbox-changing requests. Only a user-confirmed
action, or a native Gmail/Outlook rule the user enabled, changes their mailbox.
Tests check those requests are absent while browsing and present only after the
specific confirmation. Remove automatic Consider Trash recommendations and their
filters/code/tests rather than inventing inputs that make them look functional.

**12.4** Let people find calendar invitation emails and filter by checked response
when available. Also record the requested past-event label: an invitation for an
event that has ended can be a cleanup candidate even if the person never responded.
Use the event's actual time and relevant recurring occurrence, not the email date;
an old invite may describe a future event. An unclear event/time stays unknown.
The person chooses and confirms bulk cleanup. Checking a response never sends an
RSVP or changes the event. Tests should use past, future, recurring and unclear
events through the calendar-to-label-to-filter path. The past-event label is new
feature work; current invitation/response checks do not prove it exists.

## 13. Leave, return and use the app comfortably

**13.1** Signing out keeps downloaded mail. Updating or restarting TidyMail keeps
accounts, useful local/provider labels, rules, completed actions and uncertain work.
Deleting local data is a separate confirmation that states which accounts/data it
affects and that provider rules remain. Removing the unused AI work is a specific
exception: delete its internal results/labels, without deleting downloaded mail,
native Gmail/Outlook labels/folders/rules or confirmed cleanup work.

**13.2** Use the supported desktop screens with readable controls, keyboard access
and visible feedback. Keep dialogs short. Closing a pane or returning from Rules
should preserve the mailbox context; warn before discarding unsaved rule edits.

**13.3** Collect only the information needed for the chosen task. Ordinary cleanup
must not download full bodies or attachment files. Remove the Jev/OpenRouter model
client, model classification, paid-work controls and associated tests/experiments.
There is no paid email analysis to restart. Errors must not expose credentials or
private message content. Applied historical migrations remain unchanged; a new
migration removes the unused internal model data.

## 14. See progress, results and problems in Activity

**14.1** Keep in-app Activity/analytics. Show download progress, last successful
refresh, and cleanup totals: requested, changed, skipped, failed, cancelled and
still uncertain. Only show the open account's results. Check these numbers against
saved download/action records, including a partially completed action and reload.

**14.2** Record the requested shared error/retry view: say what failed (download,
size lookup or a confirmed action), which account/job it belongs to, completed and
remaining counts, attempt count, next retry time when scheduled, and what to do
when retries stop. For example, if 50 of 100 changes completed, show those 50 as
done while the other 50 wait for retry. Respect provider waiting periods; five
minutes is an example, not a fixed rule for every error. Tests should advance a
controlled retry schedule and check both the saved state and what Activity shows.
This view is feature work, not the previously proposed dedicated failed-email
retry screen. Do not add an empty screen or tests that only check its heading.

## What this does not restart or promise

Remove model classification, automatic Consider Trash and manual saved sender
groups from application code and their tests. Preserve automatic domain/address
browsing, explicit cleanup, native rules and independent local/calendar labels.
Unsubscribe, mobile work, full email reading, permanent deletion and automatic
learning from cleanup remain outside this effort.

The test review distinguishes features that exist, checks that actually ran and
new feature requirements. Earlier date, cleanup-speed and rule-update incidents
show missed coverage; they are not a request to redo already repaired features.
