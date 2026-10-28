'use strict';
const $ = id => document.getElementById(id);
function newId() {
  const bytes = new Uint8Array(16);
  if (globalThis.crypto?.getRandomValues) globalThis.crypto.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
}
const tokenKey = 'hugo_projector_v2_token';
let token = localStorage.getItem(tokenKey) || '';
const transferredToken = new URLSearchParams(location.hash.slice(1)).get('hugo_token');
if (transferredToken && /^[a-f0-9]{64}$/.test(transferredToken)) {
  token = transferredToken;
  localStorage.setItem(tokenKey, token);
  history.replaceState(null, '', location.pathname + location.search);
}
let socket = null;
let role = 'viewer';
let editorSession = '';
let scenes = [];
let snapshot = null;
let reconnectDelay = 1000;
let canvasQueue = [];
let canvasSending = false;
const feedback = (message, error = false, pair = false) => {
  const element = $(pair ? 'pairFeedback' : 'feedback');
  element.textContent = message;
  element.classList.toggle('error', error);
};
async function api(path, method = 'GET', value) {
  const options = { method, headers: token ? { Authorization: 'Bearer ' + token } : {} };
  if (method !== 'GET' && editorSession) options.headers['X-Hugo-Session'] = editorSession;
  if (value !== undefined) { options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(value); }
  const response = await fetch(path, options);
  const text = await response.text();
  let result; try { result = JSON.parse(text); } catch { result = { error: text || 'Request failed' }; }
  if (!response.ok) throw new Error(result.error || `HTTP ${response.status}`);
  return result;
}
function envelope(command, payload = {}) {
  return { version: 2, command_id: newId(), expiresAt: Date.now() + 20000, command, payload };
}
async function send(command, payload = {}) {
  if (role !== 'editor') throw new Error('This phone is a viewer. Release the active editor first.');
  await drainCanvasQueue();
  const ack = await api('/api/command', 'POST', envelope(command, payload));
  if (!ack.ok) throw new Error(ack.detail || 'Projector rejected command');
  await refresh();
  return ack;
}
async function drainCanvasQueue() {
  flushActions();
  const started = Date.now();
  while (canvasQueue.length || canvasSending) {
    if (role !== 'editor' || Date.now() - started > 30000) throw new Error('Canvas is still syncing; reconnect and retry');
    await new Promise(resolve => setTimeout(resolve, 40));
  }
}
function setConnection(online, detail) {
  $('connection').textContent = online ? '● ' + detail : '● Offline';
  $('connection').classList.toggle('online', online);
}
async function refresh() {
  const state = await api('/api/state');
  scenes = state.scenes || [];
  if(state.status?.game) updateGame(state.status.game);
  snapshot = state.status || {};
  updateFxState(snapshot.fx);
  syncMedia(snapshot);
  $('fxNav').hidden = snapshot.fxEnabled === false;
  $('deviceStatus').textContent = `${snapshot.model || 'Projector'} · Android ${snapshot.android || '?'} · ${snapshot.sceneType || 'home'} · ${snapshot.display || ''}${snapshot.screenVisible === false ? ' · Press Home on projector' : ''}`;
  $('volume').value = snapshot.volumePercent ?? 50;
  $('volumeValue').textContent = `${$('volume').value}%`;
  renderScenes();
  if (snapshot.canvas) applyCanvasSnapshot(snapshot.canvas);
  return state;
}
function connectSocket() {
  if (!token || socket && [WebSocket.OPEN, WebSocket.CONNECTING].includes(socket.readyState)) return;
  socket = new WebSocket(`ws://${location.host}/ws`);
  socket.onopen = () => socket.send(JSON.stringify({ type: 'auth', token }));
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if(message.status?.fx) updateFxState(message.status.fx);
    if(message.status)syncMedia(message.status);
    if (message.type === 'game_state') { updateGame(message.game); } else if (message.type === 'game_error') { gamePlayer=0; neutralGame(); feedback(message.detail,true); } else if (message.type === 'welcome') {
      role = message.role;
      editorSession = role === 'editor' ? message.sessionId : '';
      $('editorRole').textContent = role === 'editor' ? 'Editing' : 'Viewer';
      setConnection(true, 'Projector connected');
      reconnectDelay = 1000;
      if(gamePlayer) socket.send(JSON.stringify({type:'game_join',player:gamePlayer}));
      if (message.status?.canvas) applyCanvasSnapshot(message.status.canvas);
      canvasSending = false;
      sendNextCanvasBatch();
    } else if (message.type === 'role') {
      role = message.role;
      editorSession = role === 'editor' ? message.sessionId : '';
      $('editorRole').textContent = role === 'editor' ? 'Editing' : 'Viewer';
      feedback(role === 'editor' ? 'You have control' : 'Control released');
    } else if (message.type === 'heartbeat') {
      setConnection(true, 'Projector connected');
      sendNextCanvasBatch();
    } else if (message.type === 'snapshot') {
      if (message.status?.canvas) applyCanvasSnapshot(message.status.canvas);
      sendNextCanvasBatch();
    } else if (message.type === 'ack') {
      if (!message.ok) feedback(message.detail || 'Command rejected', true);
      if (message.status?.canvas) applyCanvasSnapshot(message.status.canvas);
      if (canvasQueue[0]?.command_id === message.command_id) { canvasQueue.shift(); canvasSending = false; sendNextCanvasBatch(); }
    }
  };
  socket.onclose = () => {
    neutralGame();
    setConnection(false);
    canvasSending = false;
    role = 'viewer'; $('editorRole').textContent = 'Reconnecting';
    editorSession = '';
    if (token) { setTimeout(connectSocket, reconnectDelay); reconnectDelay = Math.min(reconnectDelay * 2, 8000); }
  };
  socket.onerror = () => socket.close();
}
async function enter() {
  try {
    await refresh();
    $('pairPanel').hidden = true; $('workspace').hidden = false;
    connectSocket();
  } catch (error) {
    token = ''; localStorage.removeItem(tokenKey);
    $('workspace').hidden = true; $('pairPanel').hidden = false;
    feedback(error.message, true, true);
  }
}
const params = new URLSearchParams(location.search);
if (params.has('pair')) $('pairCode').value = params.get('pair').replace(/\D/g, '').slice(0, 6);
$('pairButton').onclick = async () => {
  $('pairButton').disabled = true;
  try {
    const reply = await api('/api/pair/request', 'POST', { code: $('pairCode').value.trim(), name: $('phoneName').value.trim() || 'Phone' });
    feedback('Approve this phone on the projector…', false, true);
    for (let tries = 0; tries < 45; tries++) {
      await new Promise(resolve => setTimeout(resolve, 2000));
      const status = await api('/api/pair/status?id=' + encodeURIComponent(reply.requestId));
      if (status.state === 'approved') {
        token = status.token; localStorage.setItem(tokenKey, token);
        history.replaceState(null, '', '/');
        await enter(); return;
      }
      if (status.state !== 'pending') throw new Error(`Pairing ${status.state}`);
    }
    throw new Error('Pairing timed out');
  } catch (error) { feedback(error.message, true, true); }
  finally { $('pairButton').disabled = false; }
};
async function openTab(tab) {
  try {
  feedback('');
  document.body.classList.remove('canvasFocus','gameFocus');
  if(tab!=='fx' && snapshot?.sceneType==='fx') {
    await flushFx(); await send('fx.exit');
    if(tab==='canvas') await send('show',{kind:'canvas'});
  }
  const navTab=['games','settings'].includes(tab)?'more':tab;
  for (const item of document.querySelectorAll('nav button')) item.classList.toggle('active', item.dataset.tab===navTab);
  for (const name of ['canvas','scenes','fx','games','settings','more']) $(name + 'Tab').hidden = name !== tab;
  if(tab==='fx') await send('fx.list');
  if(tab!=='scenes')$('mediaPreview').querySelector('video')?.pause();
  } catch(e) { feedback(e.message,true); }
}
for(const b of document.querySelectorAll('nav button'))b.onclick=()=>openTab(b.dataset.tab);
for(const b of document.querySelectorAll('[data-open-tab]'))b.onclick=()=>openTab(b.dataset.openTab);
function renderScenes() {
  const list = $('sceneList'); list.replaceChildren();
  for (const entry of scenes) {
    const row = document.createElement('div'); row.className = 'sceneRow';
    const name = document.createElement('span'); name.textContent = entry.name;
    const type = document.createElement('small'); type.textContent = entry.scene.kind.toUpperCase();
    const show = document.createElement('button'); show.textContent = 'Show';
    show.onclick = () => guarded(show,async()=>{if(['image','video'].includes(entry.scene.kind)){await chooseMedia({...entry.scene,name:entry.name});$('sceneLibrary').hidden=true;await flushMedia();await send('show',mediaDraft);}else await send('show',entry.scene);feedback(`U hap: ${entry.name}`);});
    const del = document.createElement('button'); del.textContent = '×'; del.title = 'Fshi skenën';
    del.onclick = async () => { try { await api('/api/scenes/' + encodeURIComponent(entry.id), 'DELETE'); await refresh(); } catch(e) { feedback(e.message, true); } };
    show.textContent='Hap';row.append(name, type, show, del); list.append(row);
  }
}
$('sceneKind').onchange = () => {
  const selected = $('sceneKind').value;
  for (const type of ['text','image','html','page','video']) $(type + 'Input').hidden = type !== selected;
};
async function upload(file) {
  if (!file) throw new Error('Choose a file');
  const response = await fetch('/api/upload', { method: 'POST', headers: { Authorization: 'Bearer ' + token, 'X-Hugo-Session': editorSession, 'Content-Type': file.type }, body: file });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || 'Upload failed');
  return result.src;
}
async function buildScene() {
  const kind = $('sceneKind').value;
  const name = $('sceneName').value.trim();
  if (kind === 'text') return { kind, name, text: $('sceneText').value, color: $('textColor').value, background: $('textBackground').value, fontSize: Number($('textSize').value) };
  if (kind === 'html') return { kind, name, html: $('sceneHtml').value };
  if (kind === 'page') return { kind, name, url: $('sceneUrl').value.trim() };
  if (kind === 'image' || kind === 'video') {
    feedback('Uploading media…');
    const file = $(kind + 'File').files[0];
    return { kind, name: name || file?.name || '', src: await upload(file), loop: $('videoLoop').checked };
  }
}
async function guarded(button, fn) {
  button.disabled = true;
  try { await fn(); } catch (error) { feedback(error.message, true); }
  finally { button.disabled = false; }
}
$('showScene').onclick = () => guarded($('showScene'), async () => { const scene = await buildScene(); const ack = await send('show', scene); feedback(`Shown: ${ack.detail}`); });
$('saveScene').onclick = () => guarded($('saveScene'), async () => { const scene = await buildScene(); const name = $('sceneName').value.trim() || scene.name || `${scene.kind} scene`; await api('/api/scenes','POST',{ name, scene }); await refresh(); feedback(`Saved: ${name}`); });
$('clearScreen').onclick = () => guarded($('clearScreen'), async () => { await send('clear'); feedback('Screen cleared'); });
$('setVolume').onclick = () => guarded($('setVolume'), async () => { await send('volume',{ percent:Number($('volume').value) }); feedback('Volume changed'); });
$('refreshStatus').onclick = () => guarded($('refreshStatus'), async () => { await refresh(); feedback('Status updated'); });
$('volume').onchange = () => $('setVolume').click();
$('volume').oninput = () => $('volumeValue').textContent = $('volume').value + '%';
for (const [label, type] of [['Release control', 'release'], ['Take control', 'claim']]) {
  const button = document.createElement('button');
  button.textContent = label;
  button.onclick = () => {
    if (socket?.readyState !== WebSocket.OPEN) return feedback('Reconnect first', true);
    socket.send(JSON.stringify({ type }));
  };
  $('forgetPairing').before(button);
}
const move = document.createElement('div');
const moveHint = document.createElement('p');
moveHint.textContent = 'Changing Wi-Fi? Keep this page open. Join the new network, enter the new controller address shown on the projector, then move this pairing.';
const moveAddress = document.createElement('input');
moveAddress.type = 'url';
moveAddress.placeholder = 'http://projector-IP:39090/';
moveAddress.setAttribute('aria-label', 'New projector address');
const moveButton = document.createElement('button');
moveButton.textContent = 'Move pairing to new address';
moveButton.onclick = () => {
  try {
    const target = new URL(moveAddress.value.trim());
    if (target.protocol !== 'http:' || target.port !== '39090' || !/^(\d{1,3}\.){3}\d{1,3}$/.test(target.hostname)) throw new Error('Enter the projector address shown on its screen');
    if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('Pair this phone first');
    location.assign(`${target.origin}/#hugo_token=${encodeURIComponent(token)}`);
  } catch (error) { feedback(error.message, true); }
};
move.append(moveHint, moveAddress, moveButton);
$('forgetPairing').before(move);
$('forgetPairing').onclick = () => { token = ''; localStorage.removeItem(tokenKey); socket?.close(); location.reload(); };
setTimeout(()=>{if (token) enter(); else setConnection(false);},0);

