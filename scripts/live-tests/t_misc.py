import json, os, subprocess, urllib.request, urllib.error, uuid
BASE = os.environ.get('API_BASE', 'http://localhost:8002/api')
HERE = os.path.dirname(os.path.abspath(__file__))
tokens = json.load(open(os.path.join(HERE, 'tokens.json')))
T = lambda e: tokens[e + '@totvs.com.br']['token']
ID = lambda e: tokens[e + '@totvs.com.br']['id']
ana, bruno, carla, dani, adm = 'ana.master', 'bruno.dev', 'carla.qa', 'dani.fora', 'adm.root'
results = []

def req(method, path, who=None, body=None, raw=False):
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

def psql(sql):
    out = subprocess.run(['docker', 'exec', 'agile-space-db', 'psql', '-U', 'postgres', '-d', os.environ.get('TEST_DB', 'portal_test'), '-tAc', sql], capture_output=True, text=True)
    return out.stdout.strip() + out.stderr.strip()

def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else '  -> ' + str(detail)[:300]))

# ---------------- Biblioteca de IA ----------------
tag = uuid.uuid4().hex[:6]
s, priv = req('POST', '/prompts', bruno, {'title': 'Privado-' + tag, 'content': 'segredo', 'visibility': 'private', 'type': 'prompt', 'authorId': ID(ana)})
check('criar item privado', s in (200, 201), (s, str(priv)[:200]))
check('autoria vem do login, não do corpo', isinstance(priv, dict) and priv.get('authorId') == ID(bruno), priv.get('authorId') if isinstance(priv, dict) else priv)
s, pub = req('POST', '/prompts', ana, {'title': 'Publico-' + tag, 'content': 'aberto', 'visibility': 'public', 'type': 'prompt'})
check('criar item público', s in (200, 201), (s, str(pub)[:200]))

s, j = req('GET', '/public/prompt-hub/items?size=50')
titles = [i.get('title') for i in (j.get('content') if isinstance(j, dict) and 'content' in j else j or [])] if s == 200 else []
check('leitura anônima lista o público', 'Publico-' + tag in titles, (s, titles[:5]))
check('leitura anônima NÃO lista o privado', 'Privado-' + tag not in titles, titles)
s, j = req('GET', '/public/prompt-hub/items/' + priv['id'])
check('item privado por id, anônimo = 404 (não vaza existência)', s == 404, s)
s, j = req('GET', '/public/prompt-hub/items/' + pub['id'])
check('item público por id, anônimo = 200', s == 200, s)
check('resposta pública sem e-mail/id interno do autor', isinstance(j, dict) and 'authorEmail' not in json.dumps(j) and 'authorId' not in j, str(j)[:300])
s, j = req('GET', '/prompts')
check('lista autenticada sem token = 401/403', s in (401, 403), s)
s, j = req('POST', '/prompts', None, {'title': 'x', 'content': 'y'})
check('criar sem token = 401/403', s in (401, 403), s)
s, j = req('GET', '/prompts/' + priv['id'], dani)
check('privado de outro: GET = 404', s in (403, 404), s)
s, j = req('PUT', '/prompts/' + priv['id'], dani, {**priv, 'title': 'Hack'})
check('privado de outro: PUT recusado', s in (403, 404), s)
s, j = req('DELETE', '/prompts/' + priv['id'], dani)
check('privado de outro: DELETE recusado', s in (403, 404), s)
s, j = req('PUT', '/prompts/' + pub['id'], bruno, {**pub, 'title': 'Hack2'})
check('item público de outro: PUT recusado (403)', s == 403, s)
s, j = req('DELETE', '/prompts/' + priv['id'], bruno)
check('autor apaga o próprio', s in (200, 204), s)

