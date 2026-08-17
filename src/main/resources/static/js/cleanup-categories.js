/**
 * Client-side behavior for the TidyMail Cleanup and Senders/Unread category pages.
 *
 * Product functionality supported: the interstitial "Review" stage of the bulk
 * cleanup workflow. These pages group messages by sender, let the user select
 * senders and stage an exact/counted/previewable/reversible bulk action
 * (archive, trash, mark-read, unsubscribe, or delete), and surface the grouped
 * Senders and Unread categories. This script wires up the sticky selection bar
 * (counts selected senders, enables/disables action buttons), lazy-loads category
 * contents on expand, drives the modal dialogs (bulk action confirmation, delete
 * source confirmation), and reconciles the Review view after an action is staged
 * so already-staged senders can't be double-counted.
 */
document.addEventListener("DOMContentLoaded", () => {
    updateSelectionBars();

    document.querySelectorAll("[data-category-toggle-all]").forEach((button) => {
        button.addEventListener("click", () => {
            const liveCategories = currentCleanupCategories();
            const expand = liveCategories.some((category) => !category.open);
            liveCategories.forEach((category) => {
                if (expand) {
                    openCategory(category);
                    return;
                }
                category.open = false;
            });
            button.textContent = expand
                ? button.dataset.collapseLabel
                : button.dataset.expandLabel;
        });
    });

    document.addEventListener("toggle", (event) => {
        const category = event.target;
        if (!(category instanceof HTMLDetailsElement)
                || !category.matches(".cleanup-categories > details")) {
            return;
        }
        const content = category.querySelector(".category-page-content");
        const guidance = category.querySelector(".category-load-guidance");
        if (!(content instanceof HTMLElement)) return;
        if (!category.open) {
            content.replaceChildren();
            if (guidance instanceof HTMLElement) guidance.replaceChildren();
            category.dataset.loadedCount = "0";
            return;
        }
        if (!content.hasChildNodes()) loadInitialCategory(category, content);
    }, true);

    document.addEventListener("click", (event) => {
        const openButton = event.target.closest("[data-dialog-open]");
        if (openButton instanceof HTMLButtonElement) {
            const dialog = document.getElementById(openButton.dataset.dialogOpen);
            if (!(dialog instanceof HTMLDialogElement)) return;
            const senderInput = dialog.querySelector("[data-dialog-sender]");
            if (senderInput instanceof HTMLInputElement) {
                senderInput.value = openButton.dataset.senderAddress || "";
            }
            const accountInput = dialog.querySelector("[data-dialog-account]");
            if (accountInput instanceof HTMLInputElement && openButton.dataset.accountId) {
                accountInput.value = openButton.dataset.accountId;
            }
            populateSelectedSenders(dialog, openButton);
            dialog.showModal();
            return;
        }

        const deleteButton = event.target.closest("[data-delete-source]");
        if (deleteButton instanceof HTMLButtonElement) {
            prepareDeleteDialog(deleteButton);
            return;
        }

        const closeButton = event.target.closest("[data-dialog-close]");
        if (closeButton instanceof HTMLButtonElement) {
            const dialog = closeButton.closest("dialog");
            if (dialog instanceof HTMLDialogElement) dialog.close();
            return;
        }

        const clearButton = event.target.closest("[data-clear-selection]");
        if (clearButton instanceof HTMLButtonElement) {
            clearSelection(clearButton);
        }
    });

    document.addEventListener("change", (event) => {
        if (event.target instanceof HTMLInputElement
                && event.target.name === "rowKey") {
            updateSelectionBars();
        }
    });

    document.body.addEventListener("htmx:afterRequest", (event) => {
        const requestElement = event.detail.elt;
        const form = requestElement instanceof HTMLFormElement
            ? requestElement
            : requestElement.closest("form");
        if (!(form instanceof HTMLFormElement)
                || !form.matches("[data-cleanup-stage]")) {
            return;
        }
        if (!event.detail.successful) {
            showCleanupError(
                "Action not staged. Try again or refresh this category.",
                form);
            return;
        }
        try {
            reconcileCleanupResult(JSON.parse(event.detail.xhr.responseText), form);
            const dialog = form.closest("dialog");
            if (dialog instanceof HTMLDialogElement) dialog.close();
            refreshRequestToken(form);
            updateSelectionBars();
        } catch (invalidResponse) {
            showCleanupError(
                "Action not staged. Reload to reconcile the latest Review state.",
                form);
        }
    });

    document.body.addEventListener("htmx:afterSwap", (event) => {
        if (event.detail.target?.closest?.(".cleanup-category")) {
            updateSelectionBars();
        }
    });

    document.body.addEventListener("htmx:responseError", (event) => {
        showCategoryLoadError(
            event.detail.target,
            "Unable to load this category. Try again.");
    });

    document.body.addEventListener("htmx:afterRequest", (event) => {
        const requestElement = event.detail.elt;
        const form = requestElement instanceof HTMLFormElement
            ? requestElement
            : requestElement.closest("form");
        if (!(form instanceof HTMLFormElement)
                || !form.matches("[data-category-organize]")
                || !event.detail.successful) {
            return;
        }
        const dialog = form.closest("dialog");
        if (dialog instanceof HTMLDialogElement) dialog.close();
        window.location.reload();
    });
});

