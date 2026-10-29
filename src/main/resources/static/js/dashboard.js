'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const state = {scope:'inbox',page:0,messagePage:0,tab:'messages',data:null,focused:null,details:null,action:'MARK_READ',preview:null,targetPage:0,revision:0};
  const selected = new Map(), expanded = new Map();
  let jobs=[], confirming=false, jobsRequest=0;
  const actionName=action=>({MARK_READ:'Mark as Read',ARCHIVE:'Archive',TRASH:'Move to Trash'}[action]||action);
  const activeJob=j=>j.remaining>0||j.unresolved>0;
  const jobCounts=j=>`${fmt(j.changed)} changed · ${fmt(j.failed)} failed · ${fmt(j.skipped)} skipped · ${fmt(j.unresolved)} unresolved · ${fmt(j.cancelled)} cancelled before dispatch · ${fmt(j.remaining)} remaining of ${fmt(j.total)}`;
  function renderJobs() {
    const focused=document.activeElement?.dataset?.cancelJob;
    $('jobProgress').replaceChildren();
    for(const job of jobs.filter(activeJob).slice(0,3)) {
      const row=node('div',undefined,'job-progress'),copy=node('div');
      copy.append(node('strong',job.cancelRequested?(job.remaining?'Cancellation pending — claimed requests may finish':(job.retryReason?.includes('limit reached')?'Cancelled — unresolved outcomes retained':'Cancelled — verification continues')):actionName(job.action)+' · '+job.status.toLowerCase().replaceAll('_',' ')),node('p',jobCounts(job)+(job.retryReason?' · '+job.retryReason:''),'muted'));
      row.append(copy);if(!job.cancelRequested){const cancel=node('button','Cancel');cancel.dataset.cancelJob=job.id;cancel.onclick=()=>cancelJob(job.id,cancel);row.append(cancel);}$('jobProgress').append(row);
    }
    updateSelection();if($('activity').open)renderActivity();
    if(focused&&!$('activity').open){const next=[...$('jobProgress').querySelectorAll('button')].find(b=>b.dataset.cancelJob===focused);(next||$('activityOpen')).focus();}
  }
  function renderActivity() {
    const focused=document.activeElement?.dataset?.cancelJob;
    $('activityContent').replaceChildren(node('p',$('syncStatus').textContent),node('p',$('coverage').textContent));
    for(const job of jobs){const item=node('div',undefined,'target');item.append(node('strong',`${actionName(job.action)} · ${job.status.toLowerCase().replaceAll('_',' ')}`),node('p',jobCounts(job)+(job.retryReason?' · '+job.retryReason:'')),node('small',`Confirmed ${dateText(job.createdAt)} · Completed changes stay.`,'muted'));if(activeJob(job)&&!job.cancelRequested){const cancel=node('button','Cancel remaining work');cancel.dataset.cancelJob=job.id;cancel.onclick=()=>cancelJob(job.id,cancel);item.append(cancel);}$('activityContent').append(item);}
    if(!jobs.length)$('activityContent').append(node('p','No cleanup jobs yet.'));
    if(focused&&$('activity').open){const next=[...$('activityContent').querySelectorAll('button')].find(b=>b.dataset.cancelJob===focused);(next||$('activity').querySelector('[data-close]')).focus();}
    const sync=state.data?.sync||{};if(jobs.some(j=>j.retryReason?.startsWith('Reconnect'))||sync.oauth_status==='REAUTH_REQUIRED'||sync.lifecycle==='REAUTH_REQUIRED'){const link=node('a','Reconnect Gmail');link.href='/oauth2/authorization/google';$('activityContent').append(link);}
  }
  async function cancelJob(id,button) {button.disabled=true;try{await api(`jobs/${id}/cancel`,{});await pollJobs();}catch(error){notice(error.message);button.disabled=false;}}
  async function pollJobs() {
    const request=++jobsRequest;try{const latest=await api('jobs');if(request!==jobsRequest)return;const prior=new Map(jobs.map(j=>[j.id,j]));if(latest.length===jobs.length&&latest.every(j=>prior.get(j.id)?.version===j.version))return;const changed=latest.some(j=>prior.has(j.id)&&prior.get(j.id).version!==j.version);jobs=latest;renderJobs();if(changed){await loadPage();if(jobs.some(j=>prior.has(j.id)&&activeJob(prior.get(j.id))&&!activeJob(j)))notice('Cleanup settled. '+jobCounts(jobs.find(j=>prior.has(j.id)&&activeJob(prior.get(j.id))&&!activeJob(j))));}}catch(error){notice('Job status unavailable. Saved work continues. '+error.message);}
  }
  async function confirmJob() {
    if(confirming||!state.preview)return;
    confirming=true;$('confirmAction').disabled=true;const preview=state.preview;
    try{const job=await api('jobs',{preview:preview.id});state.preview=null;selected.clear();$('confirm').close();renderSelectionControls();notice(actionName(preview.action)+' saved. You can close this page; work continues.');await pollJobs();if(!jobs.some(j=>j.id===job.id)){jobs.unshift(job);renderJobs();}}catch(error){notice(error.message);}finally{confirming=false;$('confirmAction').disabled=!state.preview||!state.preview.effective;}
  }
  let filterEpoch=0, listRequest=0, detailRequest=0, selectionBusy=0, mergePage=0, correctionDraft=null;
  const fmt = number => Number(number).toLocaleString();
  function node(tag,text,className) { const e=document.createElement(tag); if(text!==undefined)e.textContent=text; if(className)e.className=className; return e; }
  function notice(text) { $('notice').hidden=false; $('notice').textContent=text; }
  async function api(path,body) {
    const headers={'Accept':'application/json'};
    if(body!==undefined) {headers['Content-Type']='application/json';headers[document.querySelector('meta[name=_csrf_header]').content]=document.querySelector('meta[name=_csrf]').content;}
    const response=await fetch('/dashboard/api/'+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body),cache:'no-store'});
    if(response.redirected || response.status===401) throw new Error('Your session ended. Sign in again to continue.');
    if(!response.ok) {let error={};try{error=await response.json();}catch(_){}throw new Error(error.message||'Could not load this request. Try again.');}
    const text=await response.text(); return text?JSON.parse(text):null;
  }
  function dateText(raw) {return raw ? new Intl.DateTimeFormat(undefined,{dateStyle:'medium',timeZone:$('zone').value}).format(new Date(raw)) : 'Undated';}
  function filter() {
    let start=$('start').value,end=$('end').value;
    if($('date').value==='all')start=end='';
    if($('date').value==='7') {
      const parts=new Intl.DateTimeFormat('en-CA',{timeZone:$('zone').value,year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());
      const v=type=>parts.find(p=>p.type===type).value;
      end=`${v('year')}-${v('month')}-${v('day')}`;
      const first=new Date(end+'T12:00:00Z');first.setUTCDate(first.getUTCDate()-6);start=first.toISOString().slice(0,10);
    }
    return {scope:state.scope,q:$('search').value,start,end,zone:$('zone').value,page:state.page};
  }
  const query = (value=filter()) => new URLSearchParams(Object.entries(value)).toString();
  function checkbox(label,count,match,onChange) {
    const e=node('input');e.type='checkbox';e.setAttribute('aria-label',label);e.dataset.selection='true';
    e.checked=count>0 && match>=count;e.indeterminate=match>0 && match<count;e.disabled=selectionBusy>0;
    e.addEventListener('change',()=>onChange(e.checked));return e;
  }
  const selectedCount = (group,address) => [...selected.values()].filter(v=>v.groupId===group && (address===undefined || v.address===address)).length;
  function updateSelection() {
    $('selectionRow').hidden=selected.size===0;$('dock').hidden=selected.size===0&&!jobs.some(activeJob);$('selectionCount').textContent=`${fmt(selected.size)} messages selected`;
    const groupCount=new Set([...selected.values()].map(v=>v.groupId)).size;
    $('selectionDescription').textContent=`${fmt(groupCount)} sender ${groupCount===1?'group':'groups'} · includes other pages`;
    const groups=state.data?.groups||[], total=groups.reduce((n,g)=>n+g.messages,0), chosen=groups.reduce((n,g)=>n+selectedCount(g.id),0);
    $('selectPage').checked=total>0 && chosen>=total;$('selectPage').indeterminate=chosen>0 && chosen<total;$('selectPage').disabled=!total||selectionBusy>0;
    $('review').disabled=!selected.size||selectionBusy>0;
  }
  async function selectGroups(groups,checked,address) {
    if(selectionBusy)return;const epoch=filterEpoch;selectionBusy++;renderSelectionControls();
    try {
      if(checked) {
        const rows=await api('selection',{filter:filter(),groups,address:address??null,revision:state.revision});
        if(epoch!==filterEpoch)return;
        rows.forEach(row=>selected.set(row.id,row));
      } else {
        [...selected.entries()].forEach(([id,row])=>{if(groups.includes(row.groupId)&&(address===undefined||address===row.address))selected.delete(id);});
      }
      invalidatePreview();
    } catch(error) {notice(error.message);} finally {selectionBusy--;renderSelectionControls();}
  }
  function renderSelectionControls() {renderGroups();renderDetail();updateSelection();}
  function invalidatePreview() {
    if(state.preview) {const old=state.preview.id;state.preview=null;api(`previews/${old}/invalidate`,{}).catch(()=>{});}
    if($('confirm').open)$('confirm').close();
  }
  function clearSelection(message) {selected.clear();invalidatePreview();renderSelectionControls();if(message)notice(message);}
  function filtersChanged() {
    filterEpoch++;detailRequest++;state.page=0;state.messagePage=0;expanded.clear();state.details=null;
    if($('correction').open)$('correction').close();
    clearSelection('Filters changed. Draft selection was cleared.');loadPage();
  }
  async function loadPage() {
    const request=++listRequest, epoch=filterEpoch;$('senderTable').setAttribute('aria-busy','true');
    try {
      const data=await api('page?'+query());if(request!==listRequest||epoch!==filterEpoch)return;
      if(state.data&&state.revision!==data.revision) {selected.clear();expanded.clear();invalidatePreview();notice('Grouping changed. Draft selection was cleared.');}
      const lastPage=Math.max(0,Math.ceil(Number(data.summary.groups)/10)-1);
      if(state.page>lastPage){state.page=lastPage;return loadPage();}
      const updates=await Promise.all(data.groups.filter(g=>expanded.has(g.id)).map(async g=>{
        try{return [g.id,(await api(`groups/${g.id}?${query()}`)).addresses];}catch(_){return [g.id,null];}
      }));
      if(request!==listRequest||epoch!==filterEpoch)return;
      for(const [id,addresses] of updates){if(addresses)expanded.set(id,addresses);else expanded.delete(id);}
      state.data=data;state.revision=data.revision;
      $('accountEmail').textContent=data.sync.email;
      $('matching').textContent=fmt(data.summary.messages);$('unreadCount').textContent=fmt(data.summary.unread);$('groupCount').textContent=fmt(data.summary.groups);
      const c=data.coverage;
      $('coverage').textContent=Number(c.indexed)?`Indexed dates: ${dateText(c.oldest)}–${dateText(c.newest)} · Results include indexed messages only · As of ${dateText(c.as_of)} · ${filter().zone}`:'Coverage unavailable — no messages indexed yet.';
      const gaps=[];if(c.unknown_labels)gaps.push(`${fmt(c.unknown_labels)} messages have unknown labels and are excluded`);if(c.ambiguous_sent)gaps.push(`${fmt(c.ambiguous_sent)} Sent messages lack independent delivery evidence`);if(c.unknown_identity)gaps.push(`${fmt(c.unknown_identity)} identities are kept separate pending fresh metadata`);if(data.summary.undated)gaps.push(`${fmt(data.summary.undated)} matching messages are undated`);
      if(gaps.length)$('coverage').textContent+=' · '+gaps.join(' · ');
      const sync=data.sync;
      $('syncStatus').textContent=sync.oauth_status==='REAUTH_REQUIRED'||sync.lifecycle==='REAUTH_REQUIRED'?'Reconnect required':sync.error_code||sync.lifecycle==='ERROR'?'Sync needs attention':!sync.initial_batch_completed_at?'Fetching mail…':sync.backfill_status==='RUNNING'?'Fetching older mail…':sync.incremental_last_success_at?`Synced ${dateText(sync.incremental_last_success_at)}`:'Waiting for successful sync';
      $('empty').hidden=!!data.groups.length;$('empty').textContent=Number(c.indexed)?'No messages match these filters. Clear filters to browse more indexed mail.':'No messages indexed yet. Fetching mail…';
      $('pageLabel').textContent=data.groups.length?`${state.page*10+1}–${state.page*10+data.groups.length} of ${fmt(data.summary.groups)}`:`0 of ${fmt(data.summary.groups)}`;
      $('prevPage').disabled=state.page===0;$('nextPage').disabled=(state.page+1)*10>=data.summary.groups;
      renderGroups();updateSelection();
      if(!state.focused&&data.groups.length)focusGroup(data.groups[0]);
      else if(state.focused) {
        const current=data.groups.find(g=>g.id===state.focused.id);
        if(current)state.focused=current;
        if(epoch===filterEpoch)loadDetails();
      }
    } catch(error) {if(request===listRequest){notice(error.message);$('empty').hidden=false;$('empty').textContent='Could not load indexed mail. Change filters or reload to try again.';}}
    finally{if(request===listRequest)$('senderTable').removeAttribute('aria-busy');}
  }
  function renderGroups() {
    const body=$('senders');body.replaceChildren();
    (state.data?.groups||[]).forEach(g=>{
      const row=node('tr');if(state.focused?.id===g.id)row.classList.add('focused');
      const cell=node('td');cell.append(checkbox(`Select all ${g.messages} matching messages from ${g.label}`,g.messages,selectedCount(g.id),checked=>selectGroups([g.id],checked)));row.append(cell);
      const sender=node('td'),focus=node('button',undefined,'sender-focus'),badge=node('span',g.label.slice(0,1).toUpperCase(),'brand'),text=node('span',undefined,'sender-label');
      text.append(node('strong',g.label),node('small',`${g.addresses} sender ${g.addresses===1?'address':'addresses'}`,'muted'));focus.append(badge,text);focus.onclick=()=>focusGroup(g);sender.append(focus);row.append(sender,node('td',fmt(g.messages)),node('td',fmt(g.unread)),node('td',dateText(g.latest)));
      const expandCell=node('td');if(g.addresses>1){const expand=node('button',expanded.has(g.id)?'⌃':'⌄');expand.setAttribute('aria-label',`Show sender addresses for ${g.label}`);expand.setAttribute('aria-expanded',String(expanded.has(g.id)));expand.onclick=()=>expandGroup(g);expandCell.append(expand);}row.append(expandCell);body.append(row);
      if(expanded.has(g.id)) {
        const detail=node('tr',undefined,'address-row'),td=node('td');td.colSpan=6;
        for(const address of expanded.get(g.id)) {const a=node('div',undefined,'inline-address');a.append(checkbox(`Select ${address.messages} matching messages from ${address.address}`,address.messages,selectedCount(g.id,address.address),checked=>selectGroups([g.id],checked,address.address)),node('span',`${address.address} · ${fmt(address.messages)} messages`));td.append(a);}detail.append(td);body.append(detail);
      }
    });
  }
  async function expandGroup(g) {
    if(expanded.has(g.id)){expanded.delete(g.id);renderGroups();return;}
    const epoch=filterEpoch;
    try {const detail=await api(`groups/${g.id}?${query()}`);if(epoch!==filterEpoch||detail.revision!==state.revision)return;expanded.set(g.id,detail.addresses);renderGroups();}catch(error){notice(error.message);}
  }
  function focusGroup(g) {state.focused=g;state.messagePage=0;state.details=null;$('detail').hidden=false;renderGroups();renderDetail();loadDetails();}
  async function loadDetails() {
    if(!state.focused)return;const request=++detailRequest,group=state.focused.id,epoch=filterEpoch;
    try {const details=await api(`groups/${group}?${query()}&messagePage=${state.messagePage}`);if(request!==detailRequest||epoch!==filterEpoch||group!==state.focused?.id)return;if(details.revision!==state.revision){filtersChanged();return;}state.details=details;renderDetail();}
    catch(error){if(request===detailRequest)notice(error.message);}
  }
  function renderDetail() {
    if(!state.focused)return;
    $('detailTitle').textContent=state.focused.label;$('detailBadge').textContent=state.focused.label.slice(0,1).toUpperCase();
    $('detailCounts').textContent=state.details ? `${fmt(state.details.total)} matching messages` : `${fmt(state.focused.messages)} matching messages · ${fmt(state.focused.unread)} unread`;
    const content=$('detailContent');content.replaceChildren();const detail=state.details;
    if(!detail){content.append(node('p','Loading indexed details…','evidence'));return;}
    if(state.tab==='messages') {
      const visible=node('div',undefined,'evidence'),count=detail.messages.filter(m=>selected.has(m.id)).length;
      visible.append(checkbox(`Select visible messages (${detail.messages.length})`,detail.messages.length,count,checked=>{for(const m of detail.messages){if(checked)selected.set(m.id,m);else selected.delete(m.id);}invalidatePreview();renderSelectionControls();}),node('span',' Select visible messages'));content.append(visible);
      for(const m of detail.messages){const row=node('div',undefined,'message-row'),text=node('div',undefined,'message-text');text.append(node('span',m.subject||'(No subject)','message-subject'),node('small',m.address,'muted'),node('small',`${dateText(m.receivedAt)} · ${m.unread?'Unread':'Read'}`,'muted'));row.append(checkbox(`Select message ${m.subject||'(No subject)'}`,1,selected.has(m.id)?1:0,checked=>{if(checked)selected.set(m.id,m);else selected.delete(m.id);invalidatePreview();renderSelectionControls();}),text);content.append(row);}
      if(!detail.total)content.append(node('p','No matching messages in this group.','evidence'));
      const paging=node('div',undefined,'paging'),prev=node('button','‹'),next=node('button','›');prev.setAttribute('aria-label','Previous message page');next.setAttribute('aria-label','Next message page');prev.disabled=state.messagePage===0;next.disabled=(state.messagePage+1)*10>=detail.total;
      prev.onclick=()=>{state.messagePage--;loadDetails();};next.onclick=()=>{state.messagePage++;loadDetails();};paging.append(prev,node('span',detail.messages.length?`${state.messagePage*10+1}–${state.messagePage*10+detail.messages.length} of ${fmt(detail.total)}`:'0 messages'),next);content.append(paging);
    } else {
      for(const a of detail.addresses){const row=node('div',undefined,'message-row');row.append(checkbox(`Select ${a.messages} matching messages from ${a.address}`,a.messages,selectedCount(state.focused.id,a.address),checked=>selectGroups([state.focused.id],checked,a.address)),node('span',`${a.address} · ${fmt(a.messages)} messages`));content.append(row);}
      const reasons=[...new Set(detail.units.map(u=>u.reason))];content.append(node('p',[state.focused.evidence,...reasons].filter(Boolean).join(' · '),'evidence'));
      const controls=node('div',undefined,'corrections'),organize=node('button','Merge / keep separate');organize.disabled=!detail.units.some(u=>u.kind!=='MESSAGE');organize.onclick=openCorrections;controls.append(organize);if(organize.disabled)controls.append(node('p','Fresh sender metadata is needed before grouping corrections.','muted'));content.append(controls);
    }
  }
  async function openPreview() {
    const epoch=filterEpoch,ids=[...selected.keys()];$('review').disabled=true;
    try {const preview=await api('previews',{filter:filter(),ids,action:state.action,revision:state.revision});if(epoch!==filterEpoch||ids.length!==selected.size||ids.some(id=>!selected.has(id))){api(`previews/${preview.id}/invalidate`,{});return;}
      state.preview=preview;state.targetPage=0;
      $('confirmAction').disabled=confirming||!preview.effective;
      $('actionAvailability').textContent='Work is saved before processing. Closing this page does not cancel it.';
      const action=preview.action==='MARK_READ'?`Mark ${fmt(preview.effective)} as read`:preview.action==='ARCHIVE'?`Archive ${fmt(preview.effective)} messages`:`Move ${fmt(preview.effective)} to Trash`;
      $('confirmTitle').textContent=action+'?';$('confirmAction').textContent=action;
      $('confirmScope').textContent=`${preview.filter.scope==='inbox'?'Inbox':'All received mail'} · ${preview.filter.start||'All indexed dates'}${preview.filter.end?' through '+preview.filter.end:''} · ${preview.filter.zone}${preview.filter.q?' · Search: '+preview.filter.q:''}`;
      $('confirmCounts').textContent=`${fmt(preview.selected)} fixed targets · ${fmt(preview.effective)} eligible changes · ${fmt(preview.skipped)} will be skipped (${fmt(preview.protectedCount)} protected)`;
      $('confirmCoverage').textContent=`Indexed messages only · ${dateText(preview.coverage.oldest)}–${dateText(preview.coverage.newest)} · As of ${dateText(preview.coverage.as_of)}`;
      $('actionExplanation').textContent=preview.action==='ARCHIVE'?'Archive removes selected eligible messages from Inbox. They remain in All received mail.':preview.action==='TRASH'?'Move to Trash is not permanent deletion.':'Already-read messages will be skipped.';
      $('confirm').showModal();await loadTargets();
    }catch(error){notice(error.message);}finally{updateSelection();}
  }
  async function loadTargets() {
    if(!state.preview)return;const id=state.preview.id,page=state.targetPage;
    try{const result=await api(`previews/${id}/targets?page=${page}`);if(state.preview?.id!==id||state.targetPage!==page)return;$('exactTargets').replaceChildren();for(const target of result.rows){const row=node('div',undefined,'target');row.append(node('strong',target.subject_header||'(No subject)'),node('div',`${target.sender_address} · ${dateText(target.received_at)}`),node('small',target.reason,'muted'));$('exactTargets').append(row);}$('targetRange').textContent=`${page*10+1}–${page*10+result.rows.length} of ${fmt(result.total)}`;$('targetPrev').disabled=page===0;$('targetNext').disabled=(page+1)*10>=result.total;}catch(error){notice(error.message);invalidatePreview();}
  }
  async function openCorrections() {
    correctionDraft=null;$('applyCorrection').disabled=true;$('correctionAffected').replaceChildren();$('correctionUnit').replaceChildren();
    for(const u of state.details?.units||[]){const option=node('option',`${u.kind} · ${u.key||u.reason}`);option.value=u.id;$('correctionUnit').append(option);}
    try{const history=await api('corrections');$('resetCorrection').replaceChildren();for(const item of history){const o=node('option',`${item.kind} · ${item.label||dateText(item.created_at)}`);o.value=item.public_id;$('resetCorrection').append(o);}$('correction').showModal();mergePage=0;correctionFields();await findMergeGroups();}catch(error){notice(error.message);}
  }
  function correctionFields(){const op=$('correctionKind').value;$('mergeFields').hidden=op!=='MERGE';$('resetLabel').hidden=op!=='RESET';$('unitLabel').hidden=op==='RESET';correctionDraft=null;$('applyCorrection').disabled=true;$('correctionAffected').replaceChildren();}
  async function findMergeGroups(){try{const result=await api('page?'+query({scope:'all',q:$('mergeSearch').value,start:'',end:'',zone:$('zone').value,page:mergePage}));$('mergeGroup').replaceChildren();for(const g of result.groups.filter(g=>g.id!==state.focused?.id)){const o=node('option',g.label);o.value=g.id;$('mergeGroup').append(o);}$('mergeRange').textContent=`Page ${mergePage+1}`;$('mergePrev').disabled=mergePage===0;$('mergeNext').disabled=(mergePage+1)*10>=result.summary.groups;}catch(error){notice(error.message);}}
  async function previewCorrection(){
    correctionDraft=null;$('applyCorrection').disabled=true;const operation=$('correctionKind').value;
    try{const units=operation==='RESET'?[]:[$('correctionUnit').value];if(operation==='MERGE'){if(!$('mergeGroup').value)throw new Error('Find a second sender group first.');const second=await api(`groups/${$('mergeGroup').value}?${query({scope:'all',q:'',start:'',end:'',zone:$('zone').value,page:0})}`);if(!second.units.length)throw new Error('This group has no eligible units. Reload and try again.');units.push(second.units[0].id);}
      const request={operation,units,label:$('mergeLabel').value,resetId:operation==='RESET'?$('resetCorrection').value:null,revision:state.revision};const review=await api('corrections/preview',request);correctionDraft=request;$('correctionAffected').replaceChildren(node('p',`${fmt(review.affected.length)} affected address/list/message units. ${review.explanation}`));for(const u of review.affected)$('correctionAffected').append(node('div',u.label));$('applyCorrection').disabled=false;
    }catch(error){$('correctionAffected').textContent=error.message;}
  }
  async function applyCorrection(){if(!correctionDraft)return;$('applyCorrection').disabled=true;try{await api('corrections',correctionDraft);$('correction').close();state.focused=null;filtersChanged();notice('Grouping updated. Draft selection was cleared.');}catch(error){$('correctionAffected').textContent=error.message;}}

  document.querySelectorAll('[data-scope]').forEach(button=>button.onclick=()=>{if(state.scope===button.dataset.scope)return;state.scope=button.dataset.scope;document.querySelectorAll('[data-scope]').forEach(b=>{b.classList.toggle('active',b===button);b.setAttribute('aria-pressed',String(b===button));});filtersChanged();});
  $('search').addEventListener('input',filtersChanged);
  for(const id of ['date','start','end','zone'])$(id).addEventListener('change',()=>{if(id==='zone')api('settings',{zone:$('zone').value}).catch(error=>notice(error.message));$('startLabel').hidden=$('endLabel').hidden=$('date').value!=='custom';filtersChanged();});
  $('prevPage').onclick=()=>{state.page--;loadPage();};$('nextPage').onclick=()=>{state.page++;loadPage();};$('selectPage').onchange=e=>selectGroups((state.data?.groups||[]).map(g=>g.id),e.target.checked);
  $('clear').onclick=()=>clearSelection();$('review').onclick=openPreview;
  document.querySelectorAll('[data-action]').forEach(button=>button.onclick=()=>{state.action=button.dataset.action;invalidatePreview();document.querySelectorAll('[data-action]').forEach(b=>{b.classList.toggle('active',b===button);b.setAttribute('aria-pressed',String(b===button));});});
  document.querySelectorAll('[data-tab]').forEach((button,index,tabs)=>{button.onclick=()=>{state.tab=button.dataset.tab;tabs.forEach(b=>{b.classList.toggle('active',b===button);b.setAttribute('aria-selected',String(b===button));b.tabIndex=b===button?0:-1;});$('detailContent').setAttribute('aria-labelledby',button.id);renderDetail();};button.onkeydown=e=>{if(e.key==='ArrowLeft'||e.key==='ArrowRight'){e.preventDefault();const next=tabs[(index+1)%2];next.click();next.focus();}};});
  document.querySelectorAll('[data-close]').forEach(button=>button.onclick=()=>$(button.dataset.close).close());
  $('confirm').addEventListener('close',()=>{(selected.size?$('review'):$('senderTable')).focus();});$('correction').addEventListener('close',()=>{correctionDraft=null;});
  $('targetPrev').onclick=()=>{state.targetPage--;loadTargets();};$('targetNext').onclick=()=>{state.targetPage++;loadTargets();};
  $('activityOpen').onclick=()=>{renderActivity();$('activity').showModal();pollJobs();};
  $('confirmAction').onclick=confirmJob;
  $('activity').addEventListener('close',()=>$('activityOpen').focus());
  $('correctionKind').onchange=correctionFields;for(const id of ['correctionUnit','mergeGroup','mergeLabel','resetCorrection'])$(id).addEventListener('input',()=>{correctionDraft=null;$('applyCorrection').disabled=true;});
  $('findGroups').onclick=()=>{mergePage=0;findMergeGroups();};$('mergePrev').onclick=()=>{mergePage--;findMergeGroups();};$('mergeNext').onclick=()=>{mergePage++;findMergeGroups();};$('previewCorrection').onclick=previewCorrection;$('applyCorrection').onclick=applyCorrection;
  new ResizeObserver(()=>{document.documentElement.style.setProperty('--dock-height',$('dock').hidden?'0px':$('dock').getBoundingClientRect().height+'px');}).observe($('dock'));
  document.addEventListener('focusin',event=>{if($('dock').hidden||$('dock').contains(event.target)||event.target.closest('dialog'))return;const bottom=window.innerHeight-$('dock').getBoundingClientRect().height-24;const bounds=event.target.getBoundingClientRect();if(bounds.bottom>bottom)window.scrollBy({top:bounds.bottom-bottom,behavior:'instant'});});
  pollJobs();setInterval(pollJobs,2000);
  api('settings').then(settings=>{if(![...$('zone').options].some(o=>o.value===settings.zone)){const o=node('option',settings.zone);o.value=settings.zone;$('zone').append(o);}$('zone').value=settings.zone;return loadPage();}).catch(error=>notice(error.message));
})();
