/*
 * Vínculo com a squad (rápido), duas pessoas: quem é de fora leva 403 nos endpoints de squad; pedido de contribuidor aprovado
 * pela liderança da squad (rota por squad) faz o solicitante passar a ver a squad em /api/me/memberships; autoaprovação e
 * aprovação por quem não lidera dão 403; criar plano de ação com squad alheia dá 403. Contrato: docs/link-requests-squad-api.md.
 *
 * ATENÇÃO: o roteiro MUDA o banco de teste (a Dani vira integrante da ANATIME) e DESFAZ no fim com o psql do container
 * agile-space-db (TEST_DB, padrão portal_test). Se o roteiro cair no meio, rode o "delete" que ele imprime.
 *
 *   API_BASE=http://localhost:8002/api node t_vinculo_duas_pessoas.js
 */
const { execFileSync } = require('child_process');
const L = require('./lib_duas_pessoas');
const { check, api, uuid } = L;
const TEST_DB = process.env.TEST_DB || 'portal_test';
const psql = (sql) => execFileSync('docker', ['exec', 'agile-space-db', 'psql', '-U', 'postgres', '-d', TEST_DB, '-tAc', sql]).toString().trim();

(async () => {
  const ana = L.conta('ana.master'), bruno = L.conta('bruno.dev'), dani = L.conta('dani.fora'), adm = L.conta('adm.root');
  const SQ = L.SQUAD;
  let s, j;
  const limpeza = `delete from link_requests where requester_user_id='${dani.id}' and project_key='${SQ}'; delete from project_member_roles where user_id='${dani.id}' and project_id='${SQ}';`;
  console.log('Se o roteiro cair no meio, desfaça com:\n  ' + limpeza);
  psql(limpeza);

  // ---------------- quem é de fora ----------------
  [s] = await api('GET', `/squads/${SQ}/members`, dani);
  check('a Dani (fora) NÃO lê o roster da squad (403)', s === 403, s);
  [s] = await api('GET', `/squads/${SQ}/link-requests`, dani);
  check('a Dani NÃO lista os pedidos de vínculo da squad (403)', s === 403, s);
  [s] = await api('GET', `/poker?squadId=${SQ}`, dani);
  check('a Dani NÃO lista as salas de Poker da squad (403)', s === 403, s);
  [s] = await api('GET', `/retros?squadId=${SQ}`, dani);
  check('a Dani NÃO lista as retros da squad (403)', s === 403, s);
  [s] = await api('GET', `/brainstormings?squadId=${SQ}`, dani);
  check('a Dani NÃO lista os brainstormings da squad (403)', s === 403, s);
  [s] = await api('GET', `/health-checks?squadId=${SQ}`, dani);
  check('a Dani NÃO lista os radares da squad (403)', s === 403, s);
  [s] = await api('GET', `/action-plans?squadId=${SQ}`, dani);
  check('a Dani NÃO lista os planos de ação da squad (403)', s === 403, s);
  [s] = await api('GET', `/squads/${SQ}/members`, bruno);
  check('o Bruno (integrante) lê o roster (200)', s === 200, s);
  [s, j] = await api('GET', `/squads/${SQ}/link-requests/can-approve`, dani);
  check('can-approve responde para qualquer sessão; a Dani não pode aprovar', s === 200 && j.canApprove === false, [s, j]);
  [s, j] = await api('GET', `/squads/${SQ}/link-requests/can-approve`, ana);
  check('can-approve da Ana (AM) é verdadeiro', s === 200 && j.canApprove === true, [s, j]);
  [s, j] = await api('GET', `/squads/${SQ}/link-requests/can-approve`, bruno);
  check('can-approve do Bruno (dev) é falso', s === 200 && j.canApprove === false, [s, j]);

  // ---------------- plano de ação com squad alheia ----------------
  const plano = (quem, squadId) => api('POST', '/action-plans', quem, { title: 'TESTE vínculo', squadId, team: squadId, participantIds: [], tasks: [] });
  [s, j] = await plano(dani, SQ);
  check('a Dani (fora) NÃO cria plano na ANATIME (403)', s === 403, [s, j]);
  [s, j] = await plano(bruno, 'ADMTIME');
  check('o Bruno NÃO cria plano numa squad alheia (ADMTIME) (403)', s === 403, [s, j]);
  [s, j] = await plano(bruno, SQ);
  check('o Bruno cria plano na própria squad (201)', s === 201, [s, j]);
  if (s === 201) await api('DELETE', `/action-plans/${j.id}`, adm);

  // ---------------- pedido de contribuidor ----------------
  [s, j] = await api('GET', '/me/memberships', dani);
  check('antes do pedido a Dani NÃO tem a ANATIME em /me/memberships', s === 200 && !JSON.stringify(j).includes(`"${SQ}"`), j);
  const pedido = { projectKey: SQ, emailInJira: 'dani.fora@totvs.com.br', requestedRoleName: 'Developer', message: 'quero contribuir (teste)' };
  [s, j] = await api('POST', '/link-requests', dani, { ...pedido, requestedRoleName: 'Agile Master' });
  check('pedir papel de liderança como contribuidor é recusado (422)', s === 422, [s, j]);
  [s, j] = await api('POST', '/link-requests', dani, { ...pedido, message: 'x'.repeat(501) });
  check('mensagem com 501 caracteres é recusada (400/422)', s === 400 || s === 422, [s, j]);
  [s, j] = await api('POST', '/link-requests', dani, pedido);
  check('a Dani pede vínculo como Developer (201 PENDING)', s === 201 && j.status === 'PENDING', [s, j]);
  const rid = j && j.id;
  [s, j] = await api('POST', '/link-requests', dani, pedido);
  check('segundo pedido pendente igual é recusado (409)', s === 409, [s, j]);
  [s, j] = await api('GET', '/link-requests/mine', dani);
  check('a Dani vê o pedido em /mine como CONTRIBUTOR PENDING', s === 200 && j.some((r) => r.id === rid && r.status === 'PENDING' && r.kind === 'CONTRIBUTOR'), j);
  [s, j] = await api('GET', '/link-requests/mine', bruno);
  check('o pedido da Dani NÃO aparece em /mine do Bruno', s === 200 && !j.some((r) => r.id === rid), j);

  // ---------------- quem aprova ----------------
  [s] = await api('POST', `/squads/${SQ}/link-requests/${rid}/approve`, dani, {});
  check('autoaprovação: a Dani NÃO aprova o próprio pedido (403)', s === 403, s);
  [s] = await api('POST', `/squads/${SQ}/link-requests/${rid}/approve`, bruno, {});
  check('o Bruno (dev, sem liderança) NÃO aprova (403)', s === 403, s);
  [s] = await api('POST', `/squads/${SQ}/link-requests/${rid}/reject`, bruno, { reason: 'não' });
  check('o Bruno também NÃO recusa (403)', s === 403, s);
  [s, j] = await api('GET', `/squads/${SQ}/link-requests?status=PENDING`, bruno);
  check('o Bruno NÃO lista os pedidos da squad (403)', s === 403, [s, j]);
  [s, j] = await api('GET', `/squads/${SQ}/link-requests?status=PENDING`, ana);
  const pend = Array.isArray(j) && j.find((r) => r.id === rid);
  check('a Ana lista os pedidos pendentes e o da Dani vem com canDecide true', s === 200 && pend && pend.canDecide === true && pend.requester && pend.requester.id === dani.id, pend || j);
  [s, j] = await api('GET', `/squads/NAOEXISTE/link-requests`, ana);
  check('squad inexistente (404)', s === 404, [s, j]);
  [s, j] = await api('POST', `/squads/${SQ}/link-requests/${rid}/reject`, ana, { reason: 'ainda não' });
  check('a Ana recusa primeiro (REJECTED com motivo)', s === 200 && j.status === 'REJECTED', [s, j]);
  [s, j] = await api('GET', '/link-requests/mine', dani);
  check('a Dani vê o pedido REJECTED com a nota da decisão', j.some((r) => r.id === rid && r.status === 'REJECTED' && /ainda não/.test(r.decisionNote || '')), j);
  [s, j] = await api('GET', '/me/memberships', dani);
  check('recusado: a Dani continua sem a ANATIME em /me/memberships', !JSON.stringify(j).includes(`"${SQ}"`), j);
  [s] = await api('POST', `/squads/${SQ}/link-requests/${rid}/approve`, ana, {});
  check('aprovar um pedido já decidido é recusado (409/400)', s === 409 || s === 400, s);
  [s, j] = await api('POST', '/link-requests', dani, pedido);
  check('a Dani pede de novo (201)', s === 201, [s, j]);
  const rid2 = j && j.id;
  [s, j] = await api('POST', `/squads/${SQ}/link-requests/${rid2}/approve`, ana, {});
  check('a Ana (AM) aprova pela rota da squad (200 APPROVED)', s === 200 && j.status === 'APPROVED', [s, j]);
  [s, j] = await api('GET', '/me/memberships', dani);
  check('aprovado: a Dani passa a ver a ANATIME em /me/memberships', s === 200 && JSON.stringify(j).includes(`"${SQ}"`), j);
  [s] = await api('GET', `/squads/${SQ}/members`, dani);
  check('aprovado: a Dani lê o roster da squad (200)', s === 200, s);
  [s, j] = await plano(dani, SQ);
  check('aprovado: a Dani cria plano na ANATIME (201)', s === 201, [s, j]);
  if (s === 201) await api('DELETE', `/action-plans/${j.id}`, adm);
  [s, j] = await plano(dani, 'ADMTIME');
  check('aprovado: a Dani continua sem poder criar plano na ADMTIME (403)', s === 403, [s, j]);
  [s] = await api('POST', `/squads/${SQ}/link-requests/${rid2}/approve`, ana, {});
  check('aprovar de novo o mesmo pedido é recusado (409/400)', s === 409 || s === 400, s);

  // ---------------- cancelar ----------------
  psql(limpeza);
  [s, j] = await api('POST', '/link-requests', dani, pedido);
  const rid3 = j && j.id;
  [s] = await api('POST', `/link-requests/${rid3}/cancel`, bruno);
  check('o Bruno NÃO cancela o pedido da Dani (403/404)', s === 403 || s === 404, s);
  [s, j] = await api('POST', `/link-requests/${rid3}/cancel`, dani);
  check('a Dani cancela o próprio pedido (CANCELLED)', s === 200 && j.status === 'CANCELLED', [s, j]);
  [s] = await api('POST', `/squads/${SQ}/link-requests/${rid3}/approve`, ana, {});
  check('pedido cancelado não pode ser aprovado (409/400)', s === 409 || s === 400, s);

  psql(limpeza);
  process.exit(L.resumo('VÍNCULO') ? 0 : 1);
})();
