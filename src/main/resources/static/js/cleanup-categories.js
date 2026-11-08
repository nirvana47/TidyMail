/** Expands sender categories and manages category-edit selection. */
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
 * selected in the category table.
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
    const formId = returnTo === "unread" ? "unread-selection" : "sender-selection";
    document.querySelectorAll(`input[name='rowKey'][data-selection='${formId}']:checked`).forEach((checkbox) => {
        const row = checkbox.closest(".cleanup-row");
        const senderAddress = row?.dataset.senderAddress;
        if (senderAddress) appendSelectedSender(target, senderAddress);
    });
}

/**
 * Recomputes every sticky selection bar based on the currently selected
 * checkboxes, showing the select-count summary and enabling/disabling actions.
 *
 * Shows the number of senders selected for categorization.
 */
function updateSelectionBars() {
    document.querySelectorAll("[data-selection-bar]").forEach((bar) => {
        if (!(bar instanceof HTMLElement)) return;
        const selected = selectedCheckboxesFor(bar.id);
        const uniqueCount = new Set(selected.map((checkbox) => checkbox.value)).size;
        const hasSelection = uniqueCount > 0;
        bar.hidden = !hasSelection;
        bar.classList.toggle("is-empty", !hasSelection);
        const summary = bar.querySelector("[data-selection-summary]");
        if (summary instanceof HTMLElement) {
            summary.textContent = hasSelection
                ? `${uniqueCount} sender${uniqueCount === 1 ? "" : "s"} selected`
                : "Select senders to change their categorization.";
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
 *   selected when counting or clearing a selection.
 */
function selectedCheckboxesFor(formId) {
    return Array.from(
        document.querySelectorAll(`input[name='rowKey'][data-selection='${formId}']:checked`)
    ).filter((checkbox) => checkbox instanceof HTMLInputElement && !checkbox.disabled);
}

/**
 * Deselects all checked senders in the selection bar's form.
 *
 * Product functionality: the "Clear selection" reset for the cleanup workflow.
 */
function clearSelection(button) {
    const form = button.closest("[data-selection-bar]");
    if (!(form instanceof HTMLElement)) return;
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
 *   category change can apply to exactly those senders.
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
