/*
 * Retro com duas pessoas (WebSocket + REST): presença, cartão em tempo real, "escrevendo…" (TYPING), nota do check-in,
 * encerrar/reabrir, voto com limite por painel e assumir controle. Contrato: docs/ceremony-radar-retro-api.md e
 * docs/presence-api.md.
 *
 *   API_BASE=http://localhost:8002/api node t_retro_duas_pessoas.js
 */
const L = require('./lib_duas_pessoas');
const { check, api, conectar, ultimo, doTipo, enviar, esperar, fechar, sleep, uuid } = L;

(async () => {
  const ana = L.conta('ana.master'), bruno = L.conta('bruno.dev'), dani = L.conta('dani.fora'), adm = L.conta('adm.root');
  const rid = uuid();
  const colunas = [{ id: 'bom', title: 'Bom', theme: 'success', order: 0 }, { id: 'ruim', title: 'Ruim', theme: 'warning', order: 1 },
    { id: 'acao', title: 'Ações', theme: 'action', order: 2 }];
  let [s, j] = await api('POST', '/retros', ana, {
    id: rid, creatorId: ana.id, isCardsRevealed: false, votingStatus: 'disabled', maxVotesPerParticipant: 2, isAuthorsRevealed: false,
    timer: { status: 'stopped', endTime: null, initialDuration: 300, remainingOnPause: 300 },
    title: 'TESTE Retro duas pessoas', team: L.SQUAD, squadId: L.SQUAD, createdAt: '2026-10-17T10:00:00Z',
    participantIds: [ana.id], columns: colunas, templateKey: 'custom',
  });
  check('ana cria a retro', s === 200 || s === 201, [s, j]);
  for (const p of [ana, bruno]) {
    [s] = await api('POST', `/retros/${rid}/participants`, p, { id: p.id, boardId: rid, nickname: p === ana ? 'Ana' : 'Bruno', role: 'DEV', isCreator: p === ana });
  }
  const card = (quem, texto, col = 'bom') => ({ id: uuid(), boardId: rid, columnKey: col, content: texto, authorId: quem.id, votes: [], order: 1000 });

  // ---------------- presença ----------------
  const a = await conectar('retro', rid, ana, 'presence=1&typing=1');
  const b = await conectar('retro', rid, bruno, 'presence=1&typing=1');
  check('as duas pessoas conectam (presence=1&typing=1)', a && b);
  if (!a || !b) process.exit(1);
  const dois = await esperar(() => (ultimo(a, 'PRESENCE_UPDATED')?.payload?.total || 0) >= 2);
  const pa = ultimo(a, 'PRESENCE_UPDATED')?.payload;
  check('ana vê 2 online com os dois ids', dois && pa.online.includes(ana.id) && pa.online.includes(bruno.id), a.ev.filter((e) => e.type === 'PRESENCE_UPDATED').map((e) => e.payload));
  [s, j] = await api('GET', `/presence/retro/${rid}`, bruno);
  check('REST de presença da retro: participante lê (200, total 2)', s === 200 && j.total === 2, [s, j]);
  [s] = await api('GET', `/presence/retro/${rid}`, dani);
  check('REST de presença da retro: quem não é participante NÃO lê (403)', s === 403, s);
  fechar(b);
  check('bruno sai e a ana passa a ver 1 online', await esperar(() => ultimo(a, 'PRESENCE_UPDATED')?.payload?.total === 1, 8000), a.ev.filter((e) => e.type === 'PRESENCE_UPDATED').map((e) => e.payload));
  const b2 = await conectar('retro', rid, bruno, 'presence=1&typing=1');
  check('bruno volta e a ana vê 2 de novo', await esperar(() => ultimo(a, 'PRESENCE_UPDATED')?.payload?.total === 2), ultimo(a, 'PRESENCE_UPDATED'));

  // ---------------- cartão em tempo real ----------------
  const c1 = card(bruno, 'cartão do Bruno em tempo real');
  const antes = a.ev.length;
  [s] = await api('POST', `/retros/${rid}/cards`, bruno, c1);
  check('bruno cria um cartão (201)', s === 200 || s === 201, s);
  check('a ana recebe o cartão do Bruno pelo WebSocket', await esperar(() => a.ev.slice(antes).some((e) => JSON.stringify(e).includes(c1.id))), a.ev.slice(antes).map((e) => e.type));
  const recebido = a.ev.slice(antes).map((e) => JSON.stringify(e)).find((t) => t.includes(c1.id)) || '';
  check('com autores ocultos o evento não revela o autor para a Ana (authorId do cartão alheio)', !recebido.includes(bruno.id), recebido.slice(0, 200));

  // ---------------- TYPING ----------------
  const tipoA0 = doTipo(a, 'TYPING').length, tipoB0 = doTipo(b2, 'TYPING').length;
  enviar(b2, { type: 'TYPING', scope: 'card', columnKey: 'bom', userId: 'forjado' });
  check('ana recebe TYPING do Bruno', await esperar(() => doTipo(a, 'TYPING').length > tipoA0), doTipo(a, 'TYPING'));
  const t1 = doTipo(a, 'TYPING').slice(-1)[0]?.payload;
  check('autores ocultos: userId nulo e anonymous true', t1 && t1.userId == null && t1.anonymous === true, t1);
  check('expiresInMs é 5000 e o escopo é card', t1 && t1.expiresInMs === 5000 && t1.scope === 'card', t1);
  await sleep(500);
  check('o remetente NÃO recebe o próprio TYPING', doTipo(b2, 'TYPING').length === tipoB0, doTipo(b2, 'TYPING'));
  const antesLimite = doTipo(a, 'TYPING').length;
  for (let i = 0; i < 4; i++) enviar(b2, { type: 'TYPING', scope: 'card', columnKey: 'bom' });
  await sleep(700);
  check('limite de 1 TYPING a cada 2 s: 4 envios seguidos não chegam à Ana', doTipo(a, 'TYPING').length === antesLimite, doTipo(a, 'TYPING').length - antesLimite);
  await sleep(2100);
  enviar(b2, { type: 'TYPING', scope: 'card' });
  check('passados 2 s, o próximo TYPING chega', await esperar(() => doTipo(a, 'TYPING').length === antesLimite + 1), doTipo(a, 'TYPING').length - antesLimite);
  enviar(b2, 'isto não é JSON');
  b2.ws.send('{"type":"TYPING","scope":"inventado"}');
  await sleep(300);
  check('JSON inválido e scope desconhecido são ignorados sem derrubar a conexão', b2.ws.readyState === 1, b2.ws.readyState);
  [s] = await api('PATCH', `/retros/${rid}`, ana, { isAuthorsRevealed: true });
  await sleep(2200);
  const antesRev = doTipo(a, 'TYPING').length;
  enviar(b2, { type: 'TYPING', scope: 'card', columnKey: 'bom' });
  await esperar(() => doTipo(a, 'TYPING').length > antesRev);
  const t2 = doTipo(a, 'TYPING').slice(-1)[0]?.payload;
  check('com autores revelados o TYPING traz o userId do Bruno (do JWT, não do corpo)', t2 && t2.userId === bruno.id && t2.anonymous === false, t2);
  await sleep(2200);
  enviar(b2, { type: 'TYPING', scope: 'chat', channelId: `dm_${bruno.id}_${dani.id}` });
  await sleep(500);
  check('TYPING em DM entre Bruno e Dani NÃO chega à Ana (canal sem acesso)', doTipo(a, 'TYPING').slice(-1)[0]?.payload?.scope !== 'chat', doTipo(a, 'TYPING').slice(-1)[0]?.payload);
  await sleep(2100);
  enviar(b2, { type: 'TYPING', scope: 'chat', channelId: 'geral' });
  check('TYPING no chat "geral" chega à Ana com scope chat', await esperar(() => doTipo(a, 'TYPING').slice(-1)[0]?.payload?.scope === 'chat'), doTipo(a, 'TYPING').slice(-1)[0]?.payload);
  const semOptIn = await conectar('retro', rid, ana, 'presence=1');
  await sleep(2100);
  enviar(b2, { type: 'TYPING', scope: 'card' });
  await sleep(700);
  check('quem não abriu com typing=1 NÃO recebe TYPING', doTipo(semOptIn, 'TYPING').length === 0, doTipo(semOptIn, 'TYPING').length);
  fechar(semOptIn);

  // ---------------- nota livre do check-in ----------------
  const nota = (quem, corpo) => api('POST', `/retros/${rid}/participants`, quem, { id: quem.id, boardId: rid, nickname: 'Bruno', role: 'DEV', ...corpo });
  [s] = await nota(bruno, { checkinNote: 'x'.repeat(501) });
  check('check-in com 501 caracteres é recusado (400)', s === 400, s);
  [s] = await nota(bruno, { checkinNote: 'x'.repeat(500) });
  check('check-in com 500 caracteres é aceito', s === 200 || s === 201, s);
  [s, j] = await nota(bruno, { checkinNote: 'Cansado mas animado' });
  [s, j] = await api('GET', `/retros/${rid}/participants`, ana);
  check('a Ana lê a nota do Bruno em /participants', s === 200 && j.find((p) => p.id === bruno.id)?.checkinNote === 'Cansado mas animado', j);
  await nota(bruno, {});
  [s, j] = await api('GET', `/retros/${rid}/participants`, ana);
  check('campo ausente NÃO apaga a nota', j.find((p) => p.id === bruno.id)?.checkinNote === 'Cansado mas animado', j.find((p) => p.id === bruno.id));
  await nota(bruno, { checkinNote: '   ' });
  [s, j] = await api('GET', `/retros/${rid}/participants`, ana);
  check('texto só com espaços LIMPA a nota', !j.find((p) => p.id === bruno.id)?.checkinNote, j.find((p) => p.id === bruno.id));
  await api('POST', `/retros/${rid}/participants`, bruno, { id: ana.id, boardId: rid, nickname: 'Ana', checkinNote: 'forjada pelo Bruno' });
  [s, j] = await api('GET', `/retros/${rid}/participants`, ana);
  check('o participante só altera a própria nota (id vem do JWT)', !j.find((p) => p.id === ana.id)?.checkinNote, j.find((p) => p.id === ana.id));

  // ---------------- votação com limite por painel ----------------
  const cs = [card(bruno, 'a'), card(bruno, 'b'), card(bruno, 'c'), card(bruno, 'd', 'ruim')];
  for (const c of cs) await api('POST', `/retros/${rid}/cards`, bruno, c);
  [s] = await api('PATCH', `/retros/${rid}`, ana, { isCardsRevealed: true, votingStatus: 'active' });
  const v = (c, quem) => api('POST', `/retros/${rid}/cards/${c.id}/vote`, quem);
  const r = [];
  for (const c of cs) r.push((await v(c, bruno))[0]);
  check('limite de 2 votos por painel: 2 passam, o 3º no mesmo painel leva 409, o de outro painel passa', r[0] === 200 && r[1] === 200 && r[2] === 409 && r[3] === 200, r);
  const ev3 = await Promise.all([v(cs[0], ana), v(cs[1], ana), v(cs[2], ana)]);
  check('votos concorrentes da Ana no mesmo painel nunca passam de 2', ev3.map((x) => x[0]).filter((x) => x === 200).length === 2, ev3.map((x) => x[0]));

  // ---------------- encerrar / reabrir ----------------
  [s] = await api('POST', `/retros/${rid}/finish`, bruno);
  check('participante comum NÃO encerra (403)', s === 403, s);
  [s] = await api('POST', `/retros/${rid}/finish`, dani);
  check('quem é de fora NÃO encerra (403/404)', s === 403 || s === 404, s);
  const b0 = doTipo(b2, 'BOARD_UPDATED').length;
  [s, j] = await api('POST', `/retros/${rid}/finish`, ana);
  check('facilitador encerra: finishedAt e lifecycleStatus FINISHED', s === 200 && j.lifecycleStatus === 'FINISHED' && !!j.finishedAt, [s, j && j.lifecycleStatus, j && j.finishedAt]);
  check('o Bruno recebe BOARD_UPDATED do encerramento', await esperar(() => doTipo(b2, 'BOARD_UPDATED').length > b0), doTipo(b2, 'BOARD_UPDATED').length);
  const fa = j.finishedAt;
  [s, j] = await api('POST', `/retros/${rid}/finish`, ana);
  check('encerrar de novo é idempotente (finishedAt é o mesmo instante)', s === 200 && String(j.finishedAt).slice(0, 23) === String(fa).slice(0, 23), [s, j && j.finishedAt, fa]);
  [s] = await api('POST', `/retros/${rid}/cards`, bruno, card(bruno, 'depois de encerrar'));
  check('retro encerrada: participante NÃO escreve cartão (409)', s === 409, s);
  [s] = await v(cs[3], bruno);
  check('retro encerrada: participante NÃO vota (409)', s === 409, s);
  [s] = await api('DELETE', `/retros/${rid}/cards/${cs[0].id}`, bruno);
  check('retro encerrada: participante NÃO apaga cartão (409/403)', s === 409 || s === 403, s);
  [s] = await api('GET', `/retros/${rid}/cards`, bruno);
  check('retro encerrada: participante ainda LÊ os cartões (200)', s === 200, s);
  [s] = await api('POST', `/retros/${rid}/chat`, bruno, { id: uuid(), boardId: rid, channelId: 'geral', userId: bruno.id, userName: 'Bruno', text: 'oi', content: 'oi', createdAt: '2026-10-17T10:00:00Z' });
  check('retro encerrada: o chat segue livre (não é 409)', s !== 409, s);
  [s] = await nota(bruno, { checkinNote: 'depois de encerrar' });
  check('retro encerrada: o check-in segue livre (não é 409)', s !== 409, s);
  [s] = await api('POST', `/retros/${rid}/cards`, ana, card(ana, 'facilitador pode'));
  check('retro encerrada: o facilitador ainda escreve', s === 200 || s === 201, s);
  [s] = await api('POST', `/retros/${rid}/reopen`, bruno);
  check('participante comum NÃO reabre (403)', s === 403, s);
  [s, j] = await api('POST', `/retros/${rid}/reopen`, adm);
  check('ADMIN reabre (finishedAt nulo, OPEN)', s === 200 && j.lifecycleStatus === 'OPEN' && !j.finishedAt, [s, j && j.lifecycleStatus, j && j.finishedAt]);
  [s, j] = await api('POST', `/retros/${rid}/reopen`, ana);
  check('reabrir uma aberta é idempotente', s === 200 && j.lifecycleStatus === 'OPEN', [s, j && j.lifecycleStatus]);
  [s] = await api('POST', `/retros/${rid}/cards`, bruno, card(bruno, 'depois de reabrir'));
  check('retro reaberta: participante volta a escrever', s === 200 || s === 201, s);
  [s] = await api('PATCH', `/retros/${rid}`, bruno, { finishedAt: '2020-01-01T00:00:00', lifecycleStatus: 'FINISHED' });
  [s, j] = await api('GET', `/retros/${rid}`, ana);
  check('finishedAt/lifecycleStatus do corpo do PATCH são ignorados', j.lifecycleStatus === 'OPEN' && !j.finishedAt, [j.lifecycleStatus, j.finishedAt]);

  // ---------------- assumir controle ----------------
  [s, j] = await api('POST', `/retros/${rid}/transfer-control`, bruno);
  check('com a facilitadora CONECTADA o participante NÃO assume o controle (409)', s === 409, [s, j]);
  [s] = await api('POST', `/retros/${rid}/transfer-control`, dani);
  check('quem não está na sala NÃO assume o controle (403/404)', s === 403 || s === 404, s);
  fechar(a);
  await sleep(800);
  [s, j] = await api('POST', `/retros/${rid}/transfer-control`, bruno);
  check('a facilitadora saiu há pouco (tolerância de reconexão de 45 s): ainda 409', s === 409, [s, j]);
  console.log('   ...esperando 46 s da tolerância de reconexão do facilitador');
  await sleep(46000);
  [s, j] = await api('POST', `/retros/${rid}/transfer-control`, bruno);
  check('passada a tolerância, com a facilitadora ausente o Bruno assume o controle (creatorId dele)', s === 200 && j.creatorId === bruno.id, [s, j && j.creatorId]);
  check('o Bruno recebe o novo facilitador por BOARD_UPDATED', await esperar(() => ultimo(b2, 'BOARD_UPDATED')?.payload?.creatorId === bruno.id), ultimo(b2, 'BOARD_UPDATED')?.payload?.creatorId);
  [s, j] = await api('GET', `/retros/${rid}/participants`, bruno);
  check('só UM participante fica marcado como criador (o Bruno)', j.filter((p) => p.isCreator).length === 1 && j.find((p) => p.isCreator).id === bruno.id, j.map((p) => [p.id.slice(0, 4), p.isCreator]));
  [s] = await api('POST', `/retros/${rid}/finish`, ana);
  check('a Ana, que deixou de ser facilitadora, não encerra (403)', s === 403, s);
  [s] = await api('POST', `/retros/${rid}/finish`, bruno);
  check('o novo facilitador encerra', s === 200, s);

  fechar(b2);
  await api('DELETE', `/retros/${rid}`, adm);
  process.exit(L.resumo('RETRO') ? 0 : 1);
})();
