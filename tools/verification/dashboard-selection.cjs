// Run with Node and Playwright available in NODE_PATH. All requests use local fixtures.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium, expect } = require('playwright/test');
const root = path.resolve(__dirname, '../..');
const inmail = 'inmail-hit-reply@linkedin.com', invitations = 'invitations@linkedin.com';
const groups = [
  { id: 'linkedin', label: 'LinkedIn', addresses: 22, messages: 89, unread: 55 },
  { id: 'target', label: 'Target', addresses: 2, messages: 4, unread: 2 }
].map(g => ({ ...g, inbox: g.messages, latest: '2026-11-14T12:00:00Z', knownBytes: 0, unknownSizes: 0 }));
const messages = [[inmail, 36, 'linkedin'], [invitations, 33, 'linkedin'], ['news@target.com', 2, 'target'], ['offers@target.com', 2, 'target']]
  .flatMap(([address, count, groupId]) => Array.from({ length: count }, (_, i) => ({
    id: `${address}-${i}`, address, groupId, subject: `${address} message ${i}`, unread: i % 2 === 0,
    receivedAt: '2026-11-14T12:00:00Z', category: 'UNCLASSIFIED', displayName: address === inmail ? 'Vashu Walia' : '',
    attachmentState: 'ABSENT', snippetState: 'ABSENT'
  })));
