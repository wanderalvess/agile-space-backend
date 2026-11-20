/*
 * Review com duas pessoas ao mesmo tempo (WebSocket + REST): presença, apresentador, edição concorrente de card
 * (controle otimista por versão) e trava de decisão.
 *
 * Pré-requisito: python t_retro.py (cria as contas e o tokens.json) e npm install ws. Use sempre um banco de teste.
 * Quem é quem: ana (Agile Master da ANATIME = liderança), bruno (dev da ANATIME), dani (fora da squad).
 *
 *   API_BASE=http://localhost:8002/api node t_review_duas_pessoas.js
 */
const WebSocket = require(process.env.WS_MODULE || 'ws');
const fs = require('fs');
const path = require('path');

const BASE = (process.env.API_BASE || 'http://localhost:8002/api').replace(/\/$/, '');
const WS_BASE = (process.env.WS_BASE || BASE.replace(/^http/, 'ws').replace(/\/api$/, ''));
const tokens = JSON.parse(fs.readFileSync(path.join(__dirname, 'tokens.json'), 'utf8'));
const conta = (n) => tokens[n + '@totvs.com.br'];
const ana = conta('ana.master'), bruno = conta('bruno.dev'), dani = conta('dani.fora');
const SQUAD = 'ANATIME';

const results = [];
const check = (name, cond, detail = '') => {
  results.push([name, !!cond, detail]);
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (cond ? '' : '  -> ' + String(typeof detail === 'string' ? detail : JSON.stringify(detail)).slice(0, 300)));
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(metodo, p, quem, corpo) {
  const r = await fetch(BASE + p, {
    method: metodo,
    headers: { 'Content-Type': 'application/json', ...(quem ? { Authorization: 'Bearer ' + quem.token } : {}) },
    body: corpo === undefined ? undefined : JSON.stringify(corpo),
  });
  const txt = await r.text();
  let json = null;
  try { json = txt ? JSON.parse(txt) : null; } catch (e) { json = txt; }
  return [r.status, json];
}

// Conexão WebSocket de uma pessoa, guardando os eventos recebidos.
function conectar(quem, sid) {
  return new Promise((resolve) => {
    const ev = [];
    const ws = new WebSocket(`${WS_BASE}/ws/showcase/${encodeURIComponent(sid)}?token=${encodeURIComponent(quem.token)}&presence=1`);
    ws.on('message', (m) => { try { ev.push(JSON.parse(m.toString())); } catch (e) { /* ignora */ } });
    ws.on('open', () => resolve({ ws, ev }));
    ws.on('error', () => resolve(null));
    setTimeout(() => resolve(null), 8000);
  });
}
const ultimo = (c, tipo) => [...c.ev].reverse().find((e) => e.type === tipo);
async function esperar(cond, ms = 6000) {
  const fim = Date.now() + ms;
  while (Date.now() < fim) { if (cond()) return true; await sleep(150); }
  return false;
}

