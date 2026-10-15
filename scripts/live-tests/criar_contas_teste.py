"""
Cria várias contas de teste (para carga em sala do Poker/Retro ou testes com muitos usuários) e guarda os tokens.

Uso:
    python criar_contas_teste.py --qtd 30
    python criar_contas_teste.py --base https://app.espacoagil.com.br/api --qtd 30 --sim

- E-mails: <prefixo>.<NN>@<dominio>, ex.: carga.01@totvs.com.br. Use um domínio aceito pelo ALLOWED_EMAIL_DOMAIN.
- Senha: lida de CARGA_SENHA ou perguntada na hora (nunca vai na linha de comando nem no arquivo de saída).
  É a mesma para todas as contas; só use senha de teste.
- Conta que já existe: entra com a senha e só atualiza o token (rodar de novo é seguro).
- O cadastro e o login aceitam 10 chamadas por minuto por IP: o script espera a janela, então 30 contas levam ~3 min.
- Saída: contas-teste.json (fora do git) com e-mail, id e token de cada conta. Os tokens valem 24 h.
- Servidor que não é local exige --sim, para ninguém criar contas no lugar errado sem querer.

Para apagar as contas depois, rode no servidor (ajuste o prefixo e o domínio):
    docker exec agile-space-db psql -U <usuario> -d <banco> -c "delete from users where email like 'carga.%@totvs.com.br'"
Se alguma conta entrou em sala ou squad, apague antes os vínculos (poker_participants, project_member_roles,
squad_members) dessas contas, ou use o painel de administração.
"""
import argparse, getpass, json, os, sys, time, urllib.error, urllib.parse, urllib.request

for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(encoding='utf-8')
    except Exception:
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
AUTH_PER_MINUTE = 9  # o servidor corta na 11ª chamada em 60 s; fica uma de folga
_calls = []


def respect_auth_limit():
    now = time.time()
    _calls[:] = [t for t in _calls if now - t < 60]
    if len(_calls) >= AUTH_PER_MINUTE:
        wait = 61 - (now - _calls[0])
        print(f'  … limite de 10 cadastros/logins por minuto: esperando {wait:.0f}s')
        time.sleep(max(0.0, wait))
        _calls[:] = [t for t in _calls if time.time() - t < 60]
    _calls.append(time.time())


def post(base, path, body):
    respect_auth_limit()
    req = urllib.request.Request(base + path, method='POST', data=json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b'null')
    except urllib.error.HTTPError as e:
        text = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {'message': text[:200]}


def message(body):
    return (body or {}).get('message') or (body or {}).get('error') or ''


def main():
    ap = argparse.ArgumentParser(description='Cria contas de teste e guarda os tokens.')
    ap.add_argument('--base', default=os.environ.get('API_BASE', 'http://localhost:8002/api'),
                    help='URL da API, terminando em /api (padrão: API_BASE ou http://localhost:8002/api)')
    ap.add_argument('--qtd', type=int, default=20, help='quantas contas (padrão 20, máximo 200)')
    ap.add_argument('--prefixo', default='carga', help='início do e-mail (padrão "carga")')
    ap.add_argument('--dominio', default='totvs.com.br', help='domínio do e-mail (padrão totvs.com.br)')
    ap.add_argument('--inicio', type=int, default=1, help='número da primeira conta (padrão 1)')
    ap.add_argument('--saida', default=os.path.join(HERE, 'contas-teste.json'), help='arquivo de saída')
    ap.add_argument('--sim', action='store_true', help='confirma criar contas num servidor que não é local')
    args = ap.parse_args()

    base = args.base.rstrip('/')
    host = urllib.parse.urlparse(base).hostname or ''
    local = host in ('localhost', '127.0.0.1', '::1') or host.endswith('.localhost') or host.endswith('.test')
    if not local and not args.sim:
        sys.exit(f'{host} não é local. Confira o servidor e rode de novo com --sim para criar as contas lá.')
    if not 1 <= args.qtd <= 200:
        sys.exit('--qtd vai de 1 a 200.')

    senha = os.environ.get('CARGA_SENHA') or getpass.getpass('Senha das contas de teste (8+ caracteres): ')
    if len(senha) < 8:
        sys.exit('A senha precisa ter pelo menos 8 caracteres.')

    contas = {}
    if os.path.exists(args.saida):
        with open(args.saida, encoding='utf-8') as f:
            contas = {c['email']: c for c in json.load(f).get('contas', [])}

    largura = max(2, len(str(args.inicio + args.qtd - 1)))
    criadas = existentes = falhas = 0
    print(f'Servidor: {base}  ·  {args.qtd} contas {args.prefixo}.NN@{args.dominio}')
    for n in range(args.inicio, args.inicio + args.qtd):
        num = str(n).zfill(largura)
        email = f'{args.prefixo}.{num}@{args.dominio}'
        nome = f'{args.prefixo.capitalize()} Teste {num}'
        st, body = post(base, '/auth/register', {'email': email, 'name': nome, 'password': senha})
        if st == 200 and body and body.get('token'):
            criadas += 1
            situacao = 'criada'
        else:
            # Já cadastrada (ou outro motivo): tenta entrar com a mesma senha.
            st2, body2 = post(base, '/auth/login', {'email': email, 'password': senha})
            if st2 == 200 and body2 and body2.get('token'):
                existentes += 1
                body, situacao = body2, 'já existia'
            else:
                falhas += 1
                print(f'  FALHA {email}: cadastro {st} {message(body)} · login {st2} {message(body2)}')
                continue
        contas[email] = {'email': email, 'nome': nome, 'id': body.get('id'), 'token': body.get('token'),
                         'geradoEm': time.strftime('%Y-%m-%dT%H:%M:%S')}
        print(f'  {situacao:10s} {email}')

    with open(args.saida, 'w', encoding='utf-8') as f:
        json.dump({'base': base, 'contas': sorted(contas.values(), key=lambda c: c['email'])}, f, ensure_ascii=False, indent=2)
    print(f'\n{criadas} criadas, {existentes} já existiam, {falhas} falhas. Tokens em {args.saida} (válidos por 24 h).')
    if falhas:
        sys.exit(1)


if __name__ == '__main__':
    main()
