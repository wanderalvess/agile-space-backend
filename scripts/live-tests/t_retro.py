import json, os, subprocess, urllib.request, urllib.error, uuid, threading, sys

BASE = os.environ.get('API_BASE', 'http://localhost:8002/api')
PW = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'testpw.txt')).read().strip()
results = []

def req(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        r.add_header('Content-Type', 'application/json')
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
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

def psql(sql):
    out = subprocess.run(['docker', 'exec', 'agile-space-db', 'psql', '-U', 'postgres', '-d', os.environ.get('TEST_DB', 'portal_test'), '-tAc', sql],
                         capture_output=True, text=True)
    return out.stdout.strip() + out.stderr.strip()

def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else '  -> ' + str(detail)[:300]))

import os
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tokens.json')
cache = json.load(open(CACHE)) if os.path.exists(CACHE) else {}

def user(email, name):
    if email in cache:
        return cache[email]['token'], cache[email]['id']
    s, j = req('POST', '/auth/register', body={'email': email, 'name': name, 'password': PW})
    if s != 200:
        s, j = req('POST', '/auth/login', body={'email': email, 'password': PW})
    cache[email] = {'token': j['token'], 'id': j['id']}
    json.dump(cache, open(CACHE, 'w'))
    return j['token'], j['id']

ana, ana_id = user('ana.master@totvs.com.br', 'Ana Master')
bruno, bruno_id = user('bruno.dev@totvs.com.br', 'Bruno Dev')
carla, carla_id = user('carla.qa@totvs.com.br', 'Carla QA')
dani, dani_id = user('dani.fora@totvs.com.br', 'Dani Fora')
adm, adm_id = user('adm.root@totvs.com.br', 'Adm Root')
psql("update users set role='ADMIN' where email='adm.root@totvs.com.br'")
s, j = req('GET', '/auth/me', adm)
check('admin com role ADMIN no servidor (relido do banco)', isinstance(j, dict) and j.get('role') == 'ADMIN', (s, j))

# ---------- equipes / papéis ----------
s, j = req('POST', '/projects', bruno, {'id': 'BRUNOTIME', 'name': 'Time do Bruno'})
check('dev NÃO cria equipe (403)', s == 403, (s, j))

s, j = req('POST', '/projects', adm, {'id': 'ADMTIME', 'name': 'Time do Admin'})
check('admin cria equipe', s in (200, 409), (s, str(j)[:200]))
s, j = req('GET', '/projects/ADMTIME', adm)
mem = (j or {}).get('members') or []
check('admin fundador entra SEM liderança (Developer)', mem and mem[0].get('roleKey') == 'DEVELOPER' and not mem[0].get('leadership'), mem)

# Ana vira AM via roster de outra equipe (como se viesse do Jira)
psql("delete from project_member_roles where project_id='ADMTIME' and role_key='AGILE_MASTER'")
psql("insert into project_member_roles (id, project_id, role_name, role_key, display_name, email, user_id, is_leadership, created_at, updated_at) "
     "values (gen_random_uuid()::text, 'ADMTIME', 'Agile Master', 'AGILE_MASTER', 'Ana Master', 'ana.master@totvs.com.br', '%s', true, now(), now())" % ana_id)
s, j = req('POST', '/projects', ana, {'id': 'ANATIME', 'name': 'Time da Ana'})
check('AM (por roster) cria equipe', s in (200, 409), (s, str(j)[:200]))
s, j = req('GET', '/projects/ANATIME', ana)
mem = (j or {}).get('members') or []
check('AM fundador entra como AGILE_MASTER com liderança', mem and mem[0].get('roleKey') == 'AGILE_MASTER' and mem[0].get('leadership'), mem)
s, j = req('POST', '/projects/ANATIME/join?roleName=Agile%20Master', bruno)
check('join com papel de liderança é recusado', s in (400, 403), (s, j))
s, j = req('POST', '/projects/ANATIME/join?roleName=Developer', bruno)
check('join como Developer funciona', s == 200, (s, str(j)[:200]))
s, j = req('GET', '/projects/user/' + ana_id, bruno)
check('ver projetos de OUTRA pessoa é recusado (403)', s == 403, (s, str(j)[:150]))
s, j = req('GET', '/projects/user/' + bruno_id, bruno)
check('ver os próprios projetos funciona', s == 200, (s, str(j)[:150]))

