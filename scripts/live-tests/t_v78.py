# Roteiro da V78 (origem do plano de ação) e da presença em tempo real, com usuários de papéis diferentes.
# Pré-requisito: t_retro.py já rodou (cria as contas e o tokens.json). Use sempre um banco de teste.
# Quem é quem: ana (Agile Master da ANATIME), bruno (dev da ANATIME), dani (fora da ANATIME), adm (ADMIN).
import json, os, sys, urllib.request, urllib.error, uuid

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

BASE = os.environ.get('API_BASE', 'http://localhost:8002/api')
HERE = os.path.dirname(os.path.abspath(__file__))
tokens = json.load(open(os.path.join(HERE, 'tokens.json')))
T = lambda e: tokens[e + '@totvs.com.br']['token']
ID = lambda e: tokens[e + '@totvs.com.br']['id']
ana, bruno, dani, adm = 'ana.master', 'bruno.dev', 'dani.fora', 'adm.root'
results = []


def req(method, path, who=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        r.add_header('Content-Type', 'application/json')
    if who:
        r.add_header('Authorization', 'Bearer ' + T(who))
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            txt = resp.read().decode()
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


SQUAD = 'ANATIME'
src = 'v78-' + uuid.uuid4().hex[:8]
criados = []


def plano(who, titulo, **extra):
    corpo = {'title': titulo, 'squadId': SQUAD, 'team': SQUAD, 'participantIds': [], 'tasks': [], **extra}
    return req('POST', '/action-plans', who, corpo)


# ---------------- criar plano: quem lê a squad cria, quem é de fora não ----------------
s, p = plano(ana, 'TESTE V78 origem', sourceType='RETRO', sourceId=src, sourceTitle='Retro de teste')
check('integrante da squad cria plano com origem (201)', s == 201, (s, p))
if s == 201:
    criados.append(p['id'])
s, p2 = plano(dani, 'TESTE V78 intruso')
check('quem é de fora da squad NÃO cria plano nela (403)', s == 403, (s, p2))
if s == 201:
    criados.append(p2['id'])
s, p3 = plano(dani, 'TESTE V78 intruso com origem repetida', sourceType='RETRO', sourceId=src)
check('de fora com origem repetida leva 403, não 409 (não revela que existe)', s == 403, (s, p3))
s, p4 = plano(bruno, 'TESTE V78 repetido', sourceType='RETRO', sourceId=src)
check('mesma origem na mesma squad é recusada (409)', s == 409, (s, p4))
if s == 201:
    criados.append(p4['id'])
s, p5 = plano(bruno, 'TESTE V78 outra origem', sourceType='RETRO', sourceId=src + '-b')
check('outra origem na mesma squad é aceita', s == 201, (s, p5))
if s == 201:
    criados.append(p5['id'])
s, p6 = req('POST', '/action-plans', dani, {'title': 'TESTE V78 texto livre', 'team': 'Time de texto livre', 'participantIds': [], 'tasks': []})
check('team de texto livre (sem squad) continua livre', s == 201, (s, p6))
if s == 201:
    criados.append(p6['id'])
s, p7 = req('POST', '/action-plans', dani, {'title': 'TESTE V78 manual', 'participantIds': [], 'tasks': []})
check('plano manual sem squad continua livre', s == 201, (s, p7))
if s == 201:
    criados.append(p7['id'])

# ---------------- persistência e leitura ----------------
if criados:
    pid = criados[0]
    s, g = req('GET', '/action-plans/' + pid, bruno)
    check('outro integrante lê o plano criado', s == 200 and g.get('title') == 'TESTE V78 origem', (s, g))
    check('origem persistida (tipo, id e título)', s == 200 and g.get('sourceType') == 'RETRO' and g.get('sourceId') == src and g.get('sourceTitle') == 'Retro de teste', g)
    s, t = req('POST', f'/action-plans/{pid}/tasks', ana, {'what': 'Ação de teste', 'who': 'Ana', 'status': 'todo', 'order': 0, 'dueDate': '2026-12-31'})
    check('ação com prazo é criada', s in (200, 201), (s, t))
    s, tarefas = req('GET', f'/action-plans/{pid}/tasks', bruno)
    tarefas = tarefas if isinstance(tarefas, list) else []
    check('prazo persiste na ação', any(str(x.get('dueDate', ''))[:10] == '2026-12-31' for x in tarefas), tarefas)

s, lista = req('GET', f'/action-plans?squadId={SQUAD}', bruno)
check('integrante lista os planos da squad', s == 200 and any(x['id'] == (criados or [''])[0] for x in lista or []), s)
alvo = next((x for x in (lista or []) if x['id'] == (criados or [''])[0]), {})
check('lista traz tasksTotal/tasksDone/sourceType', 'tasksTotal' in alvo and 'tasksDone' in alvo and alvo.get('sourceType') == 'RETRO', alvo)
s, _ = req('GET', f'/action-plans?squadId={SQUAD}', dani)
check('de fora NÃO lista planos da squad (403)', s == 403, s)
s, _ = req('GET', f'/action-plans?squadId={SQUAD}', adm)
check('ADMIN lista planos de qualquer squad', s == 200, s)
s, _ = req('GET', f'/action-plans?squadId={SQUAD}')
check('sem login (401)', s == 401, s)

# ---------------- só o criador/ADMIN altera o plano em si ----------------
if criados:
    pid = criados[0]
    s, g = req('GET', '/action-plans/' + pid, ana)
    s2, _ = req('PUT', '/action-plans/' + pid, bruno, {**g, 'title': 'invadido'})
    check('outro integrante NÃO renomeia o plano (403)', s2 == 403, s2)
    s3, _ = req('DELETE', '/action-plans/' + pid, dani)
    check('de fora NÃO apaga o plano', s3 in (403, 404), s3)

# ---------------- presença ----------------
room = 'v78-' + uuid.uuid4().hex[:8]
s, _ = req('POST', '/poker', ana, {'id': room, 'title': 'TESTE presença', 'deckType': 'fibonacci', 'mode': 'sync', 'creatorId': ID(ana), 'team': SQUAD})
check('cria sala de Poker', s in (200, 201), s)
s, pr = req('GET', f'/presence/poker/{room}', bruno)
check('presença do Poker responde 200 com online/total', s == 200 and 'online' in (pr or {}) and 'total' in (pr or {}), (s, pr))
s, _ = req('GET', f'/presence/poker/{room}')
check('presença sem login (401)', s == 401, s)
s, _ = req('GET', '/presence/poker/nao-existe-xyz', bruno)
check('presença de sala inexistente (404)', s == 404, s)
s, _ = req('GET', f'/presence/modulo-estranho/{room}', bruno)
check('presença de módulo desconhecido (404)', s == 404, s)
req('DELETE', f'/poker/{room}', ana)

# Retro: só criador/participante/ADMIN
rid = 'v78r-' + uuid.uuid4().hex[:8]
s, _ = req('POST', '/retros', ana, {'id': rid, 'title': 'TESTE presença retro', 'team': SQUAD, 'squadId': SQUAD, 'createdAt': '2026-10-17T10:00:00Z', 'participantIds': [ID(ana)], 'cards': [], 'timer': {}})
check('cria Retro', s in (200, 201), s)
s, _ = req('GET', f'/presence/retro/{rid}', ana)
check('presença da Retro: criador lê (200)', s == 200, s)
s, _ = req('GET', f'/presence/retro/{rid}', dani)
check('presença da Retro: de fora NÃO lê (403)', s == 403, s)
s, _ = req('GET', f'/presence/retro/{rid}', adm)
check('presença da Retro: ADMIN lê (200)', s == 200, s)
req('DELETE', f'/retros/{rid}', ana)

# ---------------- limpeza ----------------
for pid in criados:
    req('DELETE', '/action-plans/' + pid, adm)
s, lista = req('GET', f'/action-plans?squadId={SQUAD}', adm)
check('limpeza: nenhum plano TESTE V78 sobrou', not any(str(x.get('title', '')).startswith('TESTE V78') for x in lista or []), [x['title'] for x in lista or []])

ok = sum(1 for r in results if r[1])
print(f'\nRESULTADO: {ok}/{len(results)}')
for n, c, d in results:
    if not c:
        print('  FALHOU:', n, '|', str(d)[:200])
