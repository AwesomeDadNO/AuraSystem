const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const linker=require('../app/src/main/assets/video-linker.js');
const app=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/app.js'),'utf8');
const index=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/index.html'),'utf8');
const song={origin:'ultramax',id:'device-track-1',providerId:'123456',isrc:'USRC17607839',title:'Sample Song',artist:'Sample Artist'};
const manifest={auraAmp:{musicVideoLookupPath:'video/track/{id}.json'}};
assert.equal(linker.providerRequestUrl('https://eclipse.ultramax.vip/c/123456',manifest,song),'https://eclipse.ultramax.vip/c/123456/video/track/123456.json');
assert.equal(linker.providerRequestUrl('https://eclipse.ultramax.vip/c/123456',{auraAmp:{musicVideoLookupPath:'../evil/{id}'}},song),null);
assert.deepEqual(linker.providerResult({verified:true,trackId:'123456',video:{provider:'youtube',id:'dQw4w9WgXcQ'}},song),{kind:'youtube',id:'dQw4w9WgXcQ'});
assert.equal(linker.providerResult({verified:true,trackId:'other',video:{provider:'youtube',id:'dQw4w9WgXcQ'}},song),null);
assert.equal(linker.providerResult({trackId:'123456',video:{provider:'youtube',id:'dQw4w9WgXcQ'}},song),null);
assert.equal(linker.providerResult({verified:true,trackId:'123456',video:{provider:'youtube',id:'invalid-id'}},song),null);
assert.deepEqual(linker.normalizeVideo({url:'https://www.youtube.com/watch?v=dQw4w9WgXcQ'}),{kind:'youtube',id:'dQw4w9WgXcQ'});
assert.equal(linker.getYouTubeId('https://fake-youtube.com/watch?v=dQw4w9WgXcQ'),null);
assert.match(index,/<script src="video-linker.js"><\/script>/);
assert.ok(app.includes('void findMusicVideo(s)'),'Lookup should automatically follow playback');
assert.ok(app.includes('musicVideoResolver.resolve(track,'),'Resolver must be connected');
assert.ok(app.includes('isrc:String(raw.isrc'),'ISRC must be kept from music providers');
const store=new Map();
const storage={getItem:k=>store.get(k)||null,setItem:(k,v)=>store.set(k,v)};
let requests=0;
const provider=linker.create({storage,request:async()=>{requests++;return {verified:true,trackId:'123456',video:{provider:'youtube',id:'dQw4w9WgXcQ'}};}});
(async()=>{
 const result=await provider.resolve(song,{providerBase:'https://eclipse.ultramax.vip/c/123456',providerManifest:manifest,useMusicBrainz:false});
 assert.deepEqual(result,{kind:'youtube',id:'dQw4w9WgXcQ'});
 assert.deepEqual(await provider.resolve(song,{providerBase:'https://eclipse.ultramax.vip/c/123456',providerManifest:manifest,useMusicBrainz:false}),result);
 assert.equal(requests,1,'Persistent cache should avoid repeated searches');
 assert.deepEqual(linker.create({storage,request:async()=>{throw Error('cached resolver should not run')}}).peek(song),result);
 // Real recording -> music video relationship; deliberately avoid title-only search.
 const song2={...song,id:'device-track-2',providerId:'track-2'};
 const rel='ce3de655-7451-44d1-9224-87eb948c205d';
 const videoRecording='87654321-1234-4321-1234-123456789abc';
 const responses=[
   {recordings:[{id:'12345678-1234-4321-1234-123456789abc',title:'Sample Song','artist-credit':[{artist:{name:'Sample Artist'}}],relations:[{'type-id':rel,recording:{id:videoRecording}}]}]},
   {relations:[{type:'free streaming',attributes:['video'],url:{resource:'https://youtu.be/dQw4w9WgXcQ'}}]}
 ];
 const urls=[];
 const lookup=linker.create({storage:{getItem:()=>null,setItem:()=>{}},request:async url=>{urls.push(url);return responses.shift()}});
 assert.deepEqual(await lookup.resolve(song2,{useMusicBrainz:true}),{kind:'youtube',id:'dQw4w9WgXcQ'});
 assert.ok(urls[0].includes('/isrc/USRC17607839?'));
 assert.ok(urls[1].includes('/recording/'+videoRecording+'?'));
 console.log('PASS verified provider IDs, ISRC-linked MusicBrainz video, cache, and embedded video wiring');
})().catch(e=>{console.error(e);process.exitCode=1});
