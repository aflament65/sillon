/* Enrichissement automatique (faux stockage + faux catalogue) */
const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim()+'/playwright');
let fails=0;const ok=(c,m)=>{console.log((c?'  ok  ':'  ÉCHEC ')+m);if(!c)fails++};
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:400,height:900}});
await p.route(/fonts|deezer|itunes|musicbrainz|discogs|acoustid|api\.github|coverartarchive/,r=>r.abort());
const errs=[];p.on('pageerror',e=>errs.push(e.message));
await p.addInitScript(()=>{localStorage.setItem('sillon.autoenrich','0');
  const F=new Map();let n=0;window.__FS=F;const b64d=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
  const b64e=u=>{let s='';for(let i=0;i<u.length;i+=8192)s+=String.fromCharCode(...u.subarray(i,i+8192));return btoa(s)};
  const byUri=u=>[...F.entries()].find(([k,v])=>v.uri===u);
  window.Capacitor={isNativePlatform:()=>true,Plugins:{Sillon:{getFolder:async()=>({uri:'root',name:'Music'}),keepAwake:async()=>{},addListener:async()=>({remove:async()=>{}}),
    writeFile:async({path,data,append})=>{const d=b64d(data);const e=F.get(path);if(append&&e){const u=new Uint8Array(e.b.length+d.length);u.set(e.b);u.set(d,e.b.length);e.b=u;return {uri:e.uri}}F.delete(path);const uri='fs://'+(++n);F.set(path,{uri,b:d});return {uri}},
    listDir:async({path})=>({exists:true,files:[...F.entries()].filter(([k])=>k.slice(0,k.lastIndexOf('/'))===path).map(([k,v])=>({name:k.slice(k.lastIndexOf('/')+1),uri:v.uri,size:v.b.length}))}),
    readFile:async({uri,offset,length})=>{const e=byUri(uri);const s=e[1].b.subarray(offset,offset+length);return {data:b64e(s),eof:offset+length>=e[1].b.length}},
    deleteFile:async({uri})=>{const e=byUri(uri);if(e)F.delete(e[0]);return {deleted:!!e}},
    deletePath:async({path,onlyIfEmpty})=>{const inside=[...F.keys()].filter(k=>k.startsWith(path+'/'));if(onlyIfEmpty&&inside.some(k=>!/folder\.jpg$/.test(k)))return {deleted:false};inside.forEach(k=>F.delete(k));return {deleted:true}},
    renameFile:async({uri,name})=>{const e=byUri(uri);const np=e[0].slice(0,e[0].lastIndexOf('/')+1)+name;F.delete(e[0]);F.set(np,e[1]);return {uri:e[1].uri}},
    scanMp3:async()=>({files:[...F.entries()].filter(([k])=>/\.mp3$/i.test(k)).map(([k,v])=>({dir:k.slice(0,k.lastIndexOf('/')),name:k.slice(k.lastIndexOf('/')+1),uri:v.uri,size:v.b.length}))})}}}});