/**
 * Returns all category <details> elements currently present on the page.
 *
 * Product functionality: used by the "Expand/Collapse all" toggle to discover
 * every category element on the Cleanup page without knowing their count ahead
 * of time.
 */
function currentCleanupCategories() {
    return Array.from(
        document.querySelectorAll(".cleanup-categories > details")
    ).filter((category) => category instanceof HTMLDetailsElement);
}

/**
 * Opens a single category <details> element, lazy-loading its content on first
 * display.
 *
 * Product functionality: the expand/collapse-all control calls this to expand
 * categories and ensure their grouped sender list is fetched on demand.
 */
function openCategory(category) {
    if (!(category instanceof HTMLDetailsElement)) return;
    if (!category.open) {
        category.open = true;
        return;
    }
    const content = category.querySelector(".category-page-content");
    if (content instanceof HTMLElement && !content.hasChildNodes()) {
        loadInitialCategory(category, content);
    }
}

/**
 * Fills the hidden sender-address inputs inside a bulk-action dialog from the
 * currently selected senders (or from a single sender when opened from a row).
 *
 * Product functionality: the staging dialogs submit the exact set of senders
 * selected in the Cleanup table, so the backend stages an action only on those
 * senders. Returned to the "unread" category it uses the unread bulk-stage form,
 * otherwise the sender bulk-stage form.
 */
function populateSelectedSenders(dialog, openButton) {
    const target = dialog.querySelector("[data-selected-senders]");
    if (!(target instanceof HTMLElement)) return;
    target.replaceChildren();
    if (openButton.dataset.senderAddress) {
        appendSelectedSender(target, openButton.dataset.senderAddress);
        return;
    }
    const returnTo = dialog.querySelector("input[name='returnTo']")?.value || "";
    const formId = returnTo === "unread" ? "unread-bulk-stage" : "sender-bulk-stage";
    document.querySelectorAll(`input[name='rowKey'][form='${formId}']:checked`).forEach((checkbox) => {
        const row = checkbox.closest(".cleanup-row");
        const senderAddress = row?.dataset.senderAddress;
        if (senderAddress) appendSelectedSender(target, senderAddress);
    });
}

/**
 * Recomputes every sticky selection bar based on the currently selected
 * checkboxes, showing the select-count summary and enabling/disabling actions.
 *
 * Product functionality: keeps the Review/cleanup UI honest about exactly how
 *   many senders will be affected, letting the user review before staging an
 *   action.
 */
function updateSelectionBars() {
    document.querySelectorAll("[data-selection-bar]").forEach((bar) => {
        if (!(bar instanceof HTMLFormElement)) return;
        const selected = selectedCheckboxesFor(bar.id);
        const uniqueCount = new Set(selected.map((checkbox) => checkbox.value)).size;
        const hasSelection = uniqueCount > 0;
        bar.hidden = !hasSelection;
        bar.classList.toggle("is-empty", !hasSelection);
        const summary = bar.querySelector("[data-selection-summary]");
        if (summary instanceof HTMLElement) {
            summary.textContent = hasSelection
                ? `${uniqueCount} sender${uniqueCount === 1 ? "" : "s"} selected`
                : "Select senders first, then choose an action.";
        }
        bar.querySelectorAll("button").forEach((button) => {
            if (!(button instanceof HTMLButtonElement)) return;
            button.disabled = !hasSelection;
        });
    });
}

/**
 * Returns the checked, non-disabled rowKey checkboxes belonging to the given
 * form.
 *
 * Product functionality: the single source of truth for which senders are
 *   selected when counting, staging, or clearing a selection.
 */
