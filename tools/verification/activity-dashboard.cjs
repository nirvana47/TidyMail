// Use only DashboardBrowserFixture's loopback URL.
const { chromium, expect } = require('playwright/test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const target = new URL(process.argv[2]);
assert.equal(target.hostname, '127.0.0.1');
(async () => {
  const browser = await chromium.launch({headless:true, executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:900}});
    const errors=[]; page.on('pageerror', e=>errors.push(e.message));
    await page.goto(target.href);
    await expect(page.locator('[data-browser-fixture]')).toHaveText('Synthetic test mailbox');
    await page.locator('[data-activity-link]').click();
    await expect(page).toHaveURL(target.origin+'/activity');
    await expect(page.locator('h1')).toHaveText('Activity');
    await expect(page.locator('[data-activity-link]')).toHaveAttribute('aria-current','page');
    await expect(page.locator('.app-nav a[href="/dashboard"]')).not.toHaveAttribute('aria-current','page');
    await expect(page.locator('dialog[open]')).toHaveCount(0);
    await expect(page.locator('.metric-grid article')).toHaveCount(4);
    await expect(page.locator('.chart-column')).toHaveCount(14);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    fs.mkdirSync('/tmp/tidymail-activity-review',{recursive:true});
    await page.screenshot({path:'/tmp/tidymail-activity-review/activity.png',fullPage:true});
    await page.getByRole('link',{name:'Sync details',exact:true}).click();
    await expect(page.locator('h1')).toHaveText('Sync / Activity');
    await page.locator('[data-activity-link]').click();
    await expect(page.locator('h1')).toHaveText('Activity');
    await page.locator('.app-nav a[href="/dashboard"]').click();
    await expect(page.locator('#senders tr').first()).toBeVisible();
    await expect(page.locator('#activityOpen')).toHaveCount(0);
    await page.locator('[data-activity-link]').click();
    await expect(page).toHaveURL(target.origin+'/activity');
    assert.deepEqual(errors,[]);
    console.log('PASS: standalone Activity, navigation highlight, summaries, chart, sync round trip, sidebar Activity link, no browser errors or desktop overflow.');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