# ---------- retro ----------
bid = str(uuid.uuid4())
board = {'id': bid, 'creatorId': ana_id, 'isCardsRevealed': False, 'votingStatus': 'disabled', 'maxVotesPerParticipant': 2,
         'timer': {'status': 'stopped', 'endTime': None, 'initialDuration': 300, 'remainingOnPause': 300},
         'title': 'Retro teste', 'team': 'ANATIME', 'squadId': 'ANATIME', 'createdAt': '2026-10-09T10:00:00Z',
         'participantIds': [ana_id],
         'columns': [{'id': 'bom', 'title': 'Bom', 'theme': 'success', 'order': 0}, {'id': 'ruim', 'title': 'Ruim', 'theme': 'warning', 'order': 1},
                     {'id': 'acao', 'title': 'Ações', 'theme': 'action', 'order': 2}],
         'templateKey': 'custom'}
s, j = req('POST', '/retros', ana, board)
check('criar quadro', s in (200, 201), (s, str(j)[:300]))
s, j = req('GET', '/retros/' + bid, ana)
check('quadro volta com timer e colunas', s == 200 and j.get('timer', {}).get('status') == 'stopped' and len(j.get('columns', [])) == 3, (s, str(j)[:300]))

req('POST', '/projects/ANATIME/join?roleName=QA', carla)
for tok, uid, nick in ((ana, ana_id, 'Ana'), (bruno, bruno_id, 'Bruno'), (carla, carla_id, 'Carla')):
    s, j = req('POST', '/retros/%s/participants' % bid, tok, {'id': uid, 'boardId': bid, 'nickname': nick, 'role': 'DEV', 'isCreator': uid == ana_id})
    check('participante %s entra' % nick, s in (200, 201), (s, str(j)[:200]))

s, j = req('GET', '/retros/' + bid, dani)
check('intruso NÃO lê quadro de outra squad (403)', s == 403, (s, str(j)[:120]))
s, j = req('GET', '/retros/%s/cards' % bid, dani)
check('intruso NÃO lê cards (403)', s == 403, s)
s, j = req('POST', '/retros/%s/participants' % bid, dani, {'id': carla_id, 'boardId': bid, 'nickname': 'x', 'isCreator': True})
check('intruso NÃO se infiltra como participante (id/creator forjados)', s in (403,) or (s in (200, 201) and j.get('id') == dani_id and not j.get('isCreator')), (s, str(j)[:200]))

# cards
def mk(tok, uid, col, text, **kw):
    cid = str(uuid.uuid4())
    body = {'id': cid, 'boardId': bid, 'columnKey': col, 'content': text, 'authorId': uid, 'votes': [], 'order': 1000}
    body.update(kw)
    s, j = req('POST', '/retros/%s/cards' % bid, tok, body)
    return cid, s, j

c1, s, j = mk(bruno, bruno_id, 'bom', 'Daily curta')
check('criar card', s in (200, 201), (s, str(j)[:200]))
c1b, s, j = mk(bruno, ana_id, 'bom', 'Forjando autor')
check('autor é forçado ao chamador (não aceita authorId do corpo)', j.get('authorId') == bruno_id, j)
c2, s, j = mk(carla, carla_id, 'bom', 'Review boa')
c3, s, j = mk(carla, carla_id, 'bom', 'Pair programming')
c4, s, j = mk(carla, carla_id, 'ruim', 'Deploy lento')
c5, s, j = mk(ana, ana_id, 'acao', 'Automatizar deploy', assignee='Bruno', dueDate='2026-10-30')

s, j = req('POST', '/retros/%s/cards' % bid, carla, {'id': c1, 'boardId': bid, 'columnKey': 'bom', 'content': 'HACK', 'authorId': bruno_id, 'votes': [], 'order': 1000})
s2, cards = req('GET', '/retros/%s/cards' % bid, ana)
byid = {c['id']: c for c in cards}
check('outro participante NÃO edita o texto de card alheio', byid[c1]['content'] == 'Daily curta', byid[c1]['content'])

