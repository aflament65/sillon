/* Scénarios de la discothèque sur un faux stockage de téléphone.
   Lancer : python3 -m http.server 8765 (dans le dépôt) puis node tests/albums.test.cjs */
const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim()+'/playwright');
const URL_=process.env.SILLON_URL||'http://localhost:8765/index.html';
let fails=0;const ok=(c,m)=>{console.log((c?'  ok  ':'  ÉCHEC ')+m);if(!c)fails++};
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:400,height:900}});
await p.route(/fonts|deezer|itunes|musicbrainz|discogs|acoustid|api\.github|coverartarchive/,r=>r.abort());
const errs=[];p.on('pageerror',e=>errs.push(e.message));
await p.addInitScript(()=>{
  const F=new Map();let n=0;const W={failAt:0,count:0};window.__FS=F;window.__W=W;
  const b64e=u=>{let s='';for(let i=0;i<u.length;i+=8192)s+=String.fromCharCode(...u.subarray(i,i+8192));return btoa(s)};
  const b64d=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
  const byUri=u=>[...F.entries()].find(([k,v])=>v.uri===u);
  const S={getFolder:async()=>({uri:'root',name:'Music'}),keepAwake:async()=>{},addListener:async()=>({remove:async()=>{}}),
    playerSetQueue:async()=>{},playerStop:async()=>{},playerSetRepeat:async()=>{},
    writeFile:async({path,data,append})=>{if(!append&&W.failAt&&++W.count===W.failAt)throw new Error('carte pleine (test)');
      const d=b64d(data);const e=F.get(path);if(append&&e){const u=new Uint8Array(e.b.length+d.length);u.set(e.b);u.set(d,e.b.length);e.b=u;return {uri:e.uri}}
      F.delete(path);const uri='fs://'+(++n);F.set(path,{uri,b:d});return {uri}},
    listDir:async({path})=>{const files=[...F.entries()].filter(([k])=>k.slice(0,k.lastIndexOf('/'))===path).map(([k,v])=>({name:k.slice(k.lastIndexOf('/')+1),uri:v.uri,size:v.b.length}));
      return {exists:files.length>0,files}},
    readFile:async({uri,offset,length})=>{const e=byUri(uri);if(!e)throw new Error('introuvable');const s=e[1].b.subarray(offset,offset+length);return {data:b64e(s),eof:offset+length>=e[1].b.length}},
    deleteFile:async({uri})=>{const e=byUri(uri);if(e)F.delete(e[0]);return {deleted:!!e}},
    deletePath:async({path,onlyIfEmpty})=>{const inside=[...F.keys()].filter(k=>k.startsWith(path+'/'));
      const left=inside.filter(k=>!/\/folder\.jpg$/i.test(k));if(onlyIfEmpty&&left.length)return {deleted:false,left:left.length};inside.forEach(k=>F.delete(k));return {deleted:true}},
    renameFile:async({uri,name})=>{const e=byUri(uri);if(!e)throw new Error('introuvable');const np=e[0].slice(0,e[0].lastIndexOf('/')+1)+name;if(F.has(np))throw new Error('existe');F.delete(e[0]);F.set(np,e[1]);return {uri:e[1].uri}},
    scanMp3:async()=>({files:[...F.entries()].filter(([k])=>/\.mp3$/i.test(k)).map(([k,v])=>({dir:k.slice(0,k.lastIndexOf('/')),name:k.slice(k.lastIndexOf('/')+1),uri:v.uri,size:v.b.length}))}),
  };
  window.Capacitor={isNativePlatform:()=>true,Plugins:{Sillon:S}};
});
await p.goto(URL_);await p.waitForTimeout(300);
const st=()=>p.evaluate(async()=>{await libLoad();return {albums:LIB.map(r=>({key:r.key,album:r.album,dir:r.dir,tracks:(r.tracks||[]).map(t=>({no:t.no,title:t.title,path:t.dir+'/'+t.file,uri:t.uri})).sort((a,b)=>a.no-b.no)})),
  files:[...__FS.keys()].sort(),uris:[...__FS.values()].map(v=>v.uri)}});
const consistent=s=>s.albums.every(a=>a.tracks.every(t=>s.uris.includes(t.uri)&&s.files.includes(t.path)));
// Album de départ : 3 morceaux dans Wolfmother/Rock'n'Roll Baby (2019)
await p.evaluate(async()=>{const r={key:albumKey('Wolfmother',"Rock'n'Roll Baby"),artist:'Wolfmother',album:"Rock'n'Roll Baby",year:'2019',dir:"Wolfmother/Rock'n'Roll Baby (2019)",sources:{vinyle:1},tracklist:[],tracks:[]};
  for(const [no,title] of [[1,'Higher'],[2,'Survivor'],[3,'Feelings']]){const file=fileNameOf({no,title});
    const tag=id3For({artist:r.artist,album:r.album,year:r.year},{title,artist:r.artist,no,total:3},null);
    const uri=await writeNative(r.dir+'/'+file,new Blob([tag,new Uint8Array(5000).fill(no)],{type:'audio/mpeg'}));
    r.tracks.push({id:'t'+no,no,title,artist:r.artist,dur:200,dir:r.dir,file,uri,source:'vinyle'})}
  await libPut(r);await libLoad()});
