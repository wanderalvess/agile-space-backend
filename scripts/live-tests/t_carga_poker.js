/*
 * Carga numa sala do Poker com várias pessoas logadas: cada conta de contas-teste.json entra na sala, abre o
 * WebSocket, manda heartbeat como a tela (a cada 25 s) e vota em todas as rodadas; a primeira conta cria a sala,
 * revela e abre a rodada seguinte. Mede o tempo das chamadas e quanto o aviso em tempo real leva para chegar
 * aos outros (voto e revelação).
 *
 * Pré-requisito: python criar_contas_teste.py (gera contas-teste.json) e npm install ws (já feito para t_ws.js).
 *
 * Uso:
 *   node t_carga_poker.js                       # todas as contas, 3 min
 *   node t_carga_poker.js --pessoas 30 --duracao 300 --rodada 20 --squad ANATIME
 *   node t_carga_poker.js --manter              # não exclui a sala no fim
 *
 * Limite do servidor: 300 chamadas de API por minuto por IP. De uma só máquina, o script espaça as rodadas
 * para caber nisso e avisa se não couber (com muitas pessoas, rode de mais de uma máquina).
 */
const WebSocket = require(process.env.WS_MODULE || 'ws');
const fs = require('fs');
const path = require('path');

const arg = (name, def) => {
  const i = process.argv.indexOf('--' + name);
  if (i < 0) return def;
  const v = process.argv[i + 1];
  return v === undefined || v.startsWith('--') ? true : v;
};
const ARQ = arg('contas', path.join(__dirname, 'contas-teste.json'));
const dados = JSON.parse(fs.readFileSync(ARQ, 'utf8'));
const BASE = String(arg('base', dados.base || process.env.API_BASE || 'http://localhost:8002/api')).replace(/\/$/, '');
const WS_BASE = String(arg('ws', BASE.replace(/^http/, 'ws').replace(/\/api$/, '')));
const contas = dados.contas.slice(0, Number(arg('pessoas', dados.contas.length)));
const DURACAO = Number(arg('duracao', 180)) * 1000;
const RAMPA = Number(arg('rampa', 20)) * 1000;
const SQUAD = arg('squad', undefined);
const MANTER = !!arg('manter', false);
const HEARTBEAT_MS = 25000;
const LIMITE_MIN = 270; // 300/min do servidor, com folga

if (contas.length < 2) { console.error('Precisa de pelo menos 2 contas em ' + ARQ); process.exit(1); }

// Rodada mínima para caber no limite: heartbeats + votos + ações do facilitador por minuto.
const porMinutoFixo = contas.length * 60000 / HEARTBEAT_MS;
const restante = LIMITE_MIN - porMinutoFixo;
const rodadaMin = restante > 0 ? Math.ceil(60 * (contas.length + 4) / restante) : Infinity;
let RODADA = Math.max(Number(arg('rodada', 20)), rodadaMin);
if (!isFinite(RODADA)) {
  console.error(`${contas.length} pessoas só de heartbeat já passam de ${LIMITE_MIN} chamadas/min por IP. Use menos pessoas por máquina.`);
  process.exit(1);
}
RODADA *= 1000;

// ---------- métricas ----------
const http = {};            // tipo -> { ms: [], status: {} }
const atrasoVoto = [];      // ms entre o POST do voto e o VOTE_SAVED chegar em outra pessoa
const atrasoRevelar = [];   // ms entre o POST da revelação e o ROOM_UPDATED revelado chegar
let wsAbertos = 0, wsFalhas = 0, wsCaidas = 0, eventos = 0, rodadas = 0;
const t0Voto = new Map();   // participantId -> instante do POST do voto da rodada
let t0Revelar = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const agora = () => Number(process.hrtime.bigint() / 1000000n);
const pct = (v, q) => (v.length ? v[Math.min(v.length - 1, Math.floor(q * v.length))] : NaN);

async function api(tipo, metodo, p, conta, corpo) {
  const t = agora();
  let status = 'erro', json = null;
  try {
    const r = await fetch(BASE + p, {
      method: metodo,
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + conta.token },
      body: corpo ? JSON.stringify(corpo) : undefined,
    });
    status = r.status;
    const txt = await r.text();
    try { json = txt ? JSON.parse(txt) : null; } catch (e) { json = null; }
  } catch (e) {
    status = e.cause?.code || e.name;
  }
  const h = (http[tipo] ||= { ms: [], status: {} });
  h.ms.push(agora() - t);
  h.status[status] = (h.status[status] || 0) + 1;
  return [status, json];
}

function abrirWs(conta, roomId) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`${WS_BASE}/ws/poker/${encodeURIComponent(roomId)}?token=${encodeURIComponent(conta.token)}`);
    let pronto = false;
    const fim = (ok) => { if (!pronto) { pronto = true; ok ? wsAbertos++ : wsFalhas++; resolve(ok ? ws : null); } };
    ws.on('open', () => fim(true));
    ws.on('unexpected-response', () => fim(false));
    ws.on('error', () => fim(false));
    ws.on('close', () => { if (pronto && !conta.saindo) wsCaidas++; });
    ws.on('message', (m) => {
      eventos++;
      let ev; try { ev = JSON.parse(m.toString()); } catch (e) { return; }
      const p = ev.payload || {};
      if (ev.type === 'VOTE_SAVED' && p.participantId && p.participantId !== conta.id && t0Voto.has(p.participantId)) {
        atrasoVoto.push(agora() - t0Voto.get(p.participantId));
      }
      if (ev.type === 'ROOM_UPDATED' && p.votesRevealed && t0Revelar) atrasoRevelar.push(agora() - t0Revelar);
    });
    setTimeout(() => fim(false), 15000);
  });
}

