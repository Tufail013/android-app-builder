/* ===== actions ===== */
function upd(k,v){set(P,k,v);save();const f=STEPS[S].f.find(x=>x.k===k);
document.querySelectorAll(`[data-k="${k}"]`).forEach(x=>{if(x.tagName==='INPUT'&&x.type!=='checkbox'&&x.value!==v&&(x.type!=='color'||HEXR.test(v)))x.value=v});
const el=document.querySelector(`[data-e="${k}"]`);if(el&&f?.v)el.textContent=f.v(v,P)||'';pv()}
function flush(){if(S===1&&draft!==null&&draft!==P.src.html&&confirm('Save your HTML edits? Cancel discards them.')){P.src.html=draft;save()}}
function enter(){draft=S===1?P.src.html:null}
function go(n){flush();if(n>S){const e=errs(S);if(e.length){showAll=true;enter();if(S===1)draft=P.src.html;render();return toast('Fix the highlighted items first',1)}}
showAll=false;S=n;P.step=n;enter();save();scrollTo(0,0);render();const a=$('#app');a.classList.remove('sw');void a.offsetWidth;a.classList.add('sw')}
function exportP(ids){const ps=ids.map(i=>DB.list[i]).filter(Boolean);if(!ps.length)return toast('Select projects first',1);ps.forEach(p=>{if(p.app.auto)p.app.vc=String((+p.app.vc||0)+1)});save();
dl('app-builder-projects.json',{format:'hab-project-1',note:'Project source and configuration. Not an APK or AAB.',projects:ps});toast(ps.length+' project(s) exported');pop()}
function dl(n,o){
  const text=JSON.stringify(o,null,2);
  if(window.Android&&typeof Android.saveFile==='function'){
    const r=Android.saveFile(n,'application/json',text);
    if(String(r).startsWith('error')) return toast(String(r),1);
    if(r==='disabled') return toast('File saving is disabled in this APK',1);
    return toast('Saved to Downloads/'+n);
  }
  const a=document.createElement('a');
  a.href=URL.createObjectURL(new Blob([text],{type:'application/json'}));
  a.download=n;
  a.click();
  setTimeout(()=>URL.revokeObjectURL(a.href),1000);
}
async function doImport(f){try{if(f.size>2e7)throw Error('File is too large');const d=JSON.parse(await f.text());if(d.format!=='hab-project-1'||!Array.isArray(d.projects))throw Error('Not a project export');let n=0;
for(const x of d.projects.slice(0,100)){if(!x||typeof x.name!=='string')continue;const p=merge(blank(x.name.slice(0,60)),x);p.id=uid();p.step=Math.max(0,Math.min(STEPS.length-1,+p.step||0));p.disp.wm=false;
if(!IMGR.test(p.icon.data))p.icon={name:'',data:''};if(!IMGR.test(p.splash.img.data))p.splash.img={name:'',data:''};DB.list[p.id]=p;n++}
if(!n)throw Error('No valid projects found');save();toast(n+' project(s) imported');render()}catch(e){toast('Import failed: '+e.message,1)}}
function pickImg(t){const f=t.files[0],k=t.dataset.img;if(!f)return;if(!/^image\/(png|jpeg|webp|gif)$/.test(f.type))return toast('Use PNG, JPEG, WebP or GIF',1);if(f.size>1.5e6)return toast('Image must be under 1.5 MB',1);
const r=new FileReader();r.onload=()=>{if(!IMGR.test(r.result))return toast('Unsupported image data',1);const im=new Image();im.onload=()=>{set(P,k,{name:f.name.slice(0,80),data:r.result});save();render();if(im.width!==im.height)toast(`Image is ${im.width}x${im.height}. Square is recommended.`)};im.onerror=()=>toast('That file is not a valid image',1);im.src=r.result};r.onerror=()=>toast('Could not read the file',1);r.readAsDataURL(f)}
function pickHtml(t){const f=t.files[0];if(!f)return;if(!/\.html?$/i.test(f.name)&&f.type!=='text/html')return toast('Choose an .html or .htm file',1);if(f.size>2e6)return toast('HTML file must be under 2 MB',1);
const r=new FileReader();r.onload=()=>{draft=String(r.result);P.src.file=f.name.slice(0,80);P.src.type='file';save();render();toast('Loaded. Press Save to keep it.')};r.onerror=()=>toast('Could not read the file',1);r.readAsText(f)}
const A={
new(){const n=prompt('Project name');if(n&&n.trim()){P=blank(n.trim().slice(0,60));P.app.name=P.name;S=0;view='wiz';save();enter();render()}},
imp(){$('#imp').click()},expsel(){exportP([...sel])},exp(b){exportP([b.dataset.id])},
open(b){P=DB.list[b.dataset.id];S=Math.min(P.step,STEPS.length-1);view='wiz';showAll=false;enter();render()},
ren(b){const p=DB.list[b.dataset.id],n=prompt('New name',p.name);if(n&&n.trim()){p.name=n.trim().slice(0,60);save();render()}},
dup(b){const c=JSON.parse(JSON.stringify(DB.list[b.dataset.id]));c.id=uid();c.name+=' (copy)';c.created=c.updated=Date.now();DB.list[c.id]=c;save();render()},
del(b){const p=DB.list[b.dataset.id];if(confirm(`Delete "${p.name}"? This cannot be undone.`)){delete DB.list[p.id];sel.delete(p.id);save();render()}},
home(){flush();save();P=null;view='home';render()},prev(){go(S-1)},next(){S===STEPS.length-1?A.home():go(S+1)},edit(b){go(+b.dataset.step)},
pre(b){upd(b.dataset.k,b.dataset.c)},tpl(){const k=$('#tp').value;draft=TPL[k].h;P.src.type='template';P.src.file=TPL[k].n;render()},
sv(){const v=vh(draft);if(v.err)return toast(v.err,1);P.src.html=draft;save();render();toast('HTML saved');pop()},cn(){draft=P.src.html;render()},
rm(b){set(P,b.dataset.k,{name:'',data:''});save();render()},
val(){const e=errs(SUMI);bstat=e.length?'Not ready: '+e.map(([i,m])=>`step ${i+1} - ${m}`).join('; '):'Configuration is valid. Download config.json and upload it to GitHub. No build was run here.';$('#bs').innerHTML=bs();if(!e.length)pop()},
cfg(){const e=errs(SUMI);if(e.length)return toast('Fix configuration errors first',1);dl('config.json',toV2(P));toast('Build config exported');pop()}};
document.addEventListener('click',e=>{if(e.target.closest('button'))buzz();const b=e.target.closest('[data-a]');if(b)A[b.dataset.a]?.(b)});
document.addEventListener('input',e=>{const t=e.target;
if(t.id==='q'){q=t.value;$('#list').innerHTML=lst();return}
if(t.id==='ed'){draft=t.value;clearTimeout(tm);tm=setTimeout(()=>{$('#hv').innerHTML=hv(vh(draft));$('#fr').srcdoc=draft},300);return}
if(t.dataset.k&&t.type!=='checkbox'&&t.tagName!=='SELECT')upd(t.dataset.k,t.value)});
document.addEventListener('change',e=>{const t=e.target,k=t.dataset.k;
if(t.dataset.sel){t.checked?sel.add(t.dataset.sel):sel.delete(t.dataset.sel);return render()}
if(t.dataset.perm){const s=new Set(P.perms);t.checked?s.add(t.dataset.perm):s.delete(t.dataset.perm);P.perms=[...s];return save()}
if(t.dataset.img)return pickImg(t);if(t.id==='hf')return pickHtml(t);
if(k&&(t.type==='checkbox'||t.tagName==='SELECT')){upd(k,t.type==='checkbox'?t.checked:t.value);if(k==='src.type'&&t.value==='blank')draft=TPL.blank.h;render()}});
document.addEventListener('toggle',e=>{if(e.target.id==='adv')advOpen=e.target.open},true);
$('#imp').onchange=e=>{if(e.target.files[0])doImport(e.target.files[0]);e.target.value=''};
