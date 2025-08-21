package com.edgewarelabs.aiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class OpenApiParser {
    private static final Logger logger = LoggerFactory.getLogger(OpenApiParser.class);
    private final ObjectMapper yamlMapper;
    private JsonNode rootDocument;
    private String serverBasePath;
    private String servicePrefix;

    public OpenApiParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public List<OpenApiOperation> parseOpenApiFile(String filePath) throws IOException {
        logger.info("Parsing OpenAPI file: {}", filePath);
        
        rootDocument = yamlMapper.readTree(new File(filePath));
        List<OpenApiOperation> operations = new ArrayList<>();
        
        // Parse server information to extract base path
        parseServerInformation();
        
        JsonNode paths = rootDocument.get("paths");
        if (paths == null) {
            logger.warn("No paths found in OpenAPI specification");
            return operations;
        }
        
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            JsonNode pathNode = pathEntry.getValue();
            
            Iterator<Map.Entry<String, JsonNode>> methodIterator = pathNode.fields();
            while (methodIterator.hasNext()) {
                Map.Entry<String, JsonNode> methodEntry = methodIterator.next();
                String method = methodEntry.getKey().toLowerCase();
                JsonNode operationNode = methodEntry.getValue();
                
                if (isHttpMethod(method)) {
                    OpenApiOperation operation = parseOperation(path, method, operationNode);
                    if (operation != null) {
                        operations.add(operation);
                        logger.debug("Parsed operation: {} {}", method.toUpperCase(), path);
                    }
                }
            }
        }
        
        logger.info("Parsed {} operations from OpenAPI specification", operations.size());
        return operations;
    }
    
    private boolean isHttpMethod(String method) {
        return Set.of("get", "post", "put", "delete", "patch", "head", "options").contains(method);
    }
    
    private OpenApiOperation parseOperation(String path, String method, JsonNode operationNode) {
        String operationId = getTextValue(operationNode, "operationId");
        if (operationId == null) {
            operationId = generateOperationId(method, path);
        }
        
        String summary = getTextValue(operationNode, "summary");
        String description = getTextValue(operationNode, "description");
        
        List<OpenApiOperation.OpenApiParameter> parameters = parseParameters(operationNode);
        JsonNode requestBodySchema = parseRequestBody(operationNode);
        Map<String, JsonNode> responses = parseResponses(operationNode);
        
        return new OpenApiOperation(path, method, operationId, summary, description, 
                                   parameters, requestBodySchema, responses, servicePrefix, serverBasePath);
    }
    
    private String generateOperationId(String method, String path) {
        String cleanPath = path.replaceAll("[{}]", "").replaceAll("/", "_");
        if (cleanPath.startsWith("_")) {
            cleanPath = cleanPath.substring(1);
        }
        return method + "_" + cleanPath;
    }
    
    private List<OpenApiOperation.OpenApiParameter> parseParameters(JsonNode operationNode) {
        List<OpenApiOperation.OpenApiParameter> parameters = new ArrayList<>();
        
        JsonNode parametersNode = operationNode.get("parameters");
        if (parametersNode != null && parametersNode.isArray()) {
            for (JsonNode paramNode : parametersNode) {
                String name = getTextValue(paramNode, "name");
                String in = getTextValue(paramNode, "in");
                boolean required = paramNode.has("required") && paramNode.get("required").asBoolean();
                String type = null;
                String description = getTextValue(paramNode, "description");
                JsonNode schema = paramNode.get("schema");
                
                if (schema != null && schema.has("type")) {
                    type = schema.get("type").asText();
                }
                
                if (name != null && in != null) {
                    parameters.add(new OpenApiOperation.OpenApiParameter(name, in, required, type, description, schema));
                }
            }
        }
        
        return parameters;
    }
    
    private JsonNode parseRequestBody(JsonNode operationNode) {
        JsonNode requestBody = operationNode.get("requestBody");
        if (requestBody != null) {
            JsonNode content = requestBody.get("content");
            if (content != null && content.isObject()) {
                Iterator<String> fieldNames = content.fieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    // Handle various JSON content types including charset variations
                    if (fieldName.startsWith("application/json")) {
                        JsonNode contentTypeNode = content.get(fieldName);
                        if (contentTypeNode != null) {
                            JsonNode schema = contentTypeNode.get("schema");
                            if (schema != null) {
                                // Resolve all $ref references to get the complete schema
                                return resolveAllReferences(schema);
                            }
                        }
                    }
                }
                
                // Fallback: try other content types if no JSON found
                fieldNames = content.fieldNames();
                while (fieldNames.hasNext()) {
                    String fieldName = fieldNames.next();
                    JsonNode contentTypeNode = content.get(fieldName);
                    if (contentTypeNode != null) {
                        JsonNode schema = contentTypeNode.get("schema");
                        if (schema != null) {
                            // Resolve all $ref references to get the complete schema
                            return resolveAllReferences(schema);
                        }
                    }
                }
            }
        }
        return null;
    }
    
    private Map<String, JsonNode> parseResponses(JsonNode operationNode) {
        Map<String, JsonNode> responses = new HashMap<>();
        
        JsonNode responsesNode = operationNode.get("responses");
        if (responsesNode != null) {
            Iterator<Map.Entry<String, JsonNode>> responseIterator = responsesNode.fields();
            while (responseIterator.hasNext()) {
                Map.Entry<String, JsonNode> responseEntry = responseIterator.next();
                responses.put(responseEntry.getKey(), responseEntry.getValue());
            }
        }
        
        return responses;
    }
    
    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null ? field.asText() : null;
    }
    
    /**
     * Resolves a $ref reference to the actual schema definition.
     * Handles internal references like "#/components/schemas/SchemaName"
     */
    private JsonNode resolveReference(JsonNode schema) {
        if (schema == null || rootDocument == null) {
            return schema;
        }
        
        JsonNode refNode = schema.get("$ref");
        if (refNode != null) {
            String ref = refNode.asText();
            if (ref.startsWith("#/")) {
                // Parse the reference path
                String[] pathParts = ref.substring(2).split("/");
                JsonNode current = rootDocument;
                
                for (String part : pathParts) {
                    current = current.get(part);
                    if (current == null) {
                        logger.warn("Could not resolve reference: {}", ref);
                        return schema; // Return original if resolution fails
                    }
                }
                
                // Recursively resolve in case the resolved schema has more references
                return resolveReference(current);
            }
        }
        
        return schema;
    }
    
    /**
     * Recursively resolves all $ref references in a schema, including nested ones
     */
    @SuppressWarnings("unchecked")
    private JsonNode resolveAllReferences(JsonNode schema) {
        if (schema == null) {
            return null;
        }
        
        // First resolve the current level
        JsonNode resolved = resolveReference(schema);
        
        // If this is an object, check for nested references
        if (resolved.isObject()) {
            // Handle arrays with items
            JsonNode items = resolved.get("items");
            if (items != null) {
                JsonNode resolvedItems = resolveAllReferences(items);
                if (resolvedItems != items) {
                    // Create a copy with resolved items
                    ObjectMapper mapper = new ObjectMapper();
                    try {
                        Map<String, Object> schemaMap = mapper.convertValue(resolved, Map.class);
                        schemaMap.put("items", mapper.convertValue(resolvedItems, Object.class));
                        resolved = mapper.valueToTree(schemaMap);
                    } catch (Exception e) {
                        logger.warn("Failed to resolve items reference", e);
                    }
                }
            }
            
            // Handle properties
            JsonNode properties = resolved.get("properties");
            if (properties != null && properties.isObject()) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    Map<String, Object> schemaMap = mapper.convertValue(resolved, Map.class);
                    Map<String, Object> resolvedProperties = new HashMap<>();
                    
                    Iterator<Map.Entry<String, JsonNode>> propIterator = properties.fields();
                    while (propIterator.hasNext()) {
                        Map.Entry<String, JsonNode> entry = propIterator.next();
                        JsonNode resolvedProp = resolveAllReferences(entry.getValue());
                        resolvedProperties.put(entry.getKey(), mapper.convertValue(resolvedProp, Object.class));
                    }
                    
                    schemaMap.put("properties", resolvedProperties);
                    resolved = mapper.valueToTree(schemaMap);
                } catch (Exception e) {
                    logger.warn("Failed to resolve properties references", e);
                }
            }
            
            // Handle allOf, oneOf, anyOf
            for (String compositionKey : Arrays.asList("allOf", "oneOf", "anyOf")) {
                JsonNode compositionNode = resolved.get(compositionKey);
                if (compositionNode != null && compositionNode.isArray()) {
                    ObjectMapper mapper = new ObjectMapper();
                    try {
                        Map<String, Object> schemaMap = mapper.convertValue(resolved, Map.class);
                        List<Object> resolvedComposition = new ArrayList<>();
                        
                        for (JsonNode item : compositionNode) {
                            JsonNode resolvedItem = resolveAllReferences(item);
                            resolvedComposition.add(mapper.convertValue(resolvedItem, Object.class));
                        }
                        
                        schemaMap.put(compositionKey, resolvedComposition);
                        resolved = mapper.valueToTree(schemaMap);
                    } catch (Exception e) {
                        logger.warn("Failed to resolve {} references", compositionKey, e);
                    }
                }
            }
        }
        
        return resolved;
    }
    
    /**
     * Parses server information from the OpenAPI spec to extract the base path
     */
    private void parseServerInformation() {
        JsonNode servers = rootDocument.get("servers");
        if (servers != null && servers.isArray() && servers.size() > 0) {
            // Use the first server definition
            JsonNode firstServer = servers.get(0);
            JsonNode urlNode = firstServer.get("url");
            if (urlNode != null) {
                String serverUrl = urlNode.asText();
                
                // Extract the path part from the URL (everything after the third slash)
                // e.g., "https://{serverBase}/mefApi/sonata/geographicAddressManagement/v8/" -> "/mefApi/sonata/geographicAddressManagement/v8/"
                if (serverUrl.contains("://")) {
                    int protocolEnd = serverUrl.indexOf("://") + 3;
                    int pathStart = serverUrl.indexOf("/", protocolEnd);
                    if (pathStart != -1) {
                        serverBasePath = serverUrl.substring(pathStart);
                        if (!serverBasePath.endsWith("/")) {
                            serverBasePath += "/";
                        }
                        logger.info("Extracted server base path: {}", serverBasePath);
                        
                        // Extract service prefix from the base path
                        servicePrefix = extractServicePrefix(serverBasePath);
                        logger.info("Extracted service prefix: {}", servicePrefix);
                    }
                } else {
                    // If no protocol, treat the whole URL as a path
                    serverBasePath = serverUrl;
                    if (!serverBasePath.startsWith("/")) {
                        serverBasePath = "/" + serverBasePath;
                    }
                    if (!serverBasePath.endsWith("/")) {
                        serverBasePath += "/";
                    }
                    servicePrefix = extractServicePrefix(serverBasePath);
                    logger.info("Extracted server base path: {}", serverBasePath);
                    logger.info("Extracted service prefix: {}", servicePrefix);
                }
            }
        }
        
        if (serverBasePath == null || serverBasePath.isEmpty()) {
            serverBasePath = "/";
            servicePrefix = "api"; // Default prefix
            logger.info("No server base path found, using default: / with prefix: {}", servicePrefix);
        }
    }
    
    /**
     * Extracts a meaningful service prefix from the base path.
     * For example:
     * - "/mefApi/sonata/geographicAddressManagement/v8/" -> "geographicAddressManagement"
     * - "/api/v1/users/" -> "users"
     * - "/geographicSite/" -> "geographicSite"
     * - "/" -> "api"
     */
    private String extractServicePrefix(String basePath) {
        if (basePath == null || basePath.equals("/")) {
            return "api";
        }
        
        // Remove leading and trailing slashes
        String cleanPath = basePath.replaceAll("^/+|/+$", "");
        if (cleanPath.isEmpty()) {
            return "api";
        }
        
        // Split by slash and find the most meaningful part
        String[] pathParts = cleanPath.split("/");
        
        // Look for meaningful service names by prioritizing:
        // 1. Parts that are not version numbers (v1, v2, etc.)
        // 2. Parts that are not generic (api, sonata, mef, etc.)
        // 3. Longer parts over shorter ones
        String bestCandidate = null;
        
        for (String part : pathParts) {
            // Skip version patterns
            if (part.matches("v\\d+") || part.matches("version\\d+")) {
                continue;
            }
            
            // Skip common generic terms
            if (part.equalsIgnoreCase("api") || part.equalsIgnoreCase("sonata") || 
                part.equalsIgnoreCase("mef") || part.equalsIgnoreCase("mefapi")) {
                continue;
            }
            
            // Prefer longer, more descriptive names
            if (bestCandidate == null || part.length() > bestCandidate.length()) {
                bestCandidate = part;
            }
        }
        
        // If no good candidate found, use the last non-version part
        if (bestCandidate == null) {
            for (int i = pathParts.length - 1; i >= 0; i--) {
                String part = pathParts[i];
                if (!part.matches("v\\d+") && !part.matches("version\\d+")) {
                    bestCandidate = part;
                    break;
                }
            }
        }
        
        // Final fallback
        if (bestCandidate == null || bestCandidate.isEmpty()) {
            bestCandidate = "api";
        }
        
        // Clean up the prefix to be a valid identifier
        return bestCandidate.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }
    
    /**
     * Gets the server base path extracted from the OpenAPI specification
     */
    public String getServerBasePath() {
        return serverBasePath;
    }
    
    /**
     * Gets the service prefix extracted from the OpenAPI specification
     */
    public String getServicePrefix() {
        return servicePrefix;
    }
}