/*
 * Utilitários dos roteiros de duas pessoas (WebSocket + REST): chamadas autenticadas, conexão WebSocket que guarda os
 * eventos recebidos, espera por condição e contagem PASS/FAIL. Quem usa: t_retro_duas_pessoas.js,
 * t_brainstorming_duas_pessoas.js, t_radar_duas_pessoas.js, t_poker_duas_pessoas.js, t_vinculo_duas_pessoas.js.
 *
 * Pré-requisito: python t_retro.py (cria as contas e o tokens.json) e npm install ws. Use sempre um banco de teste.
 * Quem é quem: ana (Agile Master da ANATIME = liderança), bruno (dev da ANATIME), carla (QA da ANATIME),
 * dani (fora da squad) e adm (ADMIN).
 */
const fs = require('fs');
const path = require('path');
const WebSocket = require(process.env.WS_MODULE || 'ws');

const BASE = (process.env.API_BASE || 'http://localhost:8002/api').replace(/\/$/, '');
const WS_BASE = process.env.WS_BASE || BASE.replace(/^http/, 'ws').replace(/\/api$/, '');
const tokens = JSON.parse(fs.readFileSync(path.join(__dirname, 'tokens.json'), 'utf8'));
const conta = (n) => tokens[n + '@totvs.com.br'];
const SQUAD = 'ANATIME';
const results = [];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const uuid = () => require('crypto').randomUUID();

function check(name, cond, detail = '') {
  results.push([name, !!cond, detail]);
  const txt = typeof detail === 'string' ? detail : JSON.stringify(detail);
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (cond ? '' : '  -> ' + String(txt).slice(0, 300)));
}

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

// Conexão WebSocket de uma pessoa: guarda os eventos recebidos em .ev. params: 'presence=1&typing=1' etc.
function conectar(modulo, id, quem, params = '') {
  return new Promise((resolve) => {
    const ev = [];
    const url = `${WS_BASE}/ws/${modulo}/${encodeURIComponent(id)}?token=${encodeURIComponent(quem.token)}${params ? '&' + params : ''}`;
    const ws = new WebSocket(url);
    ws.on('message', (m) => { try { ev.push(JSON.parse(m.toString())); } catch (e) { /* ignora */ } });
    ws.on('open', () => resolve({ ws, ev, quem }));
    ws.on('error', () => resolve(null));
    setTimeout(() => resolve(null), 8000);
  });
}
const doTipo = (c, tipo) => c.ev.filter((e) => e.type === tipo);
const ultimo = (c, tipo) => doTipo(c, tipo).slice(-1)[0];
const enviar = (c, obj) => c.ws.send(JSON.stringify(obj));
async function esperar(cond, ms = 6000) {
  const fim = Date.now() + ms;
  while (Date.now() < fim) { if (cond()) return true; await sleep(150); }
  return false;
}
const fechar = (...cs) => cs.forEach((c) => { try { c && c.ws.close(); } catch (e) { /* ignora */ } });

function resumo(titulo) {
  const ok = results.filter((r) => r[1]).length;
  console.log(`\nRESULTADO ${titulo}: ${ok}/${results.length}`);
  results.filter((r) => !r[1]).forEach((r) => console.log('  FALHOU:', r[0], '|', String(typeof r[2] === 'string' ? r[2] : JSON.stringify(r[2])).slice(0, 220)));
  return ok === results.length;
}

module.exports = { BASE, WS_BASE, SQUAD, conta, check, api, conectar, doTipo, ultimo, enviar, esperar, fechar, sleep, uuid, resumo, results };
