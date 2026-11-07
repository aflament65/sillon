/* Passage au dossier Music : tout finit dans Music/Artiste/Album/, rien n'est perdu ni compté deux fois.
   Faux téléphone à identifiants Android réels (« primary:Music/… »), ancien dossier Music/Sillon inclus dans Music. */
const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim()+'/playwright');
let fails=0;const ok=(c,m)=>{console.log((c?'  ok  ':'  ÉCHEC ')+m);if(!c)fails++};
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:360,height:900}});
await p.route(/fonts|deezer|itunes|musicbrainz|discogs|acoustid|api\.github|coverartarchive/,r=>r.abort());
const errs=[];p.on('pageerror',e=>errs.push(e.message));
await p.addInitScript(()=>{localStorage.setItem('sillon.autoenrich','0');
  const D=new Map();window.__D=D;window.__LOG=[];let ROOT='primary:Music/Sillon';
  const enc=encodeURIComponent,tree=()=>'content://ext/tree/'+enc(ROOT),uri=id=>tree()+'/document/'+enc(id),idOf=u=>decodeURIComponent(u.split('/document/')[1]||'');
  const b64d=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0)),b64e=u=>{let s='';for(let i=0;i<u.length;i+=8192)s+=String.fromCharCode(...u.subarray(i,i+8192));return btoa(s)};
  const parent=id=>id.slice(0,id.lastIndexOf('/'));
  window.Capacitor={isNativePlatform:()=>true,Plugins:{Sillon:{keepAwake:async()=>{},addListener:async()=>({remove:async()=>{}}),playerSetQueue:async()=>{},playerSetRepeat:async()=>{},
    getFolder:async()=>({uri:tree(),name:ROOT.split('/').pop()}),
    pickFolder:async()=>{ROOT='primary:Music';__LOG.push('pick');return {uri:tree(),name:'Music'}},
    writeFile:async({path,data,append})=>{const id=ROOT+'/'+path,d=b64d(data),e=D.get(id);if(append&&e){const u=new Uint8Array(e.length+d.length);u.set(e);u.set(d,e.length);D.set(id,u)}else D.set(id,d);return {uri:uri(id)}},
    listDir:async({path})=>({exists:true,files:[...D.entries()].filter(([k])=>parent(k)===ROOT+'/'+path).map(([k,v])=>({name:k.split('/').pop(),uri:uri(k),size:v.length}))}),
    readFile:async({uri:u,offset,length})=>{const v=D.get(idOf(u));if(!v)throw new Error('introuvable '+idOf(u));return {data:b64e(v.subarray(offset,offset+length)),eof:offset+length>=v.length}},
    deleteFile:async({uri:u})=>({deleted:D.delete(idOf(u))}),
    deletePath:async({path,onlyIfEmpty})=>{__LOG.push('deletePath:'+path);const pre=ROOT+'/'+path+'/',inside=[...D.keys()].filter(k=>k.startsWith(pre));if(onlyIfEmpty&&inside.some(k=>!/folder\.jpg$/.test(k)))return {deleted:false};inside.forEach(k=>D.delete(k));return {deleted:true}},
    renameFile:async({uri:u,name})=>{const id=idOf(u),n=parent(id)+'/'+name;if(D.has(n))throw new Error('existe');D.set(n,D.get(id));D.delete(id);return {uri:uri(n)}},
    pruneEmpty:async({uri:u})=>{__LOG.push('prune:'+decodeURIComponent(u.split('/tree/')[1]))},
    listWatchFolders:async()=>({folders:[]}),removeWatchFolder:async()=>{},
    scanMp3:async()=>{const pre=ROOT+'/';const files=[...D.entries()].filter(([k])=>k.startsWith(pre)&&/\.(mp3|flac|wav)$/i.test(k)).map(([k,v])=>{const rel=k.slice(pre.length);return {dir:rel.includes('/')?rel.slice(0,rel.lastIndexOf('/')):'',name:k.split('/').pop(),uri:uri(k),docId:k,size:v.length}});
      return {files,roots:[{name:ROOT.split('/').pop(),files:files.length}]}}}}};
});
await p.goto('http://localhost:8765/index.html');await p.waitForTimeout(500);if(errs.length)console.log('erreurs au chargement',errs);
await p.evaluate(async()=>{CATALOG.deezer=async()=>({data:[]});CATALOG.itunes=async()=>[];window.mb=async()=>{throw new Error('hors ligne')};window.fetchImage=async()=>null;window.thumbOf=async()=>'data:,';
  /* déjà dans Sillon (ancien dossier Music/Sillon) */
  const w=await writeNative('Wolfmother/Wolfmother (2005)/03 - Woman.mp3',new Blob([id3For({artist:'Wolfmother',album:'Wolfmother',year:'2005'},{title:'Woman',artist:'Wolfmother',no:3,total:3},null),new Uint8Array(4000)]));
  const l=await writeNative('Divers/Sillon 2026-11-01/Inconnu.mp3',new Blob([new Uint8Array(3000)]));
  await libPut({key:albumKey('Wolfmother','Wolfmother'),artist:'Wolfmother',album:'Wolfmother',year:'2005',dir:'Wolfmother/Wolfmother (2005)',cover:new Uint8Array([255,216,255]),coverMime:'image/jpeg',sources:{vinyle:1},tracks:[{id:'w',no:3,title:'Woman',artist:'Wolfmother',dur:200,dir:'Wolfmother/Wolfmother (2005)',file:'03 - Woman.mp3',uri:w,source:'vinyle',coverDone:true}]});
  await libPut({key:UNSORTED_KEY,artist:'',album:'Morceaux non classés',unsorted:true,dir:'Divers',sources:{tel:1},tracks:[{id:'u',no:0,title:'Inconnu',artist:'',dur:100,dir:'Divers/Sillon 2026-11-01',file:'Inconnu.mp3',uri:l,source:'tel',tried:Date.now()}]});
  /* ta collection, directement dans Music */
  __D.set('primary:Music/Pink Floyd/The Wall/1-01 In the Flesh.mp3',new Uint8Array([...id3For({artist:'Pink Floyd',album:'The Wall',year:'1979'},{title:'In the Flesh?',artist:'Pink Floyd',no:1,total:26},null),...new Uint8Array(5000)]));
  await libLoad()});
