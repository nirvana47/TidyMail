// Synthetic DashboardBrowserFixture only. Rules are saved as drafts; one manual filing job is confirmed.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium, expect } = require('playwright/test');
const target = new URL(process.argv[2]);
assert.equal(target.hostname, '127.0.0.1');
const output = path.resolve(process.argv[3] || '/tmp/tidymail-filing-screenshots'); fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    page.setDefaultTimeout(10000);
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    let confirmations = 0, saves = 0;
    page.on('request', r => { const p = new URL(r.url()).pathname; if (r.method() === 'POST' && p === '/dashboard/api/jobs') confirmations++; if (r.method() === 'POST' && p === '/rules/save') saves++; });
    await page.goto(target.href); await expect(page.locator('[data-browser-fixture]')).toBeVisible();
    await expect(page.locator('#detailContent .message-row').first()).toBeVisible();
    await page.locator('#detailContent input[data-selection]').first().check();
    await page.locator('#createMessageRule').click();await page.getByRole('button',{name:'Create new rule',exact:true}).click();
    const drawer = page.locator('#inboxRuleContent');
    await expect(drawer.locator('[name="name"]')).toHaveValue(/Organize/);
    await expect(drawer.locator('[name="senders"]')).not.toBeEmpty();
    await drawer.getByRole('button', { name: 'Add phrase', exact: true }).click();
    await drawer.locator('[name="subject"]').nth(0).fill('Indexed'); await drawer.locator('[name="subject"]').nth(1).fill('Synthetic');
    await drawer.getByRole('button', { name: 'Add exception', exact: true }).click();
    await drawer.locator('[name="exceptSubject"]').nth(0).fill('invoice'); await drawer.locator('[name="exceptSubject"]').nth(1).fill('receipt');
    const picker = drawer.locator('[data-destination-picker]');
    await picker.getByRole('combobox').nth(0).selectOption('NEW');
    await picker.getByRole('textbox', { name: 'New label name' }).fill('Offers');
    await drawer.getByRole('button', { name: 'Review rule →' }).click();
    await expect(drawer.locator('[data-destination-error]')).toContainText('already exists'); assert.equal(saves, 0);
    await expect(picker.getByRole('combobox').nth(0)).toHaveValue('EXISTING');
    // A permission failure must retain both phrases and destination choice.
    await page.route('**/rules/save?*', async route => { await route.fulfill({ status: 503, contentType: 'text/html', body: '<p role="alert">Reconnect permissions; draft retained.</p>' }); }, { times: 1 });
    await drawer.getByRole('button', { name: 'Review rule →' }).click();
    await expect(page.locator('#inboxRuleError')).toContainText('Reconnect permissions');
    await expect(drawer.locator('[name="subject"]').nth(1)).toHaveValue('Synthetic');
    await expect(picker.getByRole('combobox').nth(1)).toHaveValue('Label_42');
    await page.screenshot({ path: path.join(output, 'rule-editor.png') });
    await drawer.getByRole('button', { name: 'Review rule →' }).click();
    await expect(drawer.locator('[data-rule-preview]')).toBeVisible();
    await expect(drawer.locator('[data-rule-preview]')).toContainText('Indexed');
    await expect(drawer.locator('[name="subject"]')).toHaveCount(0);
    await drawer.getByRole('link', { name: 'Back to edit rule' }).click();
    await expect(drawer.locator('[name="subject"]')).toHaveCount(2);
    await expect(drawer.locator('[data-destination-picker] select').first()).toHaveValue('EXISTING');
    await page.locator('#inboxRule [data-close]').click();
    await page.locator('#messageAction').selectOption('ORGANIZE'); await page.locator('#review').click();
    const filing = page.locator('#filingDestinationPicker');
    await filing.getByRole('combobox').first().selectOption('NEW'); await filing.getByRole('textbox', { name: 'New label name' }).fill('Review only label');
    await page.locator('#reviewFiling').click();
    await expect(page.locator('#actionExplanation')).toContainText('Create label “Review only label”');
    await page.locator('#confirm [data-close]').last().click(); assert.equal(confirmations, 0);
    await page.locator('#review').click(); await filing.getByRole('combobox').first().selectOption('EXISTING');
    await filing.getByRole('combobox').nth(1).selectOption('Label_42'); await filing.getByRole('checkbox').check();
    await page.locator('#reviewFiling').click(); await expect(page.locator('#actionExplanation')).toContainText('Messages leave the Inbox');
    await page.screenshot({ path: path.join(output, 'filing-review.png') });
    await page.locator('#confirmAction').click(); await expect.poll(() => confirmations).toBe(1);
    await expect(page.locator('#notice')).toContainText('saved');
    await page.goto(target.origin + '/activity'); await expect(page.locator('table')).toContainText('Apply label: Offers');
    // Both action menus expose filing, and a sender selection still opens the same destination control.
    await page.goto(target.href); await page.locator('#senders input[data-selection]').first().check();
    await page.locator('#senderAction').selectOption('ORGANIZE'); await page.locator('#reviewSenders').click();
    await expect(page.locator('#filingDestination')).toBeVisible(); await page.keyboard.press('Escape');
    for (const width of [1280, 1440]) { await page.setViewportSize({ width, height: 900 }); assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)); }
    assert.deepEqual(errors, []); console.log('PASS: multi-phrase rule draft, explicit duplicate selection, retained choices after permission failure, deferred new destination, exact manual filing review, Activity destination, both selection menus, desktop layout.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