function selectedCheckboxesFor(formId) {
    return Array.from(
        document.querySelectorAll(`input[name='rowKey'][form='${formId}']:checked`)
    ).filter((checkbox) => checkbox instanceof HTMLInputElement && !checkbox.disabled);
}

/**
 * Deselects all checked senders in the selection bar's form.
 *
 * Product functionality: the "Clear selection" reset for the cleanup workflow.
 */
function clearSelection(button) {
    const form = button.closest("[data-selection-bar]");
    if (!(form instanceof HTMLFormElement)) return;
    selectedCheckboxesFor(form.id).forEach((checkbox) => {
        checkbox.checked = false;
    });
    updateSelectionBars();
}

/**
 * Appends a hidden input carrying a selected sender address to the given
 * container.
 *
 * Product functionality: serializes the selected senders into a form so a bulk
 *   action can be staged for exactly those senders.
 */
function appendSelectedSender(target, senderAddress) {
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = "senderAddress";
    input.value = senderAddress;
    target.append(input);
}

/**
 * Fetches the first page of a category's grouped sender list on expand,
 * preserving filters but resetting pagination, and swaps it into place.
 *
 * Product functionality: lazy-loads the Senders/Unread category contents one
 *   page at a time so the Cleanup page loads fast and large groups don't block
 *   initial render.
 */
function loadInitialCategory(category, content) {
    const key = category.dataset.categoryKey;
    if (!key) return;
    const configured = category.dataset.categoryUrl;
    const url = new URL(
        configured || `/senders/categories/${encodeURIComponent(key)}`,
        window.location.origin);
    new URLSearchParams(window.location.search).forEach((value, name) => {
        if (name !== "offset" && name !== "loadAll") url.searchParams.set(name, value);
    });
    url.searchParams.set("offset", "0");
    url.searchParams.set("loadAll", "false");

    if (window.htmx) {
        window.htmx.ajax("GET", url.pathname + url.search, {
            target: content,
            swap: "innerHTML"
        });
        return;
    }
    fetch(url, {headers: {"HX-Request": "true"}, credentials: "same-origin"})
        .then((response) => {
            if (!response.ok) throw new Error("Category request failed");
            return response.text();
        })
        .then((html) => {
            content.innerHTML = html;
        })
        .catch(() => {
            showCategoryLoadError(content, "Unable to load this category. Try again.");
        });
}

/**
 * Shows a friendly message inside the category when its contents fail to load.
 *
 * Product functionality: surfaces load failures without breaking the rest of
 *   the cleanup page.
 */
function showCategoryLoadError(target, message) {
    const category = target?.closest?.(".cleanup-category");
    const guidance = category?.querySelector?.(".category-load-guidance");
    if (guidance instanceof HTMLElement) {
        guidance.textContent = message;
        return;
    }
    if (target instanceof HTMLElement && !target.hasChildNodes()) {
        target.textContent = message;
    }
}

/**
 * Prepares the delete-source confirmation dialog by copying the source form's
 * data into a hidden input surface, ensuring at least one sender is selected.
 *
 * Product functionality: the destructive sender-delete flow that requires an
 * exact pre-stage confirmation before any messages are removed.
 */
function prepareDeleteDialog(button) {
    const source = button.dataset.deleteSource === "closest"
        ? button.closest("form")
        : document.getElementById(button.dataset.deleteSource);
    const surface = source instanceof HTMLFormElement
        ? source.querySelector("input[name='surface']")?.value.toLowerCase()
        : "";
    const dialog = document.getElementById(`${surface}-delete-dialog`);
    const target = dialog?.querySelector("[data-delete-fields]");
    if (!(source instanceof HTMLFormElement)
            || !(dialog instanceof HTMLDialogElement)
            || !(target instanceof HTMLElement)) {
        showCleanupError(
            "Action not staged. Delete confirmation could not open. Refresh this page and try again.",
            source instanceof HTMLFormElement ? source : button);
        return;
    }

    const data = new FormData(source);
    if (!data.getAll("rowKey").length) {
        showCleanupError("Action not staged. Select at least one sender before staging.", source);
        return;
    }
    target.replaceChildren();
    data.forEach((value, name) => {
        if (name === "deleteAcknowledged" || typeof value !== "string") return;
        const input = document.createElement("input");
        input.type = "hidden";
        input.name = name;
        input.value = value;
        target.append(input);
    });
    if (!data.has("action")) {
        const input = document.createElement("input");
        input.type = "hidden";
        input.name = "action";
        input.value = button.dataset.deleteAction || "TRASH";
        target.append(input);
    }
    const acknowledgement = dialog.querySelector("[name='deleteAcknowledged']");
    if (acknowledgement instanceof HTMLInputElement) acknowledgement.checked = false;
    dialog.showModal();
}

