/* Faux téléphone pour les tests : stockage en mémoire qui imite le module natif de Sillon */
module.exports=()=>{
  const F=new Map();let n=0;window.__FS=F;const b64d=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
  const b64e=u=>{let s='';for(let i=0;i<u.length;i+=8192)s+=String.fromCharCode(...u.subarray(i,i+8192));return btoa(s)};
  const byUri=u=>[...F.entries()].find(([k,v])=>v.uri===u);
  window.__L={};const emit=(ev,d)=>(window.__L[ev]||[]).forEach(f=>f(d));window.__CALLS=[];
  /* faux moteur audio Android : « album » = morceaux séparés de 2 s de silence (durées dans __ALB) */
  const AUDIO={
    pickAudio:async()=>window.__PICK,
    audioInfo:async({uri})=>{const e=byUri(uri);return e?.[1].info||{duration:120,title:'',artist:''}},
    decodeAlbum:async({uri,id})=>{__CALLS.push(['decode',id]);const L=window.__ALB||[60,60,60],env=[];for(const d of L){for(let i=0;i<d*20;i++)env.push(-20+Math.sin(i)*3);for(let i=0;i<40;i++)env.push(-120)}
      for(const p of [.25,.5,1])emit('audioProgress',{op:'decode',track:0,p});const f=new Float32Array(env);return {rate:48000,frames:env.length*2400,duration:env.length*.05,peak:16000,env:b64e(new Uint8Array(f.buffer))}},
    readAlbumPcm:async o=>{__CALLS.push(['read',o.id,o.rate,o.mono]);return {data:b64e(new Uint8Array(o.mono?11025*2*Math.max(1,Math.round(o.end-o.start)):4800*4)),rate:o.mono?11025:o.rate,channels:o.mono?1:2}},
    encodeAlbum:async({id,rate,tracks,gain})=>{__CALLS.push(['encode',id,rate,gain,tracks.map(t=>t.path+'@'+t.start.toFixed(1)+'-'+t.end.toFixed(1))]);
      const out=[];tracks.forEach((t,i)=>{emit('audioProgress',{op:'encode',track:i,p:(i+1)/tracks.length});if(F.has(t.path)){out.push({error:'existe déjà'});return}const uri='fs://'+(++n);F.set(t.path,{uri,b:new Uint8Array([...b64d(t.tag),...new Uint8Array(1000)])});out.push({uri,size:1000})});return {tracks:out}},
    freeAlbum:async({id})=>{__CALLS.push(['free',id])},
    convertAudio:async({uri,path,tag})=>{const e=byUri(uri);if(!e)throw new Error('introuvable');if(/\.wma$/i.test(e[0])||e[1].b.length<50)throw new Error('format non pris en charge');if(F.has(path))throw new Error('existe déjà');emit('audioProgress',{op:'convert',track:0,p:.5});
      const u='fs://'+(++n);F.set(path,{uri:u,b:new Uint8Array([...b64d(tag),...new Uint8Array(2000)])});__CALLS.push(['convert',path]);return {uri:u,size:2000,duration:e[1].info?.duration||100}}};
  /* window.__NOAUDIO : téléphone sans moteur audio Android (conversion faite dans la page) */
  window.Capacitor={isNativePlatform:()=>true,Plugins:{Sillon:{...(window.__NOAUDIO?{}:AUDIO),getFolder:async()=>({uri:'root',name:'Music'}),keepAwake:async()=>{},addListener:async(ev,f)=>{(window.__L[ev]=window.__L[ev]||[]).push(f);return {remove:async()=>{}}},
    playerSetQueue:async()=>{},playerSetRepeat:async()=>{},playerStop:async()=>{},
    writeFile:async({path,data,append})=>{const d=b64d(data);const e=F.get(path);if(append&&e){const u=new Uint8Array(e.b.length+d.length);u.set(e.b);u.set(d,e.b.length);e.b=u;return {uri:e.uri}}F.delete(path);const uri='fs://'+(++n);F.set(path,{uri,b:d});return {uri}},
    listDir:async({path})=>({exists:true,files:[...F.entries()].filter(([k])=>k.slice(0,k.lastIndexOf('/'))===path).map(([k,v])=>({name:k.slice(k.lastIndexOf('/')+1),uri:v.uri,size:v.b.length}))}),
    readFile:async({uri,offset,length})=>{const e=byUri(uri);if(!e)throw new Error('introuvable');const s=e[1].b.subarray(offset,offset+length);return {data:b64e(s),eof:offset+length>=e[1].b.length}},
    deleteFile:async({uri})=>{const e=byUri(uri);if(e)F.delete(e[0]);return {deleted:!!e}},
    deletePath:async({path,onlyIfEmpty})=>{const inside=[...F.keys()].filter(k=>k.startsWith(path+'/'));if(onlyIfEmpty&&inside.some(k=>!/folder\.jpg$/.test(k)))return {deleted:false};inside.forEach(k=>F.delete(k));return {deleted:true}},
    /* copie faite « par Android » : étiquette ID3 remplacée si demandée, jamais d'écrasement */
    rewriteFile:async({uri,path,tag,strip})=>{const e=byUri(uri);if(!e)throw new Error('introuvable');if(F.has(path))throw new Error('existe déjà');
      if(window.__FAILREWRITE&&--window.__FAILREWRITE===0)throw new Error('carte pleine (test)');
      let b=e[1].b;if(strip&&b[0]===73&&b[1]===68&&b[2]===51)b=b.subarray(10+((b[6]&127)<<21|(b[7]&127)<<14|(b[8]&127)<<7|(b[9]&127))+((b[5]&16)?10:0));
      const t=tag?b64d(tag):new Uint8Array(0),o=new Uint8Array(t.length+b.length);o.set(t);o.set(b,t.length);window.__RW=(window.__RW||0)+1;const u='fs://'+(++n);F.set(path,{uri:u,b:o});return {uri:u}},
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