// Live canvas state and actions are wired to the same projector command router.
let canvasModel = { strokes: [], texts: [], corners: [[0,0],[1,0],[1,1],[0,1]], grid:false, effect:{ type:'none', speed:5, loop:true } };
let tool = 'pen', activeStroke = null, sequence = 0, pendingActions = [], sendTimer = null, maskDraft=[];
const canvas = $('canvas'), ctx = canvas.getContext('2d');
function applyCanvasSnapshot(model) { if (!model) return; canvasModel = model; drawPreview(); }
function drawPreview() {
  ctx.fillStyle='#070c16'; ctx.fillRect(0,0,1280,720);
  if (canvasModel.grid) { ctx.strokeStyle='#4a5a73'; ctx.lineWidth=1; for(let x=0;x<=1280;x+=80){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,720);ctx.stroke()} for(let y=0;y<=720;y+=80){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(1280,y);ctx.stroke()} }
  for (const stroke of canvasModel.strokes || []) drawStroke(stroke);
  if (activeStroke) drawStroke(activeStroke);
  for (const item of canvasModel.texts || []) { ctx.fillStyle=item.color||'#ffffff'; ctx.font=`${item.size||48}px sans-serif`; ctx.fillText(item.text||'',(item.x||.5)*1280,(item.y||.5)*720); }
  if ((canvasModel.mask||[]).length>2){ctx.strokeStyle='#ffac7b';ctx.lineWidth=3;ctx.beginPath();canvasModel.mask.forEach((p,i)=>i?ctx.lineTo(p[0]*1280,p[1]*720):ctx.moveTo(p[0]*1280,p[1]*720));ctx.closePath();ctx.stroke()}
  if (maskDraft.length){ctx.fillStyle='#ffac7b';for(const p of maskDraft)ctx.fillRect(p[0]*1280-5,p[1]*720-5,10,10)}
  const c=canvasModel.corners||[[0,0],[1,0],[1,1],[0,1]]; ctx.strokeStyle='#ffd369';ctx.lineWidth=3;ctx.beginPath();ctx.moveTo(c[0][0]*1280,c[0][1]*720);for(let i=1;i<4;i++)ctx.lineTo(c[i][0]*1280,c[i][1]*720);ctx.closePath();ctx.stroke();
  c.forEach(([x,y])=>{ctx.fillStyle='#ffd369';ctx.fillRect(x*1280-8,y*720-8,16,16)});
}
function drawStroke(stroke) { const pts=stroke.points||[]; if(!pts.length)return;ctx.strokeStyle=stroke.color||'#75a5ff';ctx.lineWidth=stroke.width||8;ctx.lineCap='round';ctx.lineJoin='round';ctx.beginPath();ctx.moveTo(pts[0][0]*1280,pts[0][1]*720);for(let i=1;i<pts.length;i++)ctx.lineTo(pts[i][0]*1280,pts[i][1]*720);ctx.stroke(); }
drawPreview();
function point(event) { const rect=canvas.getBoundingClientRect();return [Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width)),Math.max(0,Math.min(1,(event.clientY-rect.top)/rect.height))]; }
function queueAction(action) { if(pendingActions.length+canvasQueue.length*64>512){feedback('Canvas queue is full; wait for the projector',true);return}pendingActions.push(action); if(pendingActions.length>=24)flushActions();else if(!sendTimer)sendTimer=setTimeout(flushActions,35); }
function flushActions() { clearTimeout(sendTimer);sendTimer=null;if(!pendingActions.length)return;while(pendingActions.length)canvasQueue.push(envelope('canvas_batch',{seq:++sequence,actions:pendingActions.splice(0,64)}));sendNextCanvasBatch(); }
function sendNextCanvasBatch(){if(canvasSending||!canvasQueue.length||socket?.readyState!==WebSocket.OPEN||role!=='editor'||socket.bufferedAmount>128000)return;canvasQueue[0].expiresAt=Date.now()+20000;socket.send(JSON.stringify({type:'command',...canvasQueue[0]}));canvasSending=true;}
let draggedCorner=-1, draggedText=null;
canvas.onpointerdown = event => { if(role!=='editor')return;event.preventDefault();canvas.setPointerCapture(event.pointerId);const p=point(event);if(tool==='mask'){maskDraft.push(p);drawPreview();return}if(tool==='eraser'){queueAction({type:'erase_at',point:p});flushActions();return}if(tool==='text'){draggedText=(canvasModel.texts||[]).find(item=>Math.hypot(item.x-p[0],item.y-p[1])<.09);return}if(document.querySelector('.map')?.open){const corners=canvasModel.corners||[];draggedCorner=corners.findIndex(c=>Math.hypot(c[0]-p[0],c[1]-p[1])<.04);if(draggedCorner>=0)return;}activeStroke={stroke_id:newId(),color:$('drawColor').value,width:Number($('drawWidth').value),points:[p]};queueAction({type:'stroke_start',stroke_id:activeStroke.stroke_id,color:activeStroke.color,width:activeStroke.width,point:p});drawPreview(); };
canvas.onpointermove = event => {if(!canvas.hasPointerCapture(event.pointerId))return;event.preventDefault();const p=point(event);if(draggedCorner>=0){canvasModel.corners[draggedCorner]=p;drawPreview();return}if(draggedText){draggedText.x=p[0];draggedText.y=p[1];drawPreview();return}if(!activeStroke)return;activeStroke.points.push(p);queueAction({type:'stroke_point',stroke_id:activeStroke.stroke_id,point:p});drawPreview();};
canvas.onpointerup = event => {if(!canvas.hasPointerCapture(event.pointerId))return;event.preventDefault();if(draggedCorner>=0){queueAction({type:'corners',corners:canvasModel.corners});draggedCorner=-1;flushActions();return}if(draggedText){queueAction({type:'text_move',id:draggedText.id,x:draggedText.x,y:draggedText.y});draggedText=null;flushActions();return}if(activeStroke){queueAction({type:'stroke_end',stroke_id:activeStroke.stroke_id});activeStroke=null;flushActions();drawPreview()}};
function chooseTool(next){tool=next;for(const [name,id] of [['pen','penTool'],['eraser','eraserTool'],['text','textTool'],['mask','maskTool']])$(id).classList.toggle('active',name===next)}
$('penTool').onclick=()=>chooseTool('pen');
$('eraserTool').onclick=()=>chooseTool('eraser');
$('textTool').onclick=()=>chooseTool('text');
$('maskTool').onclick=()=>chooseTool('mask');
$('undo').onclick=()=>send('canvas_undo').catch(e=>feedback(e.message,true));
$('redo').onclick=()=>send('canvas_redo').catch(e=>feedback(e.message,true));
$('addText').onclick=()=>{const value=$('canvasText').value.trim();if(value){queueAction({type:'text_add',id:newId(),text:value,x:.5,y:.5,color:$('drawColor').value,size:48});flushActions();$('canvasText').value='';}};
$('toggleGrid').onclick=()=>{canvasModel.grid=!canvasModel.grid;queueAction({type:'grid',enabled:canvasModel.grid});flushActions();drawPreview()};
$('resetCorners').onclick=()=>{canvasModel.corners=[[0,0],[1,0],[1,1],[0,1]];queueAction({type:'corners',corners:canvasModel.corners});flushActions();drawPreview()};
$('applyMask').onclick=()=>{if(maskDraft.length<3){feedback('Tap at least three mask points',true);return}queueAction({type:'mask',points:maskDraft});maskDraft=[];flushActions();drawPreview()};
$('clearMask').onclick=()=>{maskDraft=[];queueAction({type:'mask_clear'});flushActions();drawPreview()};