# votos
s, j = req('POST', '/retros/%s/cards/%s/vote' % (bid, c1), bruno)
check('voto com votação desligada é recusado (409)', s == 409, (s, j))
s, j = req('PATCH', '/retros/' + bid, bruno, {'votingStatus': 'active'})
check('dev NÃO inicia votação (PATCH 403)', s == 403, (s, str(j)[:150]))
s, j = req('PATCH', '/retros/' + bid, ana, {'isCardsRevealed': True, 'votingStatus': 'active'})
check('facilitador revela e inicia votação', s == 200 and j.get('votingStatus') == 'active' and j.get('isCardsRevealed') is True, (s, str(j)[:200]))
s, j = req('POST', '/retros/%s/cards/%s/vote' % (bid, c1), bruno)
check('voto 1 do Bruno', s == 200 and bruno_id in j['votes'], (s, str(j)[:200]))
s, j = req('POST', '/retros/%s/cards/%s/vote' % (bid, c2), bruno)
check('voto 2 do Bruno (mesmo painel)', s == 200, (s, str(j)[:200]))
s, j = req('POST', '/retros/%s/cards/%s/vote' % (bid, c3), bruno)
check('voto 3 no MESMO painel estoura o limite (409)', s == 409, (s, j))
s, j = req('POST', '/retros/%s/cards/%s/vote' % (bid, c4), bruno)
check('voto em OUTRO painel ainda vale (limite é por painel)', s == 200, (s, str(j)[:200]))
s, j = req('POST', '/retros/%s/cards/%s/vote' % (bid, c5), bruno)
check('voto em coluna de ação é recusado (409)', s == 409, (s, j))
s, j = req('POST', '/retros/%s/cards/%s/vote' % (bid, c1), bruno)
check('segundo clique remove o voto (alterna)', s == 200 and bruno_id not in j['votes'], (s, str(j)[:200]))

# concorrência: 6 votos simultâneos de pessoas diferentes + o mesmo usuário em paralelo
res = []
def worker(tok):
    res.append(req('POST', '/retros/%s/cards/%s/vote' % (bid, c3), tok)[0])
ths = [threading.Thread(target=worker, args=(t,)) for t in (carla, ana, carla, ana, carla, ana)]
[t.start() for t in ths]; [t.join() for t in ths]
s, cards = req('GET', '/retros/%s/cards' % bid, ana)
byid = {c['id']: c for c in cards}
votes = byid[c3]['votes']
check('votos concorrentes: sem duplicados', len(votes) == len(set(votes)), votes)
check('votos concorrentes: estado coerente com número de toggles (3 por pessoa = ligado)', set(votes) == {carla_id, ana_id}, (votes, res))
cnt = psql("select count(*) from (select card_id,user_id from retro_card_votes group by 1,2 having count(*)>1) x")
check('banco sem votos duplicados (unique)', cnt == '0', cnt)

# reação não apaga votos
s, j = req('POST', '/retros/%s/cards' % bid, carla, {**byid[c3], 'reactions': {'up': [carla_id]}, 'votes': []})
s, cards = req('GET', '/retros/%s/cards' % bid, ana)
byid = {c['id']: c for c in cards}
check('gravar card inteiro com votes vazio NÃO apaga votos', set(byid[c3]['votes']) == {carla_id, ana_id}, byid[c3]['votes'])
check('reação foi gravada', byid[c3].get('reactions', {}).get('up') == [carla_id], byid[c3].get('reactions'))

# fusão
s, j = req('POST', '/retros/%s/cards/%s/merge/%s' % (bid, c2, c4), ana)
check('fundir cards de colunas diferentes é recusado (409)', s == 409, (s, j))
s, j = req('POST', '/retros/%s/cards/%s/merge/%s' % (bid, c2, c3), ana)
check('fundir na mesma coluna', s == 200, (s, str(j)[:200]))
if s == 200:
    check('fusão: votos unidos sem repetir', len(j['votes']) == len(set(j['votes'])), j['votes'])
    check('fusão: texto vira histórico', 'Pair programming' in (j.get('originalTexts') or []), j.get('originalTexts'))
s, cards = req('GET', '/retros/%s/cards' % bid, ana)
check('fusão: origem foi apagada', c3 not in [c['id'] for c in cards], len(cards))

# apagar card alheio
s, j = req('DELETE', '/retros/%s/cards/%s' % (bid, c2), bruno)
check('participante NÃO apaga card de outro autor (403)', s == 403, s)
s, j = req('DELETE', '/retros/%s/cards/%s' % (bid, c2), ana)
check('facilitador apaga card de qualquer um', s in (200, 204), s)
s, j = req('DELETE', '/retros/%s/cards/%s' % (bid, 'nao-existe'), ana)
check('apagar card inexistente = 404', s == 404, s)

