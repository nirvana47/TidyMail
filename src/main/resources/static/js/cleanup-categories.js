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

function currentCleanupCategories() {
    return Array.from(
        document.querySelectorAll(".cleanup-categories > details")
    ).filter((category) => category instanceof HTMLDetailsElement);
}

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

function selectedCheckboxesFor(formId) {
    return Array.from(
        document.querySelectorAll(`input[name='rowKey'][form='${formId}']:checked`)
    ).filter((checkbox) => checkbox instanceof HTMLInputElement && !checkbox.disabled);
}

function clearSelection(button) {
    const form = button.closest("[data-selection-bar]");
    if (!(form instanceof HTMLFormElement)) return;
    selectedCheckboxesFor(form.id).forEach((checkbox) => {
        checkbox.checked = false;
    });
    updateSelectionBars();
}

function appendSelectedSender(target, senderAddress) {
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = "senderAddress";
    input.value = senderAddress;
    target.append(input);
}

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

function refreshRequestToken(form) {
    const token = form.querySelector("input[name='requestToken']");
    if (token instanceof HTMLInputElement && window.crypto?.randomUUID) {
        token.value = window.crypto.randomUUID();
    }
}

function showCleanupError(message, source) {
    announceCleanupFeedback(source, message, true);
}

function announceCleanupFeedback(source, message, visible) {
    const feedback = findOrCreateCleanupFeedback(source);
    if (!(feedback instanceof HTMLElement)) return;
    feedback.textContent = message;
    feedback.classList.toggle("visually-hidden", !visible);
    feedback.dataset.visible = visible ? "true" : "false";
    if (visible) feedback.focus();
}

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