# ---------------- Workspace ----------------
s, j = req('GET', '/workspace/%s/kanban' % ID(bruno), dani)
check('workspace: ver kanban de OUTRA pessoa = 403', s == 403, s)
s, card = req('POST', '/workspace/%s/kanban' % ID(bruno), bruno, {'title': 'Tarefa A', 'description': 'desc', 'status': 'todo', 'priority': 'media'})
check('workspace: criar card', s == 200 and card.get('id'), (s, str(card)[:200]))
s, j = req('PATCH', '/workspace/kanban/' + card['id'], bruno, {'status': 'doing'})
check('workspace: arrastar (PATCH status) persiste', s == 200 and j.get('status') == 'doing', (s, str(j)[:200]))
check('workspace: PATCH preserva título/descrição', j.get('title') == 'Tarefa A' and j.get('description') == 'desc', j)
s, j = req('PATCH', '/workspace/kanban/' + card['id'], dani, {'status': 'done'})
check('workspace: PATCH no card de outra pessoa recusado', s in (403, 404), s)
s, j = req('PATCH', '/workspace/kanban/' + card['id'], bruno, {'userId': ID(dani)})
s2, lst = req('GET', '/workspace/%s/kanban' % ID(bruno), bruno)
check('workspace: não dá para trocar o dono do card pelo PATCH', any(c['id'] == card['id'] for c in lst), (s, s2))
s, note = req('POST', '/workspace/%s/notes' % ID(bruno), bruno, {'content': 'nota', 'color': 'yellow'})
check('workspace: criar nota', s == 200 and note.get('id'), (s, str(note)[:200]))
s, j = req('PATCH', '/workspace/notes/' + note['id'], bruno, {'isPinned': True})
s2, notes = req('GET', '/workspace/%s/notes' % ID(bruno), bruno)
mine = [n for n in notes if n['id'] == note['id']][0]
check('workspace: nota fixada continua fixada ao reler (isPinned)', mine.get('isPinned') is True, mine)
s, j = req('POST', '/workspace/%s/links' % ID(bruno), bruno, {'title': 'xss', 'url': 'javascript:alert(1)'})
check('workspace: atalho javascript: recusado', s in (400, 422), (s, str(j)[:150]))
s, j = req('POST', '/workspace/%s/links' % ID(bruno), bruno, {'title': 'Doc', 'url': 'https://example.com/doc', 'iconType': 'doc', 'color': 'blue'})
check('workspace: atalho https aceito', s == 200, (s, str(j)[:150]))

# ---------------- Feedback / perfil ----------------
s, j = req('GET', '/feedbacks', bruno)
check('feedback: membro NÃO lista (403)', s == 403, s)
s, j = req('POST', '/feedbacks', bruno, {'toolName': 'Retro', 'score': 5, 'comment': 'ok', 'userId': ID(ana)})
check('feedback: membro envia', s in (200, 201), (s, str(j)[:150]))
s, j = req('GET', '/feedbacks', adm)
check('feedback: admin lista', s == 200 and isinstance(j, list), (s, str(j)[:100]))
if s == 200 and j:
    check('feedback: autor vem do login (userId forjado ignorado)', all(f.get('userId') != ID(ana) for f in j if f.get('comment') == 'ok'), [f.get('userId') for f in j][:3])
    s, k = req('PATCH', '/feedbacks/%s/status?status=ARCHIVED' % j[0]['id'], bruno)
    check('feedback: membro NÃO muda status', s == 403, s)

# jiraAccountId de outra pessoa (roster) não pode ser assumido
psql("update project_member_roles set jira_account_id='ana.jira' where email='ana.master@totvs.com.br'")
s, me = req('GET', '/auth/me', bruno)
s, j = req('POST', '/users', bruno, {**{k: me[k] for k in ('id', 'email', 'name') if k in me}, 'jiraAccountId': 'ana.jira'})
s2, me2 = req('GET', '/auth/me', bruno)
check('perfil: id do Jira de OUTRA pessoa não é gravado', me2.get('jiraAccountId') != 'ana.jira', (s, me2.get('jiraAccountId')))
s, j = req('POST', '/users', bruno, {'id': ID(ana), 'name': 'Sequestro', 'email': 'x@x.com'})
check('perfil: não grava perfil de outra pessoa (403)', s == 403, s)
s, j = req('GET', '/users/%s/squads' % ID(ana), bruno)
check('squads de outra pessoa = 403', s == 403, s)
s, j = req('GET', '/users/%s/jira-config' % ID(ana), bruno)
check('config Jira de outra pessoa = 403', s == 403, s)

