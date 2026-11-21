'use strict';
(() => {
  document.querySelectorAll('[data-dismiss-rule-notice]').forEach(button => {
    button.onclick = () => button.closest('.rule-notice').remove();
  });
  // Confirm consequential rule-management operations without changing form payloads.
  const forms = document.querySelectorAll('form[data-confirm]');
  if (forms.length) {
    const dialog = document.createElement('dialog');
    dialog.setAttribute('aria-labelledby', 'operationTitle');
    const title = document.createElement('h2'); title.id = 'operationTitle';
    const description = document.createElement('p'); description.id = 'operationDescription';
    dialog.setAttribute('aria-describedby', description.id);
    const footer = document.createElement('footer'); footer.className = 'dialog-footer row spread';
    const cancel = document.createElement('button'); cancel.type = 'button'; cancel.textContent = 'Cancel';
    const confirm = document.createElement('button'); confirm.type = 'button'; confirm.className = 'primary';
    footer.append(cancel, confirm); dialog.append(title, description, footer); document.body.append(dialog);
    let pending, opener;
    const approved = new WeakSet();
    for (const form of forms) form.addEventListener('submit', async event => {
      if (approved.delete(form)) return;
      event.preventDefault();
      if (dialog.open) return;
      pending = { form, submitter: event.submitter }; opener = document.activeElement;
      title.textContent = form.dataset.confirmTitle || 'Review action'; description.textContent = form.dataset.confirm;
      confirm.textContent = form.dataset.confirmAction || 'Confirm'; confirm.classList.toggle('danger', form.hasAttribute('data-confirm-danger'));
      confirm.disabled = !!form.dataset.confirmPreview;
      dialog.showModal(); cancel.focus();
      if (form.dataset.confirmPreview) {
        const request = pending;
        title.textContent = 'Review drafts'; description.textContent = 'Loading eligible drafts…';
        try {
          const response = await fetch(form.dataset.confirmPreview, { headers: { 'X-Requested-With': 'XMLHttpRequest' }, credentials: 'same-origin' });
          if (!response.ok) throw new Error();
          const snapshot = await response.json();
          if (pending !== request || !dialog.open) return;
          form.elements.confirmation.value = snapshot.token;
          title.textContent = `Delete these ${snapshot.count} drafts?`;
          description.textContent = form.dataset.confirm;
          confirm.textContent = `Delete ${snapshot.count} drafts`;
          confirm.disabled = snapshot.count === 0;
        } catch (_) {
          if (pending !== request || !dialog.open) return;
          description.textContent = 'Drafts could not be reviewed. Cancel and try again.';
        }
      }
    });
    cancel.onclick = () => dialog.close();
    confirm.onclick = () => {
      if (!pending) return;
      const { form, submitter } = pending; pending = null;
      approved.add(form); dialog.close(); form.requestSubmit(submitter || undefined);
    };
    dialog.addEventListener('close', () => { pending = null; if (opener?.isConnected) opener.focus(); });
  }
  // Warn only for unsaved standalone rule edits; drawer edits use their own guard.
  const editor = document.querySelector('form[action$="/rules/save"]');
  if (editor && !editor.closest('dialog')) {
    let dirty = false;
    editor.addEventListener('input', () => { dirty = true; });
    editor.addEventListener('submit', () => { dirty = false; });
    window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
  }
})();