await p.goto('http://localhost:8765/index.html');await p.waitForTimeout(300);if(errs.length)console.log('erreurs au chargement',errs);
await p.evaluate(async()=>{
  const ALB={10:{title:'Aleph',artist:'Gesaffelstein',year:'2013',genre:'Électro',label:'Parlophone',tracks:['Out of Line','Pursuit','Nameless']},
    20:{title:'Wolfmother',artist:'Wolfmother',year:'2005',genre:'Rock',label:'Modular',tracks:['Dimension','White Unicorn','Woman',"Joker & The Thief"]}};
  CATALOG.deezer=async u=>{if(u.startsWith('album/')){const a=ALB[+u.slice(6)];return {release_date:a.year+'-01-01',cover_xl:'img',label:a.label,genres:{data:[{name:a.genre}]},nb_tracks:a.tracks.length,tracks:{data:a.tracks.map((t,i)=>({id:+u.slice(6)*100+i,title:t,duration:200}))}}}
    const q=norm(decodeURIComponent(u.split('q=')[1].split('&')[0]));
    if(u.startsWith('search/album'))return {data:Object.entries(ALB).filter(([id,a])=>q.includes(norm(a.artist))).map(([id,a])=>({id:+id,title:a.title,artist:{name:a.artist}}))};
    const out=[];for(const [id,a] of Object.entries(ALB))a.tracks.forEach((t,i)=>{if(norm(t).split(' ').some(w=>w.length>2&&q.includes(w)))out.push({id:+id*100+i,title:t,duration:200,artist:{name:a.artist},album:{id:+id,title:a.title}})});return {data:out}};
  CATALOG.itunes=async()=>[];window.mb=async()=>{throw new Error('hors ligne')};window.fetchImage=async()=>({bytes:new Uint8Array([255,216,255,1,2,3]),mime:'image/jpeg'});window.thumbOf=async()=>'data:,';
  FOLDER={uri:'root',name:'Music'};
  const mk=async(path,tag)=>writeNative(path,new Blob([tag||new Uint8Array(0),new Uint8Array(4000)],{type:'audio/mpeg'}));
  /* un morceau non classé (titre de vidéo inversé) */
  const u1=await mk('Divers/Sillon 2026-10-31/01 - Pursuit - Gesaffelstein.mp3');
  await libPut({key:UNSORTED_KEY,artist:'',album:'Morceaux non classés',unsorted:true,dir:'Divers',sources:{tel:1},tracks:[{id:'L1',no:0,title:'Pursuit - Gesaffelstein',artist:'Techno Mix TV',dur:200,dir:'Divers/Sillon 2026-10-31',file:'01 - Pursuit - Gesaffelstein.mp3',uri:u1,source:'tel'}]});
  /* deux fois le même album (édition deluxe), sans pochette ni liste, titres approximatifs */
  const w1=await mk('Wolfmother/Wolfmother/03 - woman.mp3'),w2=await mk('Wolfmother/Wolfmother (Deluxe Edition)/04 - Joker and the Thief.mp3');
  await libPut({key:albumKey('Wolfmother','Wolfmother'),artist:'Wolfmother',album:'Wolfmother',dir:'Wolfmother/Wolfmother',sources:{vinyle:1},tracks:[{id:'W1',no:3,title:'woman',artist:'Wolfmother',dur:200,dir:'Wolfmother/Wolfmother',file:'03 - woman.mp3',uri:w1,source:'vinyle'}]});
  await libPut({key:albumKey('Wolfmother','Wolfmother (Deluxe Edition)'),artist:'Wolfmother',album:'Wolfmother (Deluxe Edition)',dir:'Wolfmother/Wolfmother (Deluxe Edition)',sources:{tel:1},tracks:[{id:'W2',no:4,title:'Joker and the Thief',artist:'Wolfmother',dur:200,dir:'Wolfmother/Wolfmother (Deluxe Edition)',file:'04 - Joker and the Thief.mp3',uri:w2,source:'tel'}]});
  /* un MP3 déposé à la main dans le dossier, avec étiquettes */
  await mk('Ma collection/Nameless.mp3',id3For({artist:'Gesaffelstein',album:'Aleph',year:''},{title:'Nameless',artist:'Gesaffelstein',no:3,total:3},null));
  await libLoad()});
await p.evaluate(()=>autoEnrich(true));
const st=await p.evaluate(()=>({albums:LIB.map(r=>({album:r.album,artist:r.artist,year:r.year,genre:r.genre,cover:!!r.cover,tl:(r.tracklist||[]).length,tracks:(r.tracks||[]).map(t=>t.no+':'+t.title+':'+t.dir+'/'+t.file)})),files:[...__FS.keys()].sort(),state:$('#enrState').textContent}));
const A=n=>st.albums.find(a=>a.album===n);
ok(!st.albums.some(a=>a.album==='Morceaux non classés'),'plus de morceaux non classés');
ok(A('Aleph')?.tracks.some(t=>t.startsWith('2:Pursuit:Gesaffelstein/Aleph/02 - Pursuit.mp3')),'« Pursuit - Gesaffelstein » identifié et rangé dans Aleph, piste 2');
ok(A('Aleph')?.tracks.some(t=>t.includes('Nameless:Ma collection/Nameless.mp3')),'MP3 déposé à la main ajouté, fichier laissé en place');
ok(!st.albums.some(a=>/Deluxe/.test(a.album))&&A('Wolfmother')?.tracks.length===2,'les deux éditions réunies');
const w=A('Wolfmother');ok(w&&w.cover&&w.year==='2005'&&w.genre==='Rock'&&w.tl===4,'pochette, année, genre et liste ajoutés : '+JSON.stringify(w&&{y:w.year,g:w.genre,tl:w.tl,c:w.cover}));
ok(w?.tracks.includes('3:Woman:Wolfmother/Wolfmother/03 - Woman.mp3'),'« woman » → « Woman », fichier renommé : '+w?.tracks.join(' | '));
ok(w?.tracks.some(t=>t.startsWith('4:Joker & The Thief:Wolfmother/Wolfmother/04 - Joker & The Thief.mp3')),'titre officiel « Joker & The Thief »');
ok(st.files.filter(f=>f.endsWith('.mp3')).length===4,'4 MP3, aucun perdu ni en double : '+st.files.join(' | '));
ok(st.files.includes('Wolfmother/Wolfmother/folder.jpg'),'pochette déposée dans le dossier');
ok(/morceau rangé|morceaux rangés/.test(st.state)&&/pochette/.test(st.state),'bilan affiché : '+st.state);
const again=await p.evaluate(async()=>{await autoEnrich();return $('#enrState').textContent});ok(/rien de nouveau/.test(again),'2e passage : rien à refaire');
ok(!errs.length,'aucune erreur JavaScript '+errs.join(' | '));
await b.close();console.log(fails?`\n${fails} ÉCHEC(S)`:'\nTout est bon');process.exit(fails?1:0)})();
