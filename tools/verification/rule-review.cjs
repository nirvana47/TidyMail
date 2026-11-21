// DashboardBrowserFixture only: all provider rules and mailbox actions are synthetic.
const fs=require('node:fs'), path=require('node:path'), assert=require('node:assert/strict');
const {chromium,expect}=require('playwright/test');
const target=new URL(process.argv[2]);assert.equal(target.hostname,'127.0.0.1');
const output=process.argv[3]||'/tmp/tidymail-rule-review';fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH});
 const page=await browser.newPage({viewport:{width:1440,height:900}});page.setDefaultTimeout(10000);
 const errors=[];page.on('pageerror',e=>errors.push(e.message));let jobs=0;
 page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/dashboard/api/jobs')jobs++;});
 const drawer=page.locator('#inboxRule'), content=page.locator('#inboxRuleContent');
 async function setup(label){await expect(content.locator('[name="name"]')).toBeVisible();await content.locator('[data-destination-picker] select').nth(1).selectOption(label);}
 async function review(){await content.getByRole('button',{name:'Review rule →',exact:true}).click();await expect(page.locator('#inboxRuleTitle')).toHaveText('Review rule');await expect(content.locator('[name="name"]')).toHaveCount(0);await expect(content.locator('.rule-sample tbody tr:visible')).toHaveCount(3);await expect(content).not.toContainText('Preview coverage');await expect(content).not.toContainText('excluded by exception');assert(await drawer.evaluate(e=>e.scrollTop<3));}
 try{
  await page.goto(target.href);await expect(page.locator('[data-browser-fixture]')).toBeVisible();
  await expect(page.locator('#detailContent .message-row').first()).toBeVisible();await page.locator('#detailContent input[data-selection]').first().check();
  const selection=await page.locator('#selectionCount').textContent();
  await page.locator('#createMessageRule').click();await page.getByRole('button',{name:'Create new rule',exact:true}).click();await setup('Label_42');
  await content.locator('[data-destination-picker] input[type=checkbox]').check();await review();
  const subjects=await content.locator('.rule-sample tbody tr:visible a').allTextContents();assert.equal(subjects.length,3);
  for(const href of await content.locator('.rule-sample tbody tr:visible a').evaluateAll(es=>es.map(e=>e.href)))assert(href.startsWith('https://mail.google.com/'));
  await content.getByRole('button',{name:'Show more',exact:true}).click();await expect(content.locator('.rule-sample tbody tr:visible')).toHaveCount(10);
  await content.getByRole('button',{name:'Show less',exact:true}).click();await expect(content.locator('.rule-sample tbody tr:visible')).toHaveCount(3);
  await content.getByRole('link',{name:'Back to edit rule',exact:true}).click();await setup('Label_42');await expect(content.locator('[data-destination-picker] input[type=checkbox]')).toBeChecked();await review();
  await page.screenshot({path:path.join(output,'review-inbox.png')});
  if(process.env.DARKREADER_SCRIPT_PATH){await page.addScriptTag({path:process.env.DARKREADER_SCRIPT_PATH});await page.evaluate(()=>{DarkReader.setFetchMethod(window.fetch);DarkReader.enable({brightness:100,contrast:90,sepia:0});});await page.waitForTimeout(700);assert(await content.locator('.rule-review-actions').evaluate(e=>{const rgb=getComputedStyle(e).backgroundColor.match(/\d+/g).map(Number);return rgb[0]<100&&rgb[1]<100&&rgb[2]<100;}),'Dark Reader decision footer must remain dark');await page.screenshot({path:path.join(output,'review-dark-reader.png')});await page.evaluate(()=>DarkReader.disable());}

  await content.getByRole('button',{name:'Create rule',exact:true}).click();await expect(page.locator('#inboxRuleTitle')).toHaveText('Rule enabled');
  await expect(content).toContainText('24 matching emails in Inbox');await expect(content).toContainText('12 unread');assert.equal(jobs,0);
  await expect(page.locator('#selectionCount')).toHaveText(selection);await expect(content.getByRole('link',{name:'Edit this rule'})).toHaveAttribute('href',/\/rules\/[a-f0-9-]+$/);
  await expect(content.getByRole('link',{name:'View all rules'})).toHaveAttribute('href','/rules');
  await page.screenshot({path:path.join(output,'success-inbox.png')});
  await content.getByRole('button',{name:'Review existing emails'}).click();await expect(page.locator('#confirm')).toBeVisible();await expect(page.locator('#confirmScope')).toContainText('Inbox');await expect(page.locator('#confirmCounts')).toContainText('24 fixed targets');assert.equal(jobs,0);
  await expect(drawer).not.toBeVisible();await page.locator('#confirm [data-close]').last().click();await expect(drawer).not.toBeVisible();await expect(page.locator('#detailContent .message-row').first()).toBeVisible();assert.equal(jobs,0);
  // Add a discovered sender to the enabled rule without leaving Inbox.
  await page.locator('#senders .sender-focus').filter({hasText:'news3@sender.example'}).click();
  await expect(page.locator('#detailContent .message-row').first()).toBeVisible();await page.locator('#detailContent input[data-selection]').first().check();
  const additionSelection=await page.locator('#selectionCount').textContent();
  await page.locator('#createMessageRule').click();await content.getByRole('button',{name:'Add sender to existing rule',exact:true}).click();
  await expect(content.locator('[data-rule-choice]')).toHaveCount(1);
  await content.getByRole('searchbox',{name:'Find a rule'}).fill('no such rule');await expect(content.locator('[data-rule-search-empty]')).toBeVisible();
  await content.getByRole('searchbox',{name:'Find a rule'}).fill('Organize');
  await content.getByRole('button',{name:'Review addition →',exact:true}).click();await expect(content.locator('[data-rule-add-review]')).toBeVisible();
  await expect(content).toContainText('news3@sender.example');await expect(content).toContainText('subject conditions and exceptions stay the same');
  await page.screenshot({path:path.join(output,'add-sender-review.png')});
  await content.getByRole('link',{name:'Back',exact:true}).click();await expect(content.locator('[data-rule-choice]')).toHaveCount(1);
  await content.getByRole('button',{name:'Review addition →',exact:true}).click();
  await content.getByRole('button',{name:'Update active rule',exact:true}).click();await expect(content.locator('[data-rule-add-result]')).toContainText('Sender added to rule');
  await expect(page.locator('#selectionCount')).toHaveText(additionSelection);assert.equal(jobs,0);
  await content.getByRole('button',{name:'Review existing emails from added senders',exact:true}).click();await expect(page.locator('#confirm')).toBeVisible();
  await expect(page.locator('#confirmCounts')).toContainText('24 fixed targets');assert.equal(jobs,0);
  await page.locator('#confirm [data-close]').last().click();
  await page.locator('#senders .sender-focus').filter({hasText:'news2@sender.example'}).click();await expect(page.locator('#detailContent .message-row').first()).toBeVisible();await page.locator('#detailContent input[data-selection]').first().check();
  await page.locator('#createMessageRule').click();await page.getByRole('button',{name:'Create new rule',exact:true}).click();await setup('Label_42');await review();await content.getByRole('button',{name:'Create rule',exact:true}).click();await expect(page.locator('#inboxRuleTitle')).toHaveText('Rule enabled');
  await content.getByRole('button',{name:'Review existing emails'}).click();await expect(drawer).not.toBeVisible();await page.locator('#confirmAction').click();await expect(page.locator('#confirm')).not.toBeVisible();assert.equal(jobs,1);
  await page.locator('[data-scope="all"]').click();await page.locator('#senders .sender-focus').filter({hasText:'news1@sender.example'}).click();
  await expect(page.locator('#detailContent .message-row').first()).toBeVisible();await page.locator('#detailContent input[data-selection]').first().check();
  await page.locator('#createMessageRule').click();await page.getByRole('button',{name:'Create new rule',exact:true}).click();await setup('Label_43');await review();await content.getByRole('button',{name:'Create rule',exact:true}).click();
  await expect(page.locator('#inboxRuleTitle')).toHaveText('Rule enabled');await expect(content).toContainText('30 matching emails in All mailbox');await expect(content).toContainText('24 in Inbox');
  await page.screenshot({path:path.join(output,'success-all-mail.png')});
  for(const width of [1280,1440]){await page.setViewportSize({width,height:900});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));const box=await content.getByRole('button',{name:'Review existing emails'}).boundingBox();assert(box.y+box.height<=900);}
  const editUrl=await content.getByRole('link',{name:'Edit this rule'}).getAttribute('href');
  await content.getByRole('button',{name:'Review existing emails'}).click();await expect(drawer).not.toBeVisible();await expect(page.locator('#confirmScope')).toContainText('All Mailbox');await expect(page.locator('#confirmCounts')).toContainText('30 fixed targets');assert.equal(jobs,1);
  await page.locator('#confirm [data-close]').last().click();await expect(drawer).not.toBeVisible();
  await page.goto(target.origin+editUrl);await expect(page).toHaveURL(/\/rules\/[a-f0-9-]+$/);await expect(page.getByRole('button',{name:'Pause to edit'})).toBeVisible();
  const ruleUrl=page.url();await page.goto(ruleUrl+'?result=true&mailScope=all');await page.getByRole('button',{name:'Review existing emails'}).click();await expect(page).toHaveURL(target.href);await expect(page.locator('#confirm')).toBeVisible();await expect(page.locator('#confirmScope')).toContainText('All Mailbox');await expect(page.locator('#confirmCounts')).toContainText('30 fixed targets');assert.equal(jobs,1);await page.locator('#confirm [data-close]').last().click();
  // Review one identifiable overlap among 42 addresses, then check them in one read-only request.
  await page.locator('#createSenderRule').click();await content.getByRole('button',{name:'Create new rule',exact:true}).click();await setup('Label_42');
  const overlapSenders=['overlap@example.test',...Array.from({length:41},(_,i)=>`other${i}@example.test`)];
  await content.locator('[name="senders"]').fill(overlapSenders.join(', '));
  await content.getByRole('button',{name:'Review rule →',exact:true}).click();
  await expect(content.locator('.rule-overlap')).toHaveCount(1);await expect(content.locator('.rule-overlap')).toContainText('1 of 42 selected addresses');
  await expect(content.locator('.rule-overlap-senders').first()).toContainText('overlap@example.test');
  await content.getByText('Full conditions of this existing rule',{exact:true}).click();
  await expect(content.locator('.rule-overlap-conditions')).toContainText('Invoice');await expect(content.locator('.rule-overlap-conditions')).toContainText('subject:receipt');
  await content.getByRole('button',{name:'Check all selected senders against this filter',exact:true}).click();
  await expect(content.locator('[data-overlap-result]')).toContainText('Gmail check: 0 current Inbox messages');
  await expect(content.locator('[data-overlap-result]')).toContainText('All 42 selected addresses checked together');
  assert.equal(jobs,1);await page.screenshot({path:path.join(output,'specific-rule-overlap.png')});
  assert.deepEqual(errors,[]);console.log('PASS: distinct setup/review/success, preserved edits and selection, recent 3 linked examples, show more, fixed decision controls, existing-rule sender addition and added-sender-only filing, separate optional filing, scoped Inbox/unread and All mailbox/Inbox counts, Rules navigation, desktop layout.');
 }catch(error){await page.screenshot({path:path.join(output,'failure.png')});console.error(await page.locator('#inboxRuleError').textContent());console.error(await page.locator('[data-destination-error]').allTextContents());throw error;}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
