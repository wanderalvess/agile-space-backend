# Roteiro das mudanças de 2026-10-13/14: sessão (refresh/logout/reset), chaves de API com validade,
# token do Jira escondido do admin, Base de Conhecimento (permissão e versões), anonimato da Retro e do Brainstorming.
# Pré-requisito: t_retro.py já rodou (cria as contas e o tokens.json). Use sempre um banco de teste.
import json, os, subprocess, urllib.request, urllib.error, uuid, base64, time

BASE = os.environ.get('API_BASE', 'http://localhost:8002/api')
HERE = os.path.dirname(os.path.abspath(__file__))
TOKFILE = os.path.join(HERE, 'tokens.json')
tokens = json.load(open(TOKFILE))
PW = open(os.path.join(HERE, 'testpw.txt')).read().strip()
T = lambda e: tokens[e + '@totvs.com.br']['token']
ID = lambda e: tokens[e + '@totvs.com.br']['id']
ana, bruno, carla, dani, adm = 'ana.master', 'bruno.dev', 'carla.qa', 'dani.fora', 'adm.root'
results = []


def req(method, path, who=None, body=None, headers=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        r.add_header('Content-Type', 'application/json')
    tok = token or (T(who) if who else None)
    if tok:
        r.add_header('Authorization', 'Bearer ' + tok)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
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
    out = subprocess.run(['docker', 'exec', 'agile-space-db', 'psql', '-U', 'postgres', '-d', os.environ.get('TEST_DB', 'portal_test'), '-tAc', sql],
                         capture_output=True, text=True)
    return out.stdout.strip() + out.stderr.strip()


def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else '  -> ' + str(detail)[:300]))


def claims(tok):
    p = tok.split('.')[1]
    p += '=' * (-len(p) % 4)
    return json.loads(base64.urlsafe_b64decode(p))


def save_token(who, tok):
    tokens[who + '@totvs.com.br']['token'] = tok
    json.dump(tokens, open(TOKFILE, 'w'), indent=1)


def relogin(who):
    s, j = req('POST', '/auth/login', body={'email': who + '@totvs.com.br', 'password': PW})
    if s == 200:
        save_token(who, j['token'])
    return s, j


# ---------------- Sessão ----------------
print('== sessão')
s, j = req('POST', '/auth/refresh', carla)
check('refresh devolve token novo', s == 200 and j and j.get('token'), (s, str(j)[:200]))
if s == 200:
    old, new = claims(T(carla)), claims(j['token'])
    check('refresh preserva o instante do login original (authAt)', new.get('authAt') == old.get('authAt', old.get('iat')), (old, new))
    check('token renovado funciona', req('GET', '/auth/me', token=j['token'])[0] == 200)
s, _ = req('POST', '/auth/refresh')
check('refresh sem token = 401', s == 401, s)
codes = [req('GET', '/auth/me', carla)[0] for _ in range(15)]
check('15 chamadas seguidas a /auth/me sem 429', 429 not in codes, codes)
codes = [req('POST', '/auth/refresh', carla)[0] for _ in range(15)]
check('15 renovações seguidas sem 429', 429 not in codes, codes)

time.sleep(1.2)
old_dani = T(dani)
s, _ = req('POST', '/auth/logout', dani)
check('logout responde 204', s == 204, s)
s, j = req('GET', '/auth/me', token=old_dani)
check('token anterior ao logout deixa de valer (401)', s == 401, (s, str(j)[:120]))
time.sleep(1.2)
s, j = relogin(dani)
check('login depois do logout volta a funcionar', s == 200, (s, str(j)[:120]))
check('token novo vale', req('GET', '/auth/me', dani)[0] == 200)

old_carla = T(carla)
time.sleep(1.2)
s, j = req('POST', '/admin/users/%s/reset-password' % ID(carla), adm)
check('admin redefine a senha (200)', s == 200, (s, str(j)[:200]))
s, _ = req('GET', '/auth/me', token=old_carla)
check('sessão aberta cai depois do reset de senha (401)', s == 401, s)
temp = j.get('tempPassword') if isinstance(j, dict) else None
time.sleep(1.2)
if temp:
    s, j2 = req('POST', '/auth/login', body={'email': carla + '@totvs.com.br', 'password': temp})
    check('login com a senha temporária funciona', s == 200, (s, str(j2)[:120]))
    if s == 200:
        save_token(carla, j2['token'])
else:
    check('reset devolve a senha temporária ao admin', False, j)

