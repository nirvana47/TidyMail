/**
 * A lightweight, self-contained HTMX-style AJAX helper for the server-rendered
 * TidyMail pages.
 *
 * Product functionality supported: powers partial page updates that keep the app
 * feeling responsive. It issues declarative requests from elements carrying
 * hx-get / hx-post / hx-target / hx-swap / hx-trigger attributes, swaps the
 * returned HTML into the DOM, provides polling for live views (e.g. the Inbox
 * activity / Reply Zero queues refreshing while background sync runs), formats
 * server-rendered dates into the visitor's local timezone, and honors CSRF
 * tokens so state-changing requests stay secure. It also exposes window.htmx.ajax
 * for programmatic cases like the Cleanup category lazy-loading.
 */
(function () {
    "use strict";

    const pollingStates = new WeakMap();
    const pollingElements = new Set();
    const temporaryWarning =
        "Activity updates are temporarily unavailable. This does not stop background sync.";

    /**
     * Builds the htmx event name from a suffix (e.g. afterRequest).
     * Product functionality: consistent event naming for the htmx lifecycle hooks.
     */
    function eventName(name) {
        return "htmx:" + name;
    }

    /**
     * Dispatches a bubbling, cancelable htmx: event.
     * Product functionality: lets page scripts hook into request lifecycle events.
     */
    function trigger(element, name, detail) {
        const event = new CustomEvent(eventName(name), {
            bubbles: true,
            cancelable: true,
            detail: detail || {}
        });
        element.dispatchEvent(event);
        return !event.defaultPrevented;
    }

    /**
     * Resolves the default swap target for an element based on its hx-target
     * attribute (supports "closest <selector>" and plain CSS selectors).
     * Product functionality: decides where swapped HTML is inserted.
     */
    function targetFor(element) {
        const selector = element.getAttribute("hx-target");
        if (!selector) return element;
        if (selector.startsWith("closest ")) {
            return element.closest(selector.slice(8)) || element;
        }
        return document.querySelector(selector) || element;
    }

    /**
     * Resolves a target option (selector string or Element) into a DOM node.
     * Product functionality: used by programmatic window.htmx.ajax() calls.
     */
    function resolveTarget(target) {
        if (!target) return null;
        if (typeof target === "string") return document.querySelector(target);
        return target instanceof Element ? target : null;
    }

    /**
     * Parses an "every Ns" polling trigger, capping it between 1 and 300 seconds.
     * Product functionality: the auto-refresh cadence for live activity views.
     */
    function parsePollingTrigger(value) {
        const match = /^every ([1-9][0-9]{0,2})s$/.exec(value || "");
        if (!match) return null;
        const seconds = Number.parseInt(match[1], 10);
        return seconds >= 1 && seconds <= 300 ? seconds : null;
    }

    /**
     * Parses an hx-swap spec (innerHTML/outerHTML/none plus optional settle delay).
     * Product functionality: governs how returned HTML replaces the page region.
     */
    function parseSwapSpec(value) {
        const match = /^(innerHTML|outerHTML|none)(?: settle:([0-9]+)(ms|s))?$/
            .exec((value || "innerHTML").trim());
        if (!match || match[1] === "none" && match[2]) return null;
        const settle = match[2]
            ? Number.parseInt(match[2], 10) * (match[3] === "s" ? 1000 : 1)
            : 0;
        if (!Number.isFinite(settle) || settle > 5000) return null;
        return { mode: match[1], settle };
    }

    /**
     * Reformats <time> elements carrying a data-local-date attribute into the
     * visitor's local timezone.
     * Product functionality: renders server-generated timestamps in local time.
     */
    function formatLocalDates(root) {
        const scope = root instanceof Element || root instanceof Document
            ? root : document;
        const formatter = new Intl.DateTimeFormat(undefined, {
            year: "numeric",
            month: "short",
            day: "numeric"
        });
        scope.querySelectorAll("time[data-local-date]").forEach((element) => {
            const value = element.getAttribute("datetime");
            const date = value ? new Date(value) : null;
            if (!date || Number.isNaN(date.getTime())) return;
            element.textContent = formatter.format(date);
            element.title = date.toLocaleString();
        });
    }

    /**
     * Extracts the CSRF token from the element's parent form, if present.
     * Product functionality: ensures state-changing AJAX requests honor Spring CSRF.
     */
    function csrfFor(element) {
        const form = element.closest("form");
        const field = form && form.querySelector("input[name='_csrf']");
        return field ? field.value : null;
    }

    /**
     * Serializes an element's parent form into URL-encoded body data.
     * Product functionality: sends form-based submissions for POST actions.
     */
    function bodyFor(element) {
        const form = element.closest("form");
        if (!form) return null;
        return new URLSearchParams(new FormData(form));
    }

    /**
     * Finds an element by id within a root subtree.
     * Product functionality: used to restore focus after an outerHTML swap.
     */
    function findById(root, id) {
        if (!id || !(root instanceof Element)) return null;
        if (root.id === id) return root;
        return Array.from(root.querySelectorAll("[id]"))
            .find((element) => element.id === id) || null;
    }

    /**
     * Parses an HTML string into a response root element.
     * Product functionality: builds the swap root for outerHTML replacements.
     */
    function responseRoot(html) {
        const template = document.createElement("template");
        template.innerHTML = html.trim();
        return template.content.firstElementChild;
    }

    /**
     * Stops polling for an element, clearing its timer and focus handler.
     * Product functionality: cleanly disposes a polling activity view.
     */
    function clearPolling(element) {
        const state = pollingStates.get(element);
        if (!state) return;
        if (state.timer !== null) window.clearTimeout(state.timer);
        if (state.focusHandler) {
            element.removeEventListener("focusout", state.focusHandler);
        }
        pollingStates.delete(element);
        pollingElements.delete(element);
    }

    /**
     * Stops polling for any element that has been disconnected or no longer polls.
     * Product functionality: prevents stale timers for removed views.
     */
    function disposeDisconnectedPolling() {
        pollingElements.forEach((element) => {
            if (!element.isConnected
                    || parsePollingTrigger(element.getAttribute("hx-trigger")) === null) {
                clearPolling(element);
            }
        });
    }

    /**
     * Completes a swap: reformats dates, restores focus and scroll, emits
     * afterSwap, and re-establishes polling for the inserted view.
     * Product functionality: post-swap bookkeeping for a live-updating view.
     */
    function finishSwap(element, target, inserted, focusedId, scrollPosition) {
        formatLocalDates(inserted || target);
        if (focusedId) {
            const focusTarget = findById(inserted, focusedId)
                || document.getElementById(focusedId);
            if (focusTarget) focusTarget.focus({ preventScroll: true });
        }
        window.scrollTo(scrollPosition.x, scrollPosition.y);
        const eventTarget = inserted || target || element;
        trigger(eventTarget, "afterSwap", { elt: eventTarget, target: eventTarget });
        initializePolling(eventTarget);
        const state = pollingStates.get(element);
        if (state && element.isConnected
                && parsePollingTrigger(element.getAttribute("hx-trigger")) !== null) {
            schedulePolling(element, state.baseInterval);
        }
        disposeDisconnectedPolling();
        return eventTarget;
    }

    /**
     * Applies the swap after the configured settle delay, restoring input focus
     * across outerHTML replacements when possible.
     * Product functionality: replaces the page region with the server's response.
     */
    function replaceAfterSettle(element, target, html, swapSpec, polling) {
        if (swapSpec.mode === "none") {
            return Promise.resolve(finishSwap(
                element, target, target, null,
                { x: window.scrollX, y: window.scrollY }));
        }

        const focused = polling && target.contains(document.activeElement)
            ? document.activeElement : null;
        const focusedId = focused && focused.id ? focused.id : null;
        const parsedRoot = swapSpec.mode === "outerHTML" ? responseRoot(html) : null;
        const canRestore = focusedId && parsedRoot && findById(parsedRoot, focusedId);
        if (focused && !canRestore) {
            const state = pollingStates.get(element);
            if (!state) return Promise.resolve(target);
            state.pendingResponse = { html, swapSpec };
            if (!state.focusHandler) {
                state.focusHandler = () => window.setTimeout(() => {
                    if (!element.contains(document.activeElement) && state.pendingResponse) {
                        const pending = state.pendingResponse;
                        const handler = state.focusHandler;
                        state.pendingResponse = null;
                        state.focusHandler = null;
                        element.removeEventListener("focusout", handler);
                        replaceAfterSettle(
                            element, target, pending.html, pending.swapSpec, true);
                    }
                }, 0);
                element.addEventListener("focusout", state.focusHandler);
            }
            return Promise.resolve(null);
        }

        const scrollPosition = { x: window.scrollX, y: window.scrollY };
        if (swapSpec.mode === "outerHTML") {
            clearPolling(element);
            target.replaceWith(parsedRoot);
        } else {
            target.innerHTML = html;
        }
        const inserted = swapSpec.mode === "outerHTML" ? parsedRoot : target;
        return new Promise((resolve) => {
            window.setTimeout(() => {
                resolve(finishSwap(
                    element, target, inserted, focusedId, scrollPosition));
            }, swapSpec.settle);
        });
    }

    /**
     * Removes the temporary "activity updates unavailable" warning element.
     * Product functionality: clears the degraded-mode notice once polling recovers.
     */
    function removePollingWarning(element) {
        const warning = element.querySelector(":scope > .activity-update-warning");
        if (warning) warning.remove();
    }

    /**
     * Records a polling failure, showing a temporary warning after repeated
     * failures and backing off the refresh interval.
     * Product functionality: degrades gracefully when live updates are unavailable.
     */
    function recordPollingFailure(element) {
        const state = pollingStates.get(element);
        if (!state || !element.isConnected) return;
        state.failures += 1;
        if (state.failures >= 2) {
            let warning = element.querySelector(":scope > .activity-update-warning");
            if (!warning) {
                warning = document.createElement("p");
                warning.className = "activity-update-warning";
                warning.textContent = temporaryWarning;
                element.append(warning);
            }
        }
        schedulePolling(element, state.failures >= 2 ? 30 : state.baseInterval);
    }

    /**
     * Performs an AJAX request for an element, dispatching the htmx lifecycle
     * events, attaching the CSRF header, applying the swap, and handling errors.
     * Product functionality: the core engine behind every declarative hx- request.
     */
    function request(element, method, url, options) {
        const target = options && options.target
            ? resolveTarget(options.target)
            : targetFor(element);
        if (!target) return Promise.resolve({ successful: false });
        const detail = { elt: element, target };
        if (!trigger(element, "beforeRequest", detail)) {
            return Promise.resolve({ successful: false });
        }
        element.classList.add("htmx-request");
        const headers = { "HX-Request": "true" };
        const csrf = csrfFor(element);
        if (csrf) headers["X-CSRF-TOKEN"] = csrf;
        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), 8000);
        const init = {
            method,
            headers,
            credentials: "same-origin",
            signal: controller.signal
        };
        if (method !== "GET") {
            init.body = options && options.values
                ? new URLSearchParams(options.values)
                : bodyFor(element);
            headers["Content-Type"] = "application/x-www-form-urlencoded;charset=UTF-8";
        }
        return fetch(url, init)
            .then((response) => response.text().then((html) => ({ response, html })))
            .then(({ response, html }) => {
                const responseDetail = {
                    elt: element,
                    target,
                    xhr: { status: response.status, responseText: html },
                    successful: response.ok
                };
                if (!response.ok) {
                    trigger(element, "responseError", responseDetail);
                    if (options && options.polling) recordPollingFailure(element);
                    return { successful: false };
                }
                const swapSpec = parseSwapSpec(
                    options && options.swap || element.getAttribute("hx-swap") || "innerHTML");
                if (!swapSpec) {
                    throw new Error("Unsupported hx-swap value");
                }
                if (options && options.polling) {
                    const state = pollingStates.get(element);
                    if (state) state.failures = 0;
                    removePollingWarning(element);
                }
                return replaceAfterSettle(
                    element, target, html, swapSpec, options && options.polling)
                    .then((inserted) => {
                        trigger(inserted || element, "afterRequest", responseDetail);
                        return { successful: true, inserted };
                    });
            })
            .catch((error) => {
                trigger(element, "responseError", {
                    elt: element, target, error, successful: false
                });
                if (options && options.polling) recordPollingFailure(element);
                return { successful: false };
            })
            .finally(() => {
                window.clearTimeout(timeout);
                element.classList.remove("htmx-request");
                const state = pollingStates.get(element);
                if (state) state.inFlight = false;
            });
    }

    /**
     * Schedules the next polling request for an element after the given seconds.
     * Product functionality: keeps live views (e.g. Inbox activity) refreshing.
     */
    function schedulePolling(element, seconds) {
        const state = pollingStates.get(element);
        if (!state || !element.isConnected) {
            clearPolling(element);
            return;
        }
        if (state.timer !== null) window.clearTimeout(state.timer);
        state.timer = window.setTimeout(() => {
            state.timer = null;
            if (!element.isConnected) {
                clearPolling(element);
                return;
            }
            if (state.inFlight) return;
            state.inFlight = true;
            request(element, "GET", element.getAttribute("hx-get"), {
                polling: true,
                target: element.getAttribute("hx-target"),
                swap: element.getAttribute("hx-swap")
            });
        }, seconds * 1000);
    }

    /**
     * Scans a root for elements with an hx-trigger polling attribute and starts
     * their refresh loops, disposing any that are no longer connected.
     * Product functionality: bootstraps auto-refreshing views on load and after swaps.
     */
    function initializePolling(root) {
        const candidates = [];
        if (root instanceof Element && root.hasAttribute("hx-trigger")) {
            candidates.push(root);
        }
        if (root instanceof Element || root instanceof Document) {
            candidates.push(...root.querySelectorAll("[hx-trigger]"));
        }
        candidates.forEach((element) => {
            const seconds = parsePollingTrigger(element.getAttribute("hx-trigger"));
            if (seconds === null || !element.hasAttribute("hx-get")
                    || pollingStates.has(element)) return;
            pollingStates.set(element, {
                timer: null,
                inFlight: false,
                failures: 0,
                baseInterval: seconds,
                pendingResponse: null,
                focusHandler: null
            });
            pollingElements.add(element);
            schedulePolling(element, seconds);
        });
        disposeDisconnectedPolling();
    }

    /**
     * Submits a form via AJAX based on its hx-post / hx-get attributes.
     * Product functionality: intercepts marked forms so they update in place.
     */
    function submitForm(form) {
        const method = form.getAttribute("hx-post") ? "POST" : "GET";
        const url = form.getAttribute("hx-post") || form.getAttribute("hx-get");
        if (!url) return;
        request(form, method, url);
    }

    document.addEventListener("click", (event) => {
        const triggerElement = event.target.closest(
            "[hx-get], a[hx-post], button[hx-post]");
        if (!triggerElement || triggerElement.closest("form")) return;
        const url = triggerElement.getAttribute("hx-get")
            || triggerElement.getAttribute("hx-post");
        if (!url) return;
        event.preventDefault();
        request(
            triggerElement,
            triggerElement.hasAttribute("hx-post") ? "POST" : "GET",
            url);
    });

    document.addEventListener("submit", (event) => {
        const form = event.target;
        if (!form.matches("form[hx-get], form[hx-post]")) return;
        event.preventDefault();
        submitForm(form);
    });

    document.addEventListener("DOMContentLoaded", () => {
        formatLocalDates(document);
        initializePolling(document);
    });

    /**
     * Public API: performs an AJAX request for a target element.
     * Product functionality: used by page scripts (e.g. Cleanup category loading)
     * to fetch and swap content programmatically.
     */
    window.htmx = {
        ajax(method, url, options) {
            const target = options && options.target
                ? resolveTarget(options.target)
                : document.body;
            return request(
                target || document.body, method.toUpperCase(), url, options || {});
        }
    };
}());