$('applyEffect').onclick=()=>send('animation',{type:$('effect').value,speed:Number($('speed').value),loop:$('effectLoop').checked,color:$('drawColor').value}).then(()=>feedback('Effect applied')).catch(e=>feedback(e.message,true));
$('saveCanvas').onclick=()=>guarded($('saveCanvas'),async()=>{await drainCanvasQueue();const name=$('canvasName').value.trim()||'Canvas scene';const current=await api('/api/state');if(!current.status.canvas)throw new Error('Open the canvas on the projector first');const scene={kind:'canvas',name,model:current.status.canvas};await api('/api/scenes','POST',{name,scene});await refresh();feedback('Canvas saved')});
canvas.onpointercancel = canvas.onpointerup;

// Compact canvas tools. Clear is an undoable model action, never global blackout.
$('paletteToggle').onclick=()=>{ $('palette').hidden=!$('palette').hidden; $('paletteToggle').setAttribute('aria-expanded',String(!$('palette').hidden)); };
for(const color of ['#ffffff','#75a5ff','#67d7ba','#f5c84b','#eb7860','#b18af4','#000000']){
 const b=document.createElement('button');b.style.background=color;b.setAttribute('aria-label',color);b.onclick=()=>{$('drawColor').value=color;$('paletteToggle').style.color=color;$('palette').hidden=true;};$('swatches').append(b);
}
$('drawColor').oninput=()=>{$('paletteToggle').style.color=$('drawColor').value;};
$('clearCanvas').onclick=()=>guarded($('clearCanvas'),async()=>{await drainCanvasQueue();await send('canvas_batch',{seq:++sequence,actions:[{type:'clear_canvas'}]});feedback('Canvas cleared · Undo available');});
$('moreTools').onclick=()=>{$('extraTools').hidden=!$('extraTools').hidden;};
$('hideTools').onclick=()=>{$('drawingTools').hidden=!$('drawingTools').hidden;$('hideTools').textContent=$('drawingTools').hidden?'Show tools':'Hide tools';};
$('focusCanvas').onclick=()=>{document.body.classList.toggle('canvasFocus');$('focusCanvas').textContent=document.body.classList.contains('canvasFocus')?'Collapse':'Expand';};
for(const id of ['effect','speed','effectLoop']) $(id).onchange=()=> $('applyEffect').click();

