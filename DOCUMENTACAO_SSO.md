# Transição para SSO Corporativo (Documentação Oficial)

## Objetivo
Esta documentação descreve o plano de chaveamento para substituir o Firebase Auth pelo SSO Corporativo (OAuth2 / OIDC), consolidando a identidade única baseada no `sso_id` e no e-mail institucional na tabela `users`.

## Passos para o Chaveamento

1. **Configuração de Identidade (IdP)**
   - Obter as credenciais (Client ID, Client Secret, e Endpoints de Autorização/Token) do provedor de SSO corporativo.
   - Configurar o provedor no `application.yml` sob `spring.security.oauth2.client.registration`.

2. **Substituição de Dependências no Backend**
   - Remover as dependências do Firebase Admin SDK.
   - Adicionar o `spring-boot-starter-oauth2-client` e `spring-boot-starter-oauth2-resource-server` no `pom.xml`.

3. **Atualização da Tabela de Usuários**
   - Garantir que a coluna `sso_id` seja populada com o `sub` (subject) do JWT proveniente do provedor de SSO.
   - A amarração final entre o usuário do Jira e do Agile Space continuará usando o `email` corporativo como chave de unificação, e posteriormente o `sso_id`.

4. **Nova Classe de Segurança (SecurityConfig)**
   - Modificar a validação do token JWT atual (do Firebase) para validar o token do provedor corporativo utilizando um JWK Set Uri (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`).

5. **Ajuste no Frontend**
   - Remover a biblioteca `firebase/auth`.
   - Adicionar uma biblioteca como `next-auth` configurada com o provider customizado OIDC ou o correspondente corporativo.
   - Transição do fluxo de login para o redirecionamento OAuth2.

## Rollback
Em caso de falhas durante o chaveamento, as instâncias de segurança do Firebase permanecerão comentadas no código, sendo ativadas se houver falha persistente no gateway do SSO.
