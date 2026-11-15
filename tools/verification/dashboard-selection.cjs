// Run with Node and Playwright available in NODE_PATH. All requests use local fixtures.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium, expect } = require('playwright/test');
const root = path.resolve(__dirname, '../..');
const inmail = 'inmail-hit-reply@linkedin.com', invitations = 'invitations@linkedin.com';
const groups = [
  { id: 'linkedin', label: 'LinkedIn', addresses: 2, messages: 69, unread: 35 },
  { id: 'target', label: 'Target', addresses: 2, messages: 4, unread: 2 }
].map(g => ({ ...g, inbox: g.messages, latest: '2026-11-14T12:00:00Z', knownBytes: 0, unknownSizes: 0 }));
const messages = [[inmail, 36, 'linkedin'], [invitations, 33, 'linkedin'], ['news@target.com', 2, 'target'], ['offers@target.com', 2, 'target']]
  .flatMap(([address, count, groupId]) => Array.from({ length: count }, (_, i) => ({
    id: `${address}-${i}`, address, groupId, subject: `${address} message ${i}`, unread: i % 2 === 0,
    receivedAt: '2026-11-14T12:00:00Z', category: 'UNCLASSIFIED', displayName: address === inmail ? 'Vashu Walia' : '',
    attachmentState: 'ABSENT', snippetState: 'ABSENT'
  })));
