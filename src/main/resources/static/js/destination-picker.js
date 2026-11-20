"use strict";
// One destination control for native rules and one-time filing. Reading choices never creates anything.
function rootAccount(root) { return root.closest('form')?.querySelector('[name="account"]')?.value || document.body.dataset.account || ''; }
window.TidyMailDestinationPicker = class {
  constructor(root, { provider, commitment, initial = null, onChange = () => {} }) {
    this.root = root; this.provider = provider; this.onChange = onChange;
    const label = provider === 'microsoft' ? 'folder' : 'label';
    root.replaceChildren();
    const make = (tag, text) => { const e = document.createElement(tag); if (text) e.textContent = text; return e; };
    const field = (text, control) => { const e = make('label', text); e.append(control); root.append(e); };
    this.mode = make('select');
    for (const [value, text] of [['EXISTING', `Use existing ${label}`], ['NEW', `Create new ${label}`]]) {
      const o = make('option', text); o.value = value; this.mode.append(o);
    }
    field('Destination', this.mode);
    this.search = make('input'); this.search.type = 'search'; this.search.placeholder = `Search ${label}s`; this.search.setAttribute('aria-label', `Search existing ${label}s`); root.append(this.search);
    this.existing = make('select'); this.existing.setAttribute('aria-label', `Existing ${label}`); root.append(this.existing);
    this.name = make('input'); this.name.maxLength = 100; this.name.placeholder = `New ${label} name`; this.name.setAttribute('aria-label', `New ${label} name`); root.append(this.name);
    this.note = make('p', `New ${label}s are created only when you ${commitment}. Closing this form creates nothing.`); this.note.className = 'muted'; root.append(this.note);
    this.status = make('p'); this.status.setAttribute('role', 'status'); root.append(this.status);
    this.retry = make('button', 'Refresh destinations'); this.retry.type = 'button'; this.retry.onclick = () => this.load(); root.append(this.retry);
    this.skip = make('input'); this.skip.type = 'checkbox'; this.skip.checked = !!initial?.skipInbox;
    if (provider !== 'microsoft') { field('Remove from Inbox ', this.skip); this.skip.parentElement.className = 'destination-skip'; }
    this.initial = initial; this.mode.value = initial?.mode === 'EXISTING' ? 'EXISTING' : initial ? 'NEW' : 'EXISTING';
    this.name.value = initial?.name || '';
    this.mode.onchange = () => this.sync(); this.search.oninput = () => this.renderOptions();
    this.existing.onchange = this.name.oninput = this.skip.onchange = () => this.onChange();
    this.sync(); this.load();
  }
  sync() { const existing = this.mode.value === 'EXISTING'; this.search.hidden = this.existing.hidden = !existing; this.name.hidden = existing; this.onChange(); }
  renderOptions() {
    const selected = this.existing.value || this.initial?.id;
    this.existing.replaceChildren(new Option('Choose a destination', ''));
    for (const d of this.options || []) if (d.name.toLowerCase().includes(this.search.value.toLowerCase()) || d.id === selected) this.existing.add(new Option(d.name, d.id));
    this.existing.value = selected || ''; this.onChange();
  }
  async load() {
    this.retry.disabled = true; this.status.textContent = 'Loading destinations…';
    try {
      const response = await fetch('/rules/destinations', { headers: { Accept: 'application/json', 'X-TidyMail-Account': rootAccount(this.root) }, cache: 'no-store' });
      if (!response.ok) throw new Error(); const data = await response.json(); if (!Array.isArray(data)) throw new Error();
      this.options = data; this.renderOptions(); this.status.textContent = data.length ? '' : 'No custom destinations yet. Choose Create new.';
    } catch { this.options = null; this.status.textContent = 'Could not load destinations. Refresh or reconnect mailbox permissions. Your choices are retained.'; }
    finally { this.retry.disabled = false; }
  }
  value() {
    if (this.mode.value === 'NEW') {
      const name = this.name.value.trim();
      if (!name || name.length > 100 || /[/\\\x00-\x1f]/.test(name)) throw new Error('Enter a new destination name without slashes (up to 100 characters).');
      if (this.options?.some(d => d.name.toLowerCase() === name.toLowerCase())) {
        this.mode.value = 'EXISTING'; this.existing.value = this.options.find(d => d.name.toLowerCase() === name.toLowerCase()).id; this.sync();
        throw new Error('This destination already exists. Review the existing destination now selected, then continue.');
      }
      return { mode: 'NEW', id: '', name, skipInbox: this.provider !== 'microsoft' && this.skip.checked };
    }
    const d = this.options?.find(d => d.id === this.existing.value);
    if (!d) throw new Error('Select an existing destination.');
    return { mode: 'EXISTING', id: d.id, name: d.name, skipInbox: this.provider !== 'microsoft' && this.skip.checked };
  }
};
