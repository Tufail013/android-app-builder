'use strict';
/* ===== helpers ===== */
const $=s=>document.querySelector(s),esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const uid=()=>'p'+Date.now().toString(36)+Math.random().toString(36).slice(2,6);
const get=(o,p)=>p.split('.').reduce((a,k)=>a?.[k],o),set=(o,p,v)=>{const a=p.split('.'),l=a.pop();a.reduce((x,k)=>x[k],o)[l]=v};
const HEXR=/^#[0-9a-f]{6}$/i,C=c=>HEXR.test(c)?c:'#000000',IMGR=/^data:image\/(png|jpeg|webp|gif);base64,/;
let tt;function toast(m,bad){const t=$('#toast');t.textContent=m;t.className='show'+(bad?' bad':'');clearTimeout(tt);tt=setTimeout(()=>t.className='',3500)}