(async () => {
  const fac = contas[0];
  const roomId = 'carga-' + Date.now().toString(36);
  console.log(`Servidor: ${BASE}  ·  ${contas.length} pessoas  ·  ${DURACAO / 1000}s  ·  rodada a cada ${RODADA / 1000}s`);
  if (RODADA / 1000 > Number(arg('rodada', 20))) console.log(`(rodada alongada para caber em ${LIMITE_MIN} chamadas/min por IP)`);

  const [stSala] = await api('sala', 'POST', '/poker', fac, {
    id: roomId, title: 'Carga ' + new Date().toISOString().slice(0, 16), deckType: 'fibonacci', mode: 'sync',
    creatorId: fac.id, ...(SQUAD ? { team: SQUAD } : {}),
  });
  if (stSala !== 201 && stSala !== 200) { console.error('Não consegui criar a sala: ' + stSala); process.exit(1); }

  // Entrada escalonada (ninguém entra no mesmo milissegundo numa reunião de verdade).
  const pessoas = [];
  const fimTeste = Date.now() + DURACAO;
  await Promise.all(contas.map(async (conta, i) => {
    await sleep((RAMPA * i) / contas.length);
    const [st] = await api('entrar', 'POST', `/poker/${roomId}/participants`, conta,
      { id: conta.id, nickname: conta.nome || conta.email, role: i === 0 ? 'organizador' : 'Developer' });
    if (st !== 201 && st !== 200) return;
    const ws = await abrirWs(conta, roomId);
    const p = { conta, ws };
    p.batida = setInterval(() => api('heartbeat', 'POST', `/poker/${roomId}/heartbeat/${conta.id}`, conta), HEARTBEAT_MS);
    pessoas.push(p);
  }));
  console.log(`Na sala: ${pessoas.length}/${contas.length}  ·  WebSocket abertos: ${wsAbertos}, falhas: ${wsFalhas}`);

  const cartas = ['1', '2', '3', '5', '8', '13'];
  while (Date.now() + RODADA <= fimTeste) {
    const inicio = Date.now();
    t0Voto.clear();
    await Promise.all(pessoas.map(async (p) => {
      await sleep(Math.random() * Math.min(5000, RODADA / 3));
      t0Voto.set(p.conta.id, agora());
      await api('votar', 'POST', `/poker/${roomId}/votes`, p.conta,
        { participantId: p.conta.id, value: cartas[Math.floor(Math.random() * cartas.length)] });
    }));
    await sleep(1000);
    const [, sala] = await api('ler sala', 'GET', `/poker/${roomId}`, fac);
    if (sala) {
      t0Revelar = agora();
      await api('revelar', 'POST', '/poker', fac, { ...sala, votesRevealed: true });
    }
    await sleep(2000);
    t0Revelar = 0;
    await api('limpar', 'DELETE', `/poker/${roomId}/votes`, fac);
    const [, sala2] = await api('ler sala', 'GET', `/poker/${roomId}`, fac);
    if (sala2) await api('nova rodada', 'POST', '/poker', fac, { ...sala2, votesRevealed: false, currentTopic: 'Item ' + (rodadas + 2) });
    rodadas++;
    process.stdout.write(`  rodada ${rodadas} ok (${Math.round((Date.now() - inicio) / 1000)}s)\n`);
    await sleep(Math.max(0, RODADA - (Date.now() - inicio)));
  }

  for (const p of pessoas) { p.conta.saindo = true; clearInterval(p.batida); p.ws?.close(); }
  await Promise.all(pessoas.map((p) => api('sair', 'DELETE', `/poker/${roomId}/participants/${p.conta.id}`, p.conta)));
  if (!MANTER) await api('excluir sala', 'DELETE', `/poker/${roomId}`, fac);

  console.log(`\n${rodadas} rodadas  ·  eventos recebidos: ${eventos}  ·  WebSocket caídos durante o teste: ${wsCaidas}`);
  for (const [tipo, h] of Object.entries(http)) {
    const v = h.ms.sort((a, b) => a - b);
    console.log(`${tipo.padEnd(12)} n=${String(v.length).padStart(5)}  p50=${String(pct(v, .5)).padStart(5)}ms  p95=${String(pct(v, .95)).padStart(5)}ms  max=${String(v[v.length - 1]).padStart(5)}ms  ${JSON.stringify(h.status)}`);
  }
  const vs = atrasoVoto.sort((a, b) => a - b), rs = atrasoRevelar.sort((a, b) => a - b);
  console.log(`tempo real: voto chega aos outros p50=${pct(vs, .5)}ms p95=${pct(vs, .95)}ms (n=${vs.length})  ·  revelação p50=${pct(rs, .5)}ms p95=${pct(rs, .95)}ms (n=${rs.length})`);
  const bloqueadas = Object.values(http).reduce((n, h) => n + (h.status[429] || 0), 0);
  if (bloqueadas) console.log(`ATENÇÃO: ${bloqueadas} chamadas levaram 429 (limite por IP). Rode com menos pessoas ou de mais de uma máquina.`);
  console.log(MANTER ? `Sala mantida: ${roomId}` : 'Sala excluída no fim.');
  process.exit(0);
})();
