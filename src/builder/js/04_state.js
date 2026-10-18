/* ===== state + persistence ===== */
const KEY='hab.projects.v1';let DB={cur:null,list:{}},P=null,S=0,draft=null,showAll=false,q='',sel=new Set(),view='home',advOpen=false,bstat='',tm;
function load(){try{const d=JSON.parse(localStorage.getItem(KEY)||'null');if(d&&d.list){for(const k in d.list)d.list[k]=merge(blank(String(d.list[k].name||'Project')),d.list[k]);DB=d}}catch(e){toast('Saved data was unreadable; starting fresh',1)}}
function save(){try{if(P){P.updated=Date.now();DB.list[P.id]=P}DB.cur=P?P.id:null;localStorage.setItem(KEY,JSON.stringify(DB))}catch(e){toast('Storage full or blocked. Export a JSON backup.',1)}}