# ---------------- Chaves de API ----------------
print('== chaves de API')
s, k = req('POST', '/admin/api-keys', adm, {'name': 'live-' + uuid.uuid4().hex[:5]})
check('criar chave pelo admin', s == 201 and isinstance(k, dict) and k.get('rawKey'), (s, str(k)[:200]))
if s == 201:
    exp = k.get('expiresAt', '')
    days = (time.mktime(time.strptime(exp[:19], '%Y-%m-%dT%H:%M:%S')) - time.time()) / 86400 if exp else -1
    check('chave nasce com validade de ~90 dias', 85 <= days <= 91, exp)
    hdr = {'X-Api-Key': k['rawKey']}
    s1, _ = req('GET', '/v1/prompt-hub/collections', headers=hdr)
    check('chave válida acessa /api/v1', s1 == 200, s1)
    psql("update api_keys set expires_at = now() - interval '1 day' where id = '%s'" % k['id'])
    s2, _ = req('GET', '/v1/prompt-hub/collections', headers=hdr)
    check('chave vencida = 401', s2 == 401, s2)
    s3, r = req('POST', '/admin/api-keys/%s/renew' % k['id'], adm, {})
    check('renovar a chave vencida (200)', s3 == 200, (s3, str(r)[:200]))
    s4, _ = req('GET', '/v1/prompt-hub/collections', headers=hdr)
    check('chave renovada volta a funcionar', s4 == 200, s4)
    s5, _ = req('POST', '/admin/api-keys/%s/revoke' % k['id'], adm)
    s6, _ = req('GET', '/v1/prompt-hub/collections', headers=hdr)
    check('chave revogada = 401', s5 == 200 and s6 == 401, (s5, s6))

# ---------------- Token do Jira escondido do admin ----------------
print('== token do Jira')
s, _ = req('POST', '/users/%s/jira-config' % ID(bruno), bruno, {'domain': 'jira.exemplo.com', 'token': 'PAT-SECRETO-123', 'userId': ID(bruno)})
check('dono salva a própria config Jira', s == 200, s)
s, j = req('GET', '/users/%s/jira-config' % ID(bruno), bruno)
check('dono lê o próprio token', s == 200 and 'PAT-SECRETO-123' in json.dumps(j), (s, str(j)[:150]))
s, j = req('GET', '/users/%s/jira-config' % ID(bruno), adm)
check('ADMIN lendo outra pessoa NÃO recebe o token', s == 200 and 'PAT-SECRETO-123' not in json.dumps(j) and j.get('configured') is True, (s, str(j)[:200]))
s, _ = req('GET', '/users/%s/jira-config' % ID(bruno), carla)
check('colega NÃO lê a config (403)', s == 403, s)
s, _ = req('POST', '/users/%s/jira-config' % ID(bruno), adm, {'domain': 'x', 'token': 'y'})
check('admin NÃO grava token em nome de outra pessoa (403)', s == 403, s)
req('DELETE', '/users/%s/jira-config' % ID(bruno), bruno)

# ---------------- Base de Conhecimento ----------------
print('== base de conhecimento')
did = str(uuid.uuid4())
doc = {'id': did, 'title': 'Doc live ' + did[:4], 'content': 'versão 1', 'status': 'published', 'tags': ['live']}
s, d = req('POST', '/knowledge', bruno, doc)
check('autor cria documento', s == 201, (s, str(d)[:200]))
if s == 201:
    did = d['id']
    s, e = req('PUT', '/knowledge/' + did, bruno, {**d, 'content': 'versão 2'})
    check('autor edita', s == 200, (s, str(e)[:200]))
    s, e = req('PUT', '/knowledge/' + did, carla, {**d, 'content': 'invasão'})
    check('colega (dev comum) NÃO edita (403)', s == 403, (s, str(e)[:200]))
    s, e = req('PUT', '/knowledge/' + did, adm, {**d, 'content': 'versão 3 admin'})
    check('ADMIN edita', s == 200, (s, str(e)[:200]))
    s, v = req('GET', '/knowledge/%s/versions' % did, bruno)
    check('histórico tem as edições', s == 200 and isinstance(v, list) and len(v) >= 2, (s, str(v)[:200]))
    num = None
    if isinstance(v, list) and v:
        nums = [x.get('versionNumber', x.get('number')) for x in v if isinstance(x, dict)]
        nums = [n for n in nums if n is not None]
        num = min(nums) if nums else None
    if num is not None:
        s, r = req('POST', '/knowledge/%s/versions/%s/restore' % (did, num), bruno)
        check('autor restaura versão antiga', s == 200, (s, str(r)[:200]))
        s, r = req('POST', '/knowledge/%s/versions/%s/restore' % (did, num), carla)
        check('colega NÃO restaura (403)', s == 403, s)
    else:
        check('histórico expõe o número da versão', False, str(v)[:200])
    s, _ = req('DELETE', '/knowledge/' + did, carla)
    check('colega NÃO apaga (403)', s == 403, s)
    s, _ = req('DELETE', '/knowledge/' + did, bruno)
    check('autor apaga', s in (200, 204), s)

