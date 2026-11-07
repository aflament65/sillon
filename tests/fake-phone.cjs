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
    /* dossiers surveillés : les chemins « EXT:<nom>/… » sont hors du dossier de sauvegarde */
    listWatchFolders:async()=>({folders:(window.__WATCH||[]).map(n=>({uri:'w:'+n,name:n}))}),
    addWatchFolder:async()=>{const n=window.__NEXTWATCH;(window.__WATCH=window.__WATCH||[]).push(n);return {uri:'w:'+n,name:n}},
    removeWatchFolder:async({uri})=>{window.__WATCH=(window.__WATCH||[]).filter(n=>'w:'+n!==uri)},
    readFile:async({uri,offset,length})=>{const e=byUri(uri);if(!e)throw new Error('introuvable');if(e[1].bad)throw new Error('illisible');const s=e[1].b.subarray(offset,offset+length);return {data:b64e(s),eof:offset+length>=e[1].b.length}},
    scanMp3:async()=>{const W=window.__WATCH||[],files=[];
      for(const [k,v] of F.entries()){if(!/\.(mp3|flac|m4a|aac|ogg|oga|opus|wav|wma)$/i.test(k))continue;
        const ext=/^EXT:([^/]+)\/(.*)$/.exec(k);if(ext&&!W.includes(ext[1]))continue;
        const path=ext?'@'+ext[1]+'/'+ext[2]:k;files.push({dir:path.slice(0,path.lastIndexOf('/')),name:path.slice(path.lastIndexOf('/')+1),uri:v.uri,size:v.b.length,docId:k})}
      return {files,roots:[{name:'Sillon',files:files.filter(f=>f.dir[0]!=='@').length},...W.map(n=>({name:n,files:files.filter(f=>f.dir.startsWith('@'+n)).length}))]}}}}};
};
