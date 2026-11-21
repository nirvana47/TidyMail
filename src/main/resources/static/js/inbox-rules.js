'use strict';
(() => {
  const dialog = document.getElementById('inboxRule');
  if (!dialog) return;
  const content = document.getElementById('inboxRuleContent');
  const error = document.getElementById('inboxRuleError');
  const title = document.getElementById('inboxRuleTitle');
  const actionNames = { ORGANIZE: 'Organize', READ: 'Mark as read', READ_ARCHIVE: 'Read and archive', ARCHIVE: 'Archive', TRASH: 'Move to Trash' };
  let selectedSenders = [], contextText = "";
  let account, mailScope = 'inbox', generation = 0, pending = false, dirty = false, opener;

  // Tab-local, mailbox-scoped recovery. Never log or send the addresses to another service.
  const savedSelection = document.getElementById('savedRuleSelection');
  const storageKey = `tidymail-rule-selection:${document.body.dataset.account}`;
  function readSelection() {
    try {
      const saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null');
      return saved && String(saved.account) === document.body.dataset.account && Array.isArray(saved.senders) && saved.senders.length && saved.senders.every(v => typeof v === 'string') && ['inbox','all','indexed'].includes(saved.mailScope) ? saved : null;
    } catch (_) { return null; }
  }
  function updateSavedSelection() {
    if (!savedSelection) return;
    const saved = readSelection(); savedSelection.hidden = !saved || dialog.open;
    if (saved) savedSelection.querySelector('[data-saved-rule-summary]').textContent = `${saved.senders.length} sender addresses saved for rule setup in this tab.`;
  }
  function clearSavedSelection() { try { sessionStorage.removeItem(storageKey); } catch (_) {} updateSavedSelection(); }
  savedSelection?.querySelector('[data-resume-rule-selection]').addEventListener('click', () => {
    const saved=readSelection();if(saved)document.dispatchEvent(new CustomEvent('tidymail:create-rule',{detail:saved}));
  });
  savedSelection?.querySelector('[data-discard-rule-selection]').addEventListener('click', clearSavedSelection);
  updateSavedSelection();

  function report(message) { error.textContent = message; error.hidden = false; }
  async function load(url, body, defaults) {
    if (pending) return;
    const request = generation;
    pending = true; error.hidden = true; content.setAttribute('aria-busy', 'true');
    const buttons = [...content.querySelectorAll('button')].map(button => [button, button.disabled]);
    buttons.forEach(([button]) => { button.disabled = true; });
    try {
      const target = new URL(url, location.href);
      if (target.origin !== location.origin || !target.pathname.startsWith('/rules/')) throw new Error('Invalid rule destination.');
      target.searchParams.set('inbox', 'true');
      if(body)target.searchParams.delete('mailScope');else target.searchParams.set('mailScope', mailScope);
      const headers = { 'X-TidyMail-Account': account };
      if (body) { body.set('inbox', 'true'); body.set('mailScope', mailScope); }
      const response = await fetch(target, { method: body ? 'POST' : 'GET', body, headers, credentials: 'same-origin' });
      const html = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (request !== generation || !dialog.open) return;
      if (!response.ok) throw new Error(html.querySelector('[role="alert"]')?.textContent || 'The rule request failed. Your draft fields are still here.');
      const destination = new URL(response.url);
      if (destination.origin !== location.origin || !destination.pathname.startsWith('/rules/')) throw new Error('Your session changed. Sign in again, then return to the inbox.');
      const editor = html.getElementById('ruleEditorContent');
      if (!editor) throw new Error('The rule editor could not be loaded. Your inbox selection is unchanged.');
      content.replaceChildren(document.importNode(editor, true));
      document.dispatchEvent(new Event('tidymail:rule-form-loaded'));
      dirty = false;
      // Consent uses a separate tab so it does not discard the inbox or the saved draft.
      for (const link of content.querySelectorAll('a[href^="/rules/connect/"]')) { link.target = '_blank'; link.rel = 'noopener'; }
      if (defaults) {
        const name = content.querySelector('[name="name"]');
        if (name && !name.value) name.value = `${actionNames[defaults.action]} · ${defaults.senders.join(', ')}`.slice(0, 100);
      }
      const addition = content.querySelector('[data-rule-add-senders]');
      const result = content.querySelector('[data-inbox-rule-result], [data-rule-add-result]');
      if (result && (result.dataset.ruleState === 'ACTIVE' || result.dataset.additionSaved === 'true')) clearSavedSelection();
      const preview = content.querySelector('[data-rule-preview]');
      title.textContent = result ? result.querySelector('h2').textContent : addition ? (content.querySelector('[data-rule-add-review]') ? 'Review sender addition' : 'Choose existing rule') : preview ? 'Review rule' : 'Create rule';
      dialog.dataset.step = result ? 'success' : preview ? 'review' : 'setup';
      document.getElementById('inboxRuleContext').hidden = !!(preview || result || addition);
      dialog.querySelector('[data-rule-setup-help]').hidden = !!(preview || result || addition);
      dialog.scrollTop = 0; content.scrollTop = 0;
      title.tabIndex = -1;
      const focus = preview || result || addition ? title : content.querySelector('[name="name"]');
      focus?.focus({ preventScroll: true });

    } catch (failure) {
      if (request === generation && dialog.open) report(failure.message);
    } finally {
      if (request === generation) {
        pending = false; content.removeAttribute('aria-busy');
        buttons.forEach(([button, disabled]) => { if (button.isConnected) button.disabled = disabled; });
      }
    }
  }

  document.addEventListener('tidymail:create-rule', event => {
    if (dialog.open || pending) return;
    const { senders, action, context } = event.detail;
    dialog.querySelectorAll('.dialog-notice').forEach(node => node.remove());
    dirty = false; account = event.detail.account; mailScope = event.detail.mailScope || 'inbox'; generation++; opener = document.activeElement;
    selectedSenders = senders; contextText = context;
    let stored=true;
    try { sessionStorage.setItem(storageKey, JSON.stringify({senders, context, account, mailScope})); } catch (_) { stored=false; }
    dialog.showModal(); showStart(); updateSavedSelection();
    if(!stored)report('This browser could not save the sender selection. Keep this tab open until you finish rule setup.');
  });
  function showStart() {
    title.textContent = 'Rules'; error.hidden = true;
    document.getElementById('inboxRuleContext').textContent = `${contextText} · ${selectedSenders.join(', ')}`;
    document.getElementById('inboxRuleContext').hidden = false;
    dialog.querySelector('[data-rule-setup-help]').hidden = true;
    const description = document.createElement('p'); description.textContent = 'Set up how future incoming email from these exact addresses is handled.';
    const create = document.createElement('button'); create.type = 'button'; create.textContent = 'Create new rule';
    create.onclick = () => load('/rules/new?' + new URLSearchParams({ sender: selectedSenders.join(', '), action: 'ORGANIZE' }), null, { senders: selectedSenders, action: 'ORGANIZE' });
    const existing = document.createElement('button'); existing.type = 'button'; existing.className = 'primary'; existing.textContent = 'Add sender to existing rule';
    existing.onclick = () => load('/rules/add-senders?' + new URLSearchParams({ account, senders: selectedSenders.join(', ') }));
    content.replaceChildren(description, existing, create); existing.focus();
  }
  content.addEventListener('input', event => {
    if (!event.target.matches('[data-rule-search]')) return;
    const query=event.target.value.trim().toLowerCase();let count=0;
    for(const row of content.querySelectorAll('[data-rule-choice]')){row.hidden=!row.textContent.toLowerCase().includes(query);if(!row.hidden)count++;}
    content.querySelector('[data-rule-search-empty]').hidden=count>0;
  });
  content.addEventListener('submit', event => {
    const form = event.target;
    if (event.defaultPrevented) return;
    event.preventDefault();
    if (pending || !form.reportValidity()) return;
    load(form.getAttribute('action'), new URLSearchParams(new FormData(form)));
  });
  content.addEventListener('click', event => {
    const more=event.target.closest('[data-addition-more]');
    if(more){const expanded=more.getAttribute('aria-expanded')==='true';for(const row of content.querySelectorAll('[data-addition-extra="true"]'))row.hidden=expanded;more.setAttribute('aria-expanded',String(!expanded));more.textContent=expanded?'Show more examples':'Show fewer examples';return;}
    if (event.target.closest('[data-rule-start]')) { showStart(); return; }
    if (event.target.closest('[data-inbox-rule-done]')) { dialog.close(); return; }
    const link = event.target.closest('a');
    if (!link || link.target === '_blank' || link.hasAttribute('data-rule-navigation')) return;
    const target = new URL(link.href);
    if (target.origin === location.origin && /^\/rules\/(new|add-senders|[0-9a-f-]{36})$/.test(target.pathname)) {
      event.preventDefault(); load(target);
    }
  });
  document.addEventListener('tidymail:rule-filing-started', () => {
    const status=content.querySelector('[data-rule-filing-status]');
    if(status){status.textContent='Filing started. You can follow progress in Activity.';const link=document.createElement('a');link.href='/activity';link.textContent='View Activity';status.append(' ',link);}
    const button=content.querySelector('[data-rule-existing]');if(button){button.disabled=true;button.textContent='Filing started';}
  });
  // An edited form must be saved and previewed again before its native rule can be enabled.
  content.addEventListener('input', event => {
    if (!event.target.closest('form[action$="/rules/save"]')) return;
    dirty = true;
    const preview = content.querySelector('[data-rule-preview]');
    if (preview) { preview.remove(); report('Your changes need a fresh preview. Save the draft to review them.'); }
  });
  function reviewDiscard() {
    report('This rule has unsaved changes. Discard them and return to your mailbox?');
    const keep = document.createElement('button'); keep.type = 'button'; keep.textContent = 'Keep editing';
    const discard = document.createElement('button'); discard.type = 'button'; discard.textContent = 'Discard changes'; discard.className = 'danger';
    const actions = document.createElement('span'); actions.className = 'row'; actions.append(keep, discard); error.append(actions);
    keep.onclick = () => { error.hidden = true; content.querySelector('[name="name"]')?.focus(); };
    discard.onclick = () => { dirty = false; dialog.close(); };
    keep.focus(); error.scrollIntoView({ block: 'nearest' });
  }
  dialog.addEventListener('click', event => {
    if (dirty && event.target.closest('[data-close="inboxRule"], [data-inbox-rule-done]')) { event.preventDefault(); event.stopImmediatePropagation(); reviewDiscard(); }
  }, true);
  dialog.addEventListener('cancel', event => { if (dirty) { event.preventDefault(); reviewDiscard(); } });
  window.addEventListener('beforeunload', event => { if (dialog.open && dirty) { event.preventDefault(); event.returnValue = ''; } });
  dialog.addEventListener('close', () => {
    generation++; pending = false; content.removeAttribute('aria-busy'); updateSavedSelection();
    if (opener?.isConnected) opener.focus({ preventScroll: true });
  });
})();
