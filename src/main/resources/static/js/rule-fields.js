"use strict";
(() => {
  function setup(root = document) {
    for (const form of root.querySelectorAll('form[action$="/rules/save"]')) {
      if (form.dataset.fieldsReady) continue; form.dataset.fieldsReady = 'true';
      const destination = form.querySelector('[data-rule-destination]');
      let picker;
      const sync = () => {
        const organize = form.querySelector('[name="action"]')?.value === 'ORGANIZE';
        destination.hidden = !organize;
        if (organize && !picker) {
          const initial = destination.dataset.initialName ? { mode: destination.dataset.initialMode, id: destination.dataset.initialId, name: destination.dataset.initialName, skipInbox: destination.dataset.initialSkip === 'true' } : null;
          picker = new window.TidyMailDestinationPicker(destination.querySelector('[data-destination-picker]'), { provider: destination.dataset.provider, commitment: 'enable the rule', initial });
        }
      };
      form.querySelector('[name="action"]').addEventListener('change', sync);
      form.addEventListener('submit', async event => {
        const errorBox = destination.querySelector('[data-destination-error]'); errorBox.textContent = '';
        try {
          if (!destination.hidden) {
            const c = picker.value();
            for (const [name, value] of Object.entries({ destination: c.name, destinationId: c.id, destinationMode: c.mode, skipInbox: String(c.skipInbox) })) form.querySelector(`[name="${name}"]`).value = value;
          }
        } catch (error) { event.preventDefault(); errorBox.textContent = error.message; return; }
        if (form.closest('#inboxRule')) return;
        event.preventDefault();
        const button = form.querySelector('button.primary'); if (button.disabled) return;
        const body = new URLSearchParams(new FormData(form)); button.disabled = true;
        try {
          const response = await fetch(form.getAttribute('action'), { method: 'POST', body, headers: { 'X-Requested-With': 'XMLHttpRequest', 'X-TidyMail-Account': form.querySelector('[name="account"]').value } });
          if (!response.ok) { const html = new DOMParser().parseFromString(await response.text(), 'text/html'); throw new Error(html.querySelector('[role="alert"]')?.textContent || (response.status === 401 || response.status === 403 ? 'Your session or permissions changed. Sign in again, then retry. Your choices are retained.' : `Could not save (HTTP ${response.status}). Your choices are retained.`)); }
          location.assign(response.url);
        } catch (error) { errorBox.textContent = error.message; errorBox.hidden = false; destination.hidden = false; }
        finally { button.disabled = false; }
      });
      for (const group of form.querySelectorAll('[data-phrases]')) {
        const add = value => {
          const row = document.createElement('div'); row.className = 'row';
          const input = document.createElement('input'); input.name = group.dataset.phrases; input.maxLength = 100; input.value = value; input.setAttribute('aria-label', group.dataset.phraseLabel);
          const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = 'Remove'; remove.onclick = () => row.remove(); row.append(input, remove); group.querySelector('[data-phrase-rows]').append(row);
        };
        const saved = group.querySelector('textarea'); const values = saved.value.split('\n').filter(Boolean); saved.remove();
        (values.length ? values : ['']).forEach(add); group.querySelector('[data-add-phrase]').onclick = () => { if (group.querySelectorAll('input').length < 20) add(''); };
      }
      sync();
    }
  }
  document.addEventListener('tidymail:rule-form-loaded', () => setup()); setup();
})();