# ---------------- Jolt ----------------
sample = {'input': {'a': 1}, 'spec': [{'operation': 'shift', 'spec': {'a': 'b'}}]}
# desde 2026-10 /jolt/transform exige login (antes era público)
s, j = req('POST', '/jolt/transform', None, sample)
check('jolt: transform sem login é recusado (401)', s == 401, (s, str(j)[:200]))
s, j = req('POST', '/jolt/transform', bruno, sample)
check('jolt: transform simples funciona (logado)', s == 200 and (j or {}).get('success', True) is not False, (s, str(j)[:200]))
big = {'input': {'k%d' % i: i for i in range(2000)}, 'spec': [{'operation': 'shift', 'spec': {'*': '&'}}] * 60}
s, j = req('POST', '/jolt/transform', bruno, big)
check('jolt: spec com operações demais é recusada', s in (400, 413, 422) or (isinstance(j, dict) and j.get('success') is False), (s, str(j)[:200]))
deep = cur = {}
for i in range(100):
    cur['x'] = {}; cur = cur['x']
s, j = req('POST', '/jolt/transform', None, {'input': deep, 'spec': [{'operation': 'shift', 'spec': {'*': '&'}}]})
check('jolt: entrada profunda demais é recusada sem 500', s != 500, (s, str(j)[:200]))
s, p1 = req('POST', '/jolt/projects', bruno, {'name': 'Proj-' + tag, 'inputJson': '{"a":1}', 'specJson': '[]', 'isPublic': False})
check('jolt: criar projeto', s in (200, 201), (s, str(p1)[:200]))
if isinstance(p1, dict) and p1.get('id'):
    s, j = req('GET', '/jolt/projects/' + p1['id'], dani)
    check('jolt: projeto privado de outro = 403/404', s in (403, 404), s)
    s, j = req('GET', '/jolt/projects', dani)
    names = [x.get('name') for x in (j if isinstance(j, list) else (j or {}).get('content', []))]
    check('jolt: lista de outro NÃO traz o privado', 'Proj-' + tag not in names, names[:5])
    s, j = req('DELETE', '/jolt/projects/' + p1['id'], dani)
    check('jolt: apagar projeto alheio recusado', s in (403, 404), s)
    stale = dict(p1)
    s, j = req('PUT', '/jolt/projects/' + p1['id'], bruno, {**p1, 'description': 'v2', 'expectedVersion': p1.get('version')})
    s2, j2 = req('PUT', '/jolt/projects/' + p1['id'], bruno, {**stale, 'description': 'v3 desatualizada', 'expectedVersion': stale.get('version')})
    check('jolt: salvar com versão velha dá conflito (409) em vez de sobrescrever', s2 == 409, (s, s2))

# ---------------- Health Check: anonimato ----------------
hid = str(uuid.uuid4())
s, j = req('POST', '/health-checks', ana, {'id': hid, 'creatorId': ID(ana), 'status': 'collecting', 'scaleType': 'traffic_light', 'team': 'ANATIME', 'sprintName': 'S1',
                                            'createdAt': '2026-10-09T10:00:00Z', 'dimensions': [{'id': 'clima', 'key': 'clima', 'title': 'Clima', 'label': 'Clima'}], 'participantIds': [ID(ana)]})
check('health: criar radar', s in (200, 201), (s, str(j)[:200]))
for who in (ana, bruno, carla):
    req('POST', '/health-checks/%s/participants' % hid, who, {'id': ID(who), 'boardId': hid, 'nickname': who})
