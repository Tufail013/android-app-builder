/* ===== data model (UI independent) ===== */
const blank=name=>({id:uid(),name,created:Date.now(),updated:Date.now(),step:0,
app:{name:'',pkg:'',vc:'1',vn:'1.0.0',auto:true,min:'24',target:'34',max:'',scheme:''},
src:{type:'blank',file:'',html:TPL.blank.h},
disp:{orient:'auto',status:true,action:false,keepOn:false,cutout:'default',desktop:false,wm:false,primary:'#22d3ee',loadBg:'#000000',statusColor:'#000000',navColor:'#000000',transp:false,tint:'auto'},
perms:['INTERNET','ACCESS_NETWORK_STATE'],icon:{name:'',data:''},
splash:{on:true,bg:'#000000',style:'ring',loader:'#22d3ee',ring:'#333333',img:{name:'',data:''},iconSize:'96',radius:'20',loaderSize:'40',gap:'24',dur:'2000',thick:'4',fade:'300'},
wv:{refresh:true,noNet:true,indicator:true,backNav:true,exitConfirm:true,js:true,dom:true,file:false,content:false,uFile:false,fileUrl:false,zoom:false,wide:true,overview:true,geo:false,cache:'default',mixed:'never'},
feat:{notify:false,reminders:false,vibrate:false,toast:false,share:false,files:false,tts:false,info:false,biometric:false,secure:false,fullscreen:false,anim:'fade'},ads:{on:false},out:{format:'apk',ksName:'',ksAlias:'',ksPath:''}});
function merge(b,i){if(!i||typeof i!=='object')return b;for(const k in b){if(!(k in i))continue;const x=b[k],y=i[k];
if(Array.isArray(x)){if(Array.isArray(y))b[k]=y.filter(s=>typeof s==='string').slice(0,50)}
else if(x&&typeof x==='object')merge(x,y);
else if(typeof x==='string'&&typeof y==='number')b[k]=String(y);
else if(typeof x===typeof y&&(typeof y!=='string'||y.length<3e6))b[k]=y}return b}