(async () => {
  const sid = 'rev2-' + Math.random().toString(36).slice(2, 10);
  const t1 = sid + '-t1', t2 = sid + '-t2';
  const card = (id, key, titulo) => ({ id, sessionId: sid, key, title: titulo, status: 'pending', order: 0, type: 'feature' });
  let [s, j] = await api('POST', '/showcase-sessions', ana, {
    id: sid, name: 'TESTE Review duas pessoas', sprintName: 'S1', status: 'active', squadName: SQUAD,
    tasks: [card(t1, 'T1', 'Entrega 1'), card(t2, 'T2', 'Entrega 2')], members: [],
  });
  check('ana cria a Review com 2 cards', s === 200, [s, j]);

  // ---------------- presença ----------------
  const a = await conectar(ana, sid);
  const b = await conectar(bruno, sid);
  check('as duas pessoas conectam no WebSocket', a && b);
  if (!a || !b) { process.exit(1); }
  const dois = await esperar(() => (ultimo(a, 'PRESENCE_UPDATED')?.payload?.total || 0) >= 2);
  check('ana vê 2 pessoas online (PRESENCE_UPDATED)', dois, a.ev.filter((e) => e.type === 'PRESENCE_UPDATED').map((e) => e.payload));
  const pa = ultimo(a, 'PRESENCE_UPDATED')?.payload;
  check('os dois ids aparecem em online', pa && pa.online.includes(ana.id) && pa.online.includes(bruno.id), pa);
  [s, j] = await api('GET', `/presence/showcase/${sid}`, dani);
  check('REST de presença: quem tem o link lê (200, total 2)', s === 200 && j.total === 2, [s, j]);
  b.ws.close();
  const um = await esperar(() => (ultimo(a, 'PRESENCE_UPDATED')?.payload?.total ?? 9) === 1, 8000);
  check('bruno sai e a ana passa a ver 1 pessoa online', um, ultimo(a, 'PRESENCE_UPDATED'));
  const b2 = await conectar(bruno, sid);
  check('bruno volta e a ana vê 2 de novo', await esperar(() => (ultimo(a, 'PRESENCE_UPDATED')?.payload?.total || 0) >= 2), ultimo(a, 'PRESENCE_UPDATED'));

  // ---------------- apresentador ----------------
  [s, j] = await api('PUT', `/showcase-sessions/${sid}/presenter`, ana, { cardId: t1, step: 'cover' });
  check('ana (criadora) vira apresentadora', s === 200 && j && j.userId === ana.id, [s, j]);
  check('bruno recebe PRESENTER_UPDATED em tempo real', await esperar(() => ultimo(b2, 'PRESENTER_UPDATED')?.payload?.presenter?.cardId === t1), ultimo(b2, 'PRESENTER_UPDATED'));
  [s, j] = await api('GET', `/showcase-sessions/${sid}/presenter`, bruno);
  check('bruno lê o apresentador atual (card 1)', s === 200 && j.cardId === t1, [s, j]);
  [s, j] = await api('PUT', `/showcase-sessions/${sid}/presenter`, bruno, { cardId: t2 });
  check('bruno (dev, sem papel) NÃO assume a apresentação (403)', s === 403, [s, j]);
  [s, j] = await api('PUT', `/showcase-sessions/${sid}/presenter`, ana, { cardId: t2, step: 'demo' });
  check('ana avança para o card 2', s === 200 && j.cardId === t2, [s, j]);
  check('bruno recebe o card 2', await esperar(() => ultimo(b2, 'PRESENTER_UPDATED')?.payload?.presenter?.cardId === t2), ultimo(b2, 'PRESENTER_UPDATED'));
  [s, j] = await api('PUT', `/showcase-sessions/${sid}/presenter`, ana, { cardId: 'card-que-nao-existe' });
  check('card inexistente é recusado (404)', s === 404, [s, j]);
  [s] = await api('PUT', `/showcase-sessions/${sid}/presenter`, ana, { step: 'nome com espaço!' });
  check('step inválido é recusado (400)', s === 400, s);
  [s] = await api('PUT', `/showcase-sessions/${sid}/presenter`, null, { cardId: t1 });
  check('sem login não apresenta (401)', s === 401, s);
  [s] = await api('DELETE', `/showcase-sessions/${sid}/presenter`, ana);
  check('ana encerra a apresentação (204)', s === 204, s);
  check('bruno recebe o fim da apresentação', await esperar(() => { const e = ultimo(b2, 'PRESENTER_UPDATED'); return e && (e.payload == null || e.payload.presenter == null); }), ultimo(b2, 'PRESENTER_UPDATED'));
  [s] = await api('GET', `/showcase-sessions/${sid}/presenter`, bruno);
  check('sem apresentador a leitura devolve 204', s === 204, s);

  // ---------------- edição concorrente de card ----------------
  const lido = async (quem) => { const [, sess] = await api('GET', '/showcase-sessions/' + sid, quem); return sess.tasks.find((t) => t.id === t1); };
  const va = await lido(ana), vb = await lido(bruno);
  check('as duas leem a mesma versão do card', va.version === vb.version, [va.version, vb.version]);
  [s, j] = await api('PATCH', `/showcase-sessions/${sid}/cards/${t1}`, bruno, { version: vb.version, title: 'Entrega 1 (editada pelo Bruno)' });
  check('bruno edita o card com a versão certa (200, versão sobe)', s === 200 && j.version === vb.version + 1, [s, j && j.version]);
  [s, j] = await api('PATCH', `/showcase-sessions/${sid}/cards/${t1}`, ana, { version: va.version, title: 'Entrega 1 (editada pela Ana)' });
  check('ana edita com a versão antiga: conflito (409) e recebe o card atual', s === 409, [s, j]);
  const conflito = j && (j.card || j.current || j);
  check('o 409 traz o título do Bruno (nada se perde em silêncio)', JSON.stringify(conflito).includes('editada pelo Bruno'), conflito);
  const nova = await lido(ana);
  [s, j] = await api('PATCH', `/showcase-sessions/${sid}/cards/${t1}`, ana, { version: nova.version, description: 'texto da Ana' });
  check('ana refaz com a versão nova (200) e o título do Bruno continua', s === 200 && j.title.includes('Bruno'), [s, j && j.title]);
  [s] = await api('PATCH', `/showcase-sessions/${sid}/cards/${t1}`, ana, { title: 'sem versão' });
  check('PATCH sem version é recusado (400)', s === 400, s);

  // ---------------- trava de decisão ----------------
  let atual = await lido(ana);
  [s, j] = await api('PATCH', `/showcase-sessions/${sid}/cards/${t1}`, bruno, { version: atual.version, decision: 'approved', feedback: 'ok' });
  check('bruno (dev) NÃO decide (403 DECISION_RESTRICTED)', s === 403 && j && j.code === 'DECISION_RESTRICTED', [s, j]);
  atual = await lido(ana);
  check('nada foi gravado pela tentativa barrada', !atual.decision || atual.decision === 'open' || atual.decision === 'pending', atual.decision);
  [s, j] = await api('PATCH', `/showcase-sessions/${sid}/cards/${t1}`, ana, { version: atual.version, decision: 'approved', feedback: 'ok' });
  check('ana (liderança) decide (200)', s === 200 && j.decision === 'approved', [s, j && j.decision]);
  check('a decisão termina em Z (com fuso)', /Z$/.test(j.decidedAt || ''), j && j.decidedAt);
  atual = await lido(bruno);
  check('bruno vê a decisão da Ana', atual.decision === 'approved', atual.decision);

  a.ws.close(); b2.ws.close();
  const ok = results.filter((r) => r[1]).length;
  console.log(`\nRESULTADO: ${ok}/${results.length}  (Review de teste: ${sid})`);
  results.filter((r) => !r[1]).forEach((r) => console.log('  FALHOU:', r[0], '|', String(typeof r[2] === 'string' ? r[2] : JSON.stringify(r[2])).slice(0, 200)));
  process.exit(0);
})();