const openA=async t=>{await p.evaluate(()=>{LIBVIEW.key=null;showView(true)});await p.locator('.libItem',{hasText:t}).first().click();await p.waitForTimeout(150)};
const formFor=async title=>{const row=p.locator('.atrk',{hasText:title}).first();await row.locator('button',{hasText:/Modifier|Classer/}).click();return row.locator('.cls')};
const done=()=>p.waitForFunction(()=>!document.querySelector('.cls'),null,{timeout:8000}).catch(()=>{});

console.log('1. Changer le numéro d\'un morceau (cas signalé)');
await openA("Rock'n'Roll Baby");let f=await formFor('Feelings');
ok((await f.locator('.dest').innerText()).includes('reste dans cet album'),'le formulaire annonce que le morceau reste dans l\'album');
await f.locator('.cn').fill('1');await f.locator('.ok').click();await done();
let s=await st();ok(s.albums.length===1,'toujours un seul album');ok(s.albums[0].tracks.length===3,'les 3 morceaux sont toujours là');
ok(s.files.filter(x=>x.endsWith('.mp3')).length===3,'3 fichiers MP3, aucun perdu ni en double');ok(s.files.includes("Wolfmother/Rock'n'Roll Baby (2019)/01 - Feelings.mp3"),'fichier renommé « 01 - Feelings »');ok(consistent(s),'discothèque et fichiers concordent');

console.log('2. Déplacer vers un album qui n\'existe pas');
f=await formFor('Survivor');await f.locator('.cb').fill('Essai');await f.locator('.cb').dispatchEvent('input');
ok((await f.locator('.ok').innerText()).includes('Créer'),'bouton explicite « Créer l\'album et déplacer »');await f.locator('.ok').click();await done();
s=await st();ok(s.albums.length===2&&s.albums.find(a=>a.album==="Rock'n'Roll Baby").tracks.length===2,'l\'album d\'origine garde ses 2 autres morceaux');ok(consistent(s),'cohérent');

console.log('3. Le remettre dans son album d\'origine');
await openA('Essai');f=await formFor('Survivor');await f.locator('.cb').fill("Rock'n'Roll Baby");await f.locator('.cb').dispatchEvent('input');
ok((await f.locator('.dest').innerText()).includes('album existant'),'annonce « album existant »');await f.locator('.ok').click();await done();
s=await st();ok(s.albums.length===1&&s.albums[0].tracks.length===3,'3 morceaux réunis, album « Essai » disparu');ok(!s.files.some(x=>x.startsWith('Wolfmother/Essai')),'dossier Essai supprimé (vide)');ok(consistent(s),'cohérent');

console.log('4. Renommer l\'album');
await p.evaluate(()=>editAlbum(albumKey('Wolfmother',"Rock'n'Roll Baby"),{artist:'Wolfmother',album:'Rock n Roll Baby',year:'2019'}));
s=await st();ok(s.albums.length===1&&s.albums[0].tracks.length===3,'3 morceaux');ok(s.files.every(x=>x.startsWith('Wolfmother/Rock n Roll Baby (2019)/')),'fichiers dans le nouveau dossier, ancien dossier vidé');ok(consistent(s),'cohérent');

console.log('5. Réunir deux albums en renommant l\'un comme l\'autre');
await p.evaluate(async()=>{const r={key:albumKey('Wolfmother','Autre'),artist:'Wolfmother',album:'Autre',year:'',dir:'Wolfmother/Autre',sources:{tel:1},tracks:[]};
  const uri=await writeNative('Wolfmother/Autre/04 - Pretty Peggy.mp3',new Blob([new Uint8Array(3000)],{type:'audio/mpeg'}));r.tracks.push({id:'t4',no:4,title:'Pretty Peggy',artist:'Wolfmother',dur:100,dir:r.dir,file:'04 - Pretty Peggy.mp3',uri,source:'tel'});await libPut(r);await libLoad();
  await editAlbum(r.key,{artist:'Wolfmother',album:'Rock n Roll Baby',year:''})});
s=await st();ok(s.albums.length===1&&s.albums[0].tracks.length===4,'un seul album de 4 morceaux');ok(consistent(s),'cohérent');

console.log('6. Changer l\'ordre');
await openA('Rock n Roll Baby');await p.click('#albOrderBtn');await p.locator('.ordRow').last().locator('button[aria-label=Monter]').click();
await p.locator('#albOrder button',{hasText:'Enregistrer l\'ordre'}).click();await p.waitForFunction(()=>document.querySelector('#albOrder').hidden,null,{timeout:8000});
s=await st();ok(s.albums[0].tracks.map(t=>t.no).join()==='1,2,3,4','numéros 1 à 4');ok(s.albums[0].tracks[2].title==='Pretty Peggy','le dernier est monté d\'un cran');ok(consistent(s),'cohérent');
ok(s.files.filter(x=>x.endsWith('.mp3')).length===4,'toujours 4 fichiers');

