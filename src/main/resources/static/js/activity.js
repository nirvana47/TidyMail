'use strict';
(() => {
  const bars = [...document.querySelectorAll('[data-value]')];
  const max = Math.max(1, ...bars.map(bar => Number(bar.dataset.value)));
  bars.forEach(bar => { bar.style.height = (Number(bar.dataset.value) / max * 100) + '%'; });
  document.querySelectorAll('[data-cancel-job]').forEach(button => button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      const token = {headerName: document.querySelector('meta[name=_csrf_header]').content, token: document.querySelector('meta[name=_csrf]').content};
      const response = await fetch('/dashboard/api/jobs/' + button.dataset.cancelJob + '/cancel', { method: 'POST', headers: { [token.headerName]: token.token } });
      if (!response.ok) throw new Error('Cancellation could not be requested. Refresh and try again.');
      location.reload();
    } catch (error) { document.getElementById('activity-notice').textContent = error.message; button.disabled = false; }
  }));
})();
