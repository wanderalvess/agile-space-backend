/*
 * Brainstorming com duas pessoas (WebSocket + REST): encerrar/reabrir, limite de votos sob concorrência, assumir e passar a
 * facilitação, mural anônimo sem vazar autoria, restrictEdits e a marca "editada". Contrato: docs/ceremony-brainstorming-api.md.
 *
 * O "assumir com o facilitador offline" depende de app.brainstorming.facilitator-offline-seconds (padrão 60 s). Para rodar
 * rápido, suba o backend de TESTE com BRAINSTORMING_FACILITATOR_OFFLINE_SECONDS=3 e passe OFFLINE_SECONDS=3 aqui; com o
 * padrão (60) esses passos são pulados e avisados no fim.
 *
 *   OFFLINE_SECONDS=3 API_BASE=http://localhost:8003/api node t_brainstorming_duas_pessoas.js
 */
const L = require('./lib_duas_pessoas');
const { check, api, conectar, ultimo, doTipo, esperar, fechar, sleep, uuid } = L;
const OFFLINE = Number(process.env.OFFLINE_SECONDS || 60);

(async () => {
  const ana = L.conta('ana.master'), bruno = L.conta('bruno.dev'), dani = L.conta('dani.fora'), adm = L.conta('adm.root');
  const criar = async (extra, settings) => {
    const id = uuid();
    const [s, j] = await api('POST', '/brainstormings', ana, {
      id, creatorId: ana.id, title: 'TESTE Brainstorming duas pessoas', team: L.SQUAD, phase: 'ideation', createdAt: '2026-10-17T10:00:00Z',
      settings: { isAnonymous: false, isRevealed: true, ...(settings || {}) }, ...(extra || {}),
    });
    for (const p of [ana, bruno]) await api('POST', `/brainstormings/${id}/participants`, p, { id: p.id, boardId: id, nickname: p === ana ? 'Ana' : 'Bruno' });
    return { id, s, j };
  };
  const ideia = (id, quem, texto) => ({ id: uuid(), boardId: id, content: texto, authorId: quem.id, votes: [] });
  const novaIdeia = async (id, quem, texto) => { const i = ideia(id, quem, texto); const [s, j] = await api('POST', `/brainstormings/${id}/ideas`, quem, i); return { i, s, j }; };
  const todas = async (id, quem) => (await api('GET', `/brainstormings/${id}/ideas`, quem))[1];
  let s, j;

  // ================= 1. encerrar / reabrir =================
  const B1 = await criar({ votesPerParticipant: 2 });
  check('ana cria o mural com limite de 2 votos', B1.s === 200 || B1.s === 201, [B1.s, B1.j]);
  check('o mural volta com status OPEN, facilitatorId e votesPerParticipant', B1.j && B1.j.status === 'OPEN' && B1.j.facilitatorId === ana.id && B1.j.votesPerParticipant === 2, B1.j);
  const a = await conectar('brainstorming', B1.id, ana, 'presence=1');
  const b = await conectar('brainstorming', B1.id, bruno, 'presence=1');
  check('as duas pessoas conectam', a && b);
  check('presença: a Ana vê 2 online', await esperar(() => ultimo(a, 'PRESENCE_UPDATED')?.payload?.total === 2), a.ev.filter((e) => e.type === 'PRESENCE_UPDATED').map((e) => e.payload));
  [s] = await api('POST', `/brainstormings/${B1.id}/finish`, bruno);
  check('participante comum NÃO encerra (403)', s === 403, s);
  [s] = await api('POST', `/brainstormings/${B1.id}/finish`);
  check('sem login não encerra (401)', s === 401, s);
  const nb0 = doTipo(b, 'BOARD_UPDATED').length;
  [s, j] = await api('POST', `/brainstormings/${B1.id}/finish`, ana);
  check('facilitador encerra (FINISHED com finishedAt)', s === 200 && j.status === 'FINISHED' && !!j.finishedAt, [s, j && j.status, j && j.finishedAt]);
  check('o Bruno recebe BOARD_UPDATED do encerramento', await esperar(() => doTipo(b, 'BOARD_UPDATED').length > nb0), doTipo(b, 'BOARD_UPDATED').length);
  const fin = j.finishedAt;
  [s, j] = await api('POST', `/brainstormings/${B1.id}/finish`, ana);
  check('encerrar de novo é idempotente (finishedAt não muda)', s === 200 && j.finishedAt === fin, [s, j && j.finishedAt]);
  [s] = await api('POST', `/brainstormings/${B1.id}/ideas`, bruno, ideia(B1.id, bruno, 'depois de encerrar'));
  check('mural encerrado: ideia nova do participante (409)', s === 409, s);
  [s] = await api('PATCH', `/brainstormings/${B1.id}`, ana, { phase: 'grouping' });
  check('mural encerrado: PATCH de fase pelo facilitador (409)', s === 409, s);
  [s] = await api('GET', `/brainstormings/${B1.id}/ideas`, bruno);
  check('mural encerrado: leitura segue livre (200)', s === 200, s);
  [s] = await api('POST', `/brainstormings/${B1.id}/reopen`, bruno);
  check('participante comum NÃO reabre (403)', s === 403, s);
  [s, j] = await api('POST', `/brainstormings/${B1.id}/reopen`, adm);
  check('ADMIN reabre (OPEN, finishedAt nulo)', s === 200 && j.status === 'OPEN' && !j.finishedAt, [s, j && j.status, j && j.finishedAt]);

  // ================= 2. limite de votos sob concorrência =================
  const ids = [];
  for (let i = 0; i < 6; i++) ids.push((await novaIdeia(B1.id, ana, 'ideia ' + i)).i.id);
  const votar = (quem, ideiaId) => api('POST', `/brainstormings/${B1.id}/ideas/${ideiaId}/vote`, quem);
  const rb = await Promise.all(ids.map((i) => votar(bruno, i)));
  check('limite de 2: 6 votos do Bruno em paralelo, só 2 passam', rb.filter((x) => x[0] === 200).length === 2 && rb.filter((x) => x[0] === 409).length === 4, rb.map((x) => x[0]));
  const rAna = await Promise.all(ids.map((i) => votar(ana, i)));
  check('as duas pessoas votando juntas: cada uma respeita o próprio limite', rAna.filter((x) => x[0] === 200).length === 2, rAna.map((x) => x[0]));
  const msg = rb.find((x) => x[0] === 409)?.[1];
  check('a mensagem do 409 explica o limite e como recuperar a cota', msg && /votos?/i.test(msg.message || ''), msg);
  const lista = await todas(B1.id, ana);
  const porPessoa = (u) => lista.filter((i) => (i.votes || []).includes(u)).length;
  check('no servidor cada pessoa tem exatamente 2 votos', porPessoa(bruno.id) === 2 && porPessoa(ana.id) === 2, [porPessoa(bruno.id), porPessoa(ana.id)]);
  const votado = ids.find((i) => (lista.find((x) => x.id === i).votes || []).includes(bruno.id));
  [s, j] = await votar(bruno, votado);
  check('o mesmo POST de novo REMOVE o voto e devolve a cota', s === 200 && !(j.votes || []).includes(bruno.id), [s, j && j.votes]);
  const livre = ids.find((i) => !(lista.find((x) => x.id === i).votes || []).includes(bruno.id));
  [s] = await votar(bruno, livre);
  check('com a cota devolvida o Bruno vota em outra ideia', s === 200, s);
  [s] = await api('PATCH', `/brainstormings/${B1.id}`, bruno, { votesPerParticipant: 10 });
  check('participante comum NÃO muda o limite (403)', s === 403, s);
  [s, j] = await api('PATCH', `/brainstormings/${B1.id}`, ana, { votesPerParticipant: 0 });
  check('limite 0 é recusado (400)', s === 400, [s, j]);
  [s, j] = await api('PATCH', `/brainstormings/${B1.id}`, ana, { votesPerParticipant: null });
  check('limite null remove o limite', s === 200 && j.votesPerParticipant == null, [s, j && j.votesPerParticipant]);

  // ================= 3. facilitação =================
  [s, j] = await api('POST', `/brainstormings/${B1.id}/facilitation/claim`, dani);
  check('quem não entrou na sala NÃO assume (403)', s === 403, [s, j]);
  [s, j] = await api('POST', `/brainstormings/${B1.id}/facilitation/claim`, bruno);
  check('com o facilitador ONLINE o participante NÃO assume (409)', s === 409, [s, j]);
  [s, j] = await api('POST', `/brainstormings/${B1.id}/facilitation/claim`, ana);
  check('quem já é o facilitador: assumir é idempotente (200)', s === 200 && j.facilitatorId === ana.id, [s, j && j.facilitatorId]);
  [s] = await api('POST', `/brainstormings/${B1.id}/facilitation/transfer`, bruno, { userId: bruno.id });
  check('participante NÃO passa a facilitação a si mesmo (403)', s === 403, s);
  [s] = await api('POST', `/brainstormings/${B1.id}/facilitation/transfer`, ana, { userId: dani.id });
  check('passar para quem não está na sala (404)', s === 404, s);
  [s] = await api('POST', `/brainstormings/${B1.id}/facilitation/transfer`, ana, {});
  check('passar sem userId (400)', s === 400, s);
  const nb1 = doTipo(b, 'BOARD_UPDATED').length;
  [s, j] = await api('POST', `/brainstormings/${B1.id}/facilitation/transfer`, ana, { userId: bruno.id });
  check('a Ana passa a facilitação ao Bruno', s === 200 && j.facilitatorId === bruno.id && j.facilitatorName === 'Bruno', [s, j && j.facilitatorId, j && j.facilitatorName]);
  check('os dois recebem o novo facilitador por BOARD_UPDATED', await esperar(() => ultimo(a, 'BOARD_UPDATED')?.payload?.facilitatorId === bruno.id && ultimo(b, 'BOARD_UPDATED')?.payload?.facilitatorId === bruno.id), [ultimo(a, 'BOARD_UPDATED')?.payload?.facilitatorId, ultimo(b, 'BOARD_UPDATED')?.payload?.facilitatorId]);
  [s, j] = await api('GET', `/brainstormings/${B1.id}/participants`, ana);
  check('em /participants só o Bruno aparece como facilitator', s === 200 && j.find((p) => p.id === bruno.id)?.facilitator === true && !j.find((p) => p.id === ana.id)?.facilitator, j);
  [s] = await api('PATCH', `/brainstormings/${B1.id}`, ana, { phase: 'grouping' });
  check('a Ana (criadora) perdeu o poder de fase, mas...', s === 403, s);
  [s] = await api('POST', `/brainstormings/${B1.id}/finish`, ana);
  check('...a criadora ainda encerra (e reabre) mesmo sem ser a facilitadora', s === 200, s);
  [s] = await api('POST', `/brainstormings/${B1.id}/reopen`, ana);
  [s] = await api('PATCH', `/brainstormings/${B1.id}`, bruno, { phase: 'grouping' });
  check('o novo facilitador muda a fase', s === 200, s);
  [s, j] = await api('POST', `/brainstormings/${B1.id}/facilitation/claim`, ana);
  check('a criadora retoma a facilitação sem checar presença', s === 200 && j.facilitatorId === ana.id, [s, j && j.facilitatorId]);
  [s] = await api('POST', `/brainstormings/${B1.id}/facilitation/transfer`, adm, { userId: bruno.id });
  check('ADMIN também passa a facilitação', s === 200, s);
  await api('POST', `/brainstormings/${B1.id}/facilitation/claim`, ana);

  // facilitador offline: só dá para exercitar com o tempo configurável curto
  let puladoOffline = false;
  if (OFFLINE <= 10) {
    fechar(a);
    [s, j] = await api('POST', `/brainstormings/${B1.id}/facilitation/claim`, bruno);
    check('facilitador saiu há pouco: o participante ainda NÃO assume (409)', s === 409, [s, j]);
    await sleep((OFFLINE + 1.5) * 1000);
    [s, j] = await api('POST', `/brainstormings/${B1.id}/facilitation/claim`, bruno);
    check(`passados ${OFFLINE} s com o facilitador offline o participante assume (200)`, s === 200 && j.facilitatorId === bruno.id, [s, j && j.facilitatorId]);
    const a2 = await conectar('brainstorming', B1.id, ana, 'presence=1');
    [s, j] = await api('POST', `/brainstormings/${B1.id}/facilitation/claim`, ana);
    check('a criadora retoma mesmo com o Bruno online', s === 200 && j.facilitatorId === ana.id, [s, j && j.facilitatorId]);
    fechar(a2);
  } else { puladoOffline = true; fechar(a); }
  fechar(b);

  // ================= 4. mural anônimo sem vazar autoria =================
  const B2 = await criar({}, { isAnonymous: true, isRevealed: true });
  const wb = await conectar('brainstorming', B2.id, bruno);
  const ia = await novaIdeia(B2.id, ana, 'ideia anônima da Ana');
  await sleep(500);
  const bruto = JSON.stringify(wb.ev);
  check('anônimo: o WebSocket do Bruno não recebe o id nem o nome da Ana', !bruto.includes(ana.id) && !bruto.includes('"authorName"'), bruto.slice(0, 300));
  const vistoPeloBruno = (await todas(B2.id, bruno)).find((i) => i.id === ia.i.id);
  check('anônimo: GET do Bruno sem authorId, authorName nem editedBy', vistoPeloBruno && !vistoPeloBruno.authorId && !vistoPeloBruno.authorName && !vistoPeloBruno.editedBy, vistoPeloBruno);
  const vistoPelaAna = (await todas(B2.id, ana)).find((i) => i.id === ia.i.id);
  check('anônimo: o próprio autor vê o authorId', vistoPelaAna && vistoPelaAna.authorId === ana.id, vistoPelaAna);
  [s, j] = await api('PATCH', `/brainstormings/${B2.id}/ideas/${ia.i.id}`, bruno, { ...ia.i, content: 'ideia anônima da Ana, editada pelo Bruno' });
  check('anônimo: Bruno edita o texto (regra de hoje) e a resposta traz editedAt', s === 200 && !!j.editedAt, [s, j]);
  check('anônimo: a resposta do Bruno NÃO traz authorId da Ana', !j.authorId || j.authorId === bruno.id, j.authorId);
  const vistoPelaAna2 = (await todas(B2.id, ana)).find((i) => i.id === ia.i.id);
  check('anônimo: a Ana vê editedAt mas NÃO vê quem editou (editedBy)', vistoPelaAna2 && !!vistoPelaAna2.editedAt && !vistoPelaAna2.editedBy && !vistoPelaAna2.editedByName, vistoPelaAna2);
  fechar(wb);

  // ideias ocultas
  const B3 = await criar({}, { isAnonymous: false, isRevealed: false });
  const ih = await novaIdeia(B3.id, ana, 'ideia oculta da Ana');
  const oculta = (await todas(B3.id, bruno)).find((i) => i.id === ih.i.id);
  check('ocultas: o Bruno não lê o texto, o autor nem os votos da ideia da Ana', !oculta || (oculta.content !== 'ideia oculta da Ana' && !oculta.authorId && !oculta.authorName), oculta);
  const ocultaAna = (await todas(B3.id, ana)).find((i) => i.id === ih.i.id);
  check('ocultas: a autora e o facilitador leem o próprio texto', ocultaAna && ocultaAna.content === 'ideia oculta da Ana', ocultaAna);

  // ================= 5. mural aberto: nome do autor e "editada" =================
  const B4 = await criar({}, { isAnonymous: false, isRevealed: true });
  const i4 = await novaIdeia(B4.id, ana, 'texto original');
  const v4 = (await todas(B4.id, bruno)).find((i) => i.id === i4.i.id);
  check('não anônimo: o Bruno vê authorName "Ana"', v4 && v4.authorName === 'Ana', v4);
  check('ideia nunca editada: sem editedAt', v4 && !v4.editedAt, v4.editedAt);
  [s, j] = await api('PATCH', `/brainstormings/${B4.id}/ideas/${i4.i.id}`, bruno, { ...i4.i, position: { x: 300, y: 300 } });
  const movida = (await todas(B4.id, ana)).find((i) => i.id === i4.i.id);
  check('mover a ideia NÃO conta como edição (sem editedAt)', s === 200 && !movida.editedAt, [s, movida.editedAt]);
  [s, j] = await api('PATCH', `/brainstormings/${B4.id}/ideas/${i4.i.id}`, bruno, { ...i4.i, content: 'texto editado pelo Bruno' });
  const editada = (await todas(B4.id, ana)).find((i) => i.id === i4.i.id);
  check('editar o texto marca editedAt, editedBy (id do token) e editedByName', s === 200 && !!editada.editedAt && editada.editedBy === bruno.id && editada.editedByName === 'Bruno', editada);
  [s, j] = await api('PATCH', `/brainstormings/${B4.id}/ideas/${i4.i.id}`, bruno, { ...i4.i, content: 'texto editado de novo', editedBy: ana.id, editedAt: '2000-01-01T00:00:00Z' });
  const forjada = (await todas(B4.id, ana)).find((i) => i.id === i4.i.id);
  check('editedBy/editedAt do corpo são ignorados (vêm do token e do servidor)', forjada.editedBy === bruno.id && forjada.editedAt !== '2000-01-01T00:00:00Z', forjada);

  // ================= 6. restrictEdits =================
  const B5 = await criar({}, { isAnonymous: false, isRevealed: true, restrictEdits: true });
  check('restrictEdits ligado na criação', B5.j && B5.j.settings && B5.j.settings.restrictEdits === true, B5.j && B5.j.settings);
  const i5 = await novaIdeia(B5.id, ana, 'texto da Ana protegido');
  [s, j] = await api('PATCH', `/brainstormings/${B5.id}/ideas/${i5.i.id}`, bruno, { ...i5.i, content: 'invadido pelo Bruno' });
  check('restrictEdits: o Bruno NÃO edita o texto da Ana (403)', s === 403, [s, j]);
  [s] = await api('PATCH', `/brainstormings/${B5.id}/ideas/${i5.i.id}`, bruno, { ...i5.i, position: { x: 50, y: 60 } });
  check('restrictEdits: mover a ideia alheia segue livre', s === 200, s);
  [s] = await api('POST', `/brainstormings/${B5.id}/ideas/${i5.i.id}/vote`, bruno);
  check('restrictEdits: votar segue livre', s === 200, s);
  [s] = await api('PATCH', `/brainstormings/${B5.id}/ideas/${i5.i.id}`, ana, { ...i5.i, content: 'texto da Ana, ela mesma editou' });
  check('restrictEdits: a autora edita o próprio texto', s === 200, s);
  const i5b = await novaIdeia(B5.id, bruno, 'texto do Bruno');
  [s] = await api('PATCH', `/brainstormings/${B5.id}/ideas/${i5b.i.id}`, ana, { ...i5b.i, content: 'o facilitador edita qualquer um' });
  check('restrictEdits: o facilitador edita o texto alheio', s === 200, s);
  [s] = await api('DELETE', `/brainstormings/${B5.id}/ideas/${i5b.i.id}`, dani);
  check('quem é de fora NÃO apaga ideia', s === 403 || s === 404, s);
  const ia2 = await novaIdeia(B5.id, ana, 'outra da Ana');
  [s] = await api('DELETE', `/brainstormings/${B5.id}/ideas/${ia2.i.id}`, bruno);
  check('o Bruno NÃO apaga ideia da Ana (403)', s === 403, s);
  [s] = await api('PATCH', `/brainstormings/${B5.id}`, bruno, { settings: { restrictEdits: false } });
  check('participante comum NÃO desliga a restrição (403)', s === 403, s);

  for (const id of [B1.id, B2.id, B3.id, B4.id, B5.id]) await api('DELETE', `/brainstormings/${id}`, adm);
  if (puladoOffline) console.log('\nAVISO: os passos de "facilitador offline" foram pulados (OFFLINE_SECONDS=' + OFFLINE + '). Rode com 3 num backend de teste com BRAINSTORMING_FACILITATOR_OFFLINE_SECONDS=3.');
  process.exit(L.resumo('BRAINSTORMING') ? 0 : 1);
})();
