/* ===== theme, haptics, confetti ===== */
let THEME='light';try{THEME=localStorage.getItem('hab.theme')||'light'}catch(e){}
const mq=matchMedia('(prefers-color-scheme:dark)');
function applyTheme(){const e=THEME==='system'?(mq.matches?'dark':'light'):THEME;document.documentElement.dataset.theme=e;const m=document.querySelector('meta[name=theme-color]');if(m)m.content=e==='dark'?'#1f232b':'#e6e9ef'}
mq.addEventListener('change',()=>{if(THEME==='system')applyTheme()});
function setTheme(v){THEME=v;try{localStorage.setItem('hab.theme',v)}catch(e){}applyTheme();render()}
A.theme=b=>setTheme(b.dataset.v);A.cyc=()=>setTheme({light:'dark',dark:'system',system:'light'}[THEME]);
const themeBar=()=>`<div class="seg" role="group" aria-label="Theme">${[['light','Light'],['dark','Dark'],['system','System']].map(([v,l])=>`<button class="${THEME===v?'on':''}" data-a="theme" data-v="${v}" aria-pressed="${THEME===v}">${l}</button>`).join('')}</div>`;
const buzz=()=>{try{navigator.vibrate&&navigator.vibrate(8)}catch(e){}};
document.addEventListener('change',e=>{if(e.target.type==='checkbox')buzz()});
function pop(){if(matchMedia('(prefers-reduced-motion:reduce)').matches)return;const cs=['#22d3ee','#8b5cf6','#f59e0b','#22c55e','#ef4444'];for(let i=0;i<26;i++){const d=document.createElement('i'),a=-Math.random()*Math.PI,r=90+Math.random()*130;d.className='cf';d.style.cssText=`background:${cs[i%5]};--x:${Math.cos(a)*r}px;--y:${Math.sin(a)*r}px;--r:${Math.random()*540}deg`;document.body.appendChild(d);setTimeout(()=>d.remove(),1000)}}
