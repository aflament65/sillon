/* Android Auto et widget : résumé de la discothèque envoyé au téléphone, file lancée hors de l'appli reprise par la page */
const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim()+'/playwright');
let fails=0;const ok=(c,m)=>{console.log((c?'  ok  ':'  ÉCHEC ')+m);if(!c)fails++};
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:360,height:780}});
await p.route(/fonts\.g|deezer|itunes|musicbrainz|discogs|acoustid|api\.github|coverartarchive|lrclib/,r=>r.abort());
const errs=[];p.on('pageerror',e=>errs.push(e.message));
await p.addInitScript(`localStorage.setItem('sillon.autoenrich','0');(${require('./fake-phone.cjs').toString()})();window.__SNAP=[];window.__PQ=[];
  Object.assign(window.Capacitor.Plugins.Sillon,{libSnapshot:async o=>{__SNAP.push(o.json)},playerSetQueue:async o=>{__PQ.push(o)},playerJump:async()=>{},playerToggle:async()=>{},playerNext:async()=>{},playerPrev:async()=>{},playerSeek:async()=>{},playerAudio:async()=>{}});`);
await p.goto('http://localhost:8765/index.html');await p.waitForTimeout(400);
await p.evaluate(async()=>{
  await libPut({key:albumKey('Muse','Absolution'),artist:'Muse',album:'Absolution',year:'2003',dir:'Muse/Absolution',created:1,tracks:[
    {id:'a1',no:1,title:'Apocalypse Please',artist:'Muse',uri:'content://a1',dir:'Muse/Absolution',file:'1.mp3',loud:-9.5},{id:'a3',no:3,title:'Time Is Running Out',artist:'Muse',uri:'content://a3',dir:'Muse/Absolution',file:'3.mp3'},{id:'ax',no:2,title:'Sans fichier',artist:'Muse'}]});
  await libPut({key:'_unsorted',artist:'',album:'Morceaux non classés',unsorted:true,created:2,tracks:[{id:'u1',title:'Inconnu',artist:'Quelqu\'un',uri:'content://u1',dir:'Divers',file:'u.mp3'}]});
  await libLoad();PLS.push({id:'p1',name:'Soirée',items:[{id:'a3',ti:'Time Is Running Out',ar:'Muse',al:'Absolution'}]});plsSave()});
await p.waitForTimeout(3500);
console.log('1. Résumé de la discothèque');
const snap=JSON.parse(await p.evaluate(()=>__SNAP.at(-1)||'{}'));
const mu=(snap.albums||[]).find(a=>a.al==='Absolution');
ok(snap.v===1&&mu&&mu.ar==='Muse'&&mu.y==='2003','album envoyé avec artiste et année');
ok(mu&&mu.tr.map(t=>t.i).join()==='a1,a3'&&mu.tr[0].u==='content://a1'&&mu.tr[0].l===-9.5&&!('l' in mu.tr[1]),'seuls les morceaux avec un fichier, dans l\'ordre, avec leur volume');
ok((snap.albums||[]).some(a=>a.al==='Morceaux non classés'&&a.tr[0].a==='Quelqu\'un'),'morceaux non classés regroupés, avec leur artiste');
ok(JSON.stringify(snap.pls)==='[{"i":"p1","n":"Soirée","it":["a3"]}]','listes de lecture envoyées');
const n0=await p.evaluate(()=>__SNAP.length);await p.evaluate(()=>libLoad());await p.waitForTimeout(3500);
ok(await p.evaluate(n0=>__SNAP.length===n0,n0),'rien de changé : pas de réécriture');
await p.evaluate(()=>{PLS[0].name='Soirée d\'été';plsSave()});await p.waitForTimeout(3500);
ok(/Soirée d'été/.test(await p.evaluate(()=>__SNAP.at(-1))),'liste renommée : résumé mis à jour');
console.log('2. File lancée depuis la voiture ou le widget');
await p.evaluate(()=>__L.playerQueue.forEach(f=>f({items:[{id:'a3',uri:'content://a3',title:'Time Is Running Out',artist:'Muse',album:'Absolution',dir:'Muse/Absolution'},{id:'zz',uri:'content://zz',title:'Hors discothèque',artist:'X',album:'',dir:''}],index:0})));
await p.evaluate(()=>__L.playerState.forEach(f=>f({index:0,pos:5000,dur:200000,playing:true,size:2})));await p.waitForTimeout(150);
const q=await p.evaluate(()=>({t:PL.q.map(x=>x.title),k:PL.q[0].key===albumKey('Muse','Absolution'),pos:PL.pos,cur:curItem()?.title,mini:$('#miniTitle')?.textContent||document.body.innerText.includes('Time Is Running Out')}));
ok(q.t.join('|')==='Time Is Running Out|Hors discothèque'&&q.k&&q.cur==='Time Is Running Out','la page reprend la file (avec pochette et album quand le morceau est connu)');
ok(!!q.mini,'le mini-lecteur affiche le morceau');
await p.evaluate(()=>{const r=LIB.find(x=>x.album==='Absolution');playItems(r.tracks.filter(t=>t.uri).map(t=>qItem(r,t)),0,false)});await p.waitForTimeout(100);
ok(await p.evaluate(()=>__PQ.at(-1).items.map(x=>x.id).join()==='a1,a3'),'les morceaux partent au lecteur avec leur identifiant (pour la reprise)');
ok(!errs.length,'aucune erreur JavaScript '+errs.join(' | '));
await b.close();console.log(fails?`\n${fails} ÉCHEC(S)`:'\nTout est bon');process.exit(fails?1:0)})();
