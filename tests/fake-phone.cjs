/* Faux téléphone pour les tests : stockage en mémoire qui imite le module natif de Sillon */
module.exports=()=>{
  const F=new Map();let n=0;window.__FS=F;const b64d=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
  const b64e=u=>{let s='';for(let i=0;i<u.length;i+=8192)s+=String.fromCharCode(...u.subarray(i,i+8192));return btoa(s)};
  const byUri=u=>[...F.entries()].find(([k,v])=>v.uri===u);
  window.Capacitor={isNativePlatform:()=>true,Plugins:{Sillon:{getFolder:async()=>({uri:'root',name:'Music'}),keepAwake:async()=>{},addListener:async()=>({remove:async()=>{}}),
    playerSetQueue:async()=>{},playerSetRepeat:async()=>{},playerStop:async()=>{},
    writeFile:async({path,data,append})=>{const d=b64d(data);const e=F.get(path);if(append&&e){const u=new Uint8Array(e.b.length+d.length);u.set(e.b);u.set(d,e.b.length);e.b=u;return {uri:e.uri}}F.delete(path);const uri='fs://'+(++n);F.set(path,{uri,b:d});return {uri}},
    listDir:async({path})=>({exists:true,files:[...F.entries()].filter(([k])=>k.slice(0,k.lastIndexOf('/'))===path).map(([k,v])=>({name:k.slice(k.lastIndexOf('/')+1),uri:v.uri,size:v.b.length}))}),
    readFile:async({uri,offset,length})=>{const e=byUri(uri);if(!e)throw new Error('introuvable');const s=e[1].b.subarray(offset,offset+length);return {data:b64e(s),eof:offset+length>=e[1].b.length}},
    deleteFile:async({uri})=>{const e=byUri(uri);if(e)F.delete(e[0]);return {deleted:!!e}},
    deletePath:async({path,onlyIfEmpty})=>{const inside=[...F.keys()].filter(k=>k.startsWith(path+'/'));if(onlyIfEmpty&&inside.some(k=>!/folder\.jpg$/.test(k)))return {deleted:false};inside.forEach(k=>F.delete(k));return {deleted:true}},
    renameFile:async({uri,name})=>{const e=byUri(uri);const np=e[0].slice(0,e[0].lastIndexOf('/')+1)+name;if(F.has(np))throw new Error('existe');F.delete(e[0]);F.set(np,e[1]);return {uri:e[1].uri}},
    scanMp3:async()=>({files:[...F.entries()].filter(([k])=>/\.(mp3|flac|m4a|aac|ogg|oga|opus|wav|wma)$/i.test(k)).map(([k,v])=>({dir:k.slice(0,k.lastIndexOf('/')),name:k.slice(k.lastIndexOf('/')+1),uri:v.uri,size:v.b.length}))})}}};
};