# reset
s, j = req('POST', '/retros/%s/votes/reset' % bid, bruno)
check('dev NÃO reseta votação (403)', s == 403, s)
s, j = req('POST', '/retros/%s/votes/reset' % bid, ana)
check('facilitador reseta votação', s == 200 and j.get('votingStatus') == 'disabled', (s, str(j)[:150]))
s, cards = req('GET', '/retros/%s/cards' % bid, ana)
check('reset zera TODOS os votos', all(not c['votes'] for c in cards), [(c['id'][:4], c['votes']) for c in cards])

# patch: colunas ordenadas e resumo (V39) + participantIds por participante
s, j = req('PATCH', '/retros/' + bid, ana, {'columnSorts': {'bom': True}, 'summary': {'feedbackCount': 3, 'actionItems': ['Automatizar deploy']}})
check('PATCH columnSorts+summary pelo facilitador', s == 200 and j.get('columnSorts') == {'bom': True}, (s, str(j)[:200]))
s, j = req('GET', '/retros/' + bid, carla)
check('columnSorts/summary persistem e chegam a outro participante', j.get('columnSorts') == {'bom': True} and (j.get('summary') or {}).get('feedbackCount') == 3, str(j)[:300])
s, j = req('PATCH', '/retros/' + bid, carla, {'participantIds': [ana_id, carla_id]})
check('participante pode PATCH participantIds (entrada)', s == 200, (s, str(j)[:150]))
s, j = req('PATCH', '/retros/' + bid, carla, {'creatorId': carla_id, 'title': 'Sequestro'})
s2, jb = req('GET', '/retros/' + bid, ana)
check('PATCH de participante NÃO muda criador nem título', jb.get('creatorId') == ana_id and jb.get('title') == 'Retro teste', (s, jb.get('creatorId'), jb.get('title')))

# timer via PATCH
s, j = req('PATCH', '/retros/' + bid, ana, {'timer': {'status': 'running', 'endTime': 1791600000000, 'initialDuration': 300, 'remainingOnPause': 300}})
check('timer aninhado persiste', s == 200 and (j.get('timer') or {}).get('status') == 'running' and int((j.get('timer') or {}).get('endTime')) == 1791600000000, (s, str(j.get('timer'))))

# transfer-control
s, j = req('POST', '/retros/%s/transfer-control' % bid, bruno)
check('assumir controle', s == 200 and j.get('creatorId') == bruno_id, (s, str(j)[:150]))
s, parts = req('GET', '/retros/%s/participants' % bid, bruno)
flags = [p['id'] for p in parts if p.get('isCreator')]
check('só UM facilitador depois de assumir controle', flags == [bruno_id], flags)

# validações
s, j = req('POST', '/retros/%s/cards' % bid, bruno, {'id': str(uuid.uuid4()), 'boardId': bid, 'columnKey': 'bom', 'content': '   ', 'authorId': bruno_id, 'votes': [], 'order': 1})
check('card vazio é recusado (400)', s == 400, (s, str(j)[:120]))
s, j = req('POST', '/retros/%s/cards' % bid, bruno, {'id': str(uuid.uuid4()), 'boardId': bid, 'columnKey': 'inexistente', 'content': 'x', 'authorId': bruno_id, 'votes': [], 'order': 1})
check('coluna inexistente é recusada (400)', s == 400, (s, str(j)[:120]))
s, j = req('GET', '/retros/inexistente', ana)
check('quadro inexistente = 404', s == 404, s)

# chat
s, j = req('POST', '/retros/%s/chat' % bid, bruno, {'id': str(uuid.uuid4()), 'channelId': 'geral', 'senderId': bruno_id, 'senderName': 'Bruno', 'text': 'oi', 'kind': 'text', 'ts': '2026-10-09T10:00:00Z'})
check('chat geral envia', s in (200, 201), (s, str(j)[:150]))
s, j = req('GET', '/retros/%s/chat?channelId=geral' % bid, carla)
check('chat geral lê', s == 200 and len(j) >= 1, (s, str(j)[:150]))
s, j = req('GET', '/retros/%s/chat?channelId=geral' % bid, dani)
check('intruso NÃO lê chat (403)', s == 403, s)

print()
ok = sum(1 for r in results if r[1]); print('RESULTADO: %d/%d' % (ok, len(results)))
for n, p, d in results:
    if not p: print('  FALHOU:', n, '|', str(d)[:200])
