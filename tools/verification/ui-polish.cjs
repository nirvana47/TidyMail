// Run only against DashboardBrowserFixture (never the retained app).
// NODE_PATH=... node tools/verification/ui-polish.cjs http://127.0.0.1:PORT/dashboard OUTPUT_DIR
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium, expect } = require('playwright/test');
const target = new URL(process.argv[2]);
assert.equal(target.hostname, '127.0.0.1');
const output = path.resolve(process.argv[3]); fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    const shot = name => page.screenshot({ path: path.join(output, name + '.png') });
    const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.goto(target.href);
    await expect(page.locator('[data-browser-fixture]')).toHaveText('Synthetic test mailbox');
    await expect(page.locator('#senders tr').first()).toBeVisible();
    await expect(page.locator('#suggestionsView,#sendersView,#suggestionsPanel,#activityOpen,#patterns')).toHaveCount(0);
    await expect(page.locator('#settingsOpen')).toBeVisible();
    await expect(page.locator('#detailContent .message-row').first()).toBeVisible();
    const density = async label => {
      const metrics = await page.evaluate(() => {
        const pane = document.querySelector('#detailContent').getBoundingClientRect();
        const visible = (selector, bottom = innerHeight) => [...document.querySelectorAll(selector)].filter(row => {
          const rect = row.getBoundingClientRect(); return rect.top >= 0 && rect.bottom <= bottom && rect.height > 0;
        }).length;
        return { workspaceTop: Math.round(document.querySelector('.workspace').getBoundingClientRect().top), senderRows: visible('#senders > tr:not(.address-row)'), messageRows: visible('#detailContent .message-row', Math.min(innerHeight, pane.bottom)) };
      });
      console.log(label + ' density: ' + JSON.stringify(metrics));
      assert(metrics.senderRows >= 8, `${label}: only ${metrics.senderRows} senders fit`);
      assert(metrics.messageRows >= 4, `${label}: only ${metrics.messageRows} messages fit`);
    };
    await noOverflow(); await density('1440x900'); await shot('mailbox-rendered');
    // All following traffic remains in the isolated synthetic fixture.
    await page.locator('#filtersOpen').click();
    await page.getByRole('checkbox', { name: 'Bill', exact: true }).check();
    await shot('filters-rendered');
    await page.keyboard.press('Escape');
    await expect(page.locator('#activeFilters')).toContainText('Bill');
    await page.getByRole('button', { name: 'Remove filter: Bill', exact: true }).click();
    await expect(page.locator('#detailContent .message-row').first()).toBeVisible();
    await page.locator('#detailContent input[data-selection]').first().check();
    await shot('selection-rendered');
    await page.locator('#messageAction').selectOption('ARCHIVE');
    await page.locator('#review').click();
    await expect(page.locator('#exactTargets .target')).not.toHaveCount(0);
    await shot('confirmation-rendered');
    await page.getByRole('button', { name: 'Back to selection', exact: true }).click();
    await page.locator('#createMessageRule').click();await page.getByRole('button',{name:'Create new rule',exact:true}).click();
    await expect(page.locator('#inboxRuleContent [name="name"]')).toBeVisible();
    await page.locator('#inboxRuleContent [name="name"]').fill('Archive newsletter');
    await page.locator('#inboxRuleContent [data-destination-picker] select').nth(1).selectOption('Label_42');
    await page.locator('#inboxRuleContent button').filter({ hasText: 'Review rule →' }).click();
    await expect(page.locator('#inboxRuleContent [data-rule-preview]')).toBeVisible();
    await shot('rule-drawer-rendered');
    await page.locator('#inboxRule [data-close]').click();
    await page.goto(target.origin + '/rules');
    await expect(page.locator('h1')).toHaveText('Rules');
    const ownRules = page.getByRole('heading', { name: 'Your rules', exact: true });
    const gmailRules = page.getByRole('heading', { name: 'Existing Gmail filters', exact: true });
    const ownBounds = await ownRules.boundingBox(), gmailBounds = await gmailRules.boundingBox();
    assert.ok(ownBounds.y < 250 && gmailBounds.y < 250);
    assert.ok(Math.abs(ownBounds.y - gmailBounds.y) < 25);
    await expect(page.getByRole('link', { name: 'Gmail filter settings ↗', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Refresh provider state', exact: true })).toHaveCount(0);
    await expect(page.locator('body')).not.toContainText('Organize future incoming email');

    await noOverflow(); await shot('rules-rendered');
    await page.getByRole('button', { name: 'Delete draft', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.locator('h2#operationTitle')).toHaveText('Delete these 1 drafts?');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(page.getByRole('dialog')).not.toBeVisible();
    await page.getByRole('button', { name: 'Delete draft', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Delete 1 drafts', exact: true }).click();
    await expect(page.getByRole('link', { name: 'Archive newsletter', exact: true })).toHaveCount(0);
    await page.goto(target.origin + '/rules/new');
    await expect(page.locator('[name="name"]')).toBeVisible();
    await noOverflow(); await shot('rule-editor-rendered');
    await page.goto(target.origin + '/activity/sync');
    await expect(page.locator('h1')).toHaveText('Sync details');
    await noOverflow(); await shot('sync-rendered');
    await page.goto(target.origin + '/login'); await shot('signin-rendered');
    await page.goto(target.href); await expect(page.locator('#senders tr').first()).toBeVisible();
    await expect(page.locator('#detailContent .message-row').first()).toBeVisible();
    await page.setViewportSize({ width: 1280, height: 800 }); await shot('mailbox-1280'); await noOverflow(); await density('1280x800');
    await page.evaluate(() => { document.body.style.zoom = '2'; });
    await noOverflow(); await shot('mailbox-zoom-200');
    assert.deepEqual(errors, []);
    console.log('PASS: rendered mailbox, filter chips, selection, exact review, contextual rule save/preview, standalone Rules, cancel/confirm removal, editor, sync, sign-in, 1280px layout and 200% zoom. Synthetic fixture only.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
