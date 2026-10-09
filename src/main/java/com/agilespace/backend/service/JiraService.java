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
        // Redirecionamento NUNCA é seguido pelo HttpURLConnection: o Bearer iria junto para onde o Location
        // mandar. followSameHostRedirects segue só redirecionamento https para o MESMO host, revalidando cada salto.
        SimpleClientHttpRequestFactory strictFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
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
                connection.setInstanceFollowRedirects(false);
            }
        };
        trustAllFactory.setConnectTimeout(15000);
        trustAllFactory.setReadTimeout(45000);
        this.trustAllRestTemplate = new RestTemplate(trustAllFactory);

        this.objectMapper = new ObjectMapper();
    }

    /**
     * Lista opcional de domínios Jira aceitos (app.jira.allowed-domains, separada por vírgula; cada entrada
     * vale para o host exato e seus subdomínios). Vazia = sem restrição por nome (comportamento anterior).
     * Com ela preenchida, `domain` livre não consegue apontar o token do Jira para um host arbitrário.
     */
    @org.springframework.beans.factory.annotation.Value("${app.jira.allowed-domains:}")
    private String allowedDomains = "";

    void assertAllowedDomain(String host) {
        if (allowedDomains == null || allowedDomains.isBlank()) return;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String entry : allowedDomains.split(",")) {
            String d = entry.trim().toLowerCase(java.util.Locale.ROOT);
            if (!d.isEmpty() && (h.equals(d) || h.endsWith("." + d))) return;
        }
        throw new IllegalArgumentException("Domain não permitido: " + host);
    }

    /**
     * `app.jira.allow-private-hosts`: libera redes privadas (10.x, 172.16-31.x, 192.168.x, 100.64/10, fc00::/7).
     * Padrão false: o backend roda numa VM na nuvem e o Jira corporativo é alcançado pela internet; liberar redes
     * privadas deixaria `domain` (texto livre do usuário) apontar para serviços internos (banco, actuator, outros
     * containers) e ler a resposta. Quem tiver Jira em rede interna liga explicitamente.
     */
    @org.springframework.beans.factory.annotation.Value("${app.jira.allow-private-hosts:false}")
    private boolean allowPrivateHosts = false;

    /**
     * Normaliza o domínio digitado: remove esquema e barras finais e exige SÓ host[:porta]. Caminho, credencial
     * (user@host), consulta e fragmento são recusados (400) — `domain` é concatenado em "https://" + domain + path.
     */
    static String cleanDomain(String domain) {
        String d = domain == null ? "" : domain.trim().replaceFirst("(?i)^https?://", "");
        while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
        if (!d.matches("[A-Za-z0-9.-]+(:[0-9]{1,5})?")) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Domínio do Jira inválido: informe só o endereço (ex.: jira.empresa.com.br), sem caminho, usuário ou parâmetros.");
        }
        return d;
    }

    /** Sempre bloqueado: loopback, link-local (inclui 169.254.169.254, metadata de nuvem), 0.0.0.0 e multicast. */
    static boolean isAlwaysBlocked(InetAddress addr) {
        return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress();
    }

    /** Redes privadas: 10/8, 172.16/12, 192.168/16, CGNAT 100.64/10 e IPv6 ULA fc00::/7. */
    static boolean isPrivateNetwork(InetAddress addr) {
        if (addr.isSiteLocalAddress()) return true;
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF, second = b[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        }
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    /**
     * Bloqueia alvos sensíveis antes de proxyar qualquer request: loopback, link-local, 0.0.0.0, multicast e,
     * por padrão, redes privadas (ver allowPrivateHosts). `domain` é texto livre salvo por usuário, então não dá
     * pra travar num host fixo. Confere TODOS os endereços que o nome resolve. Limitação conhecida: a checagem é
     * feita antes da conexão (DNS rebinding — resposta diferente entre a checagem e a conexão — não é coberto).
     */
    public void assertNotBlockedHost(URI uri) {
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("Domain inválido.");
        assertAllowedDomain(host);
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isAlwaysBlocked(addr) || (!allowPrivateHosts && isPrivateNetwork(addr))) {
                    throw new IllegalArgumentException("Domain não permitido: " + host);
                }
            }
        } catch (java.net.UnknownHostException e) {
            // Não resolveu: deixa a própria chamada HTTP falhar adiante com o
            // erro de conexão de sempre, em vez de travar aqui — evita
            // bloquear falso-positivo por falha transitória de DNS.
        }
    }

    /** Corpo de erro JSON válido (aspas e quebras de linha escapadas). */
    static String jsonError(String message) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"Erro ao falar com o Jira.\"}";
        }
    }

    /** Motivo curto e seguro para o cliente: não vaza URL, consulta nem detalhes de rede interna. */
    static String describe(Exception e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof java.net.SocketTimeoutException) return "tempo esgotado ao esperar o Jira";
            if (cur instanceof java.net.UnknownHostException) return "domínio do Jira não encontrado";
            if (cur instanceof java.net.ConnectException) return "não foi possível conectar ao Jira";
            if (cur instanceof javax.net.ssl.SSLException) return "falha no certificado TLS do Jira";
            cur = cur.getCause();
        }
        if (e instanceof IllegalArgumentException && e.getMessage() != null && e.getMessage().startsWith("Domain")) {
            return "domínio do Jira não permitido";
        }
        if (e instanceof org.springframework.web.client.RestClientException) return "falha de conexão com o Jira";
        return "erro inesperado (veja os logs do servidor)";
    }

    /**
     * Tenta a chamada com validação de certificado padrão; só cai pro
     * trust-all se o handshake TLS falhar (self-signed/CA corporativa não
     * confiada pela JVM) — mantém MITM protection pra qualquer host com
     * certificado válido, e preserva compatibilidade com o Jira corporativo
     * que motivou o trust-all original.
     */
    /** GET autenticado com as mesmas proteções do proxy (host bloqueado, TLS estrito primeiro, redirecionamento controlado, 429). */
    public ResponseEntity<String> exchangeGet(URI uri, HttpEntity<Void> entity) {
        return exchangeSecure(uri, entity, String.class);
    }

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
                return followSameHostRedirects(restTemplate, uri, entity, responseType);
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                if (e.getStatusCode().value() == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    long waitMs = retryAfterMillis(e, attempt);
                    log.warn("Jira rate limited (429) on {}, retry {}/{} after {}ms", uri.getHost(), attempt + 1, MAX_RATE_LIMIT_RETRIES, waitMs);
                    sleepUninterruptibly(waitMs);
                    attempt++;
                    continue;
                }
                throw e;
            }
        }
    }

    private static final int MAX_REDIRECTS = 3;

    /**
     * Segue redirecionamento (301/302/303/307/308) só para https no MESMO host e porta — por exemplo barra final
     * ou login do Jira. Qualquer outro destino é recusado: o Bearer não sai do host configurado.
     */
    private <T> ResponseEntity<T> followSameHostRedirects(RestTemplate restTemplate, URI uri, HttpEntity<Void> entity, Class<T> responseType) {
        URI current = uri;
        for (int hop = 0; ; hop++) {
            ResponseEntity<T> response = restTemplate.exchange(current, HttpMethod.GET, entity, responseType);
            int status = response.getStatusCode().value();
            URI location = response.getHeaders().getLocation();
            if (status < 300 || status >= 400 || location == null) return response;
            URI next = current.resolve(location);
            boolean sameHost = "https".equalsIgnoreCase(next.getScheme()) && next.getHost() != null
                    && next.getHost().equalsIgnoreCase(uri.getHost()) && next.getPort() == uri.getPort();
            if (!sameHost || hop >= MAX_REDIRECTS) {
                log.warn("Redirecionamento do Jira recusado (host {} -> {}).", uri.getHost(), next.getHost());
                throw new org.springframework.web.client.RestClientException("Redirecionamento do Jira recusado.");
            }
            assertNotBlockedHost(next);
            current = next;
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
        String cleanDomain = cleanDomain(request.getDomain());
        
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
                    .body(jsonError("Erro ao construir URL de busca do Jira: " + describe(e)));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + request.getToken().trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.debug("Proxying JQL search to Jira host {}", jiraUri.getHost());
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
                    .body(jsonError("Erro ao conectar ao Jira: " + describe(e)));
        }
    }

    /**
     * Lista os campos do Jira (/rest/api/2/field), incluindo customfields com
     * seu nome e schema — usado pra resolver dinamicamente IDs de customfield
     * (ex: Sprint) por nome/tipo, em vez de depender de um ID fixo digitado
     * manualmente por squad, que varia entre instâncias/projetos Jira.
     */
    public ResponseEntity<String> getFields(String domain, String token) {
        String cleanDomain = cleanDomain(domain);
        URI jiraUri;
        try {
            jiraUri = new URI("https://" + cleanDomain + "/rest/api/2/field");
        } catch (Exception e) {
            log.error("Failed to construct Jira field URI", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao construir URL do Jira: " + describe(e)));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.debug("Fetching Jira field metadata from host {}", jiraUri.getHost());
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Jira field metadata failed with status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Jira field metadata failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao buscar campos do Jira: " + describe(e)));
        }
    }



    /**
     * Busca um caso de teste do Zephyr Scale/ATM (/rest/atm/1.0/testcase/{key}). Só leitura, com o token do próprio usuário.
     */
    public ResponseEntity<String> getTestCase(String domain, String token, String testCaseKey) {
        String cleanDomain = cleanDomain(domain);
        String key = testCaseKey.trim();
        if (!key.matches("[A-Za-z0-9_-]{1,60}")) {
            return ResponseEntity.badRequest().body("{\"error\": \"Código de caso de teste inválido.\"}");
        }
        URI jiraUri;
        try {
            jiraUri = new URI("https://" + cleanDomain + "/rest/atm/1.0/testcase/" + key);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao construir URL do Jira: " + describe(e)));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.warn("Jira testcase {} failed with status {}", key, e.getStatusCode());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Jira testcase {} failed: {}", key, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao buscar o caso de teste no Jira: " + describe(e)));
        }
    }

    /**
     * Lista os quadros Scrum de um projeto (/rest/agile/1.0/board?projectKeyOrId=KEY&type=scrum), para descobrir o
     * rapidViewId sem o usuário precisar digitar. Devolve o JSON do Jira ({values:[{id,name,type}]}).
     */
    public ResponseEntity<String> listScrumBoards(String domain, String token, String projectKey) {
        String cleanDomain = cleanDomain(domain);
        URI jiraUri;
        try {
            jiraUri = new URI("https://" + cleanDomain + "/rest/agile/1.0/board?type=scrum&maxResults=50&projectKeyOrId="
                    + java.net.URLEncoder.encode(projectKey.trim(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao construir URL do Jira: " + describe(e)));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.warn("Jira boards failed with status {}", e.getStatusCode());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Jira boards failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao buscar quadros no Jira: " + describe(e)));
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
        String cleanDomain = cleanDomain(domain);
        URI jiraUri;
        try {
            jiraUri = new URI("https://" + cleanDomain + "/rest/agile/1.0/sprint/" + java.net.URLEncoder.encode(sprintId.trim(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Failed to construct Jira sprint URI", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao construir URL do Jira: " + describe(e)));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.debug("Fetching Jira sprint metadata from host {}", jiraUri.getHost());
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Jira sprint metadata failed with status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Jira sprint metadata failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao buscar sprint no Jira: " + describe(e)));
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
        String cleanDomain = cleanDomain(domain);

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
            log.debug("Fetching Jira attachment from host {}", parsedUrl.getHost());
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
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(jsonError("Erro ao buscar anexo do Jira: " + describe(e)));
        }
    }

    public ResponseEntity<String> getMyself(String domain, String token) {
        String cleanDomain = cleanDomain(domain);
        URI jiraUri;
        try {
            jiraUri = new URI("https://" + cleanDomain + "/rest/api/2/myself");
        } catch (Exception e) {
            log.error("Failed to construct Jira myself URI", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao construir URL do Jira: " + describe(e)));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.debug("Fetching authenticated user info from host {}", jiraUri.getHost());
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Jira myself failed with status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Jira myself failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao buscar dados do usuário no Jira: " + describe(e)));
        }
    }

    public ResponseEntity<String> getGreenhopperWorkData(String domain, String token, Long rapidViewId, String selectedProjectKey) {
        if (rapidViewId == null) {
            return ResponseEntity.badRequest().body("{\"error\": \"rapidViewId é obrigatório.\"}");
        }
        String cleanDomain = cleanDomain(domain);
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
                    .body(jsonError("Erro ao construir URL do Greenhopper: " + describe(e)));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token.trim());
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        headers.set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.debug("Fetching Greenhopper work data from host {}", jiraUri.getHost());
            ResponseEntity<String> response = exchangeSecure(jiraUri, entity, String.class);
            return ResponseEntity.ok(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("Greenhopper work data failed with status: {}, body: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Greenhopper work data failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(jsonError("Erro ao buscar dados do quadro Greenhopper: " + describe(e)));
        }
    }
}