// Touch controllers carry input only. The Android projector owns simulation and score.
let gamePlayer=0,gameMatch='',gameSeq=0,gameState=null,gameClockOffset=0;
const gameKeys=new Set();
function neutralGame(){gameKeys.clear();document.querySelectorAll('[data-drive],#gameFire').forEach(b=>b.classList.remove('active'));}
function updateGame(g){
 if(!g)return;if(g.serverTime)gameClockOffset=g.serverTime-Date.now();const first=!gameState;gameState=g;gameMatch=g.matchId||'';
 if(g.player)gamePlayer=g.player;
 const players=g.players||[];
 $('gameStatus').textContent=`${g.phase} · ${players.map((p,i)=>`${g.config?.['name'+(i+1)]||'P'+(i+1)} ${p.score}`).join(' : ')} · ${gamePlayer?'Player '+gamePlayer: 'Join a player'}${socket?.readyState===WebSocket.OPEN?'':' · Offline'}`;
 if(first&&g.config?.mode){const c=g.config;for(const [id,key] of [['gameMode','mode'],['gameDifficulty','difficulty'],['gameArena','arena'],['gameTarget','target'],['gameName1','name1'],['gameName2','name2'],['gameColor1','color1'],['gameColor2','color2']])$(id).value=c[key];$('gameMute').checked=c.mute;}
}
function gameConfig(){return {mode:+$('gameMode').value,difficulty:+$('gameDifficulty').value,arena:+$('gameArena').value,target:+$('gameTarget').value,name1:$('gameName1').value,name2:$('gameName2').value,color1:$('gameColor1').value,color2:$('gameColor2').value,mute:$('gameMute').checked};}
for(const id of ['gameColor1','gameColor2'])for(const [i,c] of ['#F5C84B','#67D7BA','#EB7860','#94A9F4'].entries()){const o=document.createElement('option');o.value=c;o.textContent=['Gold','Mint','Coral','Blue'][i];$(id).append(o);}$('gameColor2').selectedIndex=1;
function joinGame(player){neutralGame();gamePlayer=player;gameSeq=0;if(socket?.readyState!==WebSocket.OPEN)return feedback('Wait for connection',true);socket.send(JSON.stringify({type:'game_join',player}));}
$('joinP1').onclick=()=>joinGame(1);$('joinP2').onclick=()=>joinGame(2);
$('gameStart').onclick=()=>guarded($('gameStart'),async()=>{gamePlayer=0;neutralGame();await send('game_start',gameConfig());joinGame(1);$('gameSetup').hidden=true;feedback('Joined Player 1. Press Play when ready.');});
$('gameRestart').onclick=()=>guarded($('gameRestart'),async()=>{gamePlayer=0;neutralGame();await send('game_start',gameState?.config||gameConfig());joinGame(1);});
$('gamePause').onclick=()=>{neutralGame();if(socket?.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:'game_pause'}));};
$('gameResume').onclick=()=>send('game_resume').catch(e=>feedback(e.message,true));
$('gameExit').onclick=()=>send('game_exit').then(()=>{neutralGame();gamePlayer=0;document.body.classList.remove('gameFocus');}).catch(e=>feedback(e.message,true));
$('gameSetupToggle').onclick=()=>{$('gameSetup').hidden=!$('gameSetup').hidden;};
$('gameExpand').onclick=()=>{document.body.classList.toggle('gameFocus');$('gameExpand').textContent=document.body.classList.contains('gameFocus')?'Collapse':'Expand controls';};
for(const b of document.querySelectorAll('[data-drive],#gameFire')){
 const key=b.dataset.drive||'fire';b.onpointerdown=e=>{e.preventDefault();b.setPointerCapture(e.pointerId);gameKeys.add(key);b.classList.add('active');};
 const release=e=>{e.preventDefault();gameKeys.delete(key);b.classList.remove('active');};b.onpointerup=release;b.onpointercancel=release;b.onlostpointercapture=release;
}
function sendGameInput(){if(!gamePlayer||!gameMatch||socket?.readyState!==WebSocket.OPEN||socket.bufferedAmount>4096)return;socket.send(JSON.stringify({type:'game_input',matchId:gameMatch,seq:++gameSeq,expiresAt:Date.now()+gameClockOffset+3000,move:(gameKeys.has('forward')?1:0)-(gameKeys.has('back')?1:0),turn:(gameKeys.has('right')?1:0)-(gameKeys.has('left')?1:0),fire:gameKeys.has('fire')}));}
setInterval(sendGameInput,80);
window.addEventListener('blur',()=>{neutralGame();sendGameInput();});
document.addEventListener('visibilitychange',()=>{if(document.hidden){neutralGame();sendGameInput();if(gamePlayer&&socket?.readyState===WebSocket.OPEN)socket.send(JSON.stringify({type:'game_pause'}));}});