# ---------------- Anonimato: Retro ----------------
print('== anonimato Retro')
bid = str(uuid.uuid4())
board = {'id': bid, 'creatorId': ID(ana), 'isCardsRevealed': False, 'votingStatus': 'disabled', 'maxVotesPerParticipant': 2,
         'timer': {'status': 'stopped', 'endTime': None, 'initialDuration': 300, 'remainingOnPause': 300},
         'title': 'Retro anon', 'team': 'ANATIME', 'squadId': 'ANATIME', 'createdAt': '2026-10-14T10:00:00Z', 'participantIds': [ID(ana)],
         'columns': [{'id': 'bom', 'title': 'Bom', 'theme': 'success', 'order': 0}], 'templateKey': 'custom'}
check('criar quadro', req('POST', '/retros', ana, board)[0] in (200, 201))
for who, nick in ((bruno, 'Bruno'), (carla, 'Carla')):
    req('POST', '/retros/%s/participants' % bid, who, {'id': ID(who), 'boardId': bid, 'nickname': nick})
cid = str(uuid.uuid4())
s, _ = req('POST', '/retros/%s/cards' % bid, bruno, {'id': cid, 'boardId': bid, 'columnKey': 'bom', 'content': 'SEGREDO-DO-BRUNO', 'authorId': ID(bruno), 'votes': [], 'order': 1000})
check('Bruno escreve um card', s in (200, 201), s)


def seen(who):
    s, cards = req('GET', '/retros/%s/cards' % bid, who)
    return s, json.dumps(cards)


s, txt = seen(carla)
check('antes de revelar, Carla NÃO vê o texto do card', s == 200 and 'SEGREDO-DO-BRUNO' not in txt, txt[:200])
check('antes de revelar, Carla NÃO vê o autor', s == 200 and ID(bruno) not in txt, txt[:200])
check('o próprio autor vê o texto', 'SEGREDO-DO-BRUNO' in seen(bruno)[1])
check('o facilitador vê o texto', 'SEGREDO-DO-BRUNO' in seen(ana)[1])
check('ADMIN vê o texto', 'SEGREDO-DO-BRUNO' in seen(adm)[1])
req('PATCH', '/retros/' + bid, ana, {'isCardsRevealed': True})
s, txt = seen(carla)
check('depois de revelar, Carla vê o texto', 'SEGREDO-DO-BRUNO' in txt, txt[:200])

# ---------------- Anonimato: Brainstorming ----------------
print('== anonimato Brainstorming')
bb = str(uuid.uuid4())
s, _ = req('POST', '/brainstormings', ana, {'id': bb, 'creatorId': ID(ana), 'title': 'Brain anon', 'team': 'ANATIME', 'status': 'ideation', 'createdAt': '2026-10-14T10:00:00Z'})
check('criar mural', s in (200, 201), s)
for who, nick in ((bruno, 'Bruno'), (carla, 'Carla')):
    req('POST', '/brainstormings/%s/participants' % bb, who, {'id': ID(who), 'boardId': bb, 'nickname': nick})
iid = str(uuid.uuid4())
req('POST', '/brainstormings/%s/ideas' % bb, bruno, {'id': iid, 'boardId': bb, 'content': 'IDEIA-SECRETA-BRUNO', 'authorId': ID(bruno), 'votes': []})
s, _ = req('PATCH', '/brainstormings/' + bb, ana, {'settings': {'isRevealed': False}})
check('facilitador oculta as ideias', s == 200, s)


def ideas(who):
    s, j = req('GET', '/brainstormings/%s/ideas' % bb, who)
    return s, json.dumps(j)


s, txt = ideas(carla)
check('ocultas: Carla NÃO vê o texto', s == 200 and 'IDEIA-SECRETA-BRUNO' not in txt, txt[:200])
check('ocultas: Carla NÃO vê o autor', ID(bruno) not in txt, txt[:200])
check('ocultas: o autor vê a própria ideia', 'IDEIA-SECRETA-BRUNO' in ideas(bruno)[1])
check('ocultas: o facilitador vê', 'IDEIA-SECRETA-BRUNO' in ideas(ana)[1])
s, vj = req('POST', '/brainstormings/%s/ideas/%s/vote' % (bb, iid), carla)
# Decisão documentada (brainstorming.md): o voto em ideia oculta vale no servidor, a tela é que bloqueia.
# O que não pode acontecer é a resposta do voto devolver o texto ou o autor.
check('resposta do voto em ideia oculta vem redigida (sem texto nem autor)', s == 200 and 'IDEIA-SECRETA-BRUNO' not in json.dumps(vj) and ID(bruno) not in json.dumps(vj), (s, str(vj)[:200]))
req('POST', '/brainstormings/%s/ideas/%s/vote' % (bb, iid), carla)  # desfaz o voto
req('PATCH', '/brainstormings/' + bb, ana, {'settings': {'isRevealed': True}})
check('reveladas: Carla vê o texto', 'IDEIA-SECRETA-BRUNO' in ideas(carla)[1])

ok = sum(1 for r in results if r[1])
print('\nRESULTADO: %d/%d' % (ok, len(results)))
for n, c, d in results:
    if not c:
        print('  FALHOU:', n, '|', str(d)[:200])