/**
 * Applies the server response after an action is staged, marking the affected
 * senders' rows as staged and disabling their controls.
 *
 * Product functionality: keeps the Review stage consistent — sender rows that
 * already had a message staged (reused) or were just staged become immutable,
 * and an accessible summary is announced.
 */
function reconcileCleanupResult(result, sourceForm) {
    if (!Array.isArray(result.senders)) {
        showCleanupError("Action not staged. Reload to reconcile the latest Review state.", sourceForm);
        return;
    }

    const senderCount = result.senders.length;
    const message = `${result.distinctMessageCount} exact message`
        + `${result.distinctMessageCount === 1 ? "" : "s"} across ${senderCount} sender`
        + `${senderCount === 1 ? "" : "s"} staged for Review.`;

    result.senders.forEach((staged) => {
        document.querySelectorAll(".cleanup-row[data-sender-address]").forEach((row) => {
            if (row.dataset.senderAddress !== staged.senderAddress) return;
            row.classList.add("is-staged");
            row.querySelectorAll("button, input[type='checkbox']").forEach((control) => {
                control.disabled = true;
            });
            const action = row.querySelector(".cleanup-action");
            if (action) action.textContent = staged.reused ? "Already staged" : "Staged";
            const state = row.querySelector(".cleanup-row-state");
            if (state instanceof HTMLElement) {
                state.textContent = staged.reused ? "Already staged" : "Staged";
            }
        });
    });

    announceCleanupFeedback(sourceForm, message, false);
}

/**
 * Rotates the CSRF/idempotency request token on the form after a successful
 * stage.
 *
 * Product functionality: prevents stale submissions / duplicate staging of the
 *   same sender after a successful action has been accepted.
 */
function refreshRequestToken(form) {
    const token = form.querySelector("input[name='requestToken']");
    if (token instanceof HTMLInputElement && window.crypto?.randomUUID) {
        token.value = window.crypto.randomUUID();
    }
}

/**
 * Displays a non-blocking error message for a failed cleanup action.
 *
 * Product functionality: keeps the user informed when an action could not be
 * staged without discarding the current page state.
 */
function showCleanupError(message, source) {
    announceCleanupFeedback(source, message, true);
}

/**
 * Writes feedback text into the cleanup feedback element and shows or hides it.
 *
 * Product functionality: the status announcements (success counts and errors)
 *   for the staged-action flow.
 */
function announceCleanupFeedback(source, message, visible) {
    const feedback = findOrCreateCleanupFeedback(source);
    if (!(feedback instanceof HTMLElement)) return;
    feedback.textContent = message;
    feedback.classList.toggle("visually-hidden", !visible);
    feedback.dataset.visible = visible ? "true" : "false";
    if (visible) feedback.focus();
}

/**
 * Locates the feedback element for a form, creating one if needed, so
 * announcements show up on every cleanup surface.
 *
 * Product functionality: guarantees there is a visible/accessible status region
 *   (aria-live) for the cleanup workflow regardless of which form triggered it.
 */
function findOrCreateCleanupFeedback(source) {
    const form = source instanceof HTMLFormElement
        ? source
        : source?.closest?.("form");
    if (!(form instanceof HTMLFormElement)
            && source instanceof HTMLElement
            && source.dataset?.deleteSource) {
        const deleteSource = source.dataset.deleteSource === "closest"
            ? source.closest("form")
            : document.getElementById(source.dataset.deleteSource);
        if (deleteSource instanceof HTMLFormElement) {
            return findOrCreateCleanupFeedback(deleteSource);
        }
    }
    const existing = form?.querySelector("[data-cleanup-feedback]");
    if (existing instanceof HTMLElement) return existing;
    const fallback = document.querySelector("[data-cleanup-feedback]");
    if (fallback instanceof HTMLElement) return fallback;
    if (!(form instanceof HTMLFormElement)) return null;
    const created = document.createElement("p");
    created.className = "cleanup-stage-feedback visually-hidden";
    created.setAttribute("role", "status");
    created.setAttribute("aria-live", "polite");
    created.setAttribute("tabindex", "-1");
    created.dataset.cleanupFeedback = "";
    form.append(created);
    return created;
}