let previewIds = [], delaySelection = null, selectionStarted = null;
(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1500, height: 1100 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      const send = body => route.fulfill({ json: body });
      if (url.pathname === '/') {
        const html = fs.readFileSync(path.join(root, 'src/main/resources/templates/dashboard.html'), 'utf8')
          .replace('th:href="@{/static/css/dashboard.css}"', 'href="/static/css/dashboard.css"')
          .replace('th:src="@{/static/js/dashboard.js}"', 'src="/static/js/dashboard.js"')
          .replace('<body ', '<body data-provider="google" data-account="fixture" ')
          .replace('name="_csrf_header"', 'name="_csrf_header" content="X-CSRF-TOKEN"');
        return route.fulfill({ contentType: 'text/html', body: html });
      }
      if (url.pathname.startsWith('/static/')) return route.fulfill({ path: path.join(root, 'src/main/resources', url.pathname) });
      const endpoint = url.pathname.replace('/dashboard/api/', '');
      if (endpoint === 'jobs') return send([]);
      if (endpoint === 'recommendations') return send({ enabled: false, labeling: {} });
      if (endpoint === 'page') return send({ groups, revision: 1, classificationRevision: 1,
        filter: { labels: '' }, summary: { messages: 73, unread: 37, groups: 2, known_bytes: 0 },
        coverage: { indexed: 73 }, sync: { email: 'fixture@example.test', initial_batch_completed_at: '2026-11-14T12:00:00Z' } });
      if (endpoint === 'selection') {
        const request = route.request().postDataJSON();
        const rows = messages.filter(m => request.groups.includes(m.groupId) && (!request.address || request.address === m.address) && (!request.unreadOnly || m.unread));
        if (delaySelection) { const wait = delaySelection; delaySelection = null; selectionStarted?.(); await wait; }
        return send(rows.map(({ id, groupId, address }) => ({ id, groupId, address })));
      }
      if (endpoint.startsWith('groups/')) {
        const [, group, kind] = endpoint.split('/');
        const rows = messages.filter(m => m.groupId === group);
        if (kind === 'addresses') return send({ revision: 1, addresses: [...new Set(rows.map(m => m.address))].map(address => ({
          address, messages: rows.filter(m => m.address === address).length,
          displayName: address === inmail ? 'Vashu Walia' : ''
        })) });
        const filtered = rows.filter(m => (!url.searchParams.get('address') || m.address === url.searchParams.get('address')) && (url.searchParams.get('unreadOnly') !== 'true' || m.unread));
        const offset = Number(url.searchParams.get('messagePage')) * 10;
        return send({ revision: 1, messages: filtered.slice(offset, offset + 10), total: filtered.length, recommendations: [] });
      }
      if (endpoint === 'previews') {
        const request = route.request().postDataJSON(); previewIds = request.ids;
        return send({ id: 'preview', action: request.action, selected: previewIds.length, effective: previewIds.length,
          skipped: 0, protectedCount: 0, filter: request.filter, coverage: {}, knownBytes: 0 });
      }
      if (endpoint === 'previews/preview/targets') return send({ rows: [], total: previewIds.length });
      if (endpoint.endsWith('/invalidate')) return send({});
      return route.fulfill({ status: 404, json: { message: `Unexpected fixture request: ${endpoint}` } });
    });
    const addressBox = address => page.locator('#senders').getByRole('checkbox', { name: `Select all matching messages from ${address}`, exact: true });
    const openAddress = address => page.locator('#senders .sender-focus').filter({ hasText: address }).click();
    const selectedCount = count => expect(page.locator('#selectionCount')).toHaveText(`${count} messages selected`);
    const expand = label => page.getByRole('button', { name: `Show sender addresses for ${label}`, exact: true }).click();
    await page.goto('https://tidymail.test/');
    await expand('LinkedIn');
    await addressBox(inmail).check(); await selectedCount(36);
    await openAddress(invitations);
    await expect(addressBox(inmail)).toBeChecked();
    await expect(page.locator('#detailContent')).toContainText(invitations);
    await page.getByRole('checkbox', { name: 'Select visible messages (10)', exact: true }).check();
    await selectedCount(46);
    await expect(addressBox(invitations)).toHaveJSProperty('indeterminate', true);
    await page.getByRole('button', { name: 'Select all 33 matching messages in this view', exact: true }).click();
    await selectedCount(69); await expect(addressBox(invitations)).toBeChecked();
    await page.getByRole('checkbox', { name: `Select message ${invitations} message 0`, exact: true }).uncheck();
    await selectedCount(68); await expect(addressBox(invitations)).toHaveJSProperty('indeterminate', true);
    await addressBox(invitations).check(); await selectedCount(69);
    await page.getByRole('button', { name: 'Next message page', exact: true }).click();
    await selectedCount(69); await expect(addressBox(invitations)).toBeChecked();
    await expand('LinkedIn'); await expand('LinkedIn');
    await expect(addressBox(inmail)).toBeChecked(); await expect(addressBox(invitations)).toBeChecked();
    await expand('Target'); await selectedCount(69);
    await page.locator('[data-action="TRASH"]').click(); await page.locator('#review').click();
    await expect(page.locator('#confirm')).toBeVisible();
    assert.equal(previewIds.length, 69); assert.equal(new Set(previewIds).size, 69);
    assert(previewIds.every(id => id.includes('linkedin.com')));
    await page.getByRole('button', { name: 'Back to selection', exact: true }).click();
    await addressBox(inmail).uncheck(); await selectedCount(33);
    await expect(addressBox(invitations)).toBeChecked();
    await page.locator('[data-focus-key=sender-target]').click();
    await expect(page.locator('#selectionRow')).toBeHidden();
    await expect(addressBox(invitations)).not.toBeChecked();
    await expect(page.locator('#notice')).toContainText('Previous message selection was cleared');
    // Pending responses cannot repopulate selection after switching away and back.
    await openAddress(inmail);
    let release;
    delaySelection = new Promise(resolve => { release = resolve; });
    const started = new Promise(resolve => { selectionStarted = resolve; });
    await addressBox(inmail).click(); await started;
    await openAddress('news@target.com'); await openAddress(inmail);
    release();
    await expect(addressBox(inmail)).toBeEnabled();
    await expect(page.locator('#selectionRow')).toBeHidden();
    // Unread-only right-rail selection is partial relative to the full address.
    await page.locator('#detailUnreadOnly').check();
    await page.getByRole('checkbox', { name: 'Select visible messages (10)', exact: true }).check();
    await page.getByRole('button', { name: 'Select all 18 matching unread messages in this view', exact: true }).click();
    await selectedCount(18); await expect(addressBox(inmail)).toHaveJSProperty('indeterminate', true);
    await addressBox(inmail).check(); await selectedCount(36); await expect(addressBox(inmail)).toBeChecked();
    await page.locator('#detailUnreadOnly').uncheck(); await selectedCount(36);
    // A pending right-rail select-all still completes when browsing within LinkedIn.
    await openAddress(invitations);
    await page.getByRole('checkbox', { name: 'Select visible messages (10)', exact: true }).check();
    delaySelection = new Promise(resolve => { release = resolve; });
    const rightStarted = new Promise(resolve => { selectionStarted = resolve; });
    await page.getByRole('button', { name: 'Select all 33 matching messages in this view', exact: true }).click();
    await rightStarted; await openAddress(inmail); release();
    await selectedCount(69); await expect(addressBox(invitations)).toBeChecked();
    // Clearing during a request must not let the response restore selection.
    await page.locator('#clear').click();
    await addressBox(inmail).check(); await selectedCount(36);
    delaySelection = new Promise(resolve => { release = resolve; });
    const clearStarted = new Promise(resolve => { selectionStarted = resolve; });
    await addressBox(invitations).click(); await clearStarted;
    await page.locator('#clear').click(); release();
    await expect(addressBox(inmail)).toBeEnabled();
    await expect(page.locator('#selectionRow')).toBeHidden();
    await addressBox(inmail).check(); await selectedCount(36);
    await page.locator('#search').fill('changed');
    await expect(page.locator('#selectionRow')).toBeHidden();
    assert.deepEqual(errors, []);
    console.log('PASS: address/message synchronization, additive selection, paging, collapse, group boundary, exact Trash preview, unread scope, filter reset, and stale response rejection.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