// FX reuses the authorized envelope/session; no additional connection or render loop.
let fxProfile=null,fxRevision=0,fxAcknowledged=0,fxSending=null,fxTimer=null,fxDrag=null,fxHandle=0,fxHistory=[],fxMaskDraft=[],fxPhase='IDLE';
const fxCanvas=$('fxCalibration'),fxContext=fxCanvas.getContext('2d');
const fxClone=value=>JSON.parse(JSON.stringify(value));
function fxPatch(){return fxProfile?.patches.find(p=>p.id===$('fxPatch').value);}
function updateFxState(state){
 if(!state)return;fxPhase=state.phase;
 $('fxState').textContent=({IDLE:'Gati',CALIBRATING:'Përshtat zonat mbi divan',PLAYING:'Duke luajtur',PAUSED:'Në pauzë',STOPPED:'Ndaluar',PREVIEW:'Provë e figurës'})[state.phase]||state.phase;
 $('fxPause').textContent=state.phase==='PAUSED'?'Vazhdo':'Pauzë';
 if(state.profile&&fxRevision===fxAcknowledged&&!fxDrag){fxProfile=fxClone(state.profile);if(document.activeElement!==$('fxProfileName'))$('fxProfileName').value=fxProfile.target_label;if(state.selected)$('fxPatch').value=state.selected;drawFxCalibration();}
 for(const [id,key] of [['fxIntensity','intensity'],['fxSpeed','speed']])if(document.activeElement!==$(id)&&state[key]!==undefined)$(id).value=Math.round(state[key]*100);
 if(state.loop!==undefined)$('fxLoop').checked=state.loop;
 if(state.profiles){const current=$('fxProfiles').value;$('fxProfiles').replaceChildren();for(const p of state.profiles){const o=document.createElement('option');o.value=p.id;o.textContent=p.name;$('fxProfiles').append(o);}if([...$('fxProfiles').options].some(o=>o.value===current))$('fxProfiles').value=current;}
 if(state.phase!=='IDLE'&&state.profile&&state.profiles?.some(p=>p.id===state.profile.calibration_profile_id))localStorage.setItem('hugo_fx_last_profile',state.profile.calibration_profile_id);
}
function drawFxCalibration(){
 if(!fxProfile)return;const ctx=fxContext;ctx.fillStyle='#050b12';ctx.fillRect(0,0,1280,720);ctx.lineWidth=2;ctx.strokeStyle='#1e3146';
 for(let x=0;x<=1280;x+=80){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,720);ctx.stroke();}for(let y=0;y<=720;y+=80){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(1280,y);ctx.stroke();}
 for(const p of fxProfile.patches){const active=p.id===$('fxPatch').value;ctx.strokeStyle=active?'#67e4cb':'#55749a';ctx.fillStyle=active?'#244b4a':'#18273a';ctx.lineWidth=active?4:2;
  ctx.beginPath();p.corners.forEach(([x,y],i)=>i?ctx.lineTo(x*1280,y*720):ctx.moveTo(x*1280,y*720));ctx.closePath();ctx.fill();ctx.stroke();
  if(p.polygon_mask){ctx.strokeStyle='#e9b86a';ctx.beginPath();p.polygon_mask.forEach(([x,y],i)=>i?ctx.lineTo(x*1280,y*720):ctx.moveTo(x*1280,y*720));ctx.closePath();ctx.stroke();}
  ctx.fillStyle=active?'#c6fff2':'#8ba2bc';ctx.font='24px system-ui';ctx.fillText(p.id.toUpperCase(),p.corners[0][0]*1280,p.corners[0][1]*720-22);
  if(active){p.corners.forEach(([x,y],i)=>{ctx.fillStyle=fxHandle===i?'#f5c84b':'#b5ffec';ctx.beginPath();ctx.arc(x*1280,y*720,16,0,Math.PI*2);ctx.fill();ctx.fillStyle='#071c20';ctx.fillText(String(i+1),x*1280-6,y*720+8);});ctx.fillStyle=fxHandle===4?'#f5c84b':'#b5ffec';ctx.font='38px monospace';ctx.fillText('+',p.center[0]*1280-12,p.center[1]*720+12);}
 }
 if(fxMaskDraft.length){ctx.strokeStyle='#f5c84b';ctx.beginPath();fxMaskDraft.forEach(([x,y],i)=>{if(i)ctx.lineTo(x*1280,y*720);else ctx.moveTo(x*1280,y*720);});ctx.stroke();}
 const p=fxPatch();if(p){$('fxFeather').value=p.feather;$('fxOpacity').value=Math.round(p.opacity*100);$('fxOrder').value=p.z;}
}
function fxRemember(){fxHistory.push(fxClone(fxProfile));if(fxHistory.length>20)fxHistory.shift();}
function fxChanged(){fxRevision++;drawFxCalibration();clearTimeout(fxTimer);fxTimer=setTimeout(()=>flushFx().catch(e=>feedback(e.message,true)),140);}
async function flushFx(){clearTimeout(fxTimer);if(fxSending)await fxSending;if(fxRevision===fxAcknowledged)return;const version=fxRevision,profile=fxClone(fxProfile),selected=$('fxPatch').value;fxSending=send('fx.calibration.update',{profile,selected});try{await fxSending;fxAcknowledged=version;}finally{fxSending=null;}if(fxRevision!==fxAcknowledged)return flushFx();}
async function fxCommand(command,payload={}){await flushFx();return send(command,payload);}
function fxAction(id,fn){$(id).onclick=()=>guarded($(id),fn);}
function preferredFxProfile(){if(fxPhase!=='IDLE')return null;const profiles=snapshot?.fx?.profiles||[],previous=localStorage.getItem('hugo_fx_last_profile');if(profiles.some(p=>p.id===previous))return previous;const id=fxProfile?.calibration_profile_id;if(profiles.some(p=>p.id===id))return id;return profiles.length===1?profiles[0].id:null;}
async function playFx(command){await flushFx();if(fxPhase==='IDLE')await send('fx.list');const id=preferredFxProfile();if(id)await send('show',{kind:'fx',effect:'sofa-spirit',profileId:id,intensity:+$('fxIntensity').value/100,speed:+$('fxSpeed').value/100,loop:$('fxLoop').checked});else await fxCommand(command);$('fxWizard').hidden=true;}
fxAction('fxCalibrate',async()=>{await fxCommand('fx.list');const id=preferredFxProfile();if(id)await send('fx.calibration.load',{id});await fxCommand('fx.calibration.update',{profile:fxProfile,selected:$('fxPatch').value});$('fxWizard').hidden=false;drawFxCalibration();});
fxAction('fxPlay',()=>playFx('fx.play'));
fxAction('fxReplay',()=>playFx('fx.replay'));
fxAction('fxPause',()=>fxCommand(fxPhase==='PAUSED'?'fx.resume':'fx.pause'));
fxAction('fxStop',async()=>{clearTimeout(fxTimer);fxAcknowledged=fxRevision;const pending=fxSending;if(pending)try{await pending;}catch{}await send('fx.stop');});
fxAction('fxPreview',()=>fxCommand('fx.preview'));
fxAction('fxDone',async()=>{await fxCommand('fx.stop');$('fxWizard').hidden=true;});
fxAction('fxExit',async()=>{await fxCommand('fx.exit');await send('show',{kind:'canvas'});$('fxWizard').hidden=true;$('workspace nav [data-tab="canvas"]').click();});
for(const id of ['fxIntensity','fxSpeed','fxLoop'])$(id).onchange=()=>fxCommand('fx.settings',{intensity:+$('fxIntensity').value/100,speed:+$('fxSpeed').value/100,loop:$('fxLoop').checked}).catch(e=>feedback(e.message,true));
$('fxPatch').onchange=async()=>{fxMaskDraft=[];fxHandle=0;drawFxCalibration();try{await fxCommand('fx.calibration.update',{profile:fxProfile,selected:$('fxPatch').value});}catch(e){feedback(e.message,true);}};
function fxCoordinates(e){const b=fxCanvas.getBoundingClientRect();return [Math.max(0,Math.min(1,(e.clientX-b.x)/b.width)),Math.max(0,Math.min(1,(e.clientY-b.y)/b.height))];}
fxCanvas.onpointerdown=e=>{if(!fxProfile)return;e.preventDefault();const pt=fxCoordinates(e),p=fxPatch();if($('fxMaskMode').checked){if(fxMaskDraft.length<12)fxMaskDraft.push(pt);drawFxCalibration();return;}const points=[...p.corners,p.center],b=fxCanvas.getBoundingClientRect();let closest=-1,distance=34;points.forEach((v,i)=>{const d=Math.hypot((v[0]-pt[0])*b.width,(v[1]-pt[1])*b.height);if(d<distance){distance=d;closest=i;}});if(closest<0)return;fxHandle=closest;fxRemember();fxDrag={pointer:e.pointerId,index:closest};fxCanvas.setPointerCapture(e.pointerId);drawFxCalibration();};
function fxMovePoint(pt){const p=fxPatch();if(fxHandle===4){const mid=p.corners.reduce((a,v)=>[a[0]+v[0]/4,a[1]+v[1]/4],[0,0]);p.center=pt.map((v,i)=>Math.max(0,Math.min(1,Math.max(mid[i]-.075,Math.min(mid[i]+.075,v)))));}else{const oldMid=p.corners.reduce((a,v)=>[a[0]+v[0]/4,a[1]+v[1]/4],[0,0]);const offset=p.center.map((v,i)=>v-oldMid[i]);p.corners[fxHandle]=pt;const mid=p.corners.reduce((a,v)=>[a[0]+v[0]/4,a[1]+v[1]/4],[0,0]);p.center=mid.map((v,i)=>Math.max(0,Math.min(1,v+offset[i])));}fxChanged();$('fxHandleInfo').textContent=`${p.id} · ${fxHandle===4?'center':fxHandle+1} · ${(pt[0]*1280).toFixed(0)}, ${(pt[1]*720).toFixed(0)}`;}
fxCanvas.onpointermove=e=>{if(fxDrag?.pointer===e.pointerId){e.preventDefault();fxMovePoint(fxCoordinates(e));}};
fxCanvas.onpointerup=fxCanvas.onpointercancel=e=>{if(fxDrag?.pointer===e.pointerId){fxDrag=null;flushFx().catch(e=>feedback(e.message+' · Undo available',true));}};
for(const b of document.querySelectorAll('[data-fx-nudge]'))b.onclick=()=>{if(!fxProfile)return;fxRemember();const p=fxPatch(),pt=fxHandle===4?p.center:p.corners[fxHandle],d=b.dataset.fxNudge.split(',').map(Number),s=$('fxFine').checked?1:5;fxMovePoint([Math.max(0,Math.min(1,pt[0]+d[0]*s/1280)),Math.max(0,Math.min(1,pt[1]+d[1]*s/720))]);};
fxAction('fxUndo',async()=>{if(fxHistory.length){fxProfile=fxHistory.pop();fxChanged();await flushFx();}});
fxAction('fxReset',async()=>{await flushFx();fxRemember();await send('fx.calibration.reset',{patch:$('fxPatch').value});});
for(const [id,key,scale] of [['fxFeather','feather',1],['fxOpacity','opacity',100],['fxOrder','z',1]])$(id).onchange=()=>{fxRemember();fxPatch()[key]=+$(id).value/scale;fxChanged();};
fxAction('fxMaskApply',async()=>{if(fxMaskDraft.length<3)throw Error('Tap at least three clockwise convex points');fxRemember();fxPatch().polygon_mask=fxClone(fxMaskDraft);fxMaskDraft=[];fxChanged();await flushFx();});
fxAction('fxMaskClear',async()=>{fxRemember();delete fxPatch().polygon_mask;fxMaskDraft=[];fxChanged();await flushFx();});
fxAction('fxSaveProfile',async()=>{const name=$('fxProfileName').value.trim();if(!name)throw Error('Name this sofa position');fxProfile.target_label=name;fxChanged();await flushFx();await fxCommand('fx.calibration.save',{profile:fxProfile});feedback('Calibration profile saved on projector');});
fxAction('fxNewProfile',async()=>{await flushFx();fxProfile=null;fxHistory=[];await send('fx.calibration.create',{id:'sofa-'+newId().slice(0,12),name:'Sofa · New position'});drawFxCalibration();});
fxAction('fxLoadProfile',async()=>{const id=$('fxProfiles').value;if(!id)throw Error('Save a profile first');await flushFx();fxProfile=null;fxHistory=[];await send('fx.calibration.load',{id});$('fxWizard').hidden=false;drawFxCalibration();});
fxAction('fxSaveScene',async()=>{if(!fxProfile)await send('fx.list');await fxCommand('fx.scene.save',{profile:fxProfile,name:$('fxSceneName').value.trim()||fxProfile.target_label+' · Sofa Spirit'});feedback('Scene and calibration saved · reopen from Scenes');});

