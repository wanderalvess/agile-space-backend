package com.agilespace.backend.service;

import com.agilespace.backend.dto.JiraSearchRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class JiraService {

    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    // Mesmo valor usado no jiradash (jira-api.js) pra completar worklog
    // truncado — busca sequencial (1 issue por vez, dentro da mesma request)
    // arriscava estourar o readTimeout (45s) numa página com várias issues de
    // worklog longo, já que cada fetch extra é bloqueante.
    private static final int WORKLOG_FETCH_CONCURRENCY = 8;

    // RestTemplate "estrito" (validação de certificado padrão da JVM) — tentado
    // primeiro em toda chamada. Só cai pro trust-all abaixo se o handshake
    // falhar, então uma instância Jira com certificado válido (Cloud, a
    // maioria) nunca fica exposta a MITM só porque outro squad usa um Jira
    // corporativo com certificado self-signed.
    private final RestTemplate strictRestTemplate;
    // RestTemplate que ignora validação de certificado — existe só pro caso
    // documentado de SSLHandshakeException com Jira corporativo self-signed;
    // usado apenas como fallback, nunca como padrão (ver exchangeSecure).
    private final RestTemplate trustAllRestTemplate;
    private final ObjectMapper objectMapper;

    public JiraService() {
        SimpleClientHttpRequestFactory strictFactory = new SimpleClientHttpRequestFactory();
        strictFactory.setConnectTimeout(15000);
        strictFactory.setReadTimeout(45000);
        this.strictRestTemplate = new RestTemplate(strictFactory);

        SimpleClientHttpRequestFactory trustAllFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                if (connection instanceof HttpsURLConnection) {
                    try {
                        SSLContext sslContext = SSLContext.getInstance("TLS");
                        sslContext.init(null, new TrustManager[]{
                            new X509TrustManager() {
                                public X509Certificate[] getAcceptedIssuers() { return null; }
                                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                            }
                        }, new SecureRandom());
                        ((HttpsURLConnection) connection).setSSLSocketFactory(sslContext.getSocketFactory());
                        ((HttpsURLConnection) connection).setHostnameVerifier((hostname, session) -> true);
                    } catch (Exception e) {
                        log.error("Error setting trust-all SSL context", e);
                    }
                }
                super.prepareConnection(connection, httpMethod);
            }
        };
        trustAllFactory.setConnectTimeout(15000);
        trustAllFactory.setReadTimeout(45000);
        this.trustAllRestTemplate = new RestTemplate(trustAllFactory);

        this.objectMapper = new ObjectMapper();
    }

    /**
     * Bloqueia alvos claramente sensíveis (loopback, link-local — inclui
     * 169.254.169.254, o endpoint de metadata de cloud) antes de proxyar
     * qualquer request. `domain` é texto livre salvo por usuário (squad pode
     * apontar pra qualquer instância Jira), então não dá pra travar num host
     * fixo — mas ninguém tem Jira de verdade rodando em loopback/link-local,
     * então bloquear só esses reduz a superfície de SSRF sem arriscar quebrar
     * um Jira corporativo legítimo em rede interna (10.x/172.16.x/192.168.x
     * continuam permitidos — sem visibilidade de onde o Jira real está
     * hospedado pra saber se travar essas faixas quebraria produção).
     */
    private void assertNotBlockedHost(URI uri) {
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("Domain inválido.");
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
                throw new IllegalArgumentException("Domain não permitido: " + host);
            }
        } catch (java.net.UnknownHostException e) {
            // Não resolveu: deixa a própria chamada HTTP falhar adiante com o
            // erro de conexão de sempre, em vez de travar aqui — evita
            // bloquear falso-positivo por falha transitória de DNS.
        }
    }

    /**
     * Tenta a chamada com validação de certificado padrão; só cai pro
     * trust-all se o handshake TLS falhar (self-signed/CA corporativa não
     * confiada pela JVM) — mantém MITM protection pra qualquer host com
     * certificado válido, e preserva compatibilidade com o Jira corporativo
     * que motivou o trust-all original.
     */
    private <T> ResponseEntity<T> exchangeSecure(URI uri, HttpEntity<Void> entity, Class<T> responseType) {
        assertNotBlockedHost(uri);
        try {
            return exchangeWithRetry(strictRestTemplate, uri, entity, responseType);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            if (isTlsTrustFailure(e)) {
                log.warn("TLS handshake falhou com validação padrão para {} — tentando com trust-all (esperado só para Jira corporativo com certificado próprio): {}", uri.getHost(), e.getMessage());
                return exchangeWithRetry(trustAllRestTemplate, uri, entity, responseType);
            }
            throw e;
        }
    }

    private boolean isTlsTrustFailure(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof javax.net.ssl.SSLHandshakeException
                    || cur instanceof java.security.cert.CertificateException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * Envelopa restTemplate.exchange com retry para 429 (rate limit) do Jira,
     * respeitando o header Retry-After quando presente. Sem isso, um 429 durante
     * o sync do squad falha o run inteiro e força FULL sync na próxima tentativa.
     */
    private <T> ResponseEntity<T> exchangeWithRetry(RestTemplate restTemplate, URI uri, HttpEntity<Void> entity, Class<T> responseType) {
        int attempt = 0;
        while (true) {
            try {
                return restTemplate.exchange(uri, HttpMethod.GET, entity, responseType);
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                if (e.getStatusCode().value() == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    long waitMs = retryAfterMillis(e, attempt);
                    log.warn("Jira rate limited (429) on {}, retry {}/{} after {}ms", uri, attempt + 1, MAX_RATE_LIMIT_RETRIES, waitMs);
                    sleepUninterruptibly(waitMs);
                    attempt++;
                    continue;
                }
                throw e;
            }
        }
    }

    private long retryAfterMillis(org.springframework.web.client.HttpStatusCodeException e, int attempt) {
        List<String> retryAfterHeaders = e.getResponseHeaders() != null ? e.getResponseHeaders().get("Retry-After") : null;
        if (retryAfterHeaders != null && !retryAfterHeaders.isEmpty()) {
            try {
                return Long.parseLong(retryAfterHeaders.get(0).trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // Retry-After em formato de data HTTP não é tratado aqui; cai no backoff exponencial abaixo.
            }
        }
        long base = 1000L * (1L << attempt); // 1s, 2s, 4s
        return base + (long) (Math.random() * 250);
    }

    private void sleepUninterruptibly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Busca o worklog completo de cada issue em `pendingKeys` com paralelismo
     * limitado e aplica o resultado no ObjectNode correspondente em
     * `pendingWorklogNodes` (mesma posição). Mutação dos nós acontece só na
     * thread chamadora, depois que todas as tasks terminam — evita mexer em
     * árvore Jackson a partir de threads concorrentes.
     */
    private void fetchTruncatedWorklogsConcurrently(String cleanDomain, HttpEntity<Void> entity, List<String> pendingKeys, List<ObjectNode> pendingWorklogNodes) {
        if (pendingKeys.isEmpty()) return;
        int poolSize = Math.min(WORKLOG_FETCH_CONCURRENCY, pendingKeys.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(poolSize);
        try {
            List<java.util.concurrent.CompletableFuture<JsonNode>> futures = new java.util.ArrayList<>();
            for (String key : pendingKeys) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> fetchFullWorklogArray(cleanDomain, key, entity), pool));
            }
            for (int i = 0; i < futures.size(); i++) {
                JsonNode wlArray = futures.get(i).join();
                if (wlArray != null) {
                    pendingWorklogNodes.get(i).set("worklogs", wlArray);
                    log.info("Successfully fetched {} worklogs for {}", wlArray.size(), pendingKeys.get(i));
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private JsonNode fetchFullWorklogArray(String cleanDomain, String key, HttpEntity<Void> entity) {
        try {
            log.info("Fetching complete worklogs for {} (truncated in search response)...", key);
            String wlUrl = "https://" + cleanDomain + "/rest/api/2/issue/" + key + "/worklog";
            ResponseEntity<String> wlResponse = exchangeSecure(new URI(wlUrl), entity, String.class);
            JsonNode wlRoot = objectMapper.readTree(wlResponse.getBody());
            JsonNode wlArray = wlRoot.get("worklogs");
            return (wlArray != null && wlArray.isArray()) ? wlArray : null;
        } catch (Exception wlErr) {
            log.error("Failed to fetch complete worklogs for {}", key, wlErr);
            return null;
        }
    }

    public ResponseEntity<String> searchIssues(JiraSearchRequest request) {
        String cleanDomain = request.getDomain().trim().replace("https://", "").replace("http://", "");
        
        int maxResults = request.getMaxResults() != null ? Math.min(100, Math.max(1, request.getMaxResults())) : 50;
        int startAt = request.getStartAt() != null ? Math.max(0, request.getStartAt()) : 0;
        
        String fieldsStr;
        if (request.getFields() != null && !request.getFields().isEmpty()) {
            fieldsStr = String.join(",", request.getFields());
        } else {
            fieldsStr = "summary,description,issuetype,status,priority,assignee,updated,created,duedate,labels,parent,subtasks,customfield_10100,customfield_10046,customfield_25307,customfield_10016,customfield_10002,customfield_10004,customfield_10015,customfield_10014,customfield_25300,customfield_25301,timespent,timeoriginalestimate,timeestimate,aggregatetimespent,aggregatetimeoriginalestimate,aggregatetimeestimate,worklog,comment";
        }

        // Importante: passamos a URL como URI para o restTemplate.exchange.
        // Se passarmos como String, o RestTemplate decodifica e re-encoda, gerando dupla codificação
        // (por exemplo, transformando %20 em %2520), o que faz o Jira rejeitar a JQL acusando caractere reservado '%'.
        URI jiraUri;
        try {
            String encodedJql = java.net.URLEncoder.encode(request.getJql().trim(), java.nio.charset.StandardCharsets.UTF_8);
            String encodedFields = java.net.URLEncoder.encode(fieldsStr, java.nio.charset.StandardCharsets.UTF_8);
            
            String expand = Boolean.TRUE.equals(request.getIncludeChangelog()) ? "renderedFields,changelog" : "renderedFields";
            String urlStr = "https://" + cleanDomain + "/rest/api/2/search"
                    + "?jql=" + encodedJql
                    + "&fields=" + encodedFields
                    + "&expand=" + expand
                    + "&maxResults=" + maxResults
                    + "&startAt=" + startAt;
            
            jiraUri = new URI(urlStr);
        } catch (Exception e) {
            log.error("Failed to construct encoded Jira URI", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao construir URL de busca do Jira: " + e.getMessage() + "\"}");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + request.getToken().trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.info("Proxying JQL search to Jira URI: {}", jiraUri);
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            String body = response.getBody();
            
            if (body != null) {
                // Parse the response to fetch truncated worklogs
                JsonNode root = objectMapper.readTree(body);
                JsonNode issuesNode = root.get("issues");
                if (issuesNode != null && issuesNode.isArray()) {
                    List<String> pendingKeys = new java.util.ArrayList<>();
                    List<ObjectNode> pendingWorklogNodes = new java.util.ArrayList<>();
                    for (JsonNode issue : issuesNode) {
                        JsonNode fieldsNode = issue.get("fields");
                        if (fieldsNode != null) {
                            JsonNode worklogNode = fieldsNode.get("worklog");
                            if (worklogNode != null) {
                                int totalWorklogs = worklogNode.has("total") ? worklogNode.get("total").asInt() : 0;
                                JsonNode worklogsArray = worklogNode.get("worklogs");
                                int currentCount = (worklogsArray != null && worklogsArray.isArray()) ? worklogsArray.size() : 0;

                                if (totalWorklogs > currentCount) {
                                    pendingKeys.add(issue.get("key").asText());
                                    pendingWorklogNodes.add((ObjectNode) worklogNode);
                                }
                            }
                        }
                    }
                    fetchTruncatedWorklogsConcurrently(cleanDomain, entity, pendingKeys, pendingWorklogNodes);
                }
                return ResponseEntity.ok(objectMapper.writeValueAsString(root));
            }
            
            return ResponseEntity.ok(body);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Jira search failed with status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Jira search failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao conectar ao Jira: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Lista os campos do Jira (/rest/api/2/field), incluindo customfields com
     * seu nome e schema — usado pra resolver dinamicamente IDs de customfield
     * (ex: Sprint) por nome/tipo, em vez de depender de um ID fixo digitado
     * manualmente por squad, que varia entre instâncias/projetos Jira.
     */
    public ResponseEntity<String> getFields(String domain, String token) {
        String cleanDomain = domain.trim().replace("https://", "").replace("http://", "");
        URI jiraUri;
        try {
            jiraUri = new URI("https://" + cleanDomain + "/rest/api/2/field");
        } catch (Exception e) {
            log.error("Failed to construct Jira field URI", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao construir URL do Jira: " + e.getMessage() + "\"}");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.info("Fetching Jira field metadata from URI: {}", jiraUri);
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Jira field metadata failed with status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Jira field metadata failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao buscar campos do Jira: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Busca metadados oficiais de uma sprint (/rest/agile/1.0/sprint/{id}) —
     * nome/estado/datas vindos direto do Jira, em vez de confiar no blob
     * embutido no customfield Sprint das issues (que no formato Server/DC é
     * um toString() Java sujeito a truncar nome com vírgula via regex, e no
     * melhor caso é só uma cópia do que a API oficial já devolve limpo).
     */
    public ResponseEntity<String> getSprint(String domain, String token, String sprintId) {
        String cleanDomain = domain.trim().replace("https://", "").replace("http://", "");
        URI jiraUri;
        try {
            jiraUri = new URI("https://" + cleanDomain + "/rest/agile/1.0/sprint/" + java.net.URLEncoder.encode(sprintId.trim(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to construct Jira sprint URI", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao construir URL do Jira: " + e.getMessage() + "\"}");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.info("Fetching Jira sprint metadata from URI: {}", jiraUri);
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Jira sprint metadata failed with status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Jira sprint metadata failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao buscar sprint no Jira: " + e.getMessage() + "\"}");
        }
    }

    /**
     * Busca um anexo/thumbnail do próprio Jira (/secure/attachment/...,
     * /secure/thumbnail/...) e devolve os bytes com o Content-Type original —
     * usado pelo Modo Teatro do Showcase pra embutir evidência hospedada no
     * Jira, que como <img> cross-origin nunca carrega (Jira exige
     * sessão/cookie que o navegador não envia num request de terceiro).
     */
    public ResponseEntity<?> getAttachment(String domain, String token, String attachmentUrl) {
        String cleanDomain = domain.trim().replace("https://", "").replace("http://", "");

        URI parsedUrl;
        try {
            parsedUrl = new URI(attachmentUrl);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("{\"error\": \"URL de anexo inválida.\"}");
        }
        // A URL do anexo precisa pertencer ao MESMO domínio configurado — sem essa
        // checagem, esse endpoint vira um proxy autenticado aberto: qualquer host
        // https informado receberia o PAT do usuário no header Authorization.
        if (!"https".equalsIgnoreCase(parsedUrl.getScheme()) || !cleanDomain.equalsIgnoreCase(parsedUrl.getHost())) {
            return ResponseEntity.badRequest().body("{\"error\": \"URL de anexo fora do domínio Jira configurado.\"}");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.set("Accept", "image/*");
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.info("Fetching Jira attachment from URI: {}", parsedUrl);
            ResponseEntity<byte[]> response = exchangeSecure(parsedUrl, entity, byte[].class);
            MediaType contentType = response.getHeaders().getContentType();
            if (contentType == null || !"image".equals(contentType.getType())) {
                // Token inválido/sem permissão costuma devolver 200 com a página de
                // login em HTML, não um 401/403 — sem essa checagem isso "funcionaria"
                // como se fosse uma imagem válida e quebraria só no <img> do cliente.
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("{\"error\": \"O anexo retornado não é uma imagem válida.\"}");
            }
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setContentType(contentType);
            responseHeaders.setCacheControl("private, max-age=300");
            return new ResponseEntity<>(response.getBody(), responseHeaders, HttpStatus.OK);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Jira attachment fetch failed with status: {}", e.getStatusCode());
            return ResponseEntity.status(e.getStatusCode()).body("{\"error\": \"Erro do Jira: " + e.getStatusCode().value() + "\"}");
        } catch (Exception e) {
            log.error("Jira attachment fetch failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\": \"Erro ao buscar anexo do Jira: " + e.getMessage() + "\"}");
        }
    }

    public ResponseEntity<String> getMyself(String domain, String token) {
        String cleanDomain = domain.trim().replace("https://", "").replace("http://", "");
        URI jiraUri;
        try {
            jiraUri = new URI("https://" + cleanDomain + "/rest/api/2/myself");
        } catch (Exception e) {
            log.error("Failed to construct Jira myself URI", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao construir URL do Jira: " + e.getMessage() + "\"}");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.info("Fetching authenticated user info from Jira URI: {}", jiraUri);
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Jira myself failed with status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Jira myself failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao buscar dados do usuário no Jira: " + e.getMessage() + "\"}");
        }
    }

    public ResponseEntity<String> getGreenhopperWorkData(String domain, String token, Long rapidViewId, String selectedProjectKey) {
        if (rapidViewId == null) {
            return ResponseEntity.badRequest().body("{\"error\": \"rapidViewId é obrigatório.\"}");
        }
        String cleanDomain = domain.trim().replace("https://", "").replace("http://", "");
        URI jiraUri;
        try {
            String urlStr = "https://" + cleanDomain + "/rest/greenhopper/1.0/xboard/work/allData.json?rapidViewId=" + rapidViewId;
            if (selectedProjectKey != null && !selectedProjectKey.trim().isEmpty()) {
                urlStr += "&selectedProjectKey=" + java.net.URLEncoder.encode(selectedProjectKey.trim(), java.nio.charset.StandardCharsets.UTF_8);
            }
            jiraUri = new URI(urlStr);
        } catch (Exception e) {
            log.error("Failed to construct Greenhopper URI", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao construir URL do Greenhopper: " + e.getMessage() + "\"}");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.info("Fetching Greenhopper work data from URI: {}", jiraUri);
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Greenhopper work data failed with status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Greenhopper work data failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao buscar dados do quadro Greenhopper: " + e.getMessage() + "\"}");
        }
    }
}