console.log('7. Panne au milieu d\'une opération : tout revient comme avant');
const avant=await st();await p.evaluate(()=>{__W.count=0;__W.failAt=2});
const e7=await p.evaluate(()=>editAlbum(albumKey('Wolfmother','Rock n Roll Baby'),{artist:'Wolfmother',album:'Nouveau nom',year:''}).then(()=>'',e=>e.message));
await p.evaluate(()=>{__W.failAt=0});s=await st();ok(!!e7,'erreur signalée : '+e7);ok(JSON.stringify(s)===JSON.stringify(avant),'discothèque et fichiers identiques à avant');

console.log('8. Nom de fichier déjà pris : rien n\'est écrasé');
await p.evaluate(async()=>{await writeNative('Compil/Best of/01 - Higher.mp3',new Blob([new Uint8Array(10).fill(9)],{type:'audio/mpeg'}));
  await libPut({key:albumKey('Divers artistes','Best of'),artist:'Divers artistes',album:'Best of',dir:'Compil/Best of',sources:{autre:1},tracks:[]});await libLoad();
  const r=LIB.find(r=>r.album==='Rock n Roll Baby');await moveTrack(r.key,r.tracks.find(t=>t.title==='Higher').id,{artist:'Divers artistes',album:'Best of',no:1,title:'Higher'});
  const b=libGet(albumKey('Divers artistes','Best of'));await moveTrack(b.key,b.tracks[0].id,{artist:'Wolfmother',album:'Rock n Roll Baby',no:1,title:'Higher'})});
s=await st();ok(s.files.includes('Compil/Best of/01 - Higher.mp3')&&(await p.evaluate(()=>[...__FS.entries()].find(([k])=>k==='Compil/Best of/01 - Higher.mp3')[1].b[0]))===9,'le fichier étranger du même nom est intact');
ok(s.albums.find(a=>a.album==='Rock n Roll Baby').tracks.length===4,'le morceau est revenu, 4 morceaux');ok(consistent(s),'cohérent');

console.log('8b. Changer la pochette (fichiers réécrits sur place)');
await p.evaluate(async()=>{const c=document.createElement('canvas');c.width=c.height=50;c.getContext('2d').fillRect(0,0,50,50);const bl=await new Promise(r=>c.toBlob(r,'image/png'));
  await setAlbumCover(LIB.find(r=>r.album==='Rock n Roll Baby'),new File([bl],'p.png',{type:'image/png'}))});
s=await st();const rb=s.albums.find(a=>a.album==='Rock n Roll Baby');ok(rb.tracks.every(t=>!/\(maj\)|\(\d\)/.test(t.path)),'noms de fichiers inchangés');
ok(s.files.filter(x=>x.startsWith('Wolfmother/')&&x.endsWith('.mp3')).length===4,'toujours 4 fichiers');ok(consistent(s),'cohérent');
ok(await p.evaluate(()=>{const t=LIB.find(r=>r.album==='Rock n Roll Baby').tracks[0];const f=[...__FS.values()].find(v=>v.uri===t.uri);return !!readId3(f.b).cover}),'la pochette est dans les étiquettes');

console.log('9. Discothèque perdue : réparation depuis le dossier');
await p.evaluate(async()=>{await libDel(albumKey('Wolfmother','Rock n Roll Baby'));await libLoad();FOLDER={uri:'root',name:'Music'}});
await p.evaluate(()=>repairLib());s=await st();const al=s.albums.find(a=>a.album==='Rock n Roll Baby');
ok(al&&al.tracks.length===4,'les 4 morceaux sont retrouvés dans leur album (d\'après les étiquettes)');ok(consistent(s),'cohérent');
await p.evaluate(()=>repairLib());ok((await st()).albums.find(a=>a.album==='Rock n Roll Baby').tracks.length===4,'une 2e réparation ne crée pas de doublons');

console.log('10. Supprimer un album dont le dossier contient un fichier étranger');
await p.evaluate(()=>writeNative('Wolfmother/Rock n Roll Baby (2019)/bonus.mp3',new Blob([new Uint8Array(10)],{type:'audio/mpeg'})));
await p.evaluate(async()=>{await libLoad();const r=LIB.find(r=>r.album==='Rock n Roll Baby');r.tracks=r.tracks.filter(t=>!/bonus/.test(t.file));await libPut(r);await libLoad();await deleteAlbum(LIB.find(r=>r.album==='Rock n Roll Baby'))});
s=await st();ok(!s.albums.some(a=>a.album==='Rock n Roll Baby'),'album retiré');ok(s.files.includes('Wolfmother/Rock n Roll Baby (2019)/bonus.mp3'),'le fichier qui n\'appartient pas à l\'album est conservé');
ok(s.files.filter(x=>x.startsWith('Wolfmother/')&&x.endsWith('.mp3')).length===1,'seuls les MP3 de l\'album ont été effacés');
ok(!errs.length,'aucune erreur JavaScript '+errs.join(' | '));
await b.close();console.log(fails?`\n${fails} ÉCHEC(S)`:'\nTout est bon');process.exit(fails?1:0)})();
