/* ===== feature engine: registry access, detection, resolution, config v2 =====
   Pure logic (no DOM). The same rules are mirrored in engine/resolver.py and checked by tests/. */
const FEAT=Object.fromEntries(REGISTRY.features.map(f=>[f.id,f]));
const LEGACY={};REGISTRY.features.forEach(f=>{if(f.legacy)(LEGACY[f.legacy]=LEGACY[f.legacy]||[]).push(f.id)});
function selectedFromProject(p){const s=new Set(),ft=p.feat||{};for(const k in LEGACY)if(ft[k]===true)LEGACY[k].forEach(i=>s.add(i));if(ft.anim&&ft.anim!=='none')s.add('screen_transitions');return REGISTRY.features.map(f=>f.id).filter(i=>s.has(i))}
function closure(sel,appMin){
 const issues=[],origin={};
 REGISTRY.features.forEach(f=>{if(f.core)origin[f.id]='core'});
 sel.forEach(id=>{if(!FEAT[id])issues.push({level:'error',code:'UNKNOWN_FEATURE',id,msg:'Unknown feature: '+id});else if(!origin[id])origin[id]='selected'});
 const q=Object.keys(origin);
 for(let i=0;i<q.length;i++)for(const d of FEAT[q[i]].deps||[]){
  if(!FEAT[d]){issues.push({level:'error',code:'UNKNOWN_FEATURE',id:d,msg:'Unknown dependency: '+d});continue}
  if(!origin[d]){origin[d]='dependency';q.push(d)}}
 const enabled=REGISTRY.features.map(f=>f.id).filter(i=>origin[i]),seen=new Set();
 enabled.forEach(id=>{const f=FEAT[id];
  (f.conflicts||[]).forEach(c=>{if(origin[c]){const k=[id,c].sort().join('|');if(!seen.has(k)){seen.add(k);issues.push({level:'error',code:'CONFLICT',id,msg:f.name+' conflicts with '+FEAT[c].name})}}});
  if(f.status==='planned')issues.push({level:'error',code:'UNSUPPORTED',id,msg:f.name+' is not implemented yet (planned for phase '+f.phase+')'});
  if((f.min_sdk||21)>appMin)issues.push({level:'warning',code:'API_LEVEL',id,msg:f.name+' needs Android API '+f.min_sdk+' or newer'})});
 const perms={};enabled.forEach(id=>(FEAT[id].perms||[]).forEach(p=>{(perms[p]=perms[p]||[]).push(id)}));
 return{enabled,origin,issues,permissions:Object.keys(perms).sort().map(n=>({name:n,required_by:perms[n]}))}}
function detect(html){
 const ev={};
 REGISTRY.features.forEach(f=>{for(const r of f.detect||[]){let ok=true,m=null;
  for(const p of r.slice(1)){const x=new RegExp(p).exec(html);if(!x){ok=false;break}m=m||x[0]}
  if(ok){const rank={bridge:3,web:2,weak:1}[r[0]];if(!ev[f.id]||ev[f.id].rank<rank)ev[f.id]={kind:r[0],rank,sample:String(m).slice(0,40)}}}});
 return{features:ev,unsupported:(REGISTRY.unsupported_web_apis||[]).filter(u=>new RegExp(u.pattern).test(html))}}
