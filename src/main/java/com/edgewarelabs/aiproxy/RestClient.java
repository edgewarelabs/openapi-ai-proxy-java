package com.edgewarelabs.aiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

public class RestClient {
    private static final Logger logger = LoggerFactory.getLogger(RestClient.class);
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String targetUrl;

    public RestClient(String targetUrl) {
        this.targetUrl = targetUrl.endsWith("/") ? targetUrl.substring(0, targetUrl.length() - 1) : targetUrl;
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
    }

    public RestResponse executeRequest(String method, String path, Map<String, String> pathParams,
                                     Map<String, String> queryParams, Map<String, String> headers,
                                     JsonNode requestBody, String serverBasePath) throws IOException {
        
        String resolvedPath = resolvePath(path, pathParams);
        
        // Construct the full URL with the correct server base path
        String fullUrl = targetUrl;
        if (serverBasePath != null && !serverBasePath.equals("/")) {
            // Remove trailing slash from targetUrl if present, then add the base path
            if (!serverBasePath.startsWith("/")) {
                fullUrl += "/";
            }
            fullUrl += serverBasePath.endsWith("/") ? serverBasePath.substring(0, serverBasePath.length() - 1) : serverBasePath;
        }
        fullUrl += resolvedPath;
        
        if (queryParams != null && !queryParams.isEmpty()) {
            StringBuilder queryString = new StringBuilder("?");
            queryParams.forEach((key, value) -> {
                if (queryString.length() > 1) {
                    queryString.append("&");
                }
                queryString.append(key).append("=").append(value);
            });
            fullUrl += queryString.toString();
        }
        
        logger.debug("Executing {} request to: {}", method.toUpperCase(), fullUrl);
        
        HttpUriRequestBase request = createHttpRequest(method, fullUrl);
        
        if (headers != null) {
            headers.forEach(request::setHeader);
        }
        
        if (requestBody != null && (method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT") || method.equalsIgnoreCase("PATCH"))) {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
            logger.debug("Request body: {}", jsonBody);
        }
        
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getCode();
            String responseBody = null;
            
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                try {
                    responseBody = EntityUtils.toString(entity);
                } catch (Exception e) {
                    logger.warn("Failed to read response body", e);
                    responseBody = null;
                }
            }
            
            logger.debug("Response status: {}, body: {}", statusCode, responseBody);
            
            return new RestResponse(statusCode, responseBody, response.getHeaders());
        }
    }

    // Backward compatibility method - delegates to the new method with "/" as default base path
    public RestResponse executeRequest(String method, String path, Map<String, String> pathParams,
                                     Map<String, String> queryParams, Map<String, String> headers,
                                     JsonNode requestBody) throws IOException {
        return executeRequest(method, path, pathParams, queryParams, headers, requestBody, "/");
    }
    
    private String resolvePath(String path, Map<String, String> pathParams) {
        String resolvedPath = path;
        if (pathParams != null) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                resolvedPath = resolvedPath.replace(placeholder, entry.getValue());
            }
        }
        return resolvedPath;
    }
    
    private HttpUriRequestBase createHttpRequest(String method, String url) {
        return switch (method.toUpperCase()) {
            case "GET" -> new HttpGet(url);
            case "POST" -> new HttpPost(url);
            case "PUT" -> new HttpPut(url);
            case "DELETE" -> new HttpDelete(url);
            case "PATCH" -> new HttpPatch(url);
            case "HEAD" -> new HttpHead(url);
            case "OPTIONS" -> new HttpOptions(url);
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        };
    }

    public void close() throws IOException {
        httpClient.close();
    }

    public static class RestResponse {
        private final int statusCode;
        private final String body;
        private final org.apache.hc.core5.http.Header[] headers;

        public RestResponse(int statusCode, String body, org.apache.hc.core5.http.Header[] headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }

        public org.apache.hc.core5.http.Header[] getHeaders() {
            return headers;
        }
    }
}