const WebSocket = require(process.env.WS_MODULE || 'ws');
const fs = require('fs');
const path = require('path');
const dir = __dirname;
const tokens = JSON.parse(fs.readFileSync(path.join(dir, 'tokens.json'), 'utf8'));
const boardId = process.argv[2];
const BASE = process.env.API_BASE || 'http://localhost:8002/api';
const out = [];
const log = (ok, name, d) => { out.push(ok); console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '  -> ' + d)); };

function open(email, board) {
  return new Promise(resolve => {
    const ws = new WebSocket(`${(process.env.WS_BASE || 'ws://localhost:8002')}/ws/retro/${board}?token=${encodeURIComponent(tokens[email].token)}`);
    const events = [];
    ws.on('message', m => { try { events.push(JSON.parse(m.toString())); } catch (e) {} });
    ws.on('open', () => resolve({ ws, events, opened: true }));
    ws.on('unexpected-response', (req, res) => resolve({ ws, events, opened: false, status: res.statusCode }));
    ws.on('error', () => resolve({ ws, events, opened: false, status: 'erro' }));
    setTimeout(() => resolve({ ws, events, opened: false, status: 'timeout' }), 6000);
  });
}
async function api(method, p, email, body) {
  const r = await fetch(BASE + p, { method, headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tokens[email].token }, body: body ? JSON.stringify(body) : undefined });
  let j = null; try { j = await r.json(); } catch (e) {}
  return [r.status, j];
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const bruno = await open('bruno.dev@totvs.com.br', boardId);
  log(bruno.opened, 'participante abre o WebSocket do quadro', bruno.status);
  const dani = await open('dani.fora@totvs.com.br', boardId);
  log(!dani.opened, 'intruso (fora da squad) NÃO abre o WebSocket', 'abriu');
  const ghost = await open('bruno.dev@totvs.com.br', 'quadro-que-nao-existe');
  log(!ghost.opened, 'quadro inexistente NÃO abre o WebSocket', 'abriu');

  // novo card + voto: o outro cliente precisa receber os eventos
  const [, cards] = await api('GET', `/retros/${boardId}/cards`, 'ana.master@totvs.com.br');
  await api('PATCH', `/retros/${boardId}`, 'bruno.dev@totvs.com.br', { votingStatus: 'active' }); // bruno é facilitador após transfer-control
  await sleep(300);
  const evTypes1 = bruno.events.map(e => e.type);
  log(evTypes1.includes('BOARD_UPDATED'), 'BOARD_UPDATED chega ao abrir votação', evTypes1);
  const target = cards.find(c => c.columnKey !== 'acao');
  const before = bruno.events.length;
  const [s, voted] = await api('POST', `/retros/${boardId}/cards/${target.id}/vote`, 'carla.qa@totvs.com.br');
  await sleep(400);
  const got = bruno.events.slice(before).find(e => e.type === 'CARD_SAVED' && e.payload && e.payload.id === target.id);
  log(s === 200 && !!got, 'voto da Carla gera CARD_SAVED para os conectados', JSON.stringify({ s, evs: bruno.events.slice(before).map(e => e.type) }));
  // o estado enviado já inclui o voto (evento só depois do commit)
  log(got && (got.payload.votes || []).includes(tokens['carla.qa@totvs.com.br'].id), 'evento já traz o voto confirmado (pós-commit)', JSON.stringify(got && got.payload.votes));

  // card novo
  const before2 = bruno.events.length;
  const cid = crypto.randomUUID();
  await api('POST', `/retros/${boardId}/cards`, 'carla.qa@totvs.com.br', { id: cid, boardId, columnKey: 'bom', content: 'Evento ao vivo', authorId: tokens['carla.qa@totvs.com.br'].id, votes: [], order: 5 });
  await sleep(300);
  log(bruno.events.slice(before2).some(e => e.type === 'CARD_SAVED' && e.payload.id === cid), 'card novo chega por CARD_SAVED', bruno.events.slice(before2).map(e => e.type));
  const before3 = bruno.events.length;
  await api('DELETE', `/retros/${boardId}/cards/${cid}`, 'bruno.dev@totvs.com.br');
  await sleep(300);
  log(bruno.events.slice(before3).some(e => e.type === 'CARD_DELETED'), 'exclusão chega por CARD_DELETED', bruno.events.slice(before3).map(e => e.type));
  // reset
  const before4 = bruno.events.length;
  await api('POST', `/retros/${boardId}/votes/reset`, 'bruno.dev@totvs.com.br');
  await sleep(500);
  const t4 = bruno.events.slice(before4).map(e => e.type);
  log(t4.includes('BOARD_UPDATED'), 'reset de votação publica BOARD_UPDATED', t4);
  bruno.ws.close();
  console.log('RESULTADO ws: ' + out.filter(Boolean).length + '/' + out.length);
  process.exit(0);
})();