function resolveProject(p){
 const sel=selectedFromProject(p),c=closure(sel,+p.app.min||21),ev=detect(p.src.html||''),on=new Set(c.enabled),out=[];
 REGISTRY.features.forEach(f=>{
  const e=ev.features[f.id],en=on.has(f.id),o=c.origin[f.id],reasons=[],flags=[],used=e?e.kind:'';let state;
  if(f.core){state='REQUIRED';reasons.push('Always included.')}
  else if(f.status==='planned'&&(en||used)){state='UNSUPPORTED';reasons.push('Not implemented yet (planned for phase '+f.phase+').')}
  else if(en&&o==='dependency'){state='REQUIRED';reasons.push('Needed by '+c.enabled.filter(i=>(FEAT[i].deps||[]).includes(f.id)).map(i=>FEAT[i].name).join(', ')+'.')}
  else if(en){state=used==='bridge'?'REQUIRED':used==='web'?'RECOMMENDED':'OPTIONAL';reasons.push(used==='bridge'?'Your page calls it.':used==='web'?'Your page uses a matching web feature.':'Selected manually; nothing in the page uses it yet.')}
  else if(used==='bridge'){state='REQUIRED';reasons.push('Your page calls it but it is switched off, so those calls will do nothing.')}
  else if(used==='web'){state='RECOMMENDED';reasons.push('Your page uses a web feature that works better natively.')}
  else if(used==='weak'){state='RECOMMENDED';reasons.push('Possibly needed: the page looks like it has this behaviour.')}
  else if((f.recommended_with||[]).some(i=>on.has(i))){state='RECOMMENDED';reasons.push('Recommended together with '+f.recommended_with.map(i=>FEAT[i].name).join(', ')+'.')}
  else state='NOT_NEEDED';
  if(f.caution&&(en||used)){flags.push('CAUTION');reasons.push(f.caution)}
  if(c.issues.some(i=>i.id===f.id&&i.code==='CONFLICT'))flags.push('CONFLICT');
  const missing=!en&&used==='bridge';
  const tone=state==='UNSUPPORTED'||flags.includes('CONFLICT')||missing?'warning':flags.includes('CAUTION')||state==='OPTIONAL'||(state==='RECOMMENDED'&&!en)?'attention':state==='NOT_NEEDED'?'muted':'positive';
  out.push({id:f.id,name:f.name,state,origin:o||'none',enabled:en,flags,tone,reasons,evidence:used,missing,show:!f.core&&state!=='NOT_NEEDED'})});
 const suggestions=out.filter(x=>!x.enabled&&(x.state==='REQUIRED'||x.state==='RECOMMENDED')&&FEAT[x.id].status==='available').map(x=>({id:x.id,reason:x.reasons[0]}));
 return{features:out,enabled:c.enabled,permissions:c.permissions,issues:c.issues,suggestions,unsupported:ev.unsupported,manual:p.perms||[]}}
function toV2(p){
 const cfg=JSON.parse(JSON.stringify(p)),anim=(cfg.feat&&cfg.feat.anim)||'none';delete cfg.feat;
 const r=resolveProject(p);
 return{format:'hab-build-config-2',schema:2,buildPerformed:false,config:cfg,features:{selected:selectedFromProject(p),settings:{anim:anim==='none'?'fade':anim}},
  analysis:{enabled:r.enabled,permissions:r.permissions,issues:r.issues,suggestions:r.suggestions,unsupported:r.unsupported.map(u=>u.id)}}}
function analysisCard(){
 const r=resolveProject(P),L={REQUIRED:'Required',RECOMMENDED:'Recommended',OPTIONAL:'Optional',UNSUPPORTED:'Not available yet'};
 const rows=r.features.filter(x=>x.show).map(x=>`<li data-tone="${x.tone}"><b>${esc(x.name)}</b>: ${L[x.state]||x.state}${x.flags.includes('CAUTION')?' (caution)':''}<small>${esc(x.reasons.join(' '))}</small></li>`).join('');
 const un=r.unsupported.map(u=>`<li data-tone="warning"><b>${esc(u.name)}</b>: not supported<small>${esc(u.why+' '+u.alternative)}</small></li>`).join('');
 const pm=r.permissions.map(x=>x.name).concat(r.manual.filter(n=>!r.permissions.some(x=>x.name===n))).map(n=>{const m=REGISTRY.permissions[n]||{};return `<li><b>${esc(m.title||n)}</b><small>${esc(m.why||'Chosen manually in the Permissions step.')} ${m.if_denied?'If denied: '+esc(m.if_denied):''}</small></li>`}).join('');
 return `<label>Feature analysis</label><small>Based on your HTML and the switches above.</small><ul class="alist">${rows||'<li>Nothing detected or selected yet.</li>'}${un}</ul><label>Permissions this app will request</label><ul class="alist">${pm}</ul>`+(r.issues.length?`<ul class="wr">${r.issues.map(i=>`<li>${esc(i.msg)}</li>`).join('')}</ul>`:'')}