ok(!(await p.isHidden('#musicBanner')),'bannière « Choisir le dossier Music » visible tant que Sillon range ailleurs');
await p.click('#musicBtn');
await p.waitForFunction(()=>__LOG.includes('pick')&&!ENRUN,null,{timeout:30000});await p.waitForTimeout(500);
const st=await p.evaluate(()=>({files:[...__D.keys()].sort(),lib:LIB.map(r=>r.album+':'+(r.tracks||[]).map(t=>docIdOf(t.uri)).join(',')),log:__LOG,banner:$('#musicBanner').hidden,state:$('#enrState').textContent}));
const F=st.files;
ok(F.includes('primary:Music/Wolfmother/Wolfmother/03 - Woman.mp3'),'album déplacé dans Music/Wolfmother/Wolfmother/ (sans l\'année)');
ok(F.includes('primary:Music/Pink Floyd/The Wall/01 - In the Flesh.mp3'),'ta collection rangée sur place : Music/Pink Floyd/The Wall/');
ok(F.includes('primary:Music/Divers/Inconnu.mp3'),'morceau non classé ramené dans Music/Divers en attendant');
ok(!F.some(f=>f.startsWith('primary:Music/Sillon/')),'plus rien dans l\'ancien dossier Music/Sillon : '+F.filter(f=>f.includes('/Sillon/')));
ok(F.filter(f=>f.endsWith('.mp3')).length===3,'3 MP3, ni perdu ni en double : '+F.join(' | '));
ok(st.lib.every(x=>!x.includes('/Sillon/')),'discothèque à jour : '+st.lib.join(' ; '));
ok(st.log.some(x=>x==='prune:primary:Music/Sillon')&&st.log.includes('deletePath:Sillon'),'ancien dossier vidé puis retiré : '+st.log.join(', '));
ok(st.banner,'bannière masquée une fois Music choisi');
ok((await p.evaluate(()=>$('#folderName').textContent))==='Music','dossier des albums : Music');
await p.evaluate(()=>autoEnrich(true));
ok((await p.evaluate(()=>[...__D.keys()].filter(k=>k.endsWith('.mp3')).length))===3,'2e passage : rien ne bouge, aucun doublon');
ok(!errs.length,'aucune erreur JavaScript '+errs.join(' | '));
await b.close();console.log(fails?`\n${fails} ÉCHEC(S)`:'\nTout est bon');process.exit(fails?1:0)})();
