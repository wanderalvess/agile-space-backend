import json, os, urllib.request, urllib.error, uuid
BASE = os.environ.get('API_BASE', 'http://localhost:8002/api')
HERE = os.path.dirname(os.path.abspath(__file__))
tokens = json.load(open(os.path.join(HERE, 'tokens.json')))
T = lambda e: tokens[e + '@totvs.com.br']['token']
ID = lambda e: tokens[e + '@totvs.com.br']['id']
ana, bruno, carla, dani = 'ana.master', 'bruno.dev', 'carla.qa', 'dani.fora'
results = []

def req(method, path, who=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        r.add_header('Content-Type', 'application/json')
    if who:
        r.add_header('Authorization', 'Bearer ' + T(who))
    try:
        with urllib.request.urlopen(r, timeout=40) as resp:
            txt = resp.read().decode('utf-8', 'replace')
            return resp.status, (json.loads(txt) if txt else None)
    except urllib.error.HTTPError as e:
        txt = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt

def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else '  -> ' + str(detail)[:300]))

# ---------------- POKER ----------------
rid = 'room-' + uuid.uuid4().hex[:8]
room = {'id': rid, 'deckType': 'fibonacci', 'votesRevealed': False, 'creatorId': ID(ana), 'title': 'Poker teste', 'team': 'ANATIME',
        'createdAt': '2026-10-09T10:00:00Z', 'mode': 'sync', 'participantsCount': 0,
        'issuesQueue': [{'id': 'i1', 'key': 'T-1', 'title': 'Tarefa 1'}, {'id': 'i2', 'key': 'T-2', 'title': 'Tarefa 2'}],
        'activeIssueId': 'i1', 'participantIds': [ID(ana)], 'settings': {}}
s, j = req('POST', '/poker', ana, room)
check('poker: criar sala', s in (200, 201), (s, str(j)[:250]))
for who, nick, fac in ((ana, 'Ana', True), (bruno, 'Bruno', False), (carla, 'Carla', False)):
    s, j = req('POST', '/poker/%s/participants' % rid, who, {'id': ID(who), 'roomId': rid, 'nickname': nick, 'role': 'dev', 'isFacilitator': fac})
    check('poker: %s entra' % nick, s in (200, 201), (s, str(j)[:200]))
s, j = req('GET', '/poker/%s/participants' % rid, ana)
facs = [p['id'] for p in j if p.get('isFacilitator')]
check('poker: só o criador é facilitador', facs == [ID(ana)], facs)
s, j = req('POST', '/poker/%s/participants' % rid, bruno, {'id': ID(bruno), 'roomId': rid, 'nickname': 'Bruno', 'role': 'dev', 'isFacilitator': True})
s2, ps = req('GET', '/poker/%s/participants' % rid, ana)
check('poker: participante NÃO se promove a facilitador', [p['id'] for p in ps if p.get('isFacilitator')] == [ID(ana)], [(p['nickname'], p.get('isFacilitator')) for p in ps])

vid = '%s_%s_i1' % (rid, ID(bruno))
s, j = req('POST', '/poker/%s/votes' % rid, bruno, {'id': vid, 'roomId': rid, 'participantId': ID(carla), 'value': '5', 'issueId': 'i1', 'timestamp': 't'})
check('poker: voto com participantId forjado é forçado ao login', s in (200, 201) and j.get('participantId') == ID(bruno), (s, str(j)[:200]))
s, j = req('POST', '/poker/%s/votes' % rid, bruno, {'id': vid + 'x', 'roomId': rid, 'participantId': ID(bruno), 'value': '999', 'issueId': 'i1', 'timestamp': 't'})
check('poker: carta fora do baralho é recusada', s in (400, 409, 422), (s, str(j)[:150]))
s, j = req('POST', '/poker/%s/votes' % rid, dani, {'id': 'zz', 'roomId': rid, 'participantId': ID(dani), 'value': '5', 'issueId': 'i1', 'timestamp': 't'})
check('poker: quem não está na sala NÃO vota', s in (403, 404, 409), (s, str(j)[:150]))
s, j = req('POST', '/poker/%s/votes' % rid, carla, {'id': '%s_%s_i1' % (rid, ID(carla)), 'roomId': rid, 'participantId': ID(carla), 'value': '8', 'issueId': 'i1', 'timestamp': 't'})
check('poker: Carla vota', s in (200, 201), (s, str(j)[:150]))
s, j = req('GET', '/poker/%s/votes' % rid, ana)
check('poker: votos lidos', s == 200 and len(j) == 2, (s, str(j)[:150]))

