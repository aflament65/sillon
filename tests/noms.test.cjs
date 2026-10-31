/* Artiste / titre : lecture des titres de vidéos et choix dans le catalogue (catalogue simulé) */
const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim()+'/playwright');
let fails=0;const ok=(c,m)=>{console.log((c?'  ok  ':'  ÉCHEC ')+m);if(!c)fails++};
(async()=>{const b=await chromium.launch();const p=await b.newPage();
await p.route(/fonts|deezer|itunes|musicbrainz|discogs|acoustid|api\.github|coverartarchive/,r=>r.abort());
const errs=[];p.on('pageerror',e=>errs.push(e.message));
await p.goto(process.env.SILLON_URL||'http://localhost:8765/index.html');await p.waitForTimeout(300);
await p.evaluate(()=>{const CAT=[
  ['Gesaffelstein','Pursuit','Aleph',2013,277],['Daft Punk','Get Lucky (feat. Pharrell Williams)','Random Access Memories',2013,369],['Daft Punk','Get Lucky','Best of 2013 Hits',2013,248],
  ['Wolfmother','Woman','Wolfmother',2005,176],['Justice','Pusher (feat. Gesaffelstein)','VI, Original Score',2026,409],['Muse','Hysteria','Absolution',2003,227],['Hysteria','Muse','Tribute EP',2010,200]];
  const words=q=>norm(q).split(' ');
  CATALOG.deezer=async u=>{if(u.startsWith('album/'))return {release_date:'2013-01-01',nb_tracks:10,tracks:{data:[{id:-1},{id:+u.slice(6)}]}};
    const q=decodeURIComponent(u.split('q=')[1].split('&')[0]);const w=words(q);
    return {data:CAT.map((c,i)=>({c,i})).filter(({c})=>words(c[0]+' '+c[1]).some(x=>w.includes(x))).map(({c,i})=>({id:i,title:c[1],title_short:c[1].replace(/ \(feat.*\)/,''),duration:c[4],artist:{name:c[0]},album:{id:i,title:c[2]}}))}};
  CATALOG.itunes=async()=>[]});
const cases=[
  ['Gesaffelstein - Pursuit (Official Video)','Gesaffelstein','Gesaffelstein','Pursuit'],
  ['Pursuit - Gesaffelstein','Gesaffelstein','Gesaffelstein','Pursuit'],
  ['Pursuit - Gesaffelstein','Techno Mix TV','Gesaffelstein','Pursuit'],
  ['Pursuit','Gesaffelstein - Topic','Gesaffelstein','Pursuit'],
  ['Daft Punk ft. Pharrell - Get Lucky (Official Audio)','DaftPunkVEVO','Daft Punk','Get Lucky'],
  ['Woman','Wolfmother','Wolfmother','Woman'],
  ['Wolfmother','Woman','Wolfmother','Woman'],
  ['Hysteria | Live at Wembley','Muse','Muse','Hysteria'],
  ['Pusher (feat. Gesaffelstein) [from GTAVI]','Justice','Justice','Pusher'],
];
for(const [t,a,ea,et] of cases){const r=await p.evaluate(([t,a])=>{const c=cleanNp(t,a);return resolveTrack(c.artist,c.title,{alt:c.alt,chan:c.chan})},[t,a]);
  ok(r&&r.artist===ea&&r.title===et,`« ${t} » (chaîne ${a}) → ${r?r.artist+' / '+r.title+' / '+r.album:'rien'}`)}
const r=await p.evaluate(()=>resolveTrack('Daft Punk','Get Lucky',{dur:369}));ok(r?.album==='Random Access Memories','album d\'origine préféré à la compilation');
ok(!(await p.evaluate(()=>resolveTrack('Groupe inconnu','Chanson inconnue'))),'morceau absent du catalogue : rien d\'inventé');
ok(!errs.length,'aucune erreur JavaScript '+errs.join(' | '));
await b.close();console.log(fails?`\n${fails} ÉCHEC(S)`:'\nTout est bon');process.exit(fails?1:0)})();