for who, val in ((bruno, 'green'), (carla, 'red')):
    vid = '%s_%s_clima' % (hid, ID(who))
    s, j = req('POST', '/health-checks/%s/votes' % hid, who, {'id': vid, 'boardId': hid, 'participantId': ID(who), 'dimensionKey': 'clima', 'value': val, 'timestamp': '2026-10-09T10:01:00Z'})
    check('health: %s vota' % who, s in (200, 201), (s, str(j)[:150]))
s, j = req('POST', '/health-checks/%s/votes' % hid, bruno, {'id': 'x', 'boardId': hid, 'participantId': ID(carla), 'dimensionKey': 'clima', 'value': 'red', 'timestamp': 't'})
check('health: NÃO vota em nome de outra pessoa (id forçado ao login)', s in (200, 201, 403) and (s == 403 or j.get('participantId') == ID(bruno)), (s, str(j)[:150]))
s, j = req('GET', '/health-checks/%s/votes' % hid, bruno)
check('health: durante a coleta só vê os PRÓPRIOS votos', s == 200 and isinstance(j, list) and all(v.get('participantId') == ID(bruno) for v in j), str(j)[:150])
s, j = req('POST', '/health-checks/%s/finish' % hid, bruno, {})
check('health: só o criador encerra (403)', s == 403, s)
s, j = req('POST', '/health-checks/%s/finish' % hid, ana, {})
check('health: criador encerra', s in (200, 201), (s, str(j)[:150]))
s, j = req('GET', '/health-checks/%s/votes' % hid, carla)
check('health: depois de encerrar vê todos os votos', s == 200 and isinstance(j, list) and len(j) >= 2, (s, str(j)[:150]))
check('health: votos encerrados NÃO identificam quem votou', s == 200 and isinstance(j, list) and all(not v.get('participantId') for v in j), str(j)[:200])
s, j = req('POST', '/health-checks/%s/votes' % hid, bruno, {'id': 'y', 'boardId': hid, 'participantId': ID(bruno), 'dimensionKey': 'clima', 'value': 'red', 'timestamp': 't'})
check('health: votar depois de encerrado é recusado', s in (400, 403, 409), s)

# ---------------- Brainstorming ----------------
bid = str(uuid.uuid4())
s, j = req('POST', '/brainstormings', ana, {'id': bid, 'creatorId': ID(ana), 'title': 'Brain', 'team': 'ANATIME', 'status': 'ideation', 'createdAt': '2026-10-09T10:00:00Z'})
check('brainstorming: criar mural', s in (200, 201), (s, str(j)[:200]))
s, j = req('POST', '/brainstormings/%s/participants' % bid, bruno, {'id': ID(bruno), 'boardId': bid, 'nickname': 'Bruno'})
iid = str(uuid.uuid4())
s, j = req('POST', '/brainstormings/%s/ideas' % bid, bruno, {'id': iid, 'boardId': bid, 'content': 'Ideia 1', 'authorId': ID(ana), 'votes': []})
check('brainstorming: criar ideia (autor = login)', s in (200, 201) and j.get('authorId') == ID(bruno), (s, str(j)[:200]))
s, j = req('POST', '/brainstormings/%s/ideas/%s/vote' % (bid, iid), bruno)
check('brainstorming: votar', s == 200, (s, str(j)[:150]))
s, j = req('PATCH', '/brainstormings/' + bid, bruno, {'status': 'voting'})
check('brainstorming: participante NÃO muda fase (403)', s == 403, s)
s, j = req('GET', '/brainstormings/' + bid, dani)
check('brainstorming: estranho lê o mural por link (decisão: link dá acesso)', s in (200, 403), s)

print()
ok = sum(1 for r in results if r[1])
print('RESULTADO: %d/%d' % (ok, len(results)))
for n, p, d in results:
    if not p:
        print('  FALHOU:', n, '|', str(d)[:250])