// One photo/video: the phone owns the editable region, the projector owns playback.
const mediaDraftKey='hugo_media_draft_v1';
let mediaDraft=null,mediaLive=null,mediaDrag=null,mediaHistory=[],mediaRevision=0,mediaAck=0,mediaSending=null,mediaTimer=null,mediaObjectUrl=null,mediaPreviewAbort=null,mediaPreviewVersion=0;
const copyMedia=o=>JSON.parse(JSON.stringify(o));
const fullMediaPlacement=()=>({x:0,y:0,width:1,height:1});
function mediaActive(){return !!mediaDraft&&mediaLive?.src===mediaDraft.src;}
function storeMediaDraft(){if(mediaDraft)try{localStorage.setItem(mediaDraftKey,JSON.stringify(mediaDraft));}catch{}}
function syncMedia(status){
 mediaLive=status?.media||null;
 if(!mediaDraft&&mediaLive)chooseMedia({...mediaLive,loop:true},null,true).catch(e=>feedback(e.message,true));
 if(mediaDraft&&mediaActive()&&!mediaDrag&&mediaRevision===mediaAck){mediaDraft.placement=copyMedia(mediaLive.placement);drawMedia();storeMediaDraft();}
 $('mediaPause').textContent=mediaLive?.state==='PAUSED'?'Vazhdo':'Pauzë';
}
function drawMedia(){
 const has=!!mediaDraft;$('mediaRegion').hidden=!has;$('mediaEmpty').hidden=has;
 if(!has)return;
 const p=mediaDraft.placement,r=$('mediaRegion');r.style.left=p.x*100+'%';r.style.top=p.y*100+'%';r.style.width=p.width*100+'%';r.style.height=p.height*100+'%';
 $('mediaFileName').textContent=mediaDraft.name|| (mediaDraft.kind==='video'?'Video':'Foto');$('mediaRegionLabel').textContent=mediaDraft.kind==='video'?'VIDEO':'FOTO';$('mediaPause').hidden=mediaDraft.kind!=='video';$('mediaScale').value=Math.round(Math.max(p.width,p.height)*100);
}
async function mediaImagePreview(src,signal){
 if(!/^\/media\/[a-f0-9]{32}\.(png|jpg|webp|gif)$/.test(src))return null;
 const response=await fetch(src,{headers:{Authorization:'Bearer '+token},signal});if(!response.ok)throw Error('Pamja paraprake nuk u ngarkua');
 if(Number(response.headers.get('content-length')||0)>25000000){await response.body.cancel();return null;}
 const reader=response.body.getReader(),chunks=[];let bytes=0;
 while(true){const {done,value}=await reader.read();if(done)break;bytes+=value.length;if(bytes>25000000){await reader.cancel();return null;}chunks.push(value);}
 return URL.createObjectURL(new Blob(chunks,{type:response.headers.get('content-type')||'image/png'}));
}
async function chooseMedia(scene,localUrl=null,authoritative=false){
 if(!['image','video'].includes(scene.kind)||typeof scene.src!=='string'||!(/^(\/media\/[a-f0-9]{32}\.(png|jpg|webp|gif|mp4|webm)|https:\/\/[^\s]+)$/.test(scene.src)))throw Error('Media nuk është e vlefshme');
 const p=scene.placement||fullMediaPlacement();if(![p.x,p.y,p.width,p.height].every(Number.isFinite)||p.x<0||p.y<0||p.width<.02||p.height<.02||p.x+p.width>1.000001||p.y+p.height>1.000001)throw Error('Zona nuk është e vlefshme');
 clearTimeout(mediaTimer);if(mediaSending)await mediaSending;
 mediaDraft={kind:scene.kind,src:scene.src,name:(scene.name||'').slice(0,80),loop:scene.loop!==false,placement:copyMedia(p)};mediaRevision=0;mediaAck=authoritative?0:-1;mediaHistory=[];$('mediaName').value=mediaDraft.name;drawMedia();storeMediaDraft();
 for(const id of ['mediaPlay','mediaPause','mediaStop','mediaSaveToggle','mediaSave'])$(id).disabled=false;
 const version=++mediaPreviewVersion;mediaPreviewAbort?.abort();mediaPreviewAbort=new AbortController();
 $('mediaPreview').querySelector('video')?.pause();$('mediaPreview').replaceChildren();if(mediaObjectUrl)URL.revokeObjectURL(mediaObjectUrl);mediaObjectUrl=null;
 let url=localUrl;
 if(!url&&scene.kind==='image')try{url=await mediaImagePreview(scene.src,mediaPreviewAbort.signal);}catch(e){if(e.name==='AbortError')return;}
 if(version!==mediaPreviewVersion){if(url)URL.revokeObjectURL(url);return;}
 if(url){mediaObjectUrl=url;const node=document.createElement(scene.kind==='video'?'video':'img');node.src=url;if(scene.kind==='video'){node.muted=true;node.loop=true;node.playsInline=true;node.preload='metadata';node.play().catch(()=>{});}else node.alt='Fotoja në skenë';$('mediaPreview').append(node);}
 else{const text=document.createElement('span');text.textContent=scene.kind==='video'?'Video · në projektor':'Foto · në projektor';$('mediaPreview').append(text);}
}
function rememberMedia(){if(mediaDraft){mediaHistory.push(copyMedia(mediaDraft.placement));if(mediaHistory.length>20)mediaHistory.shift();}}
function changeMedia(p){mediaDraft.placement=p;mediaRevision++;drawMedia();storeMediaDraft();clearTimeout(mediaTimer);mediaTimer=setTimeout(()=>flushMedia(false).catch(e=>feedback(e.message,true)),160);}
async function flushMedia(persist=true){
 clearTimeout(mediaTimer);if(mediaSending)await mediaSending;
 if(!mediaDraft)return;if(!mediaActive()){mediaAck=mediaRevision;return;}
 if(!persist&&mediaRevision===mediaAck)return;
 const version=mediaRevision,placement=copyMedia(mediaDraft.placement);mediaSending=send('media_place',{placement,persist});
 try{await mediaSending;mediaAck=version;}finally{mediaSending=null;}
 if(mediaRevision!==mediaAck)return flushMedia(persist);
}
function mediaAction(id,fn){$(id).onclick=()=>guarded($(id),fn);}
$('mediaUpload').onclick=()=>$('mediaFile').click();
$('mediaFile').onchange=async()=>{
 const file=$('mediaFile').files[0];if(!file)return;
 await guarded($('mediaUpload'),async()=>{
  $('mediaFile').disabled=true;
  let url=null;
  try{
   const ext=file.name.split('.').pop().toLowerCase(),types={png:'image/png',jpg:'image/jpeg',jpeg:'image/jpeg',webp:'image/webp',gif:'image/gif',mp4:'video/mp4',webm:'video/webm'},type=file.type||types[ext];
   if(!Object.values(types).includes(type))throw Error('Zgjidh PNG, JPG, WebP, GIF, MP4 ose WebM');
   const kind=type.startsWith('video/')?'video':'image';if(file.size>(kind==='image'?25000000:200000000))throw Error(kind==='image'?'Fotoja lejohet deri 25 MB':'Videoja lejohet deri 200 MB');
   feedback('Po ngarkohet në projektor…');await flushMedia();
   const src=await upload(file.type?file:new File([file],file.name,{type}));url=URL.createObjectURL(file);
   let aspect=16/9;
   await new Promise(resolve=>{const node=document.createElement(kind==='video'?'video':'img');const timer=setTimeout(()=>{node.removeAttribute('src');resolve();},5000);const ready=()=>{aspect=kind==='video'?(node.videoWidth/node.videoHeight):(node.naturalWidth/node.naturalHeight);clearTimeout(timer);node.removeAttribute('src');resolve();};node.addEventListener(kind==='video'?'loadedmetadata':'load',ready,{once:true});node.onerror=()=>{clearTimeout(timer);resolve();};if(kind==='video')node.preload='metadata';node.src=url;});
   if(!Number.isFinite(aspect)||aspect<=0)aspect=16/9;let width=.7,height=width*16/9/aspect;if(height>.85){height=.85;width=height*aspect/(16/9);}
   await chooseMedia({kind,src,name:file.name.slice(0,80),loop:true,placement:{x:(1-width)/2,y:(1-height)/2,width,height}},url);url=null;feedback('U ngarkua. Vendose në skenë dhe shtyp Shfaq.');
  }finally{if(url)URL.revokeObjectURL(url);$('mediaFile').value='';$('mediaFile').disabled=false;}
 });
};
mediaAction('mediaPlay',async()=>{if(!mediaDraft)throw Error('Ngarko një foto ose video');await flushMedia();if(mediaActive())await send('media_resume');else await send('show',copyMedia(mediaDraft));$('mediaPreview').querySelector('video')?.play().catch(()=>{});feedback('Media po shfaqet. Lëvize duke parë projektimin.');});
mediaAction('mediaPause',async()=>{if(!mediaActive())throw Error('Shtyp Shfaq fillimisht');await send(mediaLive.state==='PAUSED'?'media_resume':'media_pause');const video=$('mediaPreview').querySelector('video');if(mediaLive.state==='PLAYING')video?.play().catch(()=>{});else video?.pause();});
mediaAction('mediaStop',async()=>{if(mediaLive)await send('media_stop');else if(snapshot?.sceneType==='fx')await send('fx.stop');else await send('clear');$('mediaPreview').querySelector('video')?.pause();await flushMedia();feedback('Projeksioni u ndal.');});
$('mediaSaveToggle').onclick=()=>{$('mediaSavePanel').hidden=!$('mediaSavePanel').hidden;if(!$('mediaSavePanel').hidden)$('mediaName').focus();};
mediaAction('mediaSave',async()=>{if(!mediaDraft)throw Error('Ngarko një foto ose video');await flushMedia();const name=$('mediaName').value.trim()||mediaDraft.name||'Skena ime';mediaDraft.name=name;await api('/api/scenes','POST',{name,scene:copyMedia(mediaDraft)});storeMediaDraft();await refresh();$('mediaSavePanel').hidden=true;feedback('Skena dhe vendosja u ruajtën.');});
$('toggleLibrary').onclick=()=>{$('sceneLibrary').hidden=!$('sceneLibrary').hidden;};$('closeLibrary').onclick=()=>$('sceneLibrary').hidden=true;
function mediaPoint(e){const b=$('mediaStage').getBoundingClientRect();return {x:(e.clientX-b.x)/b.width,y:(e.clientY-b.y)/b.height};}
$('mediaRegion').onpointerdown=e=>{if(!mediaDraft)return;e.preventDefault();rememberMedia();mediaDrag={id:e.pointerId,start:mediaPoint(e),placement:copyMedia(mediaDraft.placement),resize:e.target.id==='mediaResize'};$('mediaRegion').setPointerCapture(e.pointerId);};
$('mediaRegion').onpointermove=e=>{if(mediaDrag?.id!==e.pointerId)return;e.preventDefault();const q=mediaPoint(e),p=mediaDrag.placement,dx=q.x-mediaDrag.start.x,dy=q.y-mediaDrag.start.y;
 if(mediaDrag.resize){const a=dx/p.width,b=dy/p.height,d=Math.abs(a)>Math.abs(b)?a:b,k=Math.max(Math.max(.025/p.width,.025/p.height),Math.min(1+d,(1-p.x)/p.width,(1-p.y)/p.height));changeMedia({x:p.x,y:p.y,width:p.width*k,height:p.height*k});}
 else changeMedia({...p,x:Math.max(0,Math.min(1-p.width,p.x+dx)),y:Math.max(0,Math.min(1-p.height,p.y+dy))});
};
$('mediaRegion').onpointerup=$('mediaRegion').onpointercancel=$('mediaRegion').onlostpointercapture=e=>{if(mediaDrag?.id===e.pointerId){mediaDrag=null;flushMedia().catch(e=>feedback(e.message,true));}};
mediaAction('mediaCenter',async()=>{if(!mediaDraft)return;rememberMedia();const p=mediaDraft.placement;changeMedia({...p,x:(1-p.width)/2,y:(1-p.height)/2});await flushMedia();});
mediaAction('mediaFull',async()=>{if(!mediaDraft)return;rememberMedia();changeMedia(fullMediaPlacement());await flushMedia();});
mediaAction('mediaUndo',async()=>{if(mediaHistory.length){changeMedia(mediaHistory.pop());await flushMedia();}});
$('mediaScale').onpointerdown=()=>rememberMedia();$('mediaScale').oninput=()=>{if(!mediaDraft)return;const p=mediaDraft.placement,ratio=p.width/p.height,size=+$('mediaScale').value/100;let width=ratio>=1?size:size*ratio,height=width/ratio;const x=Math.max(0,Math.min(1-width,p.x+p.width/2-width/2)),y=Math.max(0,Math.min(1-height,p.y+p.height/2-height/2));changeMedia({x,y,width,height});};$('mediaScale').onchange=()=>flushMedia().catch(e=>feedback(e.message,true));
for(const id of ['mediaPlay','mediaPause','mediaStop','mediaSaveToggle','mediaSave'])$(id).disabled=true;
try{const saved=localStorage.getItem(mediaDraftKey);if(saved&&saved.length<2500)chooseMedia(JSON.parse(saved)).catch(()=>localStorage.removeItem(mediaDraftKey));}catch{localStorage.removeItem(mediaDraftKey);}
document.addEventListener('visibilitychange',()=>{if(document.hidden)$('mediaPreview').querySelector('video')?.pause();});
