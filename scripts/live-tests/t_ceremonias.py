# Roteiro das cerimônias do deploy #25 (V79-V81) com usuários de papéis diferentes: encerrar/reabrir a Retro e o
# Brainstorming (participante comum x facilitador x ADMIN), passar a facilitação, trava de decisão da Review.
# Pré-requisito: t_retro.py já rodou (cria as contas e o tokens.json). Use sempre um banco de teste.
# Quem é quem: ana (Agile Master da ANATIME = liderança), bruno (dev), carla (QA), dani (fora da squad), adm (ADMIN).
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
ana, bruno, carla, dani, adm = 'ana.master', 'bruno.dev', 'carla.qa', 'dani.fora', 'adm.root'
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
u = lambda: str(uuid.uuid4())

# ================= RETRO =================
rid = u()
s, _ = req('POST', '/retros', ana, {'id': rid, 'title': 'TESTE cerimônia retro', 'team': SQUAD, 'squadId': SQUAD,
                                    'createdAt': '2026-10-17T10:00:00Z', 'participantIds': [ID(ana), ID(bruno)], 'cards': [], 'timer': {}})
check('retro: ana cria', s in (200, 201), s)
for _w in (ana, bruno):
    req('POST', f'/retros/{rid}/participants', _w, {'id': ID(_w), 'boardId': rid, 'nickname': _w.split('.')[0].title(), 'role': 'DEV', 'isCreator': _w == ana})
card = lambda who, txt: {'id': u(), 'boardId': rid, 'columnKey': 'bom', 'content': txt, 'authorId': ID(who), 'votes': [], 'order': 1000}
s, _ = req('POST', f'/retros/{rid}/cards', bruno, card(bruno, 'antes'))
check('retro: participante escreve card com a retro aberta', s in (200, 201), s)
s, _ = req('POST', f'/retros/{rid}/finish', bruno)
check('retro: participante comum NÃO encerra (403)', s == 403, s)
s, _ = req('POST', f'/retros/{rid}/finish', dani)
check('retro: de fora NÃO encerra', s in (403, 404), s)
s, j = req('POST', f'/retros/{rid}/finish', ana)
check('retro: facilitador encerra (200, finishedAt)', s == 200 and bool((j or {}).get('finishedAt')), (s, j))
s, _ = req('POST', f'/retros/{rid}/cards', bruno, card(bruno, 'depois de encerrar'))
check('retro encerrada: participante NÃO escreve card (409)', s == 409, s)
s, cs = req('GET', f'/retros/{rid}/cards', bruno)
check('retro encerrada: participante ainda LÊ os cards', s == 200 and any(c['content'] == 'antes' for c in cs or []), s)
s, _ = req('POST', f'/retros/{rid}/cards', ana, card(ana, 'facilitador pode'))
check('retro encerrada: facilitador ainda escreve', s in (200, 201), s)
s, _ = req('POST', f'/retros/{rid}/reopen', bruno)
check('retro: participante comum NÃO reabre (403)', s == 403, s)
s, j = req('POST', f'/retros/{rid}/reopen', adm)
check('retro: ADMIN reabre', s == 200 and not (j or {}).get('finishedAt'), (s, j))
s, _ = req('POST', f'/retros/{rid}/cards', bruno, card(bruno, 'depois de reabrir'))
check('retro reaberta: participante volta a escrever', s in (200, 201), s)
req('DELETE', f'/retros/{rid}', ana)

# ================= BRAINSTORMING =================
bid = u()
s, _ = req('POST', '/brainstormings', ana, {'id': bid, 'creatorId': ID(ana), 'title': 'TESTE cerimônia brain', 'team': SQUAD,
                                           'status': 'ideation', 'createdAt': '2026-10-17T10:00:00Z', 'votesPerParticipant': 2})
check('brainstorming: ana cria (limite de 2 votos)', s in (200, 201), s)
req('POST', f'/brainstormings/{bid}/participants', bruno, {'id': ID(bruno), 'boardId': bid, 'nickname': 'Bruno'})
idea = lambda who, txt: {'id': u(), 'boardId': bid, 'content': txt, 'authorId': ID(who), 'votes': []}
ideas = []
for i in range(3):
    body = idea(ana, f'ideia {i}')
    s, _ = req('POST', f'/brainstormings/{bid}/ideas', ana, body)
    ideas.append(body['id'])