s, cur = req('GET', '/poker/' + rid, ana)
s, j = req('POST', '/poker', carla, {**cur, 'votesRevealed': True})
s2, now = req('GET', '/poker/' + rid, ana)
check('poker: não-facilitador NÃO revela votos', now.get('votesRevealed') is False, (s, now.get('votesRevealed')))
s, j = req('POST', '/poker', ana, {**cur, 'votesRevealed': True})
s2, now = req('GET', '/poker/' + rid, ana)
check('poker: facilitador revela', now.get('votesRevealed') is True, (s, str(j)[:150]))
s, j = req('POST', '/poker/%s/votes' % rid, bruno, {'id': vid, 'roomId': rid, 'participantId': ID(bruno), 'value': '13', 'issueId': 'i1', 'timestamp': 't'})
check('poker: votar depois de revelar é recusado', s in (400, 403, 409), (s, str(j)[:150]))
stale = dict(cur)
s, j = req('POST', '/poker', ana, {**now, 'currentTopic': 'novo tema'})
s2, j2 = req('POST', '/poker', ana, {**stale, 'currentTopic': 'versão antiga'})
check('poker: gravar sala com versão antiga dá conflito (409)', s2 == 409, (s, s2, str(j2)[:150]))
s, j = req('DELETE', '/poker/%s/votes' % rid, bruno)
check('poker: participante NÃO limpa votos da sala (403)', s == 403, s)
s, j = req('DELETE', '/poker/%s/votes' % rid, ana)
check('poker: facilitador limpa votos', s in (200, 204), s)

# ---------------- REVIEW (showcase) ----------------
sid = 'rev-' + uuid.uuid4().hex[:8]
task = lambda tid, title: {'id': tid, 'sessionId': sid, 'key': tid.upper(), 'title': title, 'status': 'pending', 'order': 0, 'type': 'feature'}
sess = {'id': sid, 'name': 'Review teste', 'sprintName': 'S1', 'status': 'active', 'squadName': 'ANATIME', 'createdBy': ID(bruno),
        'tasks': [task(sid + '-t1', 'Entrega 1')], 'members': []}
s, j = req('POST', '/showcase-sessions', ana, sess)
check('review: criar sessão', s == 200, (s, str(j)[:250]))
check('review: criador vem do login', isinstance(j, dict) and j.get('createdBy') == ID(ana), j.get('createdBy') if isinstance(j, dict) else j)
s, j = req('GET', '/showcase-sessions/' + sid, dani)
check('review: quem recebe o link acessa (decisão do produto)', s == 200, s)

# id de outra sessão não pode ser sequestrado
sid2 = 'rev-' + uuid.uuid4().hex[:8]
sess2 = {'id': sid2, 'name': 'Outra', 'status': 'active', 'squadName': 'ANATIME', 'tasks': [task(sid + '-t1', 'Sequestro')], 'members': []}
s, j = req('POST', '/showcase-sessions', bruno, sess2)
check('review: task de OUTRA sessão não é sequestrada (409)', s == 409, (s, str(j)[:150]))

# decisão: autoria vem do login
dec = {**sess, 'createdBy': ID(ana)}
dec['tasks'] = [{**task(sid + '-t1', 'Entrega 1'), 'decision': 'approved', 'decidedBy': ID(carla), 'decidedByName': 'Fulano Forjado', 'decidedAt': '2026-01-01T00:00:00Z'}]
s, j = req('POST', '/showcase-sessions', bruno, dec)
s2, got = req('GET', '/showcase-sessions/' + sid, bruno)
t1 = [t for t in (got.get('tasks') or []) if t['id'] == sid + '-t1']
check('review: decidedBy vem do login (não do corpo)', bool(t1) and t1[0].get('decidedBy') in (ID(bruno), None), (s, t1[0].get('decidedBy') if t1 else got))
check('review: decidedAt é do servidor', bool(t1) and t1[0].get('decidedAt') != '2026-01-01T00:00:00Z', t1[0].get('decidedAt') if t1 else None)
first = t1[0].get('decidedAt') if t1 else None
dec['tasks'][0]['decision'] = 'rejected'
s, j = req('POST', '/showcase-sessions', carla, dec)
s2, got = req('GET', '/showcase-sessions/' + sid, bruno)
t1 = [t for t in (got.get('tasks') or []) if t['id'] == sid + '-t1']
check('review: decisão já gravada não é reescrita por outra pessoa', bool(t1) and t1[0].get('decision') == 'approved', t1[0].get('decision') if t1 else got)

# links perigosos
bad = {**sess, 'tasks': [{**task(sid + '-t1', 'Entrega 1'), 'url': 'javascript:alert(1)'}]}
s, j = req('POST', '/showcase-sessions', ana, bad)
s2, got = req('GET', '/showcase-sessions/' + sid, ana)
t1 = [t for t in (got.get('tasks') or []) if t['id'] == sid + '-t1']
check('review: link javascript: é descartado', not t1 or 'javascript' not in json.dumps(t1[0].get('url') or ''), t1[0].get('url') if t1 else None)
s, j = req('GET', '/showcase-sessions?squadId=ANATIME&limit=9999', ana)
check('review: listagem limita o tamanho e respeita a squad', s == 200, (s, str(j)[:120]))
s, j = req('GET', '/showcase-sessions?squadId=ZZNAOEXISTE', dani)
check('review: listagem de squad alheia é recusada', s in (403,), s)

print()
ok = sum(1 for r in results if r[1])
print('RESULTADO: %d/%d' % (ok, len(results)))
for n, p, d in results:
    if not p:
        print('  FALHOU:', n, '|', str(d)[:250])
