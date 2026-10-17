/*
 * Poker (sala síncrona) com duas/três pessoas (WebSocket + REST): presença nas duas pontas, entrada/saída refletida, voto de
 * um sem vazar para os outros antes de revelar (votos às cegas: settings.blindVotes, opt-in do facilitador), revelar só o
 * facilitador, limpar votos. Contrato de presença: docs/presence-api.md.
 *
 * Observações do contrato: sem blindVotes (padrão) o valor do voto é visível para todos; o facilitador (criador) sempre
 * vê tudo. Por isso o "outro" que não pode ver o voto é a Carla (participante comum), e não a Ana (facilitadora).
 * O backend não tem "quórum governado pela presença": nada no PokerService calcula quórum.
 *
 *   API_BASE=http://localhost:8002/api node t_poker_duas_pessoas.js
 */
const L = require('./lib_duas_pessoas');
const { check, api, conectar, ultimo, doTipo, esperar, fechar, sleep, uuid } = L;

(async () => {
  const ana = L.conta('ana.master'), bruno = L.conta('bruno.dev'), carla = L.conta('carla.qa'), dani = L.conta('dani.fora'), adm = L.conta('adm.root');
  const criar = async (extra) => {
    const id = 'pk-' + uuid().slice(0, 8);
    const [s, j] = await api('POST', '/poker', ana, { id, title: 'TESTE Poker duas pessoas', deckType: 'fibonacci', mode: 'sync', creatorId: ana.id, team: L.SQUAD, ...(extra || {}) });
    return { id, s, j };
  };
  const entrar = (id, quem, apelido) => api('POST', `/poker/${id}/participants`, quem, { id: quem.id, nickname: apelido, role: 'Developer' });
  const votar = (id, quem, valor, participante) => api('POST', `/poker/${id}/votes`, quem, { participantId: (participante || quem).id, value: valor });
  let s, j;

  // ================= sala às cegas =================
  const R = await criar({ settings: { blindVotes: true } });
  check('ana cria a sala síncrona com votos às cegas', R.s === 200 || R.s === 201, [R.s, R.j]);
  const a = await conectar('poker', R.id, ana, 'presence=1');
  const b = await conectar('poker', R.id, bruno, 'presence=1');
  const c = await conectar('poker', R.id, carla, 'presence=1');
  check('três conexões abrem (presence=1)', a && b && c);
  if (!a || !b || !c) process.exit(1);

  // ---------------- presença e entrada/saída ----------------
  check('presença: a Ana vê 3 online com os ids', await esperar(() => ultimo(a, 'PRESENCE_UPDATED')?.payload?.total === 3), a.ev.filter((e) => e.type === 'PRESENCE_UPDATED').map((e) => e.payload));
  const pa = ultimo(a, 'PRESENCE_UPDATED').payload;
  check('os três ids aparecem em online', [ana, bruno, carla].every((p) => pa.online.includes(p.id)), pa);
  check('o Bruno também recebe a presença (as duas pontas)', ultimo(b, 'PRESENCE_UPDATED')?.payload?.total === 3, ultimo(b, 'PRESENCE_UPDATED'));
  [s, j] = await api('GET', `/presence/poker/${R.id}`, dani);
  check('REST de presença do poker: qualquer autenticado lê (200, total 3)', s === 200 && j.total === 3, [s, j]);
  [s] = await api('GET', `/presence/poker/${R.id}`);
  check('REST de presença sem login (401)', s === 401, s);
  const j0 = doTipo(a, 'PARTICIPANT_JOINED').length;
  await entrar(R.id, ana, 'Ana'); await entrar(R.id, bruno, 'Bruno'); await entrar(R.id, carla, 'Carla');
  check('a Ana recebe PARTICIPANT_JOINED do Bruno e da Carla', await esperar(() => doTipo(a, 'PARTICIPANT_JOINED').length >= j0 + 3 || doTipo(a, 'PARTICIPANT_JOINED').filter((e) => JSON.stringify(e).includes(bruno.id)).length > 0), doTipo(a, 'PARTICIPANT_JOINED').length);
  [s, j] = await api('GET', `/poker/${R.id}/participants`, ana);
  check('GET /participants lista os três', s === 200 && j.length === 3, j.length);
  [s] = await api('POST', `/poker/${R.id}/heartbeat/${bruno.id}`, bruno);
  check('heartbeat do próprio usuário (204/200)', s === 204 || s === 200, s);
  [s] = await api('POST', `/poker/${R.id}/heartbeat/${ana.id}`, bruno);
  check('heartbeat de OUTRA pessoa é recusado', s === 403 || s === 404, s);
  const l0 = doTipo(a, 'PARTICIPANT_LEFT').length;
  [s] = await api('DELETE', `/poker/${R.id}/participants/${carla.id}`, carla);
  check('a Carla sai e a Ana recebe PARTICIPANT_LEFT', await esperar(() => doTipo(a, 'PARTICIPANT_LEFT').length > l0), doTipo(a, 'PARTICIPANT_LEFT').length);
  [s] = await api('DELETE', `/poker/${R.id}/participants/${ana.id}`, bruno);
  check('o Bruno NÃO remove a Ana da sala (403)', s === 403, s);
  await entrar(R.id, carla, 'Carla');
  fechar(c);
  check('a Carla fecha a conexão e a Ana passa a ver 2 online', await esperar(() => ultimo(a, 'PRESENCE_UPDATED')?.payload?.total === 2, 8000), ultimo(a, 'PRESENCE_UPDATED'));
  const c2 = await conectar('poker', R.id, carla, 'presence=1');
  check('a Carla volta e a Ana vê 3 de novo', await esperar(() => ultimo(a, 'PRESENCE_UPDATED')?.payload?.total === 3), ultimo(a, 'PRESENCE_UPDATED'));

  // ---------------- voto às cegas ----------------
  const vistoPelaCarla = () => JSON.stringify(c2.ev);
  [s, j] = await votar(R.id, bruno, '13');
  check('o Bruno vota 13 (200/201)', s === 200 || s === 201, [s, j]);
  await sleep(600);
  [s, j] = await api('GET', `/poker/${R.id}/votes`, carla);
  const doBruno = (j || []).find((v) => v.participantId === bruno.id);
  check('a Carla vê que o Bruno votou, mas o valor vem mascarado', s === 200 && doBruno && doBruno.value !== '13', doBruno);
  check('o WebSocket da Carla NÃO recebe o valor 13', !/"value":"13"/.test(vistoPelaCarla()), vistoPelaCarla().slice(0, 300));
  [s, j] = await api('GET', `/poker/${R.id}/votes`, bruno);
  check('o Bruno vê o próprio voto em claro', (j || []).find((v) => v.participantId === bruno.id)?.value === '13', j);
  [s, j] = await api('GET', `/poker/${R.id}/votes`, ana);
  check('a facilitadora (Ana) vê o valor em claro', (j || []).find((v) => v.participantId === bruno.id)?.value === '13', j);
  check('o VOTE_SAVED em tempo real do Bruno vai em claro SÓ para ele; a Ana (facilitadora) recebe o evento mascarado e lê o valor pelo GET', doTipo(a, 'VOTE_SAVED').length > 0 && !doTipo(a, 'VOTE_SAVED').some((e) => JSON.stringify(e).includes('"13"')) && doTipo(b, 'VOTE_SAVED').some((e) => JSON.stringify(e).includes('"13"')), [doTipo(a, 'VOTE_SAVED').length, doTipo(b, 'VOTE_SAVED').length]);
  [s, j] = await api('GET', `/poker/${R.id}/votes`, dani);
  check('quem não é participante também recebe o voto mascarado', (j || []).find((v) => v.participantId === bruno.id)?.value !== '13', j);
  [s] = await votar(R.id, bruno, '21', ana);
  check('o Bruno NÃO vota em nome da Ana (403)', s === 403, s);
  await votar(R.id, carla, '5');
  await votar(R.id, ana, '8');

  // ---------------- revelar ----------------
  [s, j] = await api('GET', `/poker/${R.id}`, bruno);
  const sala = j;
  [s, j] = await api('POST', '/poker', bruno, { ...sala, votesRevealed: true });
  check('o Bruno (participante) NÃO revela os votos (403)', s === 403, [s, j]);
  [s, j] = await api('GET', `/poker/${R.id}/votes`, carla);
  check('depois da tentativa barrada os votos continuam mascarados', (j || []).find((v) => v.participantId === bruno.id)?.value !== '13', j);
  const r0 = doTipo(c2, 'ROOM_UPDATED').length;
  [s, j] = await api('POST', '/poker', ana, { ...sala, votesRevealed: true });
  check('a facilitadora revela (200/201, votesRevealed)', (s === 200 || s === 201) && j.votesRevealed === true, [s, j && j.votesRevealed]);
  check('a Carla recebe ROOM_UPDATED da revelação', await esperar(() => doTipo(c2, 'ROOM_UPDATED').length > r0), doTipo(c2, 'ROOM_UPDATED').length);
  [s, j] = await api('GET', `/poker/${R.id}/votes`, carla);
  check('depois de revelar a Carla vê o 13 do Bruno e o 8 da Ana', (j || []).find((v) => v.participantId === bruno.id)?.value === '13' && (j || []).find((v) => v.participantId === ana.id)?.value === '8', j);

  // ---------------- limpar votos ----------------
  [s] = await api('DELETE', `/poker/${R.id}/votes`, bruno);
  check('o Bruno (participante) NÃO limpa os votos da sala (403)', s === 403, s);
  const vc0 = doTipo(b, 'VOTES_CLEARED').length;
  [s] = await api('DELETE', `/poker/${R.id}/votes`, ana);
  check('a facilitadora limpa os votos (200/204)', s === 200 || s === 204, s);
  check('o Bruno recebe VOTES_CLEARED', await esperar(() => doTipo(b, 'VOTES_CLEARED').length > vc0), doTipo(b, 'VOTES_CLEARED').length);
  [s, j] = await api('GET', `/poker/${R.id}/votes`, ana);
  check('depois de limpar não há votos', s === 200 && j.length === 0, j);

  // ================= sala sem votos às cegas (padrão) =================
  const P = await criar({});
  await entrar(P.id, ana, 'Ana'); await entrar(P.id, bruno, 'Bruno');
  await votar(P.id, bruno, '3');
  [s, j] = await api('GET', `/poker/${P.id}/votes`, ana);
  check('sala padrão (sem blindVotes): o valor é visível (comportamento documentado)', (j || []).find((v) => v.participantId === bruno.id)?.value === '3', j);

  // ================= sala com o facilitador que sai =================
  [s] = await api('DELETE', `/poker/${R.id}`, bruno);
  check('o Bruno NÃO apaga a sala da Ana (403)', s === 403, s);
  fechar(a, b, c2);
  for (const id of [R.id, P.id]) await api('DELETE', `/poker/${id}`, ana);
  process.exit(L.resumo('POKER') ? 0 : 1);
})();
