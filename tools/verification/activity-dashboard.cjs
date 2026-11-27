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
    await expect(page.getByRole('heading',{name:'Estimated space freed after deletion'})).toBeVisible();
    for (const bar of await page.locator('.chart-bar[data-value="0"]').all()) await expect(bar).toBeHidden();
    for (const heading of await page.locator('.metric-grid article>p').all()) assert.equal(await heading.evaluate(e=>getComputedStyle(e).fontSize),'12px');
    fs.mkdirSync('/tmp/tidymail-activity-review',{recursive:true});
    await page.screenshot({path:'/tmp/tidymail-activity-review/activity.png',fullPage:true});
    await page.getByRole('link',{name:'Sync details',exact:true}).click();
    await expect(page.locator('h1')).toHaveText('Sync details');
    assert.equal(await page.locator('h1').evaluate(e=>getComputedStyle(e).fontSize),'24px');
    assert.equal(await page.locator('.activity-data-table thead th').first().evaluate(e=>getComputedStyle(e).fontSize),'12px');
    assert.equal(await page.locator('.activity-status').evaluate(e=>getComputedStyle(e).display),'grid');
    assert.equal(await page.locator('.activity-account-context').evaluate(e=>getComputedStyle(e).fontSize),'12px');
    const event = page.locator('.activity-events-table tbody th').first();
    if (await event.count()) {
      assert.equal(await event.evaluate(e=>getComputedStyle(e).fontSize),'14px');
      assert.notEqual(await event.evaluate(e=>getComputedStyle(e).color),await page.locator('.activity-events-table thead th').first().evaluate(e=>getComputedStyle(e).color));
    }
    await page.screenshot({path:'/tmp/tidymail-activity-review/sync.png',fullPage:true});
    await page.getByRole('link',{name:'Back to Activity'}).click();
    await expect(page.locator('h1')).toHaveText('Activity');
    await page.getByRole('link',{name:'Back to Mailbox'}).click();
    await expect(page.locator('#senders tr').first()).toBeVisible();
    await expect(page.locator('#activityOpen')).toHaveCount(0);
    await page.locator('[data-activity-link]').click();
    await expect(page).toHaveURL(target.origin+'/activity');
    for (const route of ['/activity','/activity/sync']) {
      await page.goto(target.origin+route);
      await page.setViewportSize({width:1280,height:800});
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
      await page.evaluate(()=>document.body.style.zoom='2');
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,route+' overflows at 200% zoom');
      if (route==='/activity') {
        const fits=await page.locator('.chart-column small').evaluateAll(labels=>labels.every(label=>{
          const range=document.createRange(); range.selectNodeContents(label);
          return range.getBoundingClientRect().width<=label.getBoundingClientRect().width+1;
        }));
        assert.equal(fits,true,'chart date labels overlap at 200% zoom');
      }
      await page.screenshot({path:'/tmp/tidymail-activity-review/'+(route.endsWith('sync')?'sync':'activity')+'-zoom.png',fullPage:true});
      await page.evaluate(()=>document.body.style.zoom='1');
    }
    assert.deepEqual(errors,[]);
    console.log('PASS: standalone Activity, navigation highlight, summaries, chart, sync round trip, sidebar Activity link, no browser errors or desktop overflow.');
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
