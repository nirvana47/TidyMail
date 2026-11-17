'use strict';
(() => {
  const dialog = document.getElementById('inboxRule');
  if (!dialog) return;
  const content = document.getElementById('inboxRuleContent');
  const error = document.getElementById('inboxRuleError');
  const title = document.getElementById('inboxRuleTitle');
  const actionMap = { MARK_READ: 'READ', MARK_READ_ARCHIVE: 'READ_ARCHIVE', ARCHIVE: 'ARCHIVE', TRASH: 'TRASH' };
  const actionNames = { READ: 'Mark as read', READ_ARCHIVE: 'Read and archive', ARCHIVE: 'Archive', TRASH: 'Move to Trash' };
  let account, generation = 0, pending = false, opener;

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
      const headers = { 'X-TidyMail-Account': account };
      if (body) body.set('inbox', 'true');
      const response = await fetch(target, { method: body ? 'POST' : 'GET', body, headers, credentials: 'same-origin' });
      const html = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (request !== generation || !dialog.open) return;
      if (!response.ok) throw new Error(html.querySelector('[role="alert"]')?.textContent || 'The rule request failed. Your draft fields are still here.');
      const destination = new URL(response.url);
      if (destination.origin !== location.origin || !destination.pathname.startsWith('/rules/')) throw new Error('Your session changed. Sign in again, then return to the inbox.');
      const editor = html.getElementById('ruleEditorContent');
      if (!editor) throw new Error('The rule editor could not be loaded. Your inbox selection is unchanged.');
      content.replaceChildren(document.importNode(editor, true));
      // Consent uses a separate tab so it does not discard the inbox or the saved draft.
      for (const link of content.querySelectorAll('a[href^="/rules/connect/"]')) { link.target = '_blank'; link.rel = 'noopener'; }
      if (defaults) {
        const name = content.querySelector('[name="name"]');
        if (name && !name.value) name.value = `${actionNames[defaults.action]} · ${defaults.senders.join(', ')}`.slice(0, 100);
      }
      const result = content.querySelector('[data-inbox-rule-result]');
      title.textContent = result ? 'Rule status' : 'Create rule';
      const preview = content.querySelector('[data-rule-preview]');
      if (preview) preview.scrollIntoView({ block: 'start' });
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
    account = event.detail.account; generation++; opener = document.activeElement;
    const ruleAction = actionMap[action] || 'READ_ARCHIVE';
    title.textContent = 'Create rule';
    document.getElementById('inboxRuleContext').textContent = `${context} · ${senders.join(', ')}`;
    content.replaceChildren(document.createTextNode('Loading rule editor…'));
    dialog.showModal();
    load('/rules/new?' + new URLSearchParams({ sender: senders.join(', '), action: ruleAction }), null, { senders, action: ruleAction });
  });
  content.addEventListener('submit', event => {
    const form = event.target;
    event.preventDefault();
    if (pending || !form.reportValidity()) return;
    load(form.getAttribute('action'), new URLSearchParams(new FormData(form)));
  });
  content.addEventListener('click', event => {
    if (event.target.closest('[data-inbox-rule-done]')) { dialog.close(); return; }
    const link = event.target.closest('a');
    if (!link || link.target === '_blank') return;
    const target = new URL(link.href);
    if (target.origin === location.origin && /^\/rules\/(new|[0-9a-f-]{36})$/.test(target.pathname)) {
      event.preventDefault(); load(target);
    }
  });
  // An edited form must be saved and previewed again before its native rule can be enabled.
  content.addEventListener('input', event => {
    if (!event.target.closest('form[action$="/rules/save"]')) return;
    const preview = content.querySelector('[data-rule-preview]');
    if (preview) { preview.remove(); report('Your changes need a fresh preview. Save the draft to review them.'); }
  });
  dialog.addEventListener('close', () => {
    generation++; pending = false; content.removeAttribute('aria-busy');
    if (opener?.isConnected) opener.focus({ preventScroll: true });
  });
})();
