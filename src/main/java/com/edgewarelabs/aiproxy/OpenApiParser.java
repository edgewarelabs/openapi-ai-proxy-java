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

    public OpenApiParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public List<OpenApiOperation> parseOpenApiFile(String filePath) throws IOException {
        logger.info("Parsing OpenAPI file: {}", filePath);
        
        JsonNode root = yamlMapper.readTree(new File(filePath));
        List<OpenApiOperation> operations = new ArrayList<>();
        
        JsonNode paths = root.get("paths");
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
                                   parameters, requestBodySchema, responses);
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
            if (content != null) {
                JsonNode applicationJson = content.get("application/json");
                if (applicationJson != null) {
                    return applicationJson.get("schema");
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
}