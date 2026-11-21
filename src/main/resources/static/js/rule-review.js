'use strict';
(() => {
  async function checkOverlap(button) {
    const root=button.closest('.rule-overlap'), result=root.querySelector('[data-overlap-result]');
    button.disabled=true;result.textContent='Checking all selected senders against the full Gmail filter…';
    try {
      const body=new URLSearchParams({account:button.dataset.account,preview:button.dataset.preview,remote:button.dataset.remote,fingerprint:button.dataset.fingerprint,inbox:'true'});
      const response=await fetch(`/rules/${button.dataset.ruleId}/interaction-check`,{method:'POST',body,credentials:'same-origin',headers:{'X-Requested-With':'XMLHttpRequest','X-TidyMail-Account':button.dataset.account,[document.querySelector('meta[name=_csrf_header]').content]:document.querySelector('meta[name=_csrf]').content}});
      if(!response.ok){const html=new DOMParser().parseFromString(await response.text(),'text/html');throw new Error(html.querySelector('[role="alert"]')?.textContent||'The filter could not be checked. No match was verified. Try again.');}
      const data=await response.json();if(!root.isConnected)return;
      result.replaceChildren();
      const heading=document.createElement('h4');heading.textContent=`Gmail check: ${data.currentInboxMatches} current Inbox messages match this filter`;
      const explanation=document.createElement('p');explanation.textContent=data.explanation;
      const coverage=document.createElement('p');coverage.className='muted';coverage.textContent=`Checked ${new Date(data.checkedAt).toLocaleString()}. All ${data.selectedAddresses} selected addresses checked together. ${data.indexedMatches} matching messages can be identified in TidyMail; ${data.unindexedMatches} are not currently indexed here. No mail was moved.`;
      result.append(heading,explanation,coverage);
      const wrap=document.createElement('div');wrap.className='rule-overlap-senders';wrap.tabIndex=0;wrap.setAttribute('role','region');wrap.setAttribute('aria-label','Verified matches by selected sender');
      const table=document.createElement('table'), head=table.createTHead().insertRow();
      for(const text of ['Selected address','Current Inbox matches indexed here']){const cell=document.createElement('th');cell.textContent=text;head.append(cell);}
      const tbody=table.createTBody();for(const sender of data.senders){const row=tbody.insertRow();row.insertCell().textContent=sender.address;row.insertCell().textContent=String(sender.matches);}
      wrap.append(table);result.append(wrap);
      if(data.examples.length){const title=document.createElement('h4');title.textContent='Examples that match this filter now';result.append(title);for(const e of data.examples){const p=document.createElement('p');p.append(e.sender+' · ');if(e.url){const a=document.createElement('a');a.href=e.url;a.target='_blank';a.rel='noopener noreferrer';a.textContent=e.subject;p.append(a);}else p.append(e.subject);result.append(p);}}
    }catch(error){if(root.isConnected)result.textContent=error.message;}
    finally{button.disabled=false;}
  }
  document.addEventListener('click', async event => {
    const check=event.target.closest('[data-overlap-check]');if(check){if(!check.disabled)await checkOverlap(check);return;}
    const more=event.target.closest('[data-rule-more]');
    if(more){const expanded=more.textContent==='Show less';for(const row of more.closest('[data-rule-preview]').querySelectorAll('[data-rule-extra-example="true"]'))row.hidden=expanded;more.textContent=expanded?'Show more':'Show less';return;}
    const button=event.target.closest('[data-rule-existing]');if(!button||button.disabled)return;
    button.disabled=true;
    const root=button.closest('[data-inbox-rule-result]'), status=root.querySelector('[data-rule-filing-status]');
    try {
      const account=document.body.dataset.account||document.querySelector('form [name="account"]')?.value||root.dataset.account;
      const body=new URLSearchParams({account,mailScope:button.dataset.mailScope});
      if(button.dataset.senders)body.set("senders",button.dataset.senders);
      const response=await fetch(`/rules/${button.dataset.ruleId}/existing-preview`,{method:'POST',body,headers:{'X-Requested-With':'XMLHttpRequest','X-TidyMail-Account':account,[document.querySelector('meta[name=_csrf_header]').content]:document.querySelector('meta[name=_csrf]').content}});
      if(!response.ok){const html=new DOMParser().parseFromString(await response.text(),'text/html');throw new Error(html.querySelector('[role="alert"]')?.textContent||'Could not review existing emails. Try again.');}
      const preview=await response.json();
      if(!root.isConnected || (root.closest('dialog') && !root.closest('dialog').open))return;
      if(document.getElementById('inboxRule'))document.dispatchEvent(new CustomEvent('tidymail:rule-existing-preview',{detail:preview}));
      else {sessionStorage.setItem('tidymail-rule-filing-preview',JSON.stringify({account,preview}));location.assign('/dashboard');}
    } catch(error){status.textContent=error.message;}
    finally{button.disabled=false;}
  });
})();