// A fully expanded group must be taller than the viewport to expose focus scrolling.
for (let i = 0; i < 20; i++) messages.push({ ...messages[0], id: `extra-${i}`, address: `extra${i}@linkedin.com` });
let previewIds = [], delaySelection = null, selectionStarted = null, detailFailure = false, delayDetails = null, detailsStarted = null;
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH });
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
          .replace('th:src="@{/static/js/inbox-rules.js}"', 'src="/static/js/inbox-rules.js"')
          .replace('th:href="@{/static/css/rules.css}"', 'href="/static/css/rules.css"')
          .replace('<aside th:replace="~{fragments/app-sidebar :: sidebar(\'mailbox\', \'\')}"></aside>', fs.readFileSync(path.join(root, 'src/main/resources/templates/fragments/app-sidebar.html'), 'utf8').match(/<aside[\s\S]*?<\/aside>/)[0].replace('th:attr="aria-current=${active == \'mailbox\' ? \'page\' : null}"', 'aria-current="page"'))
          .replace('<body ', '<body data-provider="google" data-account="fixture" ')
          .replace('name="_csrf_header"', 'name="_csrf_header" content="X-CSRF-TOKEN"');
        return route.fulfill({ contentType: 'text/html', body: html });
      }
      if (url.pathname === '/rules/save') return route.fulfill({ status: 400, contentType: 'text/html', body: '<p role="alert">Choose a valid sender address.</p>' });
      if (url.pathname === '/rules/new') {
        const sender = url.searchParams.get('sender'), action = url.searchParams.get('action');
        assert.equal(route.request().headers()['x-tidymail-account'], 'fixture');
        return route.fulfill({ contentType: 'text/html', body: `<main id="ruleEditorContent"><form action="/rules/save" method="post"><label>Rule name<input name="name"></label><label>From<textarea name="senders">${sender}</textarea></label><select name="action"><option value="${action}">${action}</option></select><button>Save draft and preview</button></form></main>` });
      }
      if (url.pathname.startsWith('/static/')) return route.fulfill({ path: path.join(root, 'src/main/resources', url.pathname) });
      const endpoint = url.pathname.replace('/dashboard/api/', '');
      if (endpoint === 'jobs') return send([]);
      if (endpoint === 'recommendations') return send({ enabled: false, labeling: {} });
      if (endpoint === 'page') return send({ groups, revision: 1, classificationRevision: 1,
        filter: { labels: '' }, summary: { messages: 93, unread: 57, groups: 2, known_bytes: 0 },
        coverage: { indexed: 93 }, sync: { email: 'fixture@example.test', initial_batch_completed_at: '2026-11-14T12:00:00Z' } });
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
        if (detailFailure) { detailFailure = false; return route.fulfill({ status: 503, json: { message: 'Indexed details are temporarily unavailable.' } }); }
        if (delayDetails) { const wait = delayDetails; delayDetails = null; detailsStarted?.(); await wait; }
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
    // Real keyboard-operated overlays and filter changes must preserve existing scope rules.
    await page.locator('#filtersOpen').click();
    await expect(page.locator('#filtersDialog')).toBeVisible();
    await page.locator('#start').fill('2026-01-01');
    await page.locator('#start').dispatchEvent('change');
    await page.keyboard.press('Escape');
    await expect(page.locator('#filtersOpen')).toBeFocused();
    await expect(page.locator('#activeFilters')).toContainText('From 2026-01-01');
    await page.getByRole('button', { name: 'Remove filter: From 2026-01-01', exact: true }).click();
    await expect(page.locator('#start')).toHaveValue('');
    await expect(page.locator('#filterCount')).toBeHidden();
    await page.locator('[data-settings-link]').click();
    await expect(page.locator('#settings')).toBeVisible();
    await page.locator('#toolsOpen').click();
    await expect(page.locator('#settings')).not.toBeVisible();
    await expect(page.locator('#tools')).toBeVisible();
    assert.equal(await page.locator('dialog[open]').count(), 1);
    await page.keyboard.press('Escape');
    await page.locator('#detailClose').click();
    await expect(page.locator('#detail')).toBeHidden();
    // A failed or stalled details request must leave a usable retry in the pane.
    detailFailure = true;
    await page.locator('[data-focus-key=sender-linkedin]').click();
    await expect(page.locator('#detailContent [role=alert]')).toHaveText('Indexed details are temporarily unavailable.');
    await expect(page.locator('#detailContent')).not.toContainText('Loading indexed details');
    await page.getByRole('button', { name: 'Retry loading details', exact: true }).click();
    await expect(page.locator('#detailContent .message-row')).toHaveCount(10);
    let releaseDetails;
    delayDetails = new Promise(resolve => { releaseDetails = resolve; });
    const stalled = new Promise(resolve => { detailsStarted = resolve; });
    await page.locator('[data-focus-key=sender-target]').click(); await stalled;
    await expect(page.locator('#detailContent [role=alert]')).toHaveText('Indexed details took too long to load. Try again.', { timeout: 20000 });
    await page.getByRole('button', { name: 'Retry loading details', exact: true }).click();
    await expect(page.locator('#detailContent .message-row')).toHaveCount(4);
    releaseDetails();
    await page.locator('[data-focus-key=sender-linkedin]').click();
    await expect(page.locator('#detail')).toBeVisible();
    if (process.env.TIDYMAIL_UI_SCREENSHOTS) {
      await page.setViewportSize({ width: 1440, height: 900 });
      await page.screenshot({ path: path.join(process.env.TIDYMAIL_UI_SCREENSHOTS, 'mailbox.png') });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      await page.setViewportSize({ width: 1500, height: 1100 });
    }
    await expand('LinkedIn');
    await addressBox(inmail).evaluate(input => window.scrollBy(0, input.getBoundingClientRect().top - 150));
    const scrollBeforeSelection = await page.evaluate(() => window.scrollY);
    await addressBox(inmail).check(); await selectedCount(36);
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBeLessThanOrEqual(scrollBeforeSelection + 5);
    await expect(addressBox(inmail)).toBeFocused();
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
    await page.locator('#messageAction').selectOption('MARK_READ_ARCHIVE');
    await page.locator('#createMessageRule').click();
    await expect(page.locator('#inboxRule')).toBeVisible();
    await expect(page.locator('#inboxRuleContent [name="senders"]')).toHaveValue([inmail, invitations].sort().join(', '));
    await expect(page.locator('#inboxRuleContent [name="action"]')).toHaveValue('ORGANIZE');
    assert.equal(page.url(), 'https://tidymail.test/');
    await page.locator('#inboxRuleContent button').click();
    await expect(page.locator('#inboxRuleError')).toHaveText('Choose a valid sender address.');
    await expect(page.locator('#inboxRuleContent [name="senders"]')).toHaveValue([inmail, invitations].sort().join(', '));
    await page.locator('#inboxRuleContent [name="name"]').fill('Unsaved rule name');
    await page.keyboard.press('Escape');
    await expect(page.locator('#inboxRule')).toBeVisible();
    await expect(page.locator('#inboxRuleError')).toContainText('unsaved changes');
    await page.getByRole('button', { name: 'Keep editing', exact: true }).click();
    await page.locator('#inboxRule [data-close]').click();
    await page.getByRole('button', { name: 'Discard changes', exact: true }).click(); await selectedCount(69);
    await expect(addressBox(inmail)).toBeChecked(); await expect(addressBox(invitations)).toBeChecked();
    await page.locator('#messageAction').selectOption('TRASH'); await page.locator('#review').click();
    await expect(page.locator('#confirm')).toBeVisible();
    if (process.env.TIDYMAIL_UI_SCREENSHOTS) {
      await page.setViewportSize({ width: 1280, height: 800 });
      await page.screenshot({ path: path.join(process.env.TIDYMAIL_UI_SCREENSHOTS, 'cleanup-review.png') });
      const bounds = await page.locator('#confirm').boundingBox(); assert(bounds.y >= 0 && bounds.y + bounds.height <= 800);
      await page.setViewportSize({ width: 1500, height: 1100 });
    }
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
    console.log('PASS: details failure/timeout and retry recovery, stable scroll position and checkbox focus in a 22-address group, address/message synchronization, additive selection, paging, collapse, group boundary, exact Trash preview, unread scope, filter reset, stale response rejection, and inline rule action/sender transfer with validation and selection preservation.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
