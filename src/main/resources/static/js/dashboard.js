'use strict';
(() => {
  const outlook = document.body.dataset.provider === 'microsoft';
  const $ = id => document.getElementById(id);
  const state = { scope: 'inbox', suggestion: '', page: 0, pageSize: 10, messagePage: 0, address: null, tab: 'messages', data: null, focused: null, details: null, action: 'MARK_READ', preview: null, targetPage: 0, revision: 0 };
  const selected = new Map(), expanded = new Map(), senderSelection = new Map();
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  let composition = null, selectionGeneration = 0, senderSelectionVersion = 0;
  let filingPicker = null;
  let jobs = [], confirming = false, previewLoading = false, previewFromSenders = false, previewFromRule = false;
  let jobsRequest = 0;
  const pendingText = state => ({ QUEUED: 'Cleanup queued', DISPATCH_INTENT: 'Cleanup in progress', AWAITING_VERIFICATION: 'Outcome unresolved — checking provider state' }[state] || '');
  function rememberFocus(container) {
    const current = document.activeElement;
    if (!container.contains(current)) return () => { };
    const key = current.dataset.focusKey, label = current.getAttribute('aria-label');
    if (!key && !label) return () => { };
    return () => { const next = [...container.querySelectorAll('button,input,a,summary,select,textarea')].find(e => key ? e.dataset.focusKey === key : label && e.getAttribute('aria-label') === label), other = key === 'message-next' ? container.querySelector('[data-focus-key=message-prev]') : key === 'message-prev' ? container.querySelector('[data-focus-key=message-next]') : null; (next && !next.disabled ? next : other && !other.disabled ? other : $('senderTable')).focus({ preventScroll: true }); };
  }
  function progressMeter(job) {
    const meter = node('progress'); meter.max = job.total; meter.value = job.changed + job.failed + job.skipped + job.cancelled;
    meter.setAttribute('aria-label', `${fmt(meter.value)} of ${fmt(job.total)} outcomes settled; ${fmt(job.unresolved)} unresolved`); return meter;
  }
  const actionName = action => ({ MARK_READ: 'Mark as Read', ARCHIVE: 'Archive', MARK_READ_ARCHIVE: 'Mark as Read + Archive', TRASH: outlook ? 'Move to Deleted Items' : 'Move to Trash', ORGANIZE: outlook ? 'Move to a folder' : 'Apply a label' }[action] || action);
  const activeJob = j => j.remaining > 0 || j.verificationPending;
  const jobCounts = j => `${fmt(j.changed)} ${j.executionMode === 'RESPONSE' ? 'accepted by Gmail' : j.executionMode === 'OUTLOOK' ? 'accepted by Outlook' : 'changed'} · ${fmt(j.failed)} failed · ${fmt(j.skipped)} skipped · ${fmt(j.unresolved)} unresolved${j.partialRead ? ' · '+fmt(j.partialRead)+' read, archive incomplete' : ''} · ${fmt(j.cancelled)} cancelled before dispatch · ${fmt(j.remaining)} remaining of ${fmt(j.total)}`;
  function renderJobs() {
    const focused = document.activeElement?.dataset?.cancelJob;
    $('jobProgress').replaceChildren();
    for (const job of jobs.filter(activeJob)) {
      const row = node('div', undefined, 'job-progress'), copy = node('div');
      copy.append(node('strong', job.cancelRequested ? (job.remaining ? 'Cancellation pending — claimed requests may finish' : (job.verificationPending ? 'Cancelled — verification continues' : 'Cancelled — unresolved outcomes retained')) : actionName(job.action) + (job.destinationName ? ' · ' + job.destinationName : '') + ' · ' + job.status.toLowerCase().replaceAll('_', ' ')), node('p', jobCounts(job) + (job.retryReason ? ' · ' + job.retryReason : ''), 'muted'));
      copy.append(progressMeter(job)); row.append(copy); if (job.remaining > 0 && !job.cancelRequested) { const cancel = node('button', 'Cancel'); cancel.dataset.cancelJob = job.id; cancel.onclick = () => cancelJob(job.id, cancel); row.append(cancel); } $('jobProgress').append(row);
    }
    updateSelection();
    if (focused) { const next = [...$('jobProgress').querySelectorAll('button')].find(b => b.dataset.cancelJob === focused); (next || document.querySelector('[data-activity-link]')).focus(); }
  }
  async function cancelJob(id, button) { const hadFocus = document.activeElement === button; button.disabled = true; try { await api(`jobs/${id}/cancel`, {}); notice('Cancellation requested. Remaining unclaimed work stops; claimed requests may finish. Completed changes are not reversed.'); await pollJobs(); if (hadFocus && document.activeElement === document.body) document.querySelector('[data-activity-link]').focus(); } catch (error) { notice(error.message); button.disabled = false; if (hadFocus) button.focus(); } }
  async function pollJobs() {
    const request = ++jobsRequest; try { const latest = await api('jobs'); if (request !== jobsRequest) return; const prior = new Map(jobs.map(j => [j.id, j])); if (latest.length === jobs.length && latest.every(j => prior.get(j.id)?.version === j.version)) return; const changed = latest.some(j => !prior.has(j.id) || prior.get(j.id).version !== j.version); jobs = latest; renderJobs(); if (changed) { await loadPage(); if (jobs.some(j => prior.has(j.id) && activeJob(prior.get(j.id)) && !activeJob(j))) notice('Cleanup finished. Completed changes stay. ' + jobCounts(jobs.find(j => prior.has(j.id) && activeJob(prior.get(j.id)) && !activeJob(j))), true); } } catch (error) { notice('Job status unavailable. Saved work continues. ' + error.message); }
  }
  async function confirmJob() {
    if (confirming || !state.preview) return;
    confirming = true; $('confirmAction').disabled = true;
    const preview = state.preview, fromSenders = previewFromSenders, fromRule = previewFromRule;
    try {
      const job = await api('jobs', { preview: preview.id });
      state.preview = null;
      if (fromRule) document.dispatchEvent(new Event('tidymail:rule-filing-started'));
      else if (fromSenders) senderSelection.clear();
      else { selectionGeneration++; selected.clear(); }
      $('confirm').close(); renderSelectionControls();
      if (!fromRule) notice(actionName(preview.action) + ' saved. You can close this page; work continues.');
      await pollJobs();
      if (!jobs.some(j => j.id === job.id)) { jobs.unshift(job); renderJobs(); }
    } catch (error) { notice(error.message); } finally {
      confirming = false;
      $('confirmAction').disabled = !state.preview || !state.preview.effective;
      updateSelection();
    }
  }
  let filterEpoch = 0, listRequest = 0, listLoading = false, detailRequest = 0, detailLoad = null, detailRefreshPending = false, selectionBusy = 0, correctionDraft = null, correctionGroups = [], correctionRequest = 0;
  let detailError = null;
  const fmt = number => Number(number).toLocaleString();
  function node(tag, text, className) { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; }
  function notice(text, activity = false) {
    $('notice').hidden = false; $('notice').textContent = text;
    $('notice').classList.toggle('notice-link', activity);
    if (activity) {
      const button = node('button', text + ' · View Activity →', 'notice-action');
      button.type = 'button'; button.dataset.focusKey = 'cleanup-activity';
      button.onclick = () => document.querySelector('[data-activity-link]').click();
      $('notice').replaceChildren(button);
    }
    const dialog = document.querySelector('dialog[open]');
    if (dialog) { let feedback = dialog.querySelector('.dialog-notice'); if (!feedback) { feedback = node('p', undefined, 'notice dialog-notice'); feedback.setAttribute('role', 'status'); dialog.querySelector('h2').parentElement.after(feedback); } feedback.textContent = text; }
  }
  async function api(path, body, signal) {
    const headers = { 'Accept': 'application/json', 'X-TidyMail-Account': document.body.dataset.account };
    if (body !== undefined) { headers['Content-Type'] = 'application/json'; headers[document.querySelector('meta[name=_csrf_header]').content] = document.querySelector('meta[name=_csrf]').content; }
    const response = await fetch('/dashboard/api/' + path, { method: body === undefined ? 'GET' : 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store', signal });
    if (response.redirected || response.status === 401) throw new Error('Your session ended. Sign in again to continue.');
    if (!response.ok) { let error = {}; try { error = await response.json(); } catch (_) { } throw new Error(error.message || 'Could not load this request. Try again.'); }
    const text = await response.text(); return text ? JSON.parse(text) : null;
  }
  function dateTimeText(raw) { return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: zone }).format(new Date(raw)); }
  function dateText(raw) { return raw ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: zone }).format(new Date(raw)) : 'Undated'; }
  const labelChoices = [...document.querySelectorAll('[name=labelFilter]')];
  let gmailLabelNames = {}, gmailLabelsRequestedAt = 0;
  function loadGmailLabels() {
    if (outlook || Date.now() - gmailLabelsRequestedAt < 600000) return;
    gmailLabelsRequestedAt = Date.now();
    api('gmail-labels').then(names => {
      gmailLabelNames = names;
      const selectedLabels = new Set(labelChoices.filter(input => input.checked).map(input => input.value));
      const custom = $('gmailLabelChoices');
      for (let index = labelChoices.length - 1; index >= 0; index--)
        if (custom.contains(labelChoices[index])) labelChoices.splice(index, 1);
      custom.replaceChildren();
      for (const [id, name] of Object.entries(names).sort((a,b) => a[1].localeCompare(b[1]))) {
        if (!/^Label_[0-9]{1,30}$/.test(id)) continue;
        const label = node('label'), input = node('input');
        input.type = 'checkbox';
        input.name = 'labelFilter';
        input.value = 'GMAIL_' + id.toUpperCase();
        input.checked = selectedLabels.delete(input.value);
        input.addEventListener('change', labelFilterChanged);
        label.append(input, document.createTextNode('Gmail label: ' + name));
        custom.append(label);
        labelChoices.push(input);
      }
      if ([...selectedLabels].some(label => label.startsWith('GMAIL_LABEL_'))) labelFilterChanged();
      else if (state.details && state.tab === 'messages') renderDetail();
    }).catch(() => { gmailLabelsRequestedAt = Date.now() - 540000; });
  }
  function filter() {
    return { scope: state.scope, q: $('search').value, start: $('start').value, end: $('end').value,
      zone, page: state.page, pageSize: state.pageSize, sort: $('sort').value, groupBy: $('groupBy').value, suggestion: state.suggestion,
      recommendation: $('recommendation').value, importance: $('importance').value,
      labels: labelChoices.filter(input => input.checked).map(input => input.value).join(','), match: $('labelMatch').value,
      composition: composition?.key || '', excludeProtectedShopping: $('excludeProtectedShopping').checked };
  }
  const query = (value = filter()) => new URLSearchParams(Object.entries(value)).toString();
  function checkbox(label, count, match, onChange) {
    const e = node('input'); e.type = 'checkbox'; e.setAttribute('aria-label', label); e.dataset.selection = 'true';
    e.checked = count > 0 && match >= count; e.indeterminate = match > 0 && match < count; e.disabled = selectionBusy > 0 || $('senderTable').hasAttribute('aria-busy');
    e.addEventListener('change', () => onChange(e.checked)); return e;
  }
  function updateSelection() {
    $('selectionRow').hidden = selected.size === 0;
    $('dock').hidden = selected.size === 0 && !jobs.some(activeJob);
    $('selectionCount').textContent = `${fmt(selected.size)} messages selected`;
    const addresses = new Set([...selected.values()].map(m => m.address));
    $('selectionDescription').textContent = `${state.focused?.label || 'Current group'} · ${fmt(addresses.size)} sender ${addresses.size === 1 ? 'address' : 'addresses'} · Fixed message selection`;
    const reviewBusy = previewLoading || confirming || selectionBusy > 0;
    $('review').textContent = selected.size ? `Review ${fmt(selected.size)} emails →` : 'Review selection →';
    $('review').disabled = !selected.size || reviewBusy;
    $('createMessageRule').disabled = !selected.size || reviewBusy;
    const byAddress = $('groupBy').value === 'address', count = senderSelection.size;
    const unit = byAddress ? 'sender address' : 'sender group';
    $('groupingToolbar').hidden = count === 0;
    $('groupingCount').textContent = `${fmt(count)} ${unit}${count === 1 ? '' : byAddress ? 'es' : 's'} selected`;
    $('reviewSenders').disabled = !count || reviewBusy || $('senderTable').hasAttribute('aria-busy');
    $('createSenderSelectionRule').disabled = $('reviewSenders').disabled;
    $('combineGroups').hidden = $('splitGroup').hidden = byAddress;
    $('combineGroups').disabled = count < 2 || listLoading;
    $('splitGroup').disabled = count !== 1 || listLoading;
    const visible=state.data?.groups || [], visibleSelected=visible.filter(g=>senderSelection.has(g.id)).length;
    const header=$('selectPageSenders'), total=Number(state.data?.summary.groups || 0);
    header.checked=visible.length>0 && visibleSelected===visible.length;
    header.indeterminate=visibleSelected>0 && visibleSelected<visible.length;
    header.disabled=!visible.length || listLoading || selectionBusy>0;
    const allSelected=total>0 && count===total;
    $('selectAllMatchingSenders').hidden=!header.checked || allSelected || total<=visible.length;
    $('selectAllMatchingSenders').textContent=`Select all ${fmt(total)} matching ${byAddress?'sender addresses':'sender groups'}`;
    $('selectAllMatchingSenders').disabled=reviewBusy || listLoading;
    $('senderBulkStatus').hidden=!allSelected || total<=visible.length;
    $('senderBulkStatus').textContent=`All ${fmt(total)} matching ${byAddress?'sender addresses':'sender groups'} selected.`;
  }
  async function selectAllMatching() {
    if (selectionBusy || listLoading || !state.details || !state.focused) return;
    const epoch = filterEpoch, revision = state.revision, group = state.focused.id, generation = selectionGeneration;
    selectionBusy++;
    renderSelectionControls();
    try {
      const rows = await api('selection', { filter: filter(), revision, groups: [state.focused.id], address: state.address,
        unreadOnly: $('detailUnreadOnly').checked });
      if (epoch !== filterEpoch || group !== state.focused?.id || revision !== state.revision || generation !== selectionGeneration) return;
      rows.forEach(row => selected.set(row.id, row));
      invalidatePreview();
    } catch (error) {
      if (epoch === filterEpoch && group === state.focused?.id && generation === selectionGeneration) notice(error.message);
    } finally {
      selectionBusy--;
      renderSelectionControls();
    }
  }
  function renderSelectionControls() { renderGroups(); renderDetail(); updateSelection(); }
  function invalidatePreview() {
    if (state.preview) { const old = state.preview.id; state.preview = null; api(`previews/${old}/invalidate`, {}).catch(() => { }); }
    if ($('confirm').open) $('confirm').close();
  }
  function clearSelection(message) { selectionGeneration++; selected.clear(); invalidatePreview(); renderSelectionControls(); if (message) notice(message); }
  function filtersChanged() {
    const hadSelection = selected.size > 0 || senderSelection.size > 0;
    renderFilterChips();
    senderSelection.clear();
    $('clearComposition').hidden = !composition;
    if (composition) $('clearComposition').textContent = `Clear Inbox drilldown: ${composition.label}`;
    $('compositionRows').replaceChildren();
    filterEpoch++; detailRequest++; detailLoad?.controller.abort(); state.page = 0; state.messagePage = 0; expanded.clear(); state.details = null; state.focused = null; $('detail').hidden = true;
    if ($('correction').open) $('correction').close();
    clearSelection(hadSelection ? 'Filters changed. Draft selection was cleared.' : undefined); loadPage();
  }
  async function loadPage(pagerFocus, background = false, forceDetails = false) {
    // A label update must survive a refresh deferred by a dialog or selection.
    if (forceDetails) detailRefreshPending = true;
    if (background && (document.hidden || document.querySelector('dialog[open]') || selectionBusy || previewLoading || listLoading)) return;
    forceDetails ||= detailRefreshPending;
    if (forceDetails) detailRefreshPending = false;
    let pageApplied = false;
    const restoreFocus = rememberFocus(document.querySelector('.workspace'));
    const request = ++listRequest, epoch = filterEpoch; listLoading = true;
    if (!background) { $('senderTable').setAttribute('aria-busy', 'true'); renderSelectionControls(); }
    try {
      const data = await api('page?' + query()); if (request !== listRequest || epoch !== filterEpoch) return;
      if (background && (document.querySelector('dialog[open]') || selectionBusy || previewLoading)) return;
      const sync = data.sync;
      $('syncStatus').textContent = sync.oauth_status === 'REAUTH_REQUIRED' || sync.lifecycle === 'REAUTH_REQUIRED'
        ? 'Reconnect required' : sync.error_code || sync.lifecycle === 'ERROR' ? 'Sync needs attention'
        : !sync.initial_batch_completed_at ? 'Importing mail…' : sync.backfill_status === 'RUNNING'
        ? 'Importing older mail…' : `Last refreshed ${refreshAge(sync.incremental_last_success_at || sync.initial_batch_completed_at)}`;
      if (outlook && sync.backfill_status !== 'COMPLETE' && !sync.error_code && sync.oauth_status !== 'REAUTH_REQUIRED')
        $('syncStatus').textContent = `Importing mail · ${fmt(data.coverage.indexed)} indexed`;
      $('syncStatus').dataset.state = /required|attention/i.test($('syncStatus').textContent) ? 'attention' : /Importing/i.test($('syncStatus').textContent) ? 'pending' : 'ready';
      if (background && !forceDetails && JSON.stringify(data) === JSON.stringify(state.data)) return;
      // Status text is not message evidence. Keep mailbox freshness timestamps and
      // all existing row/count/revision signals; these are not a complete row version.
      const visibleFacts = page => page && [page.groups, page.summary, page.coverage, page.filter,
        page.revision, page.classificationRevision, page.sync.email, page.sync.incremental_last_success_at];
      const refreshDetails = !background || forceDetails || JSON.stringify(visibleFacts(data)) !== JSON.stringify(visibleFacts(state.data));
      if (state.data && state.revision !== data.revision) { selectionGeneration++; selected.clear(); senderSelection.clear(); expanded.clear(); invalidatePreview(); notice('Grouping changed. Draft selection was cleared.'); }
      const lastPage = Math.max(0, Math.ceil(Number(data.summary.groups) / state.pageSize) - 1);
      if (state.page > lastPage) { state.page = lastPage; return loadPage(pagerFocus); }
      if (state.data && data.filter.labels && state.data.classificationRevision !== data.classificationRevision) {
        // Sender groups are a draft scope, resolved to current matching messages at review.
        // Only an existing message selection or message preview has become stale here.
        const staleMessages = selected.size > 0 || (state.preview && !previewFromSenders);
        selectionGeneration++; selected.clear();
        if (staleMessages) { invalidatePreview(); notice('Label results changed. Select matching messages again.'); }
      }
      state.data = data; state.revision = data.revision; pageApplied = true;
      $('accountEmail').textContent = data.sync.email;
      $('accountEmail').title = data.sync.email;
      $('settingsAccountEmail').textContent = data.sync.email;
      $('matching').textContent = fmt(data.summary.messages); $('unreadCount').textContent = fmt(data.summary.unread); $('groupCount').textContent = fmt(data.summary.groups);
      const c = data.coverage;
      $('sizeCoverage').textContent = outlook && c.outlook_unknown_sizes
        ? `Size unavailable for ${fmt(c.outlook_unknown_sizes)} received messages.` : '';
      $('coverage').textContent = Number(c.indexed) ? `${fmt(c.indexed)} indexed emails · Indexed dates: ${dateText(c.oldest)}–${dateText(c.newest)} · Results include indexed messages only · As of ${dateText(c.as_of)} · ${filter().zone}` : 'Coverage unavailable — no messages indexed yet.';
      const gaps = []; if (c.unknown_labels) gaps.push(`${fmt(c.unknown_labels)} messages have unknown labels${state.scope === 'indexed' ? '' : ' and are excluded from received mail'}`); if (!outlook && c.ambiguous_sent) gaps.push(`${fmt(c.ambiguous_sent)} Sent messages lack independent delivery evidence`); if (c.unknown_identity) gaps.push(`${fmt(c.unknown_identity)} identities are kept separate pending fresh metadata`); if (data.summary.undated) gaps.push(`${fmt(data.summary.undated)} matching messages are undated`);
      $('coverage').textContent += ` · ${bytesText(data.summary.known_bytes)} estimated${Number(data.summary.unknown_sizes) ? ' · '+fmt(data.summary.unknown_sizes)+' sizes unknown' : ''}`;
      if (gaps.length) $('coverage').textContent += ' · ' + gaps.join(' · ');
      if (state.scope === 'indexed') $('coverage').textContent += ' · Includes Sent, Spam, Trash, Drafts and unknown labels. Cleanup still requires received-mail eligibility or a Sent storage suggestion.';
      if ($('composition').open && (refreshDetails || !$('compositionRows').childElementCount)) loadComposition(request, epoch);
      $('empty').hidden = !!data.groups.length; $('empty').textContent = Number(c.indexed) ? 'No messages match these filters. Clear filters to browse more indexed mail.' : 'No messages indexed yet. Fetching mail…';
      $('pageLabel').textContent = data.groups.length ? `${state.page * state.pageSize + 1}–${state.page * state.pageSize + data.groups.length} of ${fmt(data.summary.groups)}` : `0 of ${fmt(data.summary.groups)}`;
      $('prevPage').disabled = state.page === 0; $('nextPage').disabled = (state.page + 1) * state.pageSize >= data.summary.groups;
      renderGroups(); updateSelection();
      if (pagerFocus && (document.activeElement === document.body || document.activeElement.id === pagerFocus)) {
        const preferred = $(pagerFocus), other = $(pagerFocus === 'nextPage' ? 'prevPage' : 'nextPage');
        (preferred.disabled ? (other.disabled ? $('senderTable') : other) : preferred).focus();
      }
      if (state.focused && !data.groups.some(g => g.id === state.focused.id)) {
        detailRequest++; detailLoad?.controller.abort(); state.focused = null; state.details = null; state.address = null;
        $('detail').hidden = true;
        clearSelection();
      }
      if (refreshDetails) refreshExpanded(data, request, epoch);
      if (!state.focused && data.groups.length) return focusGroup(data.groups[0]);
      else if (state.focused) {
        const current = data.groups.find(g => g.id === state.focused.id);
        if (current) state.focused = current;
        if (epoch === filterEpoch && refreshDetails) return loadDetails(true);
      }
    } catch (error) { if (request === listRequest) { notice(error.message); $('empty').hidden = false; $('empty').textContent = 'Could not load indexed mail. Change filters or reload to try again.'; } }
    finally {
      if (forceDetails && !pageApplied) detailRefreshPending = true;
      if (request === listRequest) {
        listLoading = false;
        if ($('senderTable').hasAttribute('aria-busy')) {
          $('senderTable').removeAttribute('aria-busy'); renderSelectionControls();
          if (document.activeElement === document.body || document.activeElement === $('senderTable')) restoreFocus();
        }
        updateSelection();
      }
    }
  }
  function refreshAge(raw) {
    const seconds = Math.max(0, (Date.now() - new Date(raw)) / 1000);
    if (seconds < 60) return 'less than a min ago';
    if (seconds > 600) return 'over 10 mins ago';
    const minutes = Math.floor(seconds / 60);
    return `${minutes} ${minutes === 1 ? 'min' : 'mins'} ago`;
  }
  async function loadComposition(request = listRequest, epoch = filterEpoch) {
    try {
      const groups = await api('inbox-composition?' + query());
      if (request !== listRequest || epoch !== filterEpoch || !$('composition').open) return;
      const rows = $('compositionRows'), restore = rememberFocus(rows);
      rows.replaceChildren();
      for (const group of groups) {
        const row = node('div'); row.setAttribute('role','listitem');
        const button = node('button', undefined, 'composition-row'), bar = node('meter');
        bar.max = groups[0].messages; bar.value = group.messages; bar.setAttribute('aria-hidden', 'true');
        button.dataset.focusKey = 'composition-' + group.key;
        button.append(node('span', group.label), bar, node('span', `${fmt(group.messages)} messages`));
        button.setAttribute('aria-pressed', String(composition?.key === group.key));
        button.onclick = () => {
          composition = group;
          setScope('inbox');
          $('messagesTab').click();
          $('detailUnreadOnly').checked = false;
          filtersChanged();
          $('clearComposition').focus();
        };
        row.append(button); rows.append(row);
      }
      if (!groups.length) rows.append(node('p', 'No Inbox messages match these filters.'));
      restore();
    } catch (error) {
      if (request === listRequest && epoch === filterEpoch) $('compositionRows').textContent = error.message;
    }
  }
  $('clearComposition').onclick = () => { composition = null; filtersChanged(); $('composition').querySelector('summary').focus(); };
  $('composition').addEventListener('toggle', () => { if ($('composition').open) loadComposition(); });
  function refreshExpanded(data, request, epoch) {
    for (const g of data.groups.filter(g => expanded.has(g.id))) {
      const previous = expanded.get(g.id);
      api(`groups/${g.id}/addresses?${query()}`).then(result => {
        if (request !== listRequest || epoch !== filterEpoch || result.revision !== state.revision || expanded.get(g.id) !== previous) return;
        expanded.set(g.id, result.addresses); renderGroups();
      }).catch(() => {
        if (request !== listRequest || epoch !== filterEpoch || expanded.get(g.id) !== previous) return;
        expanded.delete(g.id); renderGroups();
      });
    }
  }
  function renderGroups() {
    const restore = rememberFocus($('senders'));
    const body = $('senders'); body.replaceChildren();
    (state.data?.groups || []).forEach(g => {
      const row = node('tr'); if (state.focused?.id === g.id) row.classList.add('focused');
      const cell = node('td');
      const senderCheckbox = checkbox(`Select sender ${g.address || g.label}`, 1, Number(senderSelection.has(g.id)), checked => {
        if (checked) senderSelection.set(g.id, g); else senderSelection.delete(g.id);
        senderSelectionVersion++; updateSelection();
      });
      senderCheckbox.dataset.focusKey = 'select-sender-' + g.id;
      cell.append(senderCheckbox);
      row.append(cell);
      const sender = node('td'), focus = node('button', undefined, 'sender-focus'), badge = node('span', g.label.slice(0, 1).toUpperCase(), 'brand'), text = node('span', undefined, 'sender-label');
      text.append(node('strong', g.label), node('small', g.address || `${g.addresses} sender ${g.addresses === 1 ? 'address' : 'addresses'}`, 'muted')); focus.title = `${bytesText(g.knownBytes)} estimated${g.unknownSizes ? ' · '+fmt(g.unknownSizes)+' sizes unknown' : ''}`; if (g.pending) text.append(node('small', `${fmt(g.pending)} cleanup pending / unresolved`, 'pending')); focus.dataset.focusKey = 'sender-' + g.id; focus.append(badge, text); focus.onclick = () => focusGroup(g); sender.append(focus); row.append(sender, node('td', fmt(g.messages)), node('td', fmt(g.unread)), node('td', fmt(g.inbox)), node('td', dateText(g.latest)));
      const expandCell = node('td'); if (g.addresses > 1) { const expand = node('button', expanded.has(g.id) ? '⌃' : '⌄'); expand.setAttribute('aria-label', `Show sender addresses for ${g.label}`); expand.setAttribute('aria-expanded', String(expanded.has(g.id))); expand.onclick = () => expandGroup(g); expandCell.append(expand); } row.append(expandCell); body.append(row);
      if (expanded.has(g.id)) {
        const detail = node('tr', undefined, 'address-row'), td = node('td'); td.colSpan = 7;
        for (const address of expanded.get(g.id)) { const a = node('div', undefined, 'inline-address'); a.append(addressCheckbox(g, address), addressLink(g,address)); td.append(a); } detail.append(td); body.append(detail);
      }
    });
    restore();
  }
  async function expandGroup(g) {
    if (expanded.has(g.id)) { expanded.delete(g.id); renderGroups(); return; }
    const epoch = filterEpoch, request = listRequest;
    try { const detail = await api(`groups/${g.id}/addresses?${query()}`); if (request !== listRequest || epoch !== filterEpoch || detail.revision !== state.revision) return; expanded.set(g.id, detail.addresses); renderGroups(); } catch (error) { if (request === listRequest && epoch === filterEpoch) notice(error.message); }
  }
  function addressCheckbox(g, address) {
    const count = [...selected.values()].filter(m => m.groupId === g.id && m.address === address.address).length;
    const input = checkbox(`Select all matching messages from ${address.address}`, address.messages, count,
      checked => selectAddress(g, address.address, checked));
    input.dataset.focusKey = `select-address-${g.id}-${address.address}`;
    return input;
  }
  async function selectAddress(g, address, checked) {
    if (selectionBusy || listLoading) return;
    const restore = rememberFocus(document.querySelector('.workspace'));
    // Opening another parent group is the boundary for message selection.
    if (state.focused?.id !== g.id) focusGroup(g, address);
    if (!checked) {
      for (const [id, message] of selected) {
        if (message.groupId === g.id && message.address === address) selected.delete(id);
      }
      invalidatePreview(); renderSelectionControls(); return;
    }
    const epoch = filterEpoch, revision = state.revision, generation = selectionGeneration;
    selectionBusy++; renderSelectionControls();
    try {
      // Address-row counts use the main filters, independently of the detail unread toggle.
      const rows = await api('selection', { filter: filter(), revision, groups: [g.id], address });
      if (epoch !== filterEpoch || revision !== state.revision || generation !== selectionGeneration || state.focused?.id !== g.id) return;
      rows.forEach(row => selected.set(row.id, row));
      invalidatePreview();
    } catch (error) {
      if (epoch === filterEpoch && generation === selectionGeneration) notice(error.message);
    } finally {
      selectionBusy--; renderSelectionControls();
      if (generation === selectionGeneration && document.activeElement === $('senderTable')) restore();
    }
  }
  function addressLink(g,a) {
    const button=node('button',`${a.displayName || a.address}${a.displayName ? ' · '+a.address : ''} · ${fmt(a.messages)} messages`,'sender-focus');
    button.onclick=()=>{ state.tab='messages'; focusGroup(g,a.address); $('messagesTab').click(); $('messagesTab').focus(); };
    return button;
  }
  function focusGroup(g, address = null) {
    if (state.focused?.id !== g.id) {
      const hadSelection = selected.size > 0;
      clearSelection(hadSelection ? 'Sender group changed. Previous message selection was cleared.' : undefined);
    }
    state.address = address; state.focused = g; state.messagePage = 0; state.details = null; detailError = null; $('detail').hidden = false; renderGroups(); renderDetail(); return loadDetails(); }
  function loadDetails(refresh = false) {
    if (!state.focused) return Promise.resolve();
    const group = state.focused.id, epoch = filterEpoch, tab = state.tab;
    const parameters = `${query()}&unreadOnly=${$('detailUnreadOnly').checked}&messagePage=${state.messagePage}${state.address ? "&address="+encodeURIComponent(state.address) : ""}`;
    const key = `${epoch}/${group}/${tab}?${parameters}`;
    if (!refresh && detailLoad?.key === key && !detailLoad.controller.signal.aborted) return detailLoad.promise;
    detailLoad?.controller.abort();
    const controller = new AbortController(), request = ++detailRequest;
    detailError = null;
    renderDetail();
    let timedOut = false;
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, 15000);
    const promise = (async () => {
      try {
        let details;
        if (tab === 'messages') {
          details = await api(`groups/${group}?${parameters}`, undefined, controller.signal);
          if (details.recommendations?.some(view => view.providerLabelIds?.some(id => id.startsWith('Label_')))) loadGmailLabels();
        } else {
          const addresses = await api(`groups/${group}/addresses?${query()}`, undefined, controller.signal);
          const rows = addresses.addresses.filter(a => !state.address || a.address === state.address);
          details = { ...addresses, total: rows.reduce((n, a) => n + a.messages, 0) };
        }
        if (request !== detailRequest || epoch !== filterEpoch || group !== state.focused?.id || tab !== state.tab) return;
        if (details.revision !== state.revision) { filtersChanged(); return; }
        if (tab === 'messages') {
          const lastPage = Math.max(0, Math.ceil(details.total / 10) - 1);
          if (state.messagePage > lastPage) { state.messagePage = lastPage; return loadDetails(); }
        }
        state.details = details; renderDetail();
      } catch (error) {
        if ((!controller.signal.aborted || timedOut) && request === detailRequest && epoch === filterEpoch
            && group === state.focused?.id && tab === state.tab) {
          detailError = timedOut ? 'Indexed details took too long to load. Try again.' : error.message;
          renderDetail();
        }
      } finally { clearTimeout(timeout); if (detailLoad?.controller === controller) detailLoad = null; }
    })();
    detailLoad = { key, controller, promise };
    return promise;
  }
  const openMessageTools = new Set();
  function renderDetail() {
    if (!state.focused) return;
    $('detailTitle').textContent = state.focused.label;
    const ruleSender = state.address || state.focused.address;
    let ruleButton = $('createSenderRule');
    if (!ruleButton) { ruleButton = node('button', 'Rules…'); ruleButton.id = 'createSenderRule'; $('detailTitle').parentElement.append(ruleButton); }
    ruleButton.hidden = !ruleSender;
    ruleButton.onclick = () => createInboxRule([ruleSender], state.action, 'For this sender');
    $('detailBadge').textContent = state.focused.label.slice(0, 1).toUpperCase();
    $('detailCounts').textContent = state.details ? `${fmt(state.details.total)} matching ${$('detailUnreadOnly').checked ? 'unread messages' : 'messages'}` : `${fmt(state.focused.messages)} matching messages · ${fmt(state.focused.unread)} unread`;
    $('detailUnreadOnly').disabled = state.tab !== 'messages' || selectionBusy > 0;
    const content = $('detailContent'), restore = rememberFocus(content);
    const expandedLabels = new Set([...content.querySelectorAll('details[data-message-labels][open]')].map(el => el.dataset.messageLabels));
    content.replaceChildren(); const detail = state.details;
    if (detailError) {
      const error = node('p', detailError, 'evidence'); error.setAttribute('role', 'alert');
      const retry = node('button', 'Retry loading details'); retry.type = 'button'; retry.onclick = () => loadDetails(true);
      content.append(error, retry);
      if (!detail) return;
    }
    if (!detail) { content.append(node('p', 'Loading indexed details…', 'evidence')); return; }
    if (state.address) { const back=node('button','All messages in this group'); back.onclick=()=>focusGroup(state.focused); content.append(node('p',state.address,'evidence'),back); }
    if (state.tab === 'messages') {
      const visible = node('div', undefined, 'evidence'), count = detail.messages.filter(m => selected.has(m.id)).length;
      visible.append(checkbox(`Select visible messages (${detail.messages.length})`, detail.messages.length, count, checked => { for (const m of detail.messages) { if (checked) selected.set(m.id, m); else selected.delete(m.id); } invalidatePreview(); renderSelectionControls(); }), node('span', ' Select visible messages'));
      const total = detail.total;
      if (count && count === detail.messages.length && total > count) {
        const all = node('button', `Select all ${fmt(total)} matching ${$('detailUnreadOnly').checked ? 'unread messages' : 'messages'} in this view`);
        all.disabled = selectionBusy > 0 || listLoading;
        all.onclick = selectAllMatching;
        visible.append(all);
      }
      content.append(visible);
      for (const m of detail.messages) {
        const row = node('div', undefined, 'message-row'), text = node('div', undefined, 'message-text');
        const recommendation = (detail.recommendations || []).find(r => r.message === m.id);
        const senderName = m.displayName?.trim();
        text.append(node('span', m.subject || '(No subject)', 'message-subject'));
        const meta = node('div', undefined, 'message-meta');
        meta.append(node('span', senderName ? `${senderName} · ${m.address}` : m.address), node('span', `${dateText(m.receivedAt)} · ${m.unread ? 'Unread' : 'Read'}`));
        text.append(meta);
        if (m.reviewedPattern) text.append(node('small',`Reviewed pattern: ${categoryName(m.reviewedPattern)} · Importance is not determined by this pattern`, 'muted'));
        if (m.pendingState) text.append(node('small', pendingText(m.pendingState), 'pending'));
        const ruleButton = node('button', 'Rules…'); ruleButton.type = 'button'; ruleButton.setAttribute('aria-label', 'Rules for ' + (m.subject || 'this email')); ruleButton.onclick = () => createInboxRule([m.address], state.action, 'From this email: ' + (m.subject || '(No subject)')); const more = node('details', undefined, 'message-tools'), moreTitle = node('summary', 'Details and actions');
        moreTitle.dataset.focusKey = 'message-tools-' + m.id; more.open = openMessageTools.has(m.id);
        more.addEventListener('toggle', () => { if (more.isConnected) { if (more.open) openMessageTools.add(m.id); else openMessageTools.delete(m.id); } });
        more.append(moreTitle, ruleButton); appendEvidence(more,m); appendRecommendation(more,m,recommendation);
        appendMessageLabels(text, m, recommendation, expandedLabels.has(m.id)); text.append(more); row.append(checkbox(`Select message ${m.subject || '(No subject)'}`, 1, selected.has(m.id) ? 1 : 0, checked => { if (checked) selected.set(m.id, m); else selected.delete(m.id); invalidatePreview(); renderSelectionControls(); }), text); content.append(row); }
      if (!detail.total) content.append(node('p', 'No matching messages in this group.', 'evidence'));
      const paging = node('div', undefined, 'paging'), prev = node('button', '‹'), next = node('button', '›'); prev.setAttribute('aria-label', 'Previous message page'); next.setAttribute('aria-label', 'Next message page'); prev.disabled = state.messagePage === 0; next.disabled = (state.messagePage + 1) * 10 >= detail.total;
      prev.dataset.focusKey = 'message-prev'; next.dataset.focusKey = 'message-next'; prev.onclick = () => pageMessages(-1, prev); next.onclick = () => pageMessages(1, next); paging.append(prev, node('span', detail.messages.length ? `${state.messagePage * 10 + 1}–${state.messagePage * 10 + detail.messages.length} of ${fmt(detail.total)}` : '0 messages'), next); content.append(paging);
    } else {
      for (const a of detail.addresses) { const row = node('div', undefined, 'message-row'); row.append(addressCheckbox(state.focused, a), addressLink(state.focused,a)); content.append(row); }
      content.append(node('p', state.focused.evidence, 'evidence'));
      if ($('groupBy').value === 'current') {
        const controls = node('div', undefined, 'corrections'), organize = node('button', 'Organize sender group');
        organize.dataset.focusKey = 'organize';
        organize.onclick = () => openCorrections([state.focused], 'SPLIT');
        controls.append(organize); content.append(controls);
      }
    }
    restore();
  }
  async function pageMessages(delta, button) {
    const group = state.focused.id, page = state.messagePage + delta;
    const restore = rememberFocus($('detailContent'));
    state.details = null;
    state.messagePage = page;
    renderDetail();
    await loadDetails();
    if (state.focused?.id === group && state.messagePage === page &&
        (document.activeElement === document.body || document.activeElement === button)) restore();
  }
  async function openPreview(fromSenders = false, destination = null) {
    if (previewLoading || confirming) return;
    const epoch = filterEpoch, revision = state.revision, activeFilter = filter();
    let classificationRevision = state.data?.classificationRevision;
    const selection = fromSenders ? senderSelection : selected;
    const keys = [...selection.keys()];
    const actionType = fromSenders ? $('senderAction').value : state.action;
    const isCurrent = () => epoch === filterEpoch && revision === state.revision
      && (fromSenders || !activeFilter.labels || classificationRevision === state.data?.classificationRevision)
      && actionType === (fromSenders ? $('senderAction').value : state.action)
      && keys.length === selection.size && keys.every(id => selection.has(id));
    if (!keys.length) return;
    if (actionType === 'ORGANIZE' && !destination) {
      const selectionValid = isCurrent;
      $('filingDestinationError').textContent = '';
      if (!filingPicker) filingPicker = new window.TidyMailDestinationPicker($('filingDestinationPicker'), { provider: outlook ? 'microsoft' : 'google', commitment: 'confirm Apply' });
      $('reviewFiling').onclick = () => {
        try {
          if (!selectionValid()) throw new Error('Selection changed. Return to selection and review again.');
          const choice = filingPicker.value(); $('filingDestination').close(); openPreview(fromSenders, choice);
        } catch (error) { $('filingDestinationError').textContent = error.message; }
      };
      $('filingDestination').showModal(); return;
    }
    previewLoading = true;
    updateSelection();
    try {
      let ids = keys;
      if (fromSenders) {
        if (activeFilter.labels) {
          const latest = await api('page?' + query(activeFilter));
          if (!isCurrent()) return;
          if (latest.revision !== revision) { notice('Grouping changed. Refresh and select senders again.'); return; }
          classificationRevision = latest.classificationRevision;
        }
        const rows = await api('selection', { filter: activeFilter, groups: keys, revision });
        if (!isCurrent()) return;
        if (!rows.length) { notice('No emails match the current filters for these senders.'); return; }
        ids = rows.map(row => row.id);
      }
      const preview = await api('previews', { filter: activeFilter, ids, action: actionType, revision, classificationRevision, destination });
      if (!isCurrent()) { api(`previews/${preview.id}/invalidate`, {}).catch(() => { }); return; }
      previewFromSenders = fromSenders; previewFromRule = false;
      await showActionPreview(preview);

    } catch (error) { if (isCurrent()) notice(error.message); } finally {
      previewLoading = false;
      updateSelection();
    }
  }
  async function showActionPreview(preview) {
      state.preview = preview; state.targetPage = 0;
      $('confirmAction').disabled = confirming || !preview.effective;
      $('actionAvailability').textContent = 'Work is saved before processing. Closing this page does not cancel it.';
      const action = preview.action === 'ORGANIZE' ? `Apply to ${fmt(preview.effective)} messages` : preview.action === 'MARK_READ' ? `Mark ${fmt(preview.effective)} as read` : preview.action === 'ARCHIVE' ? `Archive ${fmt(preview.effective)} messages` : preview.action === 'MARK_READ_ARCHIVE' ? `Mark as Read + Archive ${fmt(preview.effective)} messages` : `Move ${fmt(preview.effective)} to ${outlook ? 'Deleted Items' : 'Trash'}`;
      $('confirmTitle').textContent = 'Review ' + actionName(preview.action).toLowerCase(); $('confirmAction').textContent = action;
      $('confirmAction').classList.toggle('danger', preview.action === 'TRASH');
      $('confirmScope').textContent = `${preview.filter.scope === 'inbox' ? 'Inbox' : preview.filter.scope === 'indexed' ? 'All indexed mail' : preview.filter.suggestion === 'large' && !outlook ? 'Received and Sent mail' : 'All Mailbox'} · ${preview.filter.start || 'All indexed dates'}${preview.filter.end ? ' through ' + preview.filter.end : ''} · ${preview.filter.zone}${preview.filter.q ? ' · Search: ' + preview.filter.q : ''}`;
      if (previewFromSenders) $('confirmScope').textContent += ' · ' + $('groupingCount').textContent;
      $('confirmCounts').textContent = `${fmt(preview.selected)} fixed targets · ${fmt(preview.effective)} eligible changes · ${fmt(preview.skipped)} will be skipped (${fmt(preview.protectedCount)} protected) · ${bytesText(preview.knownBytes)} selected${preview.unknownSizes ? ' · '+fmt(preview.unknownSizes)+' sizes unknown' : ''}`;
      $('confirmCoverage').textContent = `Indexed messages only · ${dateText(preview.coverage.oldest)}–${dateText(preview.coverage.newest)} · As of ${dateText(preview.coverage.as_of)}`;
      $('actionExplanation').textContent = preview.action === 'ORGANIZE' ? `${preview.destination.mode === 'NEW' ? 'Create' : 'Use existing'} ${outlook ? 'folder' : 'label'} “${preview.destination.name}”. ${outlook || preview.destination.skipInbox ? 'Messages leave the Inbox.' : 'Messages stay in the Inbox.'} No future-mail rule is created. New destinations are created only after you confirm Apply.` : preview.action === 'ARCHIVE' ? (outlook ? 'Move selected emails to Archive.' : 'Archive removes mail from Inbox but does not free storage.') : preview.action === 'TRASH' ? outlook ? 'Move selected emails to Deleted Items.' : `Move to Trash may reclaim approximately ${bytesText(preview.effectiveBytes)} after Gmail permanently deletes the emails, normally after 30 days.` : preview.action === 'MARK_READ_ARCHIVE' ? (outlook ? 'Mark selected emails as read, then move them to Archive.' : 'Gmail removes the Unread and Inbox labels in one request. Messages already read and outside Inbox are skipped.') : 'Already-read messages will be skipped.';
      $('exactTargets').replaceChildren();
      $('targetRange').textContent = 'Loading selected emails…';
      $('targetPrev').disabled = $('targetNext').disabled = true;
      $('confirm').querySelector('.dialog-notice')?.remove(); $('confirm').showModal(); await loadTargets();
  }
  document.addEventListener('tidymail:rule-existing-preview', event => {
    if (confirming || previewLoading) return;
    const ruleDialog = $('inboxRule');
    if (ruleDialog?.open) ruleDialog.close();
    previewFromSenders = false; previewFromRule = true;
    showActionPreview(event.detail).catch(error => notice(error.message));
  });
  async function loadTargets() {
    if (!state.preview) return; const id = state.preview.id, page = state.targetPage;
    try { const result = await api(`previews/${id}/targets?page=${page}`); if (state.preview?.id !== id || state.targetPage !== page) return; $('exactTargets').replaceChildren(); for (const target of result.rows) { const row = node('div', undefined, 'target'); row.append(node('strong', target.subject_header || '(No subject)'), node('div', `${target.sender_address} · ${dateText(target.received_at)}`), node('small', target.reason, 'muted')); $('exactTargets').append(row); } $('targetRange').textContent = `${result.rows.length ? page * 10 + 1 : 0}–${page * 10 + result.rows.length} of ${fmt(result.total)}`; $('targetPrev').disabled = page === 0; $('targetNext').disabled = (page + 1) * 10 >= result.total; } catch (error) { notice(error.message); invalidatePreview(); }
  }
  function invalidateCorrection() {
    correctionRequest++;
    correctionDraft = null; $('applyCorrection').disabled = true; $('correctionAffected').replaceChildren();
  }
  async function openCorrections(groups, operation) {
    const epoch = filterEpoch, revision = state.revision;
    invalidateCorrection();
    const request = correctionRequest;
    try {
      const [history, ...members] = await Promise.all([api('corrections'), ...groups.map(group =>
        api(`groups/${group.id}/grouping`))]);
      if (epoch !== filterEpoch || revision !== state.revision || request !== correctionRequest) return;
      if (members.some(result => result.revision !== revision)) throw new Error('Grouping changed. Reload and try again.');
      correctionGroups = groups.map((group, i) => ({ ...group, units: members[i].units }));
      $('correctionGroups').replaceChildren(...correctionGroups.map(group => {
        const names = [...new Set(group.units.flatMap(u => u.addresses))];
        const details = node('details'); details.append(node('summary', `${group.label} · ${names.length} addresses`), node('p', names.join(', ')));
        return details;
      }));
      $('resetCorrection').replaceChildren();
      for (const item of history) {
        const option = node('option', `${item.kind} · ${item.label || dateText(item.created_at)}`);
        option.value = item.public_id; $('resetCorrection').append(option);
      }
      $('splitAddresses').replaceChildren();
      if (groups.length === 1) {
        const addresses = new Set(correctionGroups[0].units.filter(u => u.kind !== 'MESSAGE').flatMap(u => u.addresses));
        for (const address of [...addresses].sort()) {
          const label = node('label'), box = node('input'); box.type = 'checkbox'; box.value = address;
          box.onchange = invalidateCorrection;
          label.append(box, document.createTextNode(address));
          $('splitAddresses').append(label);
        }
      }
      $('mergeLabel').value = ''; $('remainderLabel').value = '';
      $('correctionKind').value = operation;
      $('correctionKind').querySelector('[value=MERGE]').disabled = groups.length < 2;
      $('correctionKind').querySelector('[value=SPLIT]').disabled = groups.length !== 1;
      correctionFields();
      if (!$('correction').open) $('correction').showModal();
    } catch (error) { if (epoch === filterEpoch) notice(error.message); }
  }
  function correctionFields() {
    const op = $('correctionKind').value;
    $('splitFields').hidden = op !== 'SPLIT'; $('resetLabel').hidden = op !== 'RESET';
    $('groupName').hidden = op === 'RESET';
    invalidateCorrection();
  }
  async function previewCorrection() {
    invalidateCorrection();
    const operation = $('correctionKind').value, generation = correctionRequest;
    try {
      const units = operation === 'MERGE' ? correctionGroups.map(group => group.units.find(u => u.kind !== 'MESSAGE')?.id) : [];
      const addresses = operation === 'SPLIT' ? [...$('splitAddresses').querySelectorAll(':checked')].map(box => box.value) : [];
      const label = $('mergeLabel').value.trim();
      if (operation !== 'RESET' && !label) throw new Error('Enter a group name.');
      if (operation === 'MERGE' && units.some(id => !id)) throw new Error('Fresh sender metadata is needed for all groups.');
      if (operation === 'SPLIT' && !addresses.length) throw new Error('Select at least one address.');
      const request = { operation, units, addresses, group: operation === 'SPLIT' ? correctionGroups[0].id : null,
        label, remainderLabel: $('remainderLabel').value.trim(),
        resetId: operation === 'RESET' ? $('resetCorrection').value : null, revision: state.revision };
      const review = await api('corrections/preview', request);
      if (!$('correction').open || request.revision !== state.revision || generation !== correctionRequest) return;
      correctionDraft = request;
      $('correctionAffected').textContent = `${fmt(review.affected.length)} sender identities in this change. ${review.explanation}`;
      $('applyCorrection').disabled = false;
    } catch (error) { $('correctionAffected').textContent = error.message; }
  }
  async function applyCorrection() { if (!correctionDraft) return; $('applyCorrection').disabled = true; try { await api('corrections', correctionDraft); $('correction').close(); state.focused = null; composition = null; filtersChanged(); notice('Grouping updated. Draft selection was cleared.'); } catch (error) { $('correctionAffected').textContent = error.message; } }

  function bytesText(value) {
    if (value === null || value === undefined) return 'Size unknown';
    const bytes = Number(value); return bytes >= 1000000 ? `${(bytes / 1000000).toFixed(1)} MB` : bytes >= 1000 ? `${(bytes / 1000).toFixed(1)} KB` : `${bytes} bytes`;
  }
  function calendarDate(value) {
    if (!value) return 'unknown';
    const match=/^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z)?)?$/.exec(value);
    if (!match) return 'unknown';
    const [,year,month,day,hour,minute,second,utc]=match;
    if (utc) return dateTimeText(`${year}-${month}-${day}T${hour}:${minute}:${second}Z`);
    return `${month}/${day}/${year}${hour ? ' at '+hour+':'+minute : ' (all day)'}`;
  }
  function calendarDuration(value) {
    const match=/^\+?P(?:(\d+)W|(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?)$/.exec(value || '');
    if(!match)return 'Duration needs interpretation';
    const units=['week','day','hour','minute','second'];
    const parts=match.slice(1).map((v,i)=>v && Number(v)>0 ? `${Number(v)} ${units[i]}${Number(v)===1 ? '' : 's'}` : '').filter(Boolean);
    return parts.length ? 'Duration '+parts.join(', ') : 'Zero duration';
  }
  const evidenceViews=new Map(), openEvidence=new Set();
  const recommendationNames = { KEEP_IN_INBOX:'Keep in inbox', KEEP_STORED:'Keep stored', ARCHIVE:'Archive', SUGGEST_TRASH:'Consider Trash', REVIEW:'Review', NONE:'Excluded' };
  const importanceNames = { IMPORTANT:'Important', ROUTINE:'Routine', UNKNOWN:'Unknown' };
  const recommendationReasons = {
    EXCLUDED_MAIL:'Excluded from cleanup suggestions.', UNKNOWN_MAILBOX:'Folder information is incomplete.', MANUAL_DECISION:'Your saved recommendation.',
    SENT_COPY:'Keep this Sent copy; review its size separately.', EVIDENCE_STALE:'Information changed; assessment needs updating.',
    EVIDENCE_UNRESOLVED:'There is not enough evidence for a more specific recommendation.', EVIDENCE_CONFLICT:'Available evidence conflicts.',
    CONTEXT_REQUIRED:'The message needs more context.', PROTECTED_CONTENT:'Contains correspondence or records worth keeping.', IMPORTANT_MESSAGE:'Marked important.',
    CRITICAL_SIGNAL_UNKNOWN:'Protective labels are not yet established.', ROUTINE_BULK:'Routine marketing, newsletter or social content.',
    STATEMENT_POLICY:'Your statement retention preference.', CALENDAR_REVIEW:'Calendar invite; event status is unresolved.', NO_DISPOSAL_RULE:'No established cleanup rule for this email.',
    RECENT_UNREAD_ACTION:'Recent unread financial mail is held for review.', UNKNOWN_READ_OR_DATE:'Read status or date is unclear.',
    READ_OR_OLD_BILL:'Read or older financial mail under your retention preference.', BILL_RECORD_POLICY:'Your preference keeps bills.',
    PRIOR_SENT_CORRESPONDENCE:'You have previously emailed this exact address.', OBSERVED_CALENDAR:'A calendar invite was observed.'
  };
  const recommendationLabelNames = { CONTACT:'Contact',CALENDAR_INVITE:'Calendar invite',LARGE_MESSAGE:'Large email',HAS_ATTACHMENTS:'Has attachments',HUMAN_CORRESPONDENCE:'Personal',PERSONAL:'Personal',SENT:'Sent',GMAIL_PROMOTIONS:'Gmail: Promotions',
    CALENDAR_RESPONDED:'Calendar: responded',CALENDAR_NEEDS_RESPONSE:'Calendar: needs response',CALENDAR_CANCELLED:'Calendar: cancelled',
    OLDER_DOCUMENT_COMMENT:'Document comment older than 30 days' };
  function recommendationLabel(value) { return recommendationLabelNames[value] || value.toLowerCase().replaceAll('_',' ').replace(/^./,c=>c.toUpperCase()); }
  const recommendationDrafts=new Map(), openRecommendationEditors=new Set();
  function appendMessageLabels(text, message, recommendation, expanded) {
    const labels = new Set((recommendation?.labels || []).filter(label => !['UNKNOWN','SENT','CALENDAR_INVITE','PAST_EVENT'].includes(label)).map(recommendationLabel));
    if (!['UNCLASSIFIED','CALENDAR_INVITE'].includes(message.category)) labels.add(recommendationLabel(message.category));
    if (message.contact) labels.add('Contact');
    if (message.attachmentState === 'PRESENT') labels.add('Has attachments');
    const reasons = new Map(Object.entries(recommendation?.labelReasons || {}).map(([key,value]) => [recommendationLabel(key),value]));
    const response = calendarChecks.get(message.id)?.snapshot || message.calendarResponse;
    if (response && Date.parse(response.expiresAt) > Date.now()) {
      const label = { RESPONDED: 'Calendar: responded', NEEDS_RESPONSE: 'Calendar: needs response', CANCELLED: 'Calendar: cancelled' }[response.state];
      if (label) { labels.add(label); reasons.set(label, 'Calendar checked ' + dateTimeText(response.checkedAt) + '. This does not establish whether an update was reviewed.'); }
    }
    for (const id of recommendation?.providerLabelIds || []) {
      if (!Object.hasOwn(gmailLabelNames,id)) continue;
      const label = 'Gmail label: ' + gmailLabelNames[id];
      labels.add(label);
      reasons.set(label,'Gmail reports this custom label on the email.');
    }
    if (recommendation?.relayRecipients?.length)
      text.append(node('small', `Relay recipient: ${recommendation.relayRecipients.join(', ')}`, 'muted'));
    if (!labels.size) {
      if (message.calendarInvite) text.append(node('small', 'Calendar status not confirmed', 'muted'));
      return;
    }
    const detail = node('details', undefined, 'evidence');
    detail.dataset.messageLabels = message.id;
    detail.open = expanded;
    const summary = node('summary', undefined, 'label-summary');
    summary.setAttribute('aria-label', 'Inspect labels: ' + [...labels].join(', '));
    for (const label of labels) summary.append(node('span', label, 'message-tag'));
    detail.append(summary);
    for (const label of labels)
      detail.append(node('small', label + ' — ' + (reasons.get(label) || 'From existing saved classification or message evidence.'), 'muted'));
    text.append(detail);
  }
  function appendRecommendation(text,m,view) {
    const result=view?.freshness === 'CURRENT' ? view.result : null;
    if (result) {
      text.append(node('strong',recommendationNames[result.action] || 'Review'));
      text.append(node('small',`Importance: ${importanceNames[result.importance] || 'Unknown'} · ${recommendationReasons[result.reason] || 'Review the available evidence.'}`,'muted'));
    } else {
      text.append(node('small',view?.freshness === 'STALE' ? 'Recommendation updating — saved information changed.' : 'Not assessed yet. Build recommendations to assess this email.','muted'));
    }
    const edit=node('details'),summary=node('summary','Adjust recommendation'),form=node('div',undefined,'recommendation-edit');
    const action=node('select'),importance=node('select');
    action.setAttribute('aria-label','Recommendation for '+(m.subject || 'this email'));
    importance.setAttribute('aria-label','Importance for '+(m.subject || 'this email'));
    for(const [value,label] of [['','Automatic'],...Object.entries(recommendationNames).filter(([value])=>value !== 'NONE')]) {const o=node('option',label);o.value=value;action.append(o);}
    for(const [value,label] of [['','Automatic'],...Object.entries(importanceNames)]) {const o=node('option',label);o.value=value;importance.append(o);}
    const draft=recommendationDrafts.get(m.id);
    action.value=draft?.action ?? view?.correction?.action ?? ''; importance.value=draft?.importance ?? view?.correction?.importance ?? '';
    const rememberDraft=()=>recommendationDrafts.set(m.id,{action:action.value,importance:importance.value,revision:draft?.revision ?? view?.correction?.revision ?? 0});
    action.onchange=rememberDraft;importance.onchange=rememberDraft;
    edit.open=openRecommendationEditors.has(m.id);
    edit.addEventListener('toggle',()=>{if(edit.isConnected){if(edit.open)openRecommendationEditors.add(m.id);else openRecommendationEditors.delete(m.id);}});
    const actionLabel=node('label','Recommendation '),importanceLabel=node('label','Importance ');actionLabel.append(action);importanceLabel.append(importance);
    const save=node('button','Save recommendation');save.type='button';save.dataset.focusKey='recommendation-save-'+m.id;
    save.disabled=!view;
    save.onclick=async()=>{
      save.disabled=true;
      try {
        await api(`messages/${m.id}/recommendation`,{action:action.value || null,importance:importance.value || null,revision:recommendationDrafts.get(m.id)?.revision ?? view.correction.revision});
        recommendationDrafts.delete(m.id);openRecommendationEditors.delete(m.id);
        selectionGeneration++;selected.clear();invalidatePreview();notice('Recommendation saved for this email. No mail was moved.');
        state.details=null;await loadPage();await loadRecommendations();
      } catch(error) { notice(error.message);save.disabled=false; }
    };
    form.append(actionLabel,importanceLabel,save,node('small','Automatic clears that correction. This changes your recommendation only.','muted'));edit.append(summary,form);text.append(edit);
  }
  let recommendationStatus=null,recommendationStatusBusy=false;
  function renderRecommendationStatus(status) {
    recommendationStatus=status;
    $('localLabelProgress').textContent = status.error ? 'Local label processing needs a retry.' :
      status.localChecked < status.indexed ? `${fmt(status.localChecked)} of ${fmt(status.indexed)} indexed emails checked for local labels.` :
      'Local rules have checked all indexed emails. Some messages may have no matching label.';
    $('localLabelProgress').textContent += ' Calendar labels refresh gradually with Calendar access and use checks from the last hour. Unchecked or uncertain events remain unlabelled.';
    $('recommendationStatus').textContent=`${fmt(status.assessed)} of ${fmt(status.indexed)} emails have saved local recommendations${status.queued ? ' · updates queued' : ''}${status.error ? ' · refresh stopped; retry to continue' : !status.workerAvailable ? ' · automatic local refresh is disabled in this session' : status.enabled ? ' · refreshing locally' : ' · paused'}`;
    if(outlook) $('labelingCoverage').textContent = 'Automatic local labels';
    else if(status.labeling) {
      const c=status.labeling;
      const coverage=`${fmt(c.semanticCurrent)} of ${fmt(c.indexed)} received emails have current new-label results · ${fmt(Math.max(0,c.indexed-c.semanticCurrent))} still need labeling · ${fmt(c.mimeObserved)} inspected for calendar evidence`;
      $('labelingCoverage').textContent=coverage;
      $('labelingDialogCoverage').textContent=coverage;
      $('labelingCoverage').textContent+=`${c.pending ? ' · '+fmt(c.pending)+' checks queued or running' : ''}${c.paused ? ' · '+fmt(c.paused)+' checks paused' : ''}${c.unresolved ? ' · '+fmt(c.unresolved)+' have uncertain labels' : ''}${c.failed ? ' · '+fmt(c.failed)+' checks need review' : ''} · Filter counts include only labels already found.`;
    }
    $('recommendationProcessing').textContent=status.enabled ? 'Pause recommendations' : status.assessed ? 'Resume recommendations' : 'Build recommendations';
    $('recommendationProcessing').disabled=recommendationStatusBusy || !status.workerAvailable;
  }
  async function loadRecommendations() {
    try {
      const prior=recommendationStatus,status=await api('recommendations');renderRecommendationStatus(status);
      if(prior && (prior.lastRun !== status.lastRun || prior.labeling?.updatedAt !== status.labeling?.updatedAt)) {
        await loadPage(undefined,true,true);
      }
    } catch(error) { $('recommendationStatus').textContent='Recommendation status unavailable'; }
  }
  $('recommendationProcessing').onclick=async()=>{
    if(recommendationStatusBusy || !recommendationStatus)return;
    recommendationStatusBusy=true;$('recommendationProcessing').disabled=true;
    try { const status=await api('recommendations/processing',{enabled:!recommendationStatus.enabled});renderRecommendationStatus(status); }
    catch(error) { notice(error.message); }
    finally { recommendationStatusBusy=false;if(recommendationStatus)renderRecommendationStatus(recommendationStatus); }
  };
  function updateLabelSummary() {
    const count = labelChoices.filter(input => input.checked).length;
    $('shoppingExclusions').hidden = !labelChoices.some(input => input.value === 'SHOPPING' && input.checked);
    $('labelSummary').textContent = count ? `Labels (${count}, ${$('labelMatch').value === 'all' ? 'match all' : 'match any'})` : 'Labels';
  }
  function labelFilterChanged() { updateLabelSummary(); filtersChanged(); }
  for (const input of [...labelChoices, $('labelMatch'), $('excludeProtectedShopping')]) input.addEventListener('change', labelFilterChanged);
  $('labelFilter').addEventListener('toggle', () => { if ($('labelFilter').open) loadGmailLabels(); });
  $('clearLabels').onclick = () => { labelChoices.forEach(input => { input.checked = false; }); $('excludeProtectedShopping').checked = false; labelFilterChanged(); };
  for (const id of ['recommendation', 'importance']) $(id).addEventListener('change', filtersChanged);
  const calendarChecks = new Map();
  function appendCalendarResponse(text, m) {
    const view = calendarChecks.get(m.id), snapshot = view?.snapshot || m.calendarResponse;
    const labels = (state.details?.recommendations || []).find(r => r.message === m.id)?.labels || [];
    if (!snapshot && !m.calendarInvite && !labels.includes('CALENDAR_INVITE')
        && !evidenceViews.get(m.id)?.evidence?.calendars?.length) return;
    const box = node('div', undefined, 'evidence'), check = node('button', snapshot ? 'Refresh calendar response' : 'Check calendar response');
    check.type = 'button'; check.dataset.focusKey = 'calendar-' + m.id;
    check.disabled = !!view?.pending;
    if (view?.pending) box.append(node('p', 'Checking your calendar…'));
    if (view?.error) box.append(node('p', view.error));
    if (snapshot) {
      const names = { accepted: 'Accepted', declined: 'Declined', tentative: 'Tentative', needsAction: 'Needs response', cancelled: 'Cancelled', organizer: 'You organize this event', unknown: 'Unknown' };
      for (const event of snapshot.events) {
        const scope = { event: 'event', series: 'series', occurrence: 'this occurrence' }[event.scope];
        box.append(node('strong', 'Calendar: ' + (names[event.response] || 'Unknown') + (scope ? ' · ' + scope : '')));
        box.append(node('small', event.reason, 'muted'));
      }
      box.append(node('small', 'Checked ' + dateTimeText(snapshot.checkedAt) + (snapshot.state === 'UNKNOWN' ? ' · Response remains unknown.' : Date.parse(snapshot.expiresAt) <= Date.now() ? ' · Refresh needed for response filters.' : ' · Included in response filters for one hour.'), 'muted'));
    }
    box.append(node('small', 'RSVP does not establish whether you reviewed an update. Checking does not change the event or email.', 'muted'));
    check.onclick = async () => {
      if (calendarChecks.get(m.id)?.pending) return;
      calendarChecks.set(m.id, { pending: true, snapshot }); renderDetail();
      try {
        const result = await api(`messages/${m.id}/calendar`, {});
        calendarChecks.set(m.id, { snapshot: result });
        await loadPage(undefined, false, true);
      } catch (error) { calendarChecks.set(m.id, { snapshot, error: error.message }); }
      finally { renderDetail(); }
    };
    box.append(check); text.append(box);
  }
  function appendEvidence(text,m) {
    if (state.suggestion === 'large' && m.sent) text.append(node('small', 'Sent', 'evidence'));
    if (m.sizeEstimate > 1000000) text.append(node('small', 'Large email', 'evidence'));
    if (m.contact) text.append(node('small', 'Contact', 'evidence'));
    text.append(node('small', `${bytesText(m.sizeEstimate)} · ${m.attachmentState === 'PRESENT' ? 'Attachment found' : m.attachmentState === 'ABSENT' ? 'No attachments observed' : 'Attachments not checked'}`, 'muted'));
    if (m.snippet) text.append(node('p', m.snippet + (m.snippetState === 'TRUNCATED' ? '…' : ''), 'message-snippet'));
    else text.append(node('small', m.snippetState === 'ABSENT' ? 'No preview text available' : 'Preview text not checked', 'muted'));
    if (outlook) {
      if (m.outlookFolder) text.append(node('small', 'Folder · ' + m.outlookFolder, 'muted'));
      if (m.outlookCategories?.length) text.append(node('small', 'Outlook categories · ' + m.outlookCategories.join(', '), 'muted'));
      if (m.outlookWebLink) {
        const link = node('a', 'Open in Outlook'); link.href = m.outlookWebLink;
        link.target = '_blank'; link.rel = 'noopener noreferrer'; text.append(link);
      }
      appendCalendarResponse(text, m);
      return;
    }
    if (m.gmailWebLink) {
      const link = node('a', 'Open in Gmail'); link.href = m.gmailWebLink;
      link.target = '_blank'; link.rel = 'noopener noreferrer'; text.append(link);
    }
    appendCalendarResponse(text, m);
    const details=node('details'), summary=node('summary','Email details'), output=node('div'), check=node('button','Check details');
    details.open=openEvidence.has(m.id);
    details.addEventListener('toggle',()=>{if(details.isConnected){if(details.open)openEvidence.add(m.id);else openEvidence.delete(m.id);}});
    check.type='button'; check.dataset.focusKey='evidence-'+m.id;
    const view=evidenceViews.get(m.id);
    if (view?.pending) { check.disabled=true;output.append(node('p','Checking this email…')); }
    if (view?.error) output.append(node('p',view.error));
    if (view?.evidence) {
      const evidence=view.evidence;
      if (evidence.snippet?.text) output.append(node('p',evidence.snippet.text,'message-snippet'));
      if (evidence.observedAt) output.append(node('small',`Checked ${dateTimeText(evidence.observedAt)}`, 'muted'));
      output.append(node('small',evidence.structureState === 'TRUNCATED' ? 'Some email parts could not be checked.' : 'Email structure checked.','muted'));
      for (const part of evidence.parts || []) if (part.filename || part.calendar || part.inline) output.append(node('p',`${part.filename || part.mimeType}${part.inline ? ' · Inline content' : ''}${part.bytes == null ? '' : ' · '+bytesText(part.bytes)}`));
      for (const calendar of evidence.calendars || []) {
        output.append(node('p',calendar.conclusion));
        for (const event of calendar.events || []) output.append(node('small',`Starts ${calendarDate(event.start)} · ${event.end ? 'Ends '+calendarDate(event.end) : event.duration ? calendarDuration(event.duration) : 'No explicit end'}${event.timezone ? ' · '+event.timezone : ''}${event.recurring ? ' · Repeats or belongs to a series' : ''}${event.cancelled ? ' · Cancellation notice' : ''}`, 'muted'));
      }
      if (!(evidence.calendars || []).length) output.append(node('p',evidence.structureState === 'AVAILABLE' ? 'No calendar part observed.' : 'Calendar presence is unknown because some parts were not checked.'));
      if (view.stale) output.append(node('p','Related calendar details were checked elsewhere. Refresh the comparison to include them.'));
      for (const event of view.stale ? [] : evidence.reconciledEvents || []) {
        output.append(node('p',event.reason));
        if (event.latestObservedEndDate) output.append(node('small',`All-day schedule ends before ${event.latestObservedEndDate} (exclusive end date) · ${event.observedInstances} observed instance(s)`, 'muted'));
        if (event.latestObservedEnd) output.append(node('small',`Latest observed end: ${dateTimeText(event.latestObservedEnd)} (${zone}) · ${event.messages.length} contributing email(s)`, 'muted'));
      }
      const retryable=(evidence.calendars || []).some(c=>c.state === 'FAILED');
      const calendarPresent=(evidence.calendars || []).length>0;
      check.textContent=retryable ? 'Try checking calendar again' : calendarPresent ? 'Refresh calendar comparison' : 'Details checked';check.disabled=!retryable && !calendarPresent;
    }
    check.onclick=async()=>{
      if(evidenceViews.get(m.id)?.pending)return;
      evidenceViews.set(m.id,{pending:true});renderDetail();
      try {
        const evidence=await api(`messages/${m.id}/evidence`,{});
        const comparisons=new Map((evidence.reconciledEvents || []).map(e=>[e.uidHash+':'+e.organizerHash,JSON.stringify(e)]));
        for (const [id,other] of evidenceViews) if(id!==m.id && other.evidence && (other.evidence.reconciledEvents || []).some(e=>comparisons.has(e.uidHash+':'+e.organizerHash) && comparisons.get(e.uidHash+':'+e.organizerHash)!==JSON.stringify(e))) other.stale=true;
        evidenceViews.set(m.id,{evidence});
        await loadDetails();
      }
      catch(error) { evidenceViews.set(m.id,{error:error.message}); }
      if(evidenceViews.size>100)evidenceViews.delete(evidenceViews.keys().next().value);
      renderDetail();
    };
    details.append(summary,node('p','Checks this email’s preview, attachment names and calendar information. Only calendar files are read; other attachments stay unopened. Nothing is sent to a model.','muted'),check,output);text.append(details);
  }
  function setSuggestion(value) {
    state.suggestion = value;
    filtersChanged();
  }
  function syncSizeFilter() {
    const large = state.suggestion === 'large';
    $('largeEmails').checked = large;
    $('activeSuggestion').hidden = !large;
    document.querySelector('[data-scope="all"]').textContent = large && !outlook ? 'Received and Sent mail' : 'All Mailbox';
    const sent = labelChoices.find(input => input.value === 'SENT');
    sent.parentElement.hidden = outlook || !large;
    if (sent.parentElement.hidden) sent.checked = false;
  }
  $('largeEmails').onchange=()=>{const large = $('largeEmails').checked; composition = null; if (large) setScope('all'); setSuggestion(large ? 'large' : '');};
  $('clearSuggestion').onclick=()=>setSuggestion('');

  let labelingScope=null,labelingPreview=null,labelingBusy=false,labelingEpoch=0,labelingPolling=false,labelingRecentStamp='',labelingViewEpoch=0,labelingFinancialReview=null;
  const moneyFormat=new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',minimumFractionDigits:2,maximumFractionDigits:6});
  const money=value=>moneyFormat.format(Number(value || 0));
  const labelingSelection=()=>({mode:$('labelingMode').value,kind:$('labelingKind').value,limit:Number($('labelingLimit').value),messages:$('labelingMode').value==='SELECTED'?[...selected.keys()]:[]});
  function labelingButtons() {
    $('labelingPrepare').disabled=labelingBusy || !labelingPreview?.messages.length;
    for(const id of ['labelingMode','labelingKind','labelingLimit','labelingSpend','labelingNewLimit','labelingIncreaseReview','labelingIncreaseConfirm']) $(id).disabled=labelingBusy;
    for(const button of $('labelingRecent').querySelectorAll('button'))button.disabled=labelingBusy;
    for(const id of ['labelingStart','labelingPause','labelingRetry']) $(id).disabled=labelingBusy || $(id).dataset.unavailable==='true';
  }
  async function previewLabeling() {
    const epoch=++labelingEpoch;++labelingViewEpoch;labelingScope=null;labelingFinancialReview=null;labelingPreview=null;$('labelingReview').hidden=true;
    $('labelingCandidates').textContent='Checking locally saved messages…';labelingButtons();
    $('labelingSpendLabel').hidden=$('labelingKind').value==='MIME';
    try {
      const preview=await api('labeling/preview',labelingSelection());if(epoch!==labelingEpoch)return;labelingPreview=preview;
      $('labelingCandidates').textContent=`Selection preview: ${fmt(preview.matching)} received emails in this scope · ${fmt(preview.eligible)} eligible for new checks · next batch: ${fmt(preview.messages.length)} emails. This batch does not process the entire mailbox.${preview.previouslySubmitted ? ' '+fmt(preview.previouslySubmitted)+' already have a paid submission and will not be automatically resubmitted.' : ''}`;
      $('labelingAllowance').textContent=$('labelingKind').value==='MIME' ? 'No Jev cost. Exact Gmail reads and byte limits will be shown before starting.' :
        `Shared new-label spending: ${money(preview.reported)} of ${money(preview.allowance)}. Each request must reserve ${money(preview.reservation)} before dispatch; the batch pauses early if the remaining allowance cannot cover that reservation. The shared limit is not increased by starting another batch.`;
    } catch(error) {if(epoch===labelingEpoch)$('labelingCandidates').textContent=error.message;}
    finally{if(epoch===labelingEpoch)labelingButtons();}
  }
  function renderLabelingScope(saved,running=false) {
    if(labelingScope?.hash!==saved.scope.hash){labelingFinancialReview=null;$('labelingIncreaseConfirm').hidden=true;$('labelingIncreaseSummary').textContent='';}
    labelingScope=saved.scope;const s=saved.scope,manifest=s.manifest,work=saved.work;
    $('labelingReview').hidden=false;
    const mime=manifest.kind==='MIME',snippet=manifest.messages.some(row=>row.variant==='SNIPPET'),done=work.filter(w=>w.state==='DONE').length,
      failed=work.filter(w=>['FAILED','STALE','UNKNOWN'].includes(w.state)).length,pending=work.length-done-failed;
    $('labelingDisclosure').textContent=mime ?
      `Read MIME structure for these ${fmt(work.length)} exact emails from Gmail: at most ${fmt(manifest.maxRequests)} requests, ${bytesText(manifest.maxResponseBytes)} per response and ${bytesText(manifest.maxTotalMimeBytes)} total. No snippets, bodies or calendar attachment downloads. No mailbox changes.` :
      `Send these ${fmt(work.length)} exact emails' sender addresses/names, subjects, reply/unsubscribe flags and Gmail categories${snippet?' and saved snippets (up to 1,024 UTF-8 bytes each)':''} to TypeSafe Jev through OpenRouter (${manifest.destination}). TypeSafe only, zero retention, data collection denied, no fallback. No bodies, ${snippet?'':'snippets, '}recipients, attachments or correspondent history. Maximum ${money(manifest.maxSpend)} for this batch, also subject to the shared allowance. No mailbox changes.`;
    if($('labelingManifest').dataset.review!==s.id+':'+s.hash) {
      $('labelingTargets').replaceChildren();
      for(const row of manifest.messages) {
      const entry=node('details');entry.append(node('summary',`${row.subject || row.evidence?.subject || '(No subject)'} · ${row.sender || row.evidence?.address || row.message}`));
      const fields=node('pre',JSON.stringify(mime ? {message:row.message,sender:row.sender,subject:row.subject} : row.request.state.email_0,null,2));
      fields.style.whiteSpace='pre-wrap';fields.style.overflowWrap='anywhere';entry.append(fields);$('labelingTargets').append(entry);
      }
      // Preserve an open review and focus during progress polling.
      $('labelingManifest').textContent=JSON.stringify(manifest,null,2);$('labelingManifest').dataset.review=s.id+':'+s.hash;
    }
    const error=work.find(w=>w.error)?.error;
    $('labelingRunStatus').textContent=`${!saved.authorized ? 'Prepared; nothing sent' : !pending ? 'Batch finished' : saved.paused ? 'Batch paused' : running ? 'Processing this reviewed batch' : 'Batch saved; press Start / resume to continue'} · ${fmt(done)} completed · ${fmt(pending)} remaining · ${fmt(failed)} need review · ${money(saved.reported)} reported${Number(saved.reserved)>0 ? ' · '+money(saved.reserved)+' reserved or unresolved' : ''}${error ? ' · '+error.toLowerCase().replaceAll('_',' ') : ''}.`;
    $('labelingProgress').hidden=false;$('labelingProgress').max=Math.max(1,work.length);$('labelingProgress').value=done+failed;
    $('labelingStart').textContent=saved.authorized?'Start / resume reviewed batch':'Approve and start this exact batch';
    const funding=saved.spending;
    $('labelingIncrease').hidden=mime || !funding?.canIncrease;
    if(funding){
      $('labelingAllowance').textContent=`Shared labeling spending: ${money(funding.sharedReported)} of ${money(funding.sharedLimit)}${Number(funding.sharedReserved)>0?' · '+money(funding.sharedReserved)+' reserved or unresolved':''}. Batch total limit: ${money(funding.batchLimit)}. Each new request requires a ${money(funding.reservation)} reservation.`;
      const reasons={SHARED_LIMIT:`The remaining shared allowance (${money(Number(funding.sharedLimit)-Number(funding.sharedReported)-Number(funding.sharedReserved))}) cannot reserve the next request. Review a higher allowance to continue.`,BATCH_LIMIT:'This batch cannot fund the next request within its current total limit. Review a higher allowance to continue.',DEPLOYMENT_LIMIT:'The deployment spending checkpoint cannot fund the next request. Review a higher allowance to continue.',UNKNOWN_CHARGES:'Billing is unresolved. Review outstanding charges before increasing the allowance or resuming.',IN_FLIGHT:'Waiting for the current request to settle.'};
      $('labelingFunding').textContent=funding.recoveryAvailable && funding.blockedReason?'A saved response can be recovered without another paid request. Further labeling still requires funding.':funding.canStart?'The current limits can fund the next request.':reasons[funding.blockedReason] || 'Funding is unavailable.';
    } else $('labelingFunding').textContent='';
    if(funding?.recoveryAvailable)$('labelingStart').textContent='Recover saved responses';
    $('labelingStart').dataset.unavailable=String(!pending || running || work.some(w=>w.state==='UNKNOWN') && !funding?.recoveryAvailable || funding && !funding.canStart);
    $('labelingPause').hidden=!pending || !saved.authorized || saved.paused;
    $('labelingRetry').hidden=!mime || !work.some(w=>w.state==='FAILED');labelingButtons();
  }
  async function loadLabelingScope(id,progressOnly=false) {
    if(progressOnly && labelingScope?.id===id){const epoch=labelingViewEpoch,manifest=labelingScope.manifest,progress=await api(`labeling/scopes/${id}/progress`);if(epoch!==labelingViewEpoch || labelingScope?.id!==id)return;if(progress.status.scope.hash!==labelingScope.hash)return loadLabelingScope(id);progress.status.scope.manifest=manifest;renderLabelingScope(progress.status,progress.running);return;}
    const epoch=++labelingViewEpoch;
    const [saved,active]=await Promise.all([api(`labeling/scopes/${id}`),api(`labeling/scopes/${id}/running`)]);
    if(epoch===labelingViewEpoch)renderLabelingScope(saved,active.running);
  }
  async function loadLabelingRecent() {
    const batches=await api('labeling/scopes'),stamp=JSON.stringify(batches);if(stamp===labelingRecentStamp)return;labelingRecentStamp=stamp;
    const restore=rememberFocus($('labelingRecent'));$('labelingRecent').replaceChildren();
    if(!batches.length)$('labelingRecent').append(node('p','No batches prepared yet.'));
    for(const batch of batches){const button=node('button',`${batch.kind==='MIME'?'Calendar / attachment checks':'Jev labels'} · ${fmt(batch.completed)} of ${fmt(batch.total)} completed${batch.completed===batch.total ? ' · complete' : batch.paused ? ' · paused' : !batch.authorized ? ' · awaiting approval' : ''}`);button.type='button';button.disabled=labelingBusy;button.dataset.focusKey='labeling-batch-'+batch.id;button.onclick=()=>loadLabelingScope(batch.id).catch(error=>{$('labelingCandidates').textContent=error.message;});$('labelingRecent').append(button);}restore();
  }
  $('labelingOpen').onclick=async()=>{$('tools').close();$('labeling').showModal();try{await Promise.all([previewLabeling(),loadLabelingRecent()]);}catch(error){$('labelingCandidates').textContent=error.message;}};
  for(const id of ['labelingMode','labelingKind','labelingLimit','labelingSpend']) $(id).onchange=previewLabeling;
  $('labelingPrepare').onclick=async()=>{
    if(labelingBusy)return;labelingBusy=true;labelingButtons();
    try{const scope=await api('labeling/prepare',{selection:labelingSelection(),maxSpend:$('labelingKind').value==='MIME'?0:Number($('labelingSpend').value)});labelingScope=scope;await loadLabelingScope(scope.id,true);await loadLabelingRecent();}
    catch(error){$('labelingCandidates').textContent=error.message;}finally{labelingBusy=false;labelingButtons();}
  };
  async function labelingAction(operation) {
    if(labelingBusy||!labelingScope)return;labelingBusy=true;labelingButtons();
    const scope=labelingScope;
    try{await api(`labeling/scopes/${scope.id}/${operation}`,{hash:scope.hash});if(operation==='retry-mime')await api(`labeling/scopes/${scope.id}/start`,{hash:scope.hash});await loadLabelingScope(scope.id,true);await loadLabelingRecent();await loadRecommendations();}
    catch(error){$('labelingRunStatus').textContent=error.message;}finally{labelingBusy=false;labelingButtons();}
  }
  $('labelingStart').onclick=()=>labelingAction('start');$('labelingPause').onclick=()=>labelingAction('pause');$('labelingRetry').onclick=()=>labelingAction('retry-mime');
  $('labelingNewLimit').oninput=()=>{labelingFinancialReview=null;$('labelingIncreaseConfirm').hidden=true;$('labelingIncreaseSummary').textContent='Review the new total before confirming.';};
  $('labelingIncreaseReview').onclick=async()=>{
    if(labelingBusy||!labelingScope)return;labelingBusy=true;labelingButtons();const scope=labelingScope;
    try{
      const plan=await api(`labeling/scopes/${scope.id}/allowance-review`,{baseHash:scope.hash,newLimit:Number($('labelingNewLimit').value)});labelingFinancialReview=plan;
      const c=plan.change;$('labelingIncreaseSummary').textContent=`Batch total limit: ${money(c.batchBefore)} → ${money(c.newBatchLimit)} (${money(c.batchSpent)} already spent). Shared labeling allowance: ${money(c.sharedBefore)} → ${money(c.newSharedLimit)}.${Number(c.newDeploymentLimit)!==Number(c.deploymentBefore)?' Deployment checkpoint: '+money(c.deploymentBefore)+' → '+money(c.newDeploymentLimit)+'.':''} The same emails and requests are retained; completed emails will not be sent again. Confirmation changes only the limits. Resume is a separate action.`;
      $('labelingIncreaseConfirm').textContent=`Confirm ${money(c.newBatchLimit)} total limit`;$('labelingIncreaseConfirm').hidden=false;
    }catch(error){labelingFinancialReview=null;$('labelingIncreaseConfirm').hidden=true;$('labelingIncreaseSummary').textContent=error.message;}
    finally{labelingBusy=false;labelingButtons();}
  };
  $('labelingIncreaseConfirm').onclick=async()=>{
    if(labelingBusy||!labelingFinancialReview||!labelingScope)return;labelingBusy=true;labelingButtons();const plan=labelingFinancialReview,scope=labelingScope;
    try{await api(`labeling/scopes/${scope.id}/allowance`,{baseHash:plan.change.baseHash,newLimit:Number(plan.change.newBatchLimit),reviewHash:plan.hash});labelingFinancialReview=null;await loadLabelingScope(scope.id);$('labelingIncreaseSummary').textContent='New limit saved. The batch has not resumed; use Start / resume when ready.';$('labelingIncreaseConfirm').hidden=true;}
    catch(error){labelingFinancialReview=null;$('labelingIncreaseConfirm').hidden=true;$('labelingIncreaseSummary').textContent=error.message;}
    finally{labelingBusy=false;labelingButtons();}
  };
  $('labeling').addEventListener('close',()=>{$('labelingOpen').focus();loadPage();});
  setInterval(async()=>{if(!$('labeling').open||labelingBusy||labelingPolling)return;labelingPolling=true;
    try{if(labelingScope)await loadLabelingScope(labelingScope.id,true);await loadLabelingRecent();await loadRecommendations();}catch(error){$('labelingRunStatus').textContent=error.message;}finally{labelingPolling=false;}
  },3000);

  const categoryName = value => ({ PERSONAL: 'Personal', MARKETING: 'Marketing', NEWSLETTER: 'Newsletter', SOCIAL: 'Social', RECEIPT: 'Receipt', SECURITY: 'Security', NOTIFICATION: 'Notification', OTHER: 'Other', UNCERTAIN: 'Other', UNCLASSIFIED: 'Unclassified' }[value] || 'Unclassified');
  let classification = null, classificationLoading = false;
  const classifying = value => value && ['QUEUED', 'RUNNING'].includes(value.state);
  function renderClassification() {
    const active = classifying(classification), count = classification ? classification.done + classification.skipped : 0;
    $('classificationStart').disabled = !classification?.configured || (active && !classification?.automaticRun);
    $('classificationStart').textContent = classification?.state === 'FAILED' ? 'Retry classification' : 'Classify new or changed mail';
    $('classificationCancel').hidden = !active;
    $('classificationAutomatic').checked=!!classification?.automatic;
    const budget=classification?.budget;
    $('classificationContinue').hidden=!budget?.paused;
    $('classificationContinue').disabled=!budget?.canContinue;
    $('classificationAcknowledgeLabel').hidden=!budget?.unknown;
    $('classificationBudget').textContent=budget?.unknown ? 'Billing is unknown for an interrupted request. Check OpenRouter activity, then acknowledge before continuing.' : budget?.paused ? 'The $1 reported-spend checkpoint has been reached. Wait for requests to settle, then explicitly continue for another $1.' : 'Each $1 of newly reported spend pauses paid work. Already dispatched requests may add a small overshoot. Keep an external OpenRouter key cap for a hard limit.';
    $('classificationStart').disabled ||= !!budget?.paused;

    $('classificationProgress').hidden = !classification?.total;
    if (classification?.total) { $('classificationProgress').max = classification.total; $('classificationProgress').value = count; }
    $('classificationStatus').textContent = !classification?.configured ? 'Add OPENROUTER_API_KEY to your local .env file and restart TidyMail to enable classification.' :
      classification.state === 'NONE' ? 'Ready. No classification has been started.' : `${classification.state.toLowerCase()} · ${fmt(classification.done)} classified · ${fmt(classification.skipped)} skipped · ${fmt(Math.max(0, classification.total - count))} remaining of ${fmt(classification.total)}${classification.message ? ' · ' + classification.message : ''}`;
    $('classificationSummary').textContent = active ? `Legacy categories: ${fmt(count)} of ${fmt(classification.total)}` : classification?.state === 'COMPLETE' ? 'Legacy categories complete' : classification?.state === 'FAILED' ? 'Legacy categories need attention' : '';
    $('categoryCorrectionCount').textContent = selected.size ? `${fmt(selected.size)} selected ${selected.size===1?'message':'messages'}. This correction stays when Jev runs again.` : 'Select messages to correct their category.';
    $('categoryCorrectionApply').disabled = !selected.size || selected.size > 5000 || selectionBusy > 0;
  }
  async function pollClassification() {
    if (classificationLoading) return; classificationLoading = true;
    try {
      const previous = classification; classification = await api('classification'); renderClassification();
      if (previous && (previous.done !== classification.done || previous.state !== classification.state)) {
        if (!document.querySelector('dialog[open]')) await loadPage(undefined, true, previous.done !== classification.done);
        if (classifying(previous) && classification.state === 'COMPLETE') notice('Classification complete. Categories and sender groups updated; mailbox actions require your selection and confirmation.');
      }
    } catch (error) { if ($('classification').open) $('classificationStatus').textContent = error.message; } finally { classificationLoading = false; }
  }
  $('correctSelectedCategory').onclick=()=>{renderClassification();$('classification').showModal();$('categoryCorrection').focus();};
  $('classificationOpen').onclick = () => { $('tools').close(); renderClassification(); $('classification').showModal(); pollClassification(); };
  $('classification').addEventListener('close', () => { $('classificationOpen').focus(); loadPage(); });
  $('classificationStart').onclick = async () => { if (classificationLoading) return; classificationLoading = true; $('classificationStart').disabled = true; try { classification = await api('classification/start', {}); renderClassification(); } catch (error) { $('classificationStatus').textContent = error.message; } finally { classificationLoading = false; } };
  $('classificationAutomatic').onchange = async () => { try{ classification=await api('classification/automatic',{enabled:$('classificationAutomatic').checked});renderClassification(); }catch(error){$('classificationStatus').textContent=error.message;} };
  $('classificationContinue').onclick = async () => { if(classificationLoading)return; classificationLoading=true; try { classification=await api('classification/continue',{generation:classification.budget.generation,acknowledgeUnknown:$('classificationAcknowledge').checked}); $('classificationAcknowledge').checked=false; renderClassification(); }catch(error){$('classificationStatus').textContent=error.message;}finally{classificationLoading=false;} };
  $('classificationCancel').onclick = async () => { if (classificationLoading) return; classificationLoading = true; try { classification = await api('classification/cancel', {}); renderClassification(); } catch (error) { $('classificationStatus').textContent = error.message; } finally { classificationLoading = false; } };
  $('categoryCorrectionApply').onclick = async () => { const ids = [...selected.keys()]; $('categoryCorrectionApply').disabled = true; try { await api('classification/category', { ids, category: $('categoryCorrection').value, revision: state.revision }); clearSelection('Category correction saved.'); $('classification').close(); } catch (error) { $('classificationStatus').textContent = error.message; } finally { renderClassification(); } };
  pollClassification(); setInterval(pollClassification, 5000);

  function setScope(scope) {
    state.scope = scope;
    document.querySelectorAll('[data-scope]').forEach(button => {
      const active = button.dataset.scope === scope;
      button.classList.toggle('active', active);
      button.setAttribute('aria-pressed', String(active));
    });
  }
  document.querySelectorAll('[data-scope]').forEach(button => button.onclick = () => {
    if (state.scope === button.dataset.scope && !composition) return;
    composition = null;
    setScope(button.dataset.scope);
    filtersChanged();
  });
  $('search').addEventListener('input', filtersChanged);
  function sortChanged() {
    const unread = $('sort').value === 'unread';
    $('sortMessages').textContent = $('sort').value === 'messages' ? 'Total ↓' : 'Total ↕';
    $('sortUnread').textContent = unread ? 'Unread ↓' : 'Unread ↕';
    $('sortMessages').closest('th').setAttribute('aria-sort', $('sort').value === 'messages' ? 'descending' : 'none');
    $('sortUnread').closest('th').setAttribute('aria-sort', unread ? 'descending' : 'none');
    $('sortSummary').textContent = 'Sorted by ' + $('sort').selectedOptions[0].textContent.toLowerCase();
    filtersChanged();
  }
  $('sort').addEventListener('change', sortChanged);
  $('groupBy').addEventListener('change', () => {
    composition = null;
    state.data = null;
    filtersChanged();
  });
  $('sortMessages').onclick = () => { $('sort').value = 'messages'; sortChanged(); };
  $('sortUnread').onclick = () => { $('sort').value = 'unread'; sortChanged(); };
  $('detailUnreadOnly').onchange = () => { state.messagePage = 0; state.details = null; renderDetail(); loadDetails(); };
  for (const id of ['start','end']) $(id).addEventListener('change', filtersChanged);
  $('senderPageSize').onchange = () => { state.pageSize = Number($('senderPageSize').value); state.page = 0; loadPage(); };
  $('prevPage').onclick = () => { state.page--; loadPage('prevPage'); };
  $('nextPage').onclick = () => { state.page++; loadPage('nextPage'); };
  $('combineGroups').onclick = () => openCorrections([...senderSelection.values()], 'MERGE');
  $('splitGroup').onclick = () => openCorrections([...senderSelection.values()], 'SPLIT');
  $('selectPageSenders').onchange = () => {
    if(listLoading || selectionBusy)return;
    for(const g of state.data?.groups || []){if($('selectPageSenders').checked)senderSelection.set(g.id,g);else senderSelection.delete(g.id);}
    senderSelectionVersion++;renderSelectionControls();
  };
  $('selectAllMatchingSenders').onclick = async () => {
    if(listLoading || selectionBusy || !state.data)return;
    const epoch=filterEpoch, revision=state.revision, version=senderSelectionVersion, activeFilter=filter();
    const total=Number(state.data.summary.groups), all=new Map();
    selectionBusy++;renderSelectionControls();
    try {
      for(let page=0;all.size<total;page++){
        const result=await api('page?'+query({...activeFilter,page,pageSize:50}));
        if(epoch!==filterEpoch || revision!==state.revision || version!==senderSelectionVersion)return;
        if(result.revision!==revision || Number(result.summary.groups)!==total)throw new Error('Matching senders changed. Review the current list and select again.');
        const before=all.size;for(const g of result.groups)all.set(g.id,g);
        if(all.size===before)throw new Error('Matching senders could not be fully loaded. Your previous selection is retained.');
      }
      for(const [id,g] of all)senderSelection.set(id,g);
      senderSelectionVersion++;
    } catch(error){if(epoch===filterEpoch)notice(error.message);}
    finally{selectionBusy--;renderSelectionControls();}
  };
  $('clearGroups').onclick = () => { senderSelectionVersion++;senderSelection.clear(); renderSelectionControls(); };
  $('clear').onclick = () => clearSelection();
  $('review').onclick = () => openPreview();
  function createInboxRule(addresses, action, context) {
    const senders = [...new Set(addresses.filter(Boolean))].sort();
    if (!senders.length) { notice('Choose at least one exact sender address for a rule.'); return; }
    document.dispatchEvent(new CustomEvent('tidymail:create-rule', { detail: { senders, action, context, mailScope: state.scope, account: document.body.dataset.account } }));
  }
  $('createMessageRule').onclick = () => createInboxRule([...selected.values()].map(m => m.address), state.action, `From ${fmt(selected.size)} selected emails`);
  $('createSenderSelectionRule').onclick = async () => {
    const epoch = filterEpoch, revision = state.revision, generation = selectionGeneration, groups = [...senderSelection.keys()], action = $('senderAction').value;
    if (!groups.length || selectionBusy || listLoading) return;
    selectionBusy++; updateSelection();
    try {
      const rows = await api('selection', { filter: filter(), revision, groups });
      if (epoch !== filterEpoch || revision !== state.revision || generation !== selectionGeneration || groups.length !== senderSelection.size || groups.some(id => !senderSelection.has(id)) || action !== $('senderAction').value) return;
      createInboxRule(rows.map(m => m.address), action, `From ${fmt(groups.length)} selected sender groups · ${fmt(rows.length)} matching emails`);
    } catch (error) { notice(error.message); } finally { selectionBusy--; updateSelection(); }
  };
  $('reviewSenders').onclick = () => openPreview(true);
  $('messageAction').onchange = () => { state.action = $('messageAction').value; invalidatePreview(); };
  document.querySelectorAll('[data-tab]').forEach((button, index, tabs) => { button.onclick = () => { const changed = state.tab !== button.dataset.tab; state.tab = button.dataset.tab; if (changed) state.details = null; tabs.forEach(b => { b.classList.toggle('active', b === button); b.setAttribute('aria-selected', String(b === button)); b.tabIndex = b === button ? 0 : -1; }); $('detailContent').setAttribute('aria-labelledby', button.id); renderDetail(); if (changed) loadDetails(); }; button.onkeydown = e => { if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) { e.preventDefault(); const next = tabs[e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : (index + 1) % 2]; next.click(); next.focus(); } }; });
  document.querySelectorAll('[data-close]').forEach(button => button.onclick = () => $(button.dataset.close).close());
  $('confirm').addEventListener('close', () => {
    if (previewFromRule && $('inboxRule').open) { $('inboxRule').querySelector('[data-rule-existing]')?.focus({ preventScroll: true }); return; }
    const review = $(previewFromSenders ? 'reviewSenders' : 'review');
    (review.disabled ? $('senderTable') : review).focus();
  });
  $('correction').addEventListener('close', () => { invalidateCorrection(); ($('detailContent').querySelector('[data-focus-key=organize]') || $('senderTable')).focus(); });
  $('targetPrev').onclick = () => { state.targetPage--; loadTargets(); }; $('targetNext').onclick = () => { state.targetPage++; loadTargets(); };
  $('confirmAction').onclick = confirmJob;
  $('correctionKind').onchange = correctionFields;
  for (const id of ['mergeLabel', 'remainderLabel', 'resetCorrection']) $(id).addEventListener('input', invalidateCorrection);
  $('previewCorrection').onclick = previewCorrection; $('applyCorrection').onclick = applyCorrection;
  function keepFocusVisible() {
    const target = document.activeElement;
    if (!target || target === document.body || $('dock').hidden || $('dock').contains(target) || target.closest('dialog')) return;
    // The table is a temporary focus fallback while selection controls are disabled.
    // Its bottom can be far below the viewport; scrolling it into view jumps the page.
    if (target === $('senderTable')) return;
    const bottom = $('dock').getBoundingClientRect().top - 8, bounds = target.getBoundingClientRect();
    if (bounds.bottom > bottom) window.scrollBy({ top: bounds.bottom - bottom, behavior: 'instant' });
  }
  new ResizeObserver(() => { document.documentElement.style.setProperty('--dock-height', $('dock').hidden ? '0px' : $('dock').getBoundingClientRect().height + 'px'); requestAnimationFrame(keepFocusVisible); }).observe($('dock'));
  document.addEventListener('focusin', keepFocusVisible);
  const transferred = sessionStorage.getItem('tidymail-rule-filing-preview');
  function restoreRuleFilingPreview(){
  if(transferred){sessionStorage.removeItem('tidymail-rule-filing-preview');try{const saved=JSON.parse(transferred);if(String(saved.account)!==document.body.dataset.account)throw new Error('Mailbox changed. Review existing emails again from the rule.');previewFromRule=true;showActionPreview(saved.preview).catch(error=>notice(error.message));}catch(error){notice(error.message);}}
  }
  const initialJobs=pollJobs(); setInterval(pollJobs, 2000);
  loadRecommendations();setInterval(()=>{if(!document.hidden)loadRecommendations();},5000);
  const overlayOpeners = new Map();
  function openLocalDialog(id, opener) {
    const dialog = $(id);
    if (dialog.open) return;
    overlayOpeners.set(id, opener || document.activeElement);
    dialog.showModal();
  }
  $('filtersOpen').onclick = () => openLocalDialog('filtersDialog');
  $('settingsOpen').onclick = () => openLocalDialog('settings');
  $('toolsOpen').onclick = () => { $('settings').close(); openLocalDialog('tools', $('settingsOpen')); };
  document.querySelector('[data-settings-link]').onclick = event => { event.preventDefault(); openLocalDialog('settings', event.currentTarget); };
  document.querySelector('[data-activity-link]').href = '/activity';
  for (const id of ['filtersDialog', 'settings', 'tools']) $(id).addEventListener('close', () => {
    const opener = overlayOpeners.get(id);
    if (opener?.isConnected && !document.querySelector('dialog[open]')) opener.focus({ preventScroll: true });
  });
  $('detailClose').onclick = () => { $('detail').hidden = true; ([...$('senders').querySelectorAll('[data-focus-key]')].find(button => button.dataset.focusKey === 'sender-' + state.focused?.id) || $('senderTable')).focus({ preventScroll: true }); };
  function renderFilterChips() {
    syncSizeFilter();
    const chips = $('activeFilters'); chips.replaceChildren();
    const add = (label, clear) => { const button = node('button', label + ' ×', 'filter-chip'); button.type = 'button'; button.setAttribute('aria-label', 'Remove filter: ' + label); button.onclick = () => { clear(); updateLabelSummary(); filtersChanged(); }; chips.append(button); };
    for (const id of ['search', 'start', 'end', 'recommendation', 'importance']) {
      const input = $(id); if (!input.value) continue;
      const label = input.tagName === 'SELECT' ? input.selectedOptions[0].textContent : id === 'search' ? 'Search: ' + input.value : (id === 'start' ? 'From ' : 'Through ') + input.value;
      add(label, () => { input.value = ''; });
    }
    for (const input of labelChoices.filter(input => input.checked)) add(input.closest('label').textContent.trim(), () => { input.checked = false; });
    if (state.suggestion) add('Large emails', () => { state.suggestion = ''; $('activeSuggestion').hidden = true; });
    if (composition) add(composition.label, () => { composition = null; });
    if ($('excludeProtectedShopping').checked) add('Exclude Receipt and Security', () => { $('excludeProtectedShopping').checked = false; });
    $('filterCount').hidden = !chips.children.length; $('filterCount').textContent = chips.children.length;
  }
  $('resetFilters').onclick = () => {
    for (const id of ['search', 'start', 'end', 'recommendation', 'importance']) $(id).value = '';
    labelChoices.forEach(input => { input.checked = false; }); $('labelMatch').value = 'all'; $('excludeProtectedShopping').checked = false;
    composition = null; state.suggestion = ''; $('activeSuggestion').hidden = true; updateLabelSummary(); filtersChanged();
  };
  if (location.hash === '#settings') $('settingsOpen').click();
  if (location.hash === '#activity') document.querySelector('[data-activity-link]').click();
  // Initial sender focus clears mailbox selections; restore the independent rule preview afterward.
  Promise.all([initialJobs,loadPage()]).then(restoreRuleFilingPreview);
  setInterval(() => loadPage(undefined, true), 15000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) loadPage(undefined, true); });
})();