check('brainstorming: ideias criadas', s in (200, 201), s)
sts = [req('POST', f'/brainstormings/{bid}/ideas/{i}/vote', bruno)[0] for i in ideas]
check('brainstorming: limite de votos por pessoa (2 passam, o 3º é barrado)', sts[0] in (200, 201) and sts[1] in (200, 201) and sts[2] in (400, 403, 409), sts)
s, _ = req('POST', f'/brainstormings/{bid}/finish', bruno)
check('brainstorming: participante comum NÃO encerra (403)', s == 403, s)
s, j = req('POST', f'/brainstormings/{bid}/finish', ana)
check('brainstorming: facilitador encerra (FINISHED)', s == 200 and (j or {}).get('status') == 'FINISHED', (s, j and j.get('status')))
s, _ = req('POST', f'/brainstormings/{bid}/ideas', bruno, idea(bruno, 'depois de encerrar'))
check('brainstorming encerrado: NÃO aceita ideia (409)', s == 409, s)
s, _ = req('POST', f'/brainstormings/{bid}/ideas/{ideas[0]}/vote', carla)
check('brainstorming encerrado: NÃO aceita voto (409)', s in (403, 409), s)
s, _ = req('GET', f'/brainstormings/{bid}', bruno)
check('brainstorming encerrado: participante ainda lê', s == 200, s)
s, _ = req('POST', f'/brainstormings/{bid}/reopen', bruno)
check('brainstorming: participante NÃO reabre (403)', s == 403, s)
s, j = req('POST', f'/brainstormings/{bid}/reopen', ana)
check('brainstorming: facilitador reabre (OPEN)', s == 200 and (j or {}).get('status') == 'OPEN', (s, j and j.get('status')))
s, _ = req('POST', f'/brainstormings/{bid}/facilitation/claim', dani)
check('facilitação: quem é de fora NÃO assume (403)', s == 403, s)
s, _ = req('POST', f'/brainstormings/{bid}/facilitation/transfer', bruno, {'userId': ID(bruno)})
check('facilitação: participante NÃO passa a facilitação a si mesmo (403)', s == 403, s)
s, j = req('POST', f'/brainstormings/{bid}/facilitation/transfer', ana, {'userId': ID(bruno)})
check('facilitação: facilitador passa a facilitação ao Bruno', s == 200, (s, str(j)[:200]))
s, j = req('POST', f'/brainstormings/{bid}/finish', bruno)
check('facilitação: o novo facilitador encerra', s == 200, (s, str(j)[:200]))
s, _ = req('POST', f'/brainstormings/{bid}/reopen', adm)
check('facilitação: ADMIN reabre mesmo sem ser facilitador', s == 200, s)
s, _ = req('POST', f'/brainstormings/{bid}/finish')
check('brainstorming: sem login (401)', s == 401, s)
req('DELETE', f'/brainstormings/{bid}', ana)

# ================= REVIEW: trava de decisão =================
sid = 'rev-' + uuid.uuid4().hex[:8]
tid = sid + '-t1'
task = {'id': tid, 'sessionId': sid, 'key': 'T1', 'title': 'Entrega 1', 'status': 'pending', 'order': 0, 'type': 'feature'}
sess = {'id': sid, 'name': 'TESTE trava decisão', 'sprintName': 'S1', 'status': 'active', 'squadName': SQUAD, 'tasks': [task], 'members': []}
s, j = req('POST', '/showcase-sessions', ana, sess)
check('review: ana cria a sessão', s == 200, (s, str(j)[:200]))


def decidir(who, decisao):
    s0, atual = req('GET', '/showcase-sessions/' + sid, who)
    corpo = {**atual}
    corpo['tasks'] = [{**t, 'decision': decisao, 'feedback': 'ok'} if t['id'] == tid else t for t in atual['tasks']]
    return req('POST', '/showcase-sessions', who, corpo)


def decisao_gravada():
    _, got = req('GET', '/showcase-sessions/' + sid, ana)
    t = next((x for x in got['tasks'] if x['id'] == tid), {})
    return t.get('decision')


s, _ = decidir(bruno, 'approved')
check('review: dev SEM papel de decisão NÃO decide (403)', s == 403, s)
s, _ = decidir(carla, 'approved')
check('review: QA SEM papel de decisão NÃO decide (403)', s == 403, s)
check('review: nada foi gravado pelas tentativas barradas', decisao_gravada() in (None, '', 'pending'), decisao_gravada())
s, j = decidir(ana, 'approved')
check('review: Agile Master (liderança) decide', s == 200, (s, str(j)[:200]))
check('review: decisão da liderança ficou gravada', str(decisao_gravada()).lower() in ('approved', 'aprovado'), decisao_gravada())
s, _ = decidir(adm, 'rejected')
check('review: ADMIN decide', s in (200, 409), s)
s, _ = req('GET', f'/showcase-sessions/jql?squadName={SQUAD}', bruno)
check('review: rota de JQL por squad responde para quem lê a squad', s in (200, 400), s)
s, _ = req('GET', f'/showcase-sessions/jql?squadId={SQUAD}')
check('review: JQL sem login (401)', s == 401, s)

ok = sum(1 for r in results if r[1])
print(f'\nRESULTADO: {ok}/{len(results)}')
for n, c, d in results:
    if not c:
        print('  FALHOU:', n, '|', str(d)[:200])
