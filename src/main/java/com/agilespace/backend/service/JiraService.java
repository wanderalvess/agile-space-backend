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
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;

@Service
@Slf4j
public class JiraService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public JiraService() {
        // Configura uma request factory que ignora a validação de SSL (confia em todos os certificados)
        // para evitar SSLHandshakeException com servidores corporativos do Jira.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
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
        
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(45000);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    public ResponseEntity<String> searchIssues(JiraSearchRequest request) {
        String cleanDomain = request.getDomain().trim().replace("https://", "").replace("http://", "");
        
        int maxResults = request.getMaxResults() != null ? Math.min(100, Math.max(1, request.getMaxResults())) : 50;
        int startAt = request.getStartAt() != null ? Math.max(0, request.getStartAt()) : 0;
        
        String fieldsStr;
        if (request.getFields() != null && !request.getFields().isEmpty()) {
            fieldsStr = String.join(",", request.getFields());
        } else {
            fieldsStr = "summary,description,issuetype,status,priority,assignee,updated,created,duedate,labels,parent,customfield_10100,customfield_10046,customfield_25307,customfield_10016,customfield_10002,customfield_10004,timespent,timeoriginalestimate,timeestimate,aggregatetimespent,aggregatetimeoriginalestimate,aggregatetimeestimate,worklog,comment";
        }

        // Importante: passamos a URL como URI para o restTemplate.exchange.
        // Se passarmos como String, o RestTemplate decodifica e re-encoda, gerando dupla codificação
        // (por exemplo, transformando %20 em %2520), o que faz o Jira rejeitar a JQL acusando caractere reservado '%'.
        URI jiraUri;
        try {
            String encodedJql = java.net.URLEncoder.encode(request.getJql().trim(), java.nio.charset.StandardCharsets.UTF_8);
            String encodedFields = java.net.URLEncoder.encode(fieldsStr, java.nio.charset.StandardCharsets.UTF_8);
            
            String urlStr = "https://" + cleanDomain + "/rest/api/2/search"
                    + "?jql=" + encodedJql
                    + "&fields=" + encodedFields
                    + "&expand=renderedFields"
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
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            log.info("Proxying JQL search to Jira URI: {}", jiraUri);
            ResponseEntity<String> response = restTemplate.exchange(jiraUri, HttpMethod.GET, entity, String.class);
            String body = response.getBody();
            
            if (body != null) {
                // Parse the response to fetch truncated worklogs
                JsonNode root = objectMapper.readTree(body);
                JsonNode issuesNode = root.get("issues");
                if (issuesNode != null && issuesNode.isArray()) {
                    for (JsonNode issue : issuesNode) {
                        JsonNode fieldsNode = issue.get("fields");
                        if (fieldsNode != null) {
                            JsonNode worklogNode = fieldsNode.get("worklog");
                            if (worklogNode != null) {
                                int totalWorklogs = worklogNode.has("total") ? worklogNode.get("total").asInt() : 0;
                                JsonNode worklogsArray = worklogNode.get("worklogs");
                                int currentCount = (worklogsArray != null && worklogsArray.isArray()) ? worklogsArray.size() : 0;
                                
                                if (totalWorklogs > currentCount) {
                                    String key = issue.get("key").asText();
                                    log.info("Issue {} has truncated worklogs ({}/{}). Fetching all worklogs...", key, currentCount, totalWorklogs);
                                    String wlUrl = "https://" + cleanDomain + "/rest/api/2/issue/" + key + "/worklog";
                                    try {
                                        ResponseEntity<String> wlResponse = restTemplate.exchange(new URI(wlUrl), HttpMethod.GET, entity, String.class);
                                        JsonNode wlRoot = objectMapper.readTree(wlResponse.getBody());
                                        JsonNode wlArray = wlRoot.get("worklogs");
                                        if (wlArray != null && wlArray.isArray()) {
                                            ((ObjectNode) worklogNode).set("worklogs", wlArray);
                                            log.info("Successfully fetched {} worklogs for {}", wlArray.size(), key);
                                        }
                                    } catch (Exception wlErr) {
                                        log.error("Failed to fetch complete worklogs for {}", key, wlErr);
                                    }
                                }
                            }
                        }
                    }
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
}
