/* Enregistrement plein écran : attente du son, chrono, morceau en cours, fin de face, face suivante */
const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim()+'/playwright');
const fs=require('fs');
let fails=0;const ok=(c,m)=>{console.log((c?'  ok  ':'  ÉCHEC ')+m);if(!c)fails++};
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:360,height:780},hasTouch:true});
await p.route(/fonts\.g|deezer|itunes|musicbrainz|discogs|acoustid|api\.github|coverartarchive/,r=>r.abort());
await p.route(/lamejs.*lame\.min\.js|lib\/lame\.min\.js/,r=>r.fulfill({body:fs.readFileSync(process.env.LAME_JS),contentType:'text/javascript'}));
const errs=[];p.on('pageerror',e=>errs.push(e.message));
await p.goto('http://localhost:8765/index.html');await p.waitForTimeout(400);
/* entrée audio simulée : un flux muet ; les blocs sont injectés à la main dans onBlock */
await p.evaluate(()=>{
  navigator.mediaDevices.getUserMedia=async()=>{const c=new AudioContext();return c.createMediaStreamDestination().stream};
  window.__VIB=[];navigator.vibrate=x=>{__VIB.push(x);return true};
  window.feed=(sec,db)=>{const N=4096,a=Math.pow(10,db/20);let n=Math.round(sec*SR/N);for(let k=0;k<n&&(S.recording);k++){const L=new Float32Array(N);for(let i=0;i<N;i++)L[i]=a*Math.sin((k*N+i)*0.05);onBlock(L,L)}};
  setMode('vinyle');
  loadAlbum({artist:'Les Tests',album:'Face cachée',year:'1979',tracks:[
    {number:'A1',title:'Premier',length:35000},{number:'A2',title:'Deuxième',length:35000},{number:'A3',title:'Troisième',length:35000},
    {number:'B1',title:'Quatrième',length:40000},{number:'B2',title:'Cinquième',length:40000}]});
});
const stopProc=()=>p.evaluate(()=>{proc.onaudioprocess=null});
console.log('1. Départ quand le son arrive');
await p.check('#autostart');await p.click('#recBtn');await p.waitForTimeout(200);await stopProc();
ok(await p.isVisible('#rf'),'l\'écran d\'enregistrement s\'ouvre en plein écran');
ok(/attente du son/i.test(await p.textContent('#rfState')),'état : « En attente du son · pose le diamant »');
ok(/^Face A — Les Tests — Face cachée$/.test(await p.textContent('#rfFrom')),'en-tête : artiste, album, face ('+await p.textContent('#rfFrom')+')');
await p.evaluate(()=>feed(3,-80));
ok(await p.evaluate(()=>S.frames===0&&REC.armed),'silence : rien n\'est enregistré');
await p.evaluate(()=>{feed(1,-30)});
const f0=await p.evaluate(()=>({armed:REC.armed,fr:S.frames/SR}));
ok(!f0.armed&&f0.fr>1&&f0.fr<3,'le son arrive : enregistrement lancé, avec 1,5 s d\'avance ('+f0.fr.toFixed(2)+' s)');
ok(await p.evaluate(()=>$('#rf').classList.contains('on')&&/Enregistrement/.test($('#rfState').textContent)),'état : « ● Enregistrement »');
console.log('2. Chrono et morceau en cours');
await p.evaluate(()=>{feed(34,-14);feed(2,-80);feed(13,-14)});
ok(/^0:5\d$/.test(await p.textContent('#rfClock')),'grand chrono : '+await p.textContent('#rfClock'));
ok((await p.textContent('#rfTrack'))==='Deuxième','morceau estimé : « Deuxième »');
ok(/Morceau 2 sur 3/.test(await p.textContent('#rfTrackSub')),'« '+await p.textContent('#rfTrackSub')+' »');
const pw=await p.evaluate(()=>parseFloat($('#rfProg').style.width));ok(pw>40&&pw<60,'barre de la face : '+pw.toFixed(0)+' %');
ok(await p.evaluate(()=>$('#rfMeter').className===$('#meter').className&&$('#rfMeter i').style.width===$('#meter i').style.width&&$('#rfLvlTxt').textContent.length>3),'vumètre recopié en grand');
console.log('3. Réduire, rouvrir, écran noir');
await p.click('#rfMin');ok(await p.isHidden('#rf')&&await p.isVisible('#rfOpen'),'réduit : bouton « Plein écran » à côté d\'Arrêter');
await p.click('#rfOpen');ok(await p.isVisible('#rf'),'rouvert');
await p.click('#rfDim');ok(await p.isVisible('#black')&&/Enregistrement en cours/.test(await p.textContent('#black')),'écran noir pendant l\'enregistrement');
await p.click('#black');ok(await p.isHidden('#black'),'un toucher rallume');
console.log('4. Fin de face');
/* reste de la face : blancs de 2 s entre morceaux, puis le silence final */
await p.evaluate(()=>{feed(22,-14);feed(2,-80);feed(35,-14);feed(30,-80)});
const e=await p.evaluate(()=>({rec:S.recording,vib:__VIB.slice(),done:$('#rf').classList.contains('done'),t:$('#rfEndTitle').textContent,x:$('#rfEndTxt').textContent,go:$('#rfGo').textContent,goMain:$('#rfGo').className,b:S.bounds.length}));
ok(!e.rec,'arrêt automatique au silence de fin de face');
ok(e.vib.some(v=>Array.isArray(v)),'le téléphone vibre');
ok(e.done&&/Fin de face A détectée/i.test(e.t),'bilan : « '+e.t+' »');
ok(/3 morceaux repérés/.test(e.x)&&/tombent sur un silence/.test(e.x)&&/face B/i.test(e.x),'« '+e.x+' »');
ok(/Valider et enregistrer la Face B/.test(e.go)&&e.goMain==='main','bouton principal : « '+e.go+' »');
await p.screenshot({path:'/tmp/claude-0/s/rec-fin.png'});
console.log('5. Enchaîner la face B');
await p.click('#rfGo');await p.waitForTimeout(600);await stopProc();
const g=await p.evaluate(()=>({side:S.sides[S.side].name,rec:S.recording,armed:REC.armed,jobs:JOBS.length,rf:!$('#rf').hidden,from:$('#rfFrom').textContent,hint:$('#recHint').textContent}));
ok(g.jobs===1,'face A envoyée en conversion');
ok(g.side==='Face B'&&g.rec&&g.armed,'face B en attente du son, sans rien toucher');
ok(g.rf&&/face B/i.test(g.from),'plein écran rouvert sur la face B');
ok(/Retourne le disque/.test(g.hint),'« '+g.hint+' »');
await p.screenshot({path:'/tmp/claude-0/s/rec-attente.png'});
console.log('6. Coupure douteuse, annulation');
await p.click('#rfStop');ok(await p.evaluate(()=>!S.recording&&$('#rf').hidden&&/Attente annulée/.test($('#recHint').textContent)),'arrêter pendant l\'attente annule, sans rien créer');
await p.uncheck('#autostart');await p.click('#recBtn');await p.waitForTimeout(200);await stopProc();
ok(await p.evaluate(()=>S.recording&&!REC.armed),'sans l\'option, l\'enregistrement démarre tout de suite');
/* morceaux enchaînés sans blanc : la coupure ne tombe pas sur un silence */
await p.evaluate(()=>{feed(80,-14);feed(16,-80)});
const u=await p.evaluate(()=>({x:$('#rfEndTxt').textContent,go:$('#rfGo').textContent,check:$('#rfCheck').className}));
ok(/hors silence/.test(u.x)&&/quand même/.test(u.go)&&u.check==='main','coupure douteuse : « Vérifier les coupures » passe en premier ('+u.x+')');
await p.click('#rfCheck');ok(await p.evaluate(()=>$('#rf').hidden&&!$('#s3').hidden),'« Vérifier » ouvre l\'éditeur de coupures');
const W=await p.evaluate(()=>{$('#rf').hidden=false;const W=document.documentElement.clientWidth;const r=[...document.querySelectorAll('#rf *')].filter(e=>{const r=e.getBoundingClientRect();return r.width&&r.right>W+1}).map(e=>e.id||e.className).slice(0,4);$('#rf').hidden=true;return r});
ok(!W.length,'rien ne déborde à 360 px '+W);
ok(!errs.length,'aucune erreur JavaScript '+errs.join(' | '));
await b.close();console.log(fails?`\n${fails} ÉCHEC(S)`:'\nTout est bon');process.exit(fails?1:0)})();
