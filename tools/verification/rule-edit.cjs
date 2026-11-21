// DashboardBrowserFixture only: standalone save and paused-rule edits use synthetic providers.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium, expect } = require('playwright/test');
const target = new URL(process.argv[2]);
assert.equal(target.hostname, '127.0.0.1');
const output = process.argv[3] || '/tmp/tidymail-rule-edit';
fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [], posts = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => { if (request.method() === 'POST') posts.push(new URL(request.url()).pathname); });
    await page.goto(target.href);
    await expect(page.locator('[data-browser-fixture]')).toBeVisible();
    await page.goto(target.origin + '/rules/new');
    await page.getByRole('textbox', { name: 'Rule name', exact: true }).fill('Synthetic standalone edit');
    await page.getByRole('textbox', { name: 'From these exact addresses', exact: true }).fill('news0@sender.example');
    await page.getByRole('combobox', { name: 'Existing label', exact: true }).selectOption('Label_42');
    await page.getByRole('button', { name: 'Review rule →', exact: true }).click();
    await expect(page.locator('[data-rule-preview]')).toBeVisible();
    await expect(page).toHaveURL(/\/rules\/[a-f0-9-]+\?preview=true/);
    await page.getByRole('button', { name: 'Create rule', exact: true }).click();
    await expect(page.locator('[data-inbox-rule-result]')).toContainText('Rule enabled');
    await page.getByRole('link', { name: 'Edit this rule', exact: true }).click();
    const ruleUrl = page.url();
    await page.getByRole('button', { name: 'Pause to edit', exact: true }).click();
    await expect(page.locator('h1')).toHaveText('Rules');
    await page.goto(ruleUrl);
    await expect(page.getByRole('combobox', { name: 'Destination', exact: true })).toHaveValue('EXISTING');
    await expect(page.getByRole('combobox', { name: 'Existing label', exact: true })).toHaveValue('Label_42');
    await expect(page.getByRole('checkbox', { name: 'Remove from Inbox', exact: true })).not.toBeChecked();
    await page.getByRole('checkbox', { name: 'Remove from Inbox', exact: true }).check();
    // A rejected save must show why and preserve the edited checkbox and destination.
    await page.route('**/rules/save', route => route.fulfill({ status: 403, contentType: 'text/html', body: 'Forbidden' }), { times: 1 });
    await page.getByRole('button', { name: 'Review rule →', exact: true }).click();
    await expect(page.locator('[data-destination-error]')).toContainText('session or permissions changed');
    await expect(page.getByRole('checkbox', { name: 'Remove from Inbox', exact: true })).toBeChecked();
    await page.getByRole('button', { name: 'Review rule →', exact: true }).click();
    await expect(page.locator('[data-rule-preview]')).toBeVisible();
    await expect(page.locator('[data-rule-preview]')).toContainText('remove matching incoming mail from Inbox');
    await page.screenshot({ path: path.join(output, 'edited-rule-review.png') });
    await page.getByRole('button', { name: 'Create rule', exact: true }).click();
    await expect(page.locator('[data-inbox-rule-result]')).toContainText('Rule enabled');
    assert.equal(posts.filter(url => url === '/rules/save').length, 3);
    assert.ok(posts.every(url => !url.includes('HTMLSelectElement')));
    assert.deepEqual(errors, []);
    console.log('PASS: standalone save, pause/edit, existing label retained, Remove from Inbox saved and enabled, rejected save preserves choices. Synthetic mailbox only.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
