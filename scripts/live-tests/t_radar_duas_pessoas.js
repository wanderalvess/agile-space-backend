/*
 * Radar de clima com duas pessoas (WebSocket + REST): "N de M já responderam" em tempo real sem vazar voto, /progress com
 * pendingParticipantIds só para o facilitador, encerrar (finishedAt, idempotente) e apresentação sincronizada
 * (PRESENTATION_STAGE só com ?presentation=1). Contrato: docs/ceremony-radar-retro-api.md (parte 1) e docs/presence-api.md.
 *
 *   API_BASE=http://localhost:8002/api node t_radar_duas_pessoas.js
 */
const L = require('./lib_duas_pessoas');
const { check, api, conectar, ultimo, doTipo, esperar, fechar, sleep, uuid } = L;

(async () => {
  const ana = L.conta('ana.master'), bruno = L.conta('bruno.dev'), dani = L.conta('dani.fora'), adm = L.conta('adm.root');
  const hid = uuid();
  const dims = [{ id: 'clima', key: 'clima', title: 'Clima', label: 'Clima' }, { id: 'time', key: 'time', title: 'Time', label: 'Time' }];
  let [s, j] = await api('POST', '/health-checks', ana, {
    id: hid, creatorId: ana.id, status: 'collecting', scaleType: 'traffic_light', team: L.SQUAD, sprintName: 'S1',
    createdAt: '2026-10-17T10:00:00Z', dimensions: dims, participantIds: [ana.id],
  });
  check('ana cria o radar com 2 dimensões', s === 200 || s === 201, [s, j]);
  check('radar novo: finishedAt nulo, lifecycleStatus OPEN, presentationStage nulo', j && j.finishedAt == null && j.lifecycleStatus === 'OPEN' && j.presentationStage == null, j && [j.finishedAt, j.lifecycleStatus, j.presentationStage]);
  for (const p of [ana, bruno]) await api('POST', `/health-checks/${hid}/participants`, p, { id: p.id, boardId: hid, nickname: p === ana ? 'Ana' : 'Bruno' });
  const voto = (quem, dim, valor) => api('POST', `/health-checks/${hid}/votes`, quem, { id: `${hid}_${quem.id}_${dim}`, boardId: hid, participantId: quem.id, dimensionKey: dim, value: valor, timestamp: '2026-10-17T10:01:00Z' });

  const a = await conectar('health-check', hid, ana, 'presence=1&presentation=1');
  const b = await conectar('health-check', hid, bruno, 'presence=1');
  const bp = await conectar('health-check', hid, bruno, 'presentation=1');
  check('três conexões: Ana (presentation=1), Bruno sem opt-in e Bruno com presentation=1', a && b && bp);
  check('presença: a Ana vê 2 online (as duas abas do Bruno contam como 1)', await esperar(() => ultimo(a, 'PRESENCE_UPDATED')?.payload?.total === 2), a.ev.filter((e) => e.type === 'PRESENCE_UPDATED').map((e) => e.payload));
  [s, j] = await api('GET', `/presence/health-check/${hid}`, dani);
  check('REST de presença do radar: qualquer autenticado lê (200)', s === 200 && j.total === 2, [s, j]);

  // ---------------- progresso ----------------
  [s, j] = await api('GET', `/health-checks/${hid}/progress`, ana);
  check('/progress da Ana: 2 pessoas, 0 responderam, 2 dimensões', s === 200 && j.participantsCount === 2 && j.respondedCount === 0 && j.completedCount === 0 && j.dimensionsCount === 2, j);
  check('/progress da Ana (facilitadora) traz pendingParticipantIds com os dois', Array.isArray(j.pendingParticipantIds) && j.pendingParticipantIds.length === 2, j);
  [s, j] = await api('GET', `/health-checks/${hid}/progress`, bruno);
  check('/progress do Bruno: o campo pendingParticipantIds NÃO existe', s === 200 && !('pendingParticipantIds' in j), j);
  [s, j] = await api('GET', `/health-checks/${hid}/progress`, dani);
  check('/progress de quem não está na sala: sem pendingParticipantIds', s === 200 && !('pendingParticipantIds' in j), j);
  [s, j] = await api('GET', `/health-checks/${hid}/progress`, adm);
  check('/progress do ADMIN traz pendingParticipantIds', s === 200 && Array.isArray(j.pendingParticipantIds), j);
  [s] = await api('GET', `/health-checks/${hid}/progress`);
  check('/progress sem login (401)', s === 401, s);
  [s] = await api('GET', `/health-checks/${uuid()}/progress`, ana);
  check('/progress de radar inexistente (404)', s === 404, s);

  // ---------------- contagem em tempo real ----------------
  const bu0 = doTipo(a, 'BOARD_UPDATED').length;
  [s] = await voto(bruno, 'clima', 'red');
  check('bruno vota na 1ª dimensão (200/201)', s === 200 || s === 201, s);
  check('a Ana recebe BOARD_UPDATED com respondedCount 1 e completedCount 0', await esperar(() => { const p = ultimo(a, 'BOARD_UPDATED')?.payload; return doTipo(a, 'BOARD_UPDATED').length > bu0 && p && p.respondedCount === 1 && p.completedCount === 0; }), ultimo(a, 'BOARD_UPDATED')?.payload);
  const bruto = JSON.stringify(doTipo(a, 'BOARD_UPDATED').slice(bu0));
  check('o evento NÃO vaza o voto do Bruno (valor "red", participantId ou dimensionKey)', !bruto.includes('"red"') && !bruto.includes(bruno.id) && !bruto.includes('dimensionKey'), bruto.slice(0, 300));
  check('o participantsCount do evento é 2 e responded <= participants', ultimo(a, 'BOARD_UPDATED').payload.participantsCount === 2, ultimo(a, 'BOARD_UPDATED').payload);
  const bu1 = doTipo(a, 'BOARD_UPDATED').length;
  [s] = await voto(bruno, 'clima', 'green');
  await sleep(700);
  check('trocar o voto da mesma dimensão NÃO gera novo BOARD_UPDATED', doTipo(a, 'BOARD_UPDATED').length === bu1, doTipo(a, 'BOARD_UPDATED').length - bu1);
  [s] = await voto(bruno, 'time', 'yellow');
  check('ao completar as 2 dimensões: completedCount 1', await esperar(() => ultimo(a, 'BOARD_UPDATED')?.payload?.completedCount === 1), ultimo(a, 'BOARD_UPDATED')?.payload);
  [s, j] = await api('GET', `/health-checks/${hid}/progress`, ana);
  check('/progress da Ana: 1 respondeu, 1 completou, pendentes só a própria Ana', j.respondedCount === 1 && j.completedCount === 1 && j.pendingParticipantIds.length === 1 && j.pendingParticipantIds[0] === ana.id, j);
  [s, j] = await api('GET', `/health-checks/${hid}`, bruno);
  check('GET do quadro traz os 3 contadores', j.respondedCount === 1 && j.completedCount === 1 && j.participantsCount === 2, [j.respondedCount, j.completedCount, j.participantsCount]);
  [s, j] = await api('GET', `/health-checks/${hid}/votes`, bruno);
  check('durante a coleta cada um vê só o PRÓPRIO voto', s === 200 && j.every((v) => v.participantId === bruno.id), j);
  [s, j] = await api('GET', `/health-checks/${hid}/votes`, ana);
  check('durante a coleta a Ana (que não votou) não vê o voto do Bruno', s === 200 && j.every((v) => v.participantId !== bruno.id), j);
  await api('DELETE', `/health-checks/${hid}/participants/${bruno.id}`, bruno);
  [s, j] = await api('GET', `/health-checks/${hid}/progress`, ana);
  check('quem sai da sala deixa de contar (responded 0, participants 1)', j.participantsCount === 1 && j.respondedCount === 0, j);
  await api('POST', `/health-checks/${hid}/participants`, bruno, { id: bruno.id, boardId: hid, nickname: 'Bruno' });

  // ---------------- apresentação: antes de encerrar ----------------
  [s, j] = await api('PUT', `/health-checks/${hid}/presentation`, ana, { stage: 'radar' });
  check('publicar etapa ANTES de encerrar (409)', s === 409, [s, j]);

  // ---------------- encerrar ----------------
  [s] = await api('POST', `/health-checks/${hid}/finish`, bruno, {});
  check('participante comum NÃO encerra (403)', s === 403, s);
  const bu2 = doTipo(b, 'BOARD_UPDATED').length;
  [s, j] = await api('POST', `/health-checks/${hid}/finish`, ana, {});
  check('criadora encerra: finishedAt, status finished e lifecycleStatus FINISHED', s === 200 && j.status === 'finished' && j.lifecycleStatus === 'FINISHED' && !!j.finishedAt, j && [j.status, j.lifecycleStatus, j.finishedAt]);
  check('o Bruno recebe o BOARD_UPDATED do encerramento (sem opt-in)', await esperar(() => doTipo(b, 'BOARD_UPDATED').length > bu2), doTipo(b, 'BOARD_UPDATED').length);
  const fa = j.finishedAt;
  await sleep(1100);
  [s, j] = await api('POST', `/health-checks/${hid}/finish`, ana, {});
  check('encerrar de novo é idempotente (finishedAt não muda)', s === 200 && j.finishedAt === fa, [j && j.finishedAt, fa]);
  [s] = await voto(bruno, 'clima', 'red');
  check('radar encerrado: votar é recusado', s === 400 || s === 403 || s === 409, s);
  [s, j] = await api('GET', `/health-checks/${hid}/votes`, bruno);
  check('encerrado: o Bruno vê os votos de todos, sem identificar quem votou', s === 200 && j.length >= 2 && j.every((v) => !v.participantId), j);

  // ---------------- apresentação sincronizada ----------------
  const pa0 = doTipo(bp, 'PRESENTATION_STAGE').length, pb0 = doTipo(b, 'PRESENTATION_STAGE').length;
  [s] = await api('PUT', `/health-checks/${hid}/presentation`, bruno, { stage: 'radar' });
  check('participante comum NÃO publica etapa (403)', s === 403, s);
  [s] = await api('PUT', `/health-checks/${hid}/presentation`, dani, { stage: 'radar' });
  check('quem é de fora NÃO publica etapa (403)', s === 403, s);
  [s] = await api('PUT', `/health-checks/${hid}/presentation`, ana, {});
  check('etapa ausente (400)', s === 400, s);
  [s] = await api('PUT', `/health-checks/${hid}/presentation`, ana, { stage: 'etapa inválida!' });
  check('etapa fora do formato (400)', s === 400, s);
  [s] = await api('PUT', `/health-checks/${hid}/presentation`, ana, { stage: 'x'.repeat(41) });
  check('etapa com 41 caracteres (400)', s === 400, s);
  [s, j] = await api('PUT', `/health-checks/${hid}/presentation`, ana, { stage: 'radar' });
  check('a criadora publica a etapa "radar" (200)', s === 200 && j.presentationStage === 'radar', [s, j && j.presentationStage]);
  check('o Bruno com presentation=1 recebe PRESENTATION_STAGE radar', await esperar(() => doTipo(bp, 'PRESENTATION_STAGE').length > pa0 && ultimo(bp, 'PRESENTATION_STAGE').payload.stage === 'radar'), ultimo(bp, 'PRESENTATION_STAGE'));
  await sleep(500);
  check('o Bruno SEM opt-in NÃO recebe PRESENTATION_STAGE', doTipo(b, 'PRESENTATION_STAGE').length === pb0, doTipo(b, 'PRESENTATION_STAGE').length - pb0);
  [s, j] = await api('GET', `/health-checks/${hid}`, bruno);
  check('qualquer um lê presentationStage no GET (quem entra depois)', j.presentationStage === 'radar', j.presentationStage);
  [s] = await api('PUT', `/health-checks/${hid}/presentation`, adm, { stage: 'plan' });
  check('ADMIN também publica etapa', s === 200, s);
  check('o Bruno recebe a etapa "plan"', await esperar(() => ultimo(bp, 'PRESENTATION_STAGE')?.payload?.stage === 'plan'), ultimo(bp, 'PRESENTATION_STAGE'));
  [s, j] = await api('DELETE', `/health-checks/${hid}/presentation`, bruno);
  check('participante comum NÃO encerra a apresentação (403)', s === 403, s);
  [s, j] = await api('DELETE', `/health-checks/${hid}/presentation`, ana);
  check('a criadora encerra a apresentação (presentationStage nulo)', s === 200 && j.presentationStage == null, [s, j && j.presentationStage]);
  check('o Bruno recebe stage nulo', await esperar(() => ultimo(bp, 'PRESENTATION_STAGE')?.payload?.stage == null && doTipo(bp, 'PRESENTATION_STAGE').length >= 3), doTipo(bp, 'PRESENTATION_STAGE').map((e) => e.payload));
  [s, j] = await api('PUT', `/health-checks/${hid}/presentation`, ana, { stage: 'comments' });
  const pb1 = doTipo(bp, 'PRESENTATION_STAGE').length;
  [s, j] = await api('POST', '/health-checks', ana, { id: hid, creatorId: ana.id, status: 'finished', presentationStage: 'hack' });
  [s, j] = await api('GET', `/health-checks/${hid}`, ana);
  check('o corpo do POST num radar existente NÃO muda a etapa (continua "comments")', j.presentationStage === 'comments', j.presentationStage);
  [s, j] = await api('PUT', `/health-checks/${hid}/presentation`, ana, { stage: 'comments' });
  check('republicar a mesma etapa é idempotente (200)', s === 200 && j.presentationStage === 'comments', [s, j && j.presentationStage]);
  const bu3 = doTipo(a, 'BOARD_UPDATED').length;
  [s] = await api('PUT', `/health-checks/${hid}/presentation`, ana, { stage: 'score' });
  await sleep(600);
  check('mudar de etapa NÃO dispara BOARD_UPDATED', doTipo(a, 'BOARD_UPDATED').length === bu3, doTipo(a, 'BOARD_UPDATED').length - bu3);

  // radar encerrado antes da V79 não tem como ser criado aqui; o caso "finishedAt nulo" fica coberto pelo contrato.
  fechar(a, b, bp);
  await api('DELETE', `/health-checks/${hid}`, adm);
  process.exit(L.resumo('RADAR') ? 0 : 1);
})();
