package com.edgewarelabs.aiproxy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

public class OpenApiMcpServer {
    private static final Logger logger = LoggerFactory.getLogger(OpenApiMcpServer.class);
    
    private final String[] swaggerFilePaths;
    private final String targetUrl;
    private RestClient restClient;
    private final ObjectMapper objectMapper;
    private List<OpenApiOperation> operations;
    private McpSyncServer mcpServer;

    public OpenApiMcpServer(String[] swaggerFilePaths,String targetUrl) {
        this.swaggerFilePaths = swaggerFilePaths;
        this.targetUrl = targetUrl;
        this.objectMapper = new ObjectMapper();
    }

    public void initialize(McpServerTransportProvider transportProvider) throws Exception {
        logger.info("Loading OpenAPI operations from {} file(s): {}", swaggerFilePaths.length, String.join(", ", swaggerFilePaths));
        
        operations = new ArrayList<>();
        
        // Parse all swagger files and collect operations
        for (String swaggerFilePath : swaggerFilePaths) {
            logger.info("Parsing file: {}", swaggerFilePath);
            OpenApiParser fileParser = new OpenApiParser(); // Create a new parser for each file
            List<OpenApiOperation> fileOperations = fileParser.parseOpenApiFile(swaggerFilePath);
            operations.addAll(fileOperations);
            
            logger.info("Loaded {} operations from {}", fileOperations.size(), swaggerFilePath);
        }
        
        if (operations.isEmpty()) {
            throw new IllegalStateException("No operations found in any OpenAPI specification");
        }
        
        // Create RestClient with just the target URL (no base path)
        logger.info("Target URL: {}", targetUrl);
        this.restClient = new RestClient(targetUrl);
        
        logger.info("Building MCP server with {} total tools from {} file(s)", operations.size(), swaggerFilePaths.length);

        mcpServer = McpServer.sync(transportProvider)
                .serverInfo("OpenAPI MCP Proxy", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();
        
        for (OpenApiOperation operation : operations) {
            registerOperation(mcpServer, operation);
        }

        // Keep the server running
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                stop();
            } catch (Exception e) {
                logger.error("Error stopping server", e);
            }
        }));        
     }
    
    private void registerOperation(McpSyncServer server, OpenApiOperation operation) throws IOException {
        String toolName = operation.getPrefixedToolName();
        String description = buildToolDescription(operation);
        
        // Build input schema
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        
        // Add parameters to the schema
        for (OpenApiOperation.OpenApiParameter param : operation.getParameters()) {
            Map<String, Object> paramSchema = new HashMap<>();
            paramSchema.put("type", convertToJsonSchemaType(param.getType()));
            if (param.getDescription() != null) {
                paramSchema.put("description", param.getDescription());
            }
            properties.put(param.getName(), paramSchema);
            
            if (param.isRequired()) {
                required.add(param.getName());
            }
        }
        
        // If there's a request body schema, add it as a separate requestBody parameter
        if (operation.getRequestBodySchema() != null) {
            JsonNode requestBodySchema = operation.getRequestBodySchema();
            logger.debug("Processing request body schema for {}: {}", toolName, requestBodySchema.toPrettyString());
            
            try {
                // Convert the JsonNode to a Map and add as requestBody parameter
                @SuppressWarnings("unchecked")
                Map<String, Object> bodySchemaMap = objectMapper.convertValue(requestBodySchema, Map.class);
                properties.put("requestBody", bodySchemaMap);
                logger.debug("Added request body schema as separate parameter for {}", toolName);
            } catch (Exception e) {
                logger.warn("Failed to convert request body schema for {}, using generic object", toolName, e);
                // Fallback to generic object if conversion fails
                Map<String, Object> bodySchema = new HashMap<>();
                bodySchema.put("type", "object");
                bodySchema.put("description", "Request body");
                properties.put("requestBody", bodySchema);
            }
        }
        
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) {
            inputSchema.put("required", required);
        }
        
        // Convert inputSchema to JSON string
        String inputSchemaJson = objectMapper.writeValueAsString(inputSchema);
        
        // Debug logging to see the actual schema being generated
        logger.debug("Generated schema for {}: {}", toolName, inputSchemaJson);
        
        var tool = McpSchema.Tool.builder()
                .name(toolName)
                .description(description)
                .inputSchema(inputSchemaJson)
                .build();

        
        var toolSpec = SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((McpSyncServerExchange exchange, CallToolRequest callToolRequest) -> {
                    try {
                        var result = executeOperation(operation, callToolRequest);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(result))),
                                false
                        );
                    } catch (Exception e) {
                        logger.error("Error executing operation: {} (tool: {})", operation.getOperationId(), toolName, e);
                        var errorResult = createErrorResponse("Error executing operation: " + e.getMessage());
                        try {
                            return new McpSchema.CallToolResult(
                                    List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(errorResult))),
                                    true
                            );
                        } catch (Exception jsonEx) {
                            return new McpSchema.CallToolResult(
                                    List.of(new McpSchema.TextContent("Error: " + e.getMessage())),
                                    true
                            );
                        }
                    }
                })
                .build();
        
        server.addTool(toolSpec);
        logger.debug("Registered tool: {} (from operation: {}) - {}", toolName, operation.getOperationId(), description);
    }
    
    private String buildToolDescription(OpenApiOperation operation) {
        StringBuilder desc = new StringBuilder();
        
        if (operation.getSummary() != null) {
            desc.append(operation.getSummary());
        } else {
            desc.append(operation.getMethod().toUpperCase()).append(" ").append(operation.getPath());
        }
        
        if (operation.getDescription() != null) {
            if (desc.length() > 0) {
                desc.append(" - ");
            }
            desc.append(operation.getDescription());
        }
        
        return desc.toString();
    }
    
    private String convertToJsonSchemaType(String openApiType) {
        if (openApiType == null) {
            return "string";
        }
        
        return switch (openApiType.toLowerCase()) {
            case "integer", "int32", "int64" -> "number";
            case "number", "float", "double" -> "number";
            case "boolean" -> "boolean";
            case "array" -> "array";
            case "object" -> "object";
            default -> "string";
        };
    }

    private ObjectNode executeOperation(OpenApiOperation operation, CallToolRequest callToolRequest) throws IOException {
        Map<String, String> pathParams = new HashMap<>();
        Map<String, String> queryParams = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        JsonNode requestBody = null;
        
        // Debug: Log all incoming arguments
        logger.debug("Incoming arguments for {} (tool: {}): {}", operation.getOperationId(), operation.getPrefixedToolName(), callToolRequest.arguments());
        
        // Process parameters
        for (OpenApiOperation.OpenApiParameter param : operation.getParameters()) {
            Object paramValue = callToolRequest.arguments().get(param.getName());
            logger.debug("Parameter '{}' has value: {}", param.getName(), paramValue);
            
            if (paramValue != null) {
                String stringValue = paramValue.toString();
                
                switch (param.getIn().toLowerCase()) {
                    case "path" -> pathParams.put(param.getName(), stringValue);
                    case "query" -> queryParams.put(param.getName(), stringValue);
                    case "header" -> headers.put(param.getName(), stringValue);
                }
            }
        }
        
        // Process request body as a separate parameter
        Object requestBodyArg = callToolRequest.arguments().get("requestBody");
        logger.debug("Request body argument: {}", requestBodyArg);
        
        if (requestBodyArg != null) {
            // Convert the request body argument directly to JsonNode
            requestBody = objectMapper.valueToTree(requestBodyArg);
            logger.debug("Request body: {}", requestBody.toPrettyString());
        }
        
        RestClient.RestResponse response = restClient.executeRequest(
                operation.getMethod(),
                operation.getPath(),
                pathParams,
                queryParams,
                headers,
                requestBody,
                operation.getServerBasePath()
        );
        
        return createSuccessResponse(response);
    }
    
    private ObjectNode createSuccessResponse(RestClient.RestResponse response) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("success", true);
        result.put("statusCode", response.getStatusCode());
        
        if (response.getBody() != null) {
            try {
                JsonNode bodyJson = objectMapper.readTree(response.getBody());
                result.set("data", bodyJson);
            } catch (Exception e) {
                result.put("data", response.getBody());
            }
        }
        
        if (response.getHeaders() != null && response.getHeaders().length > 0) {
            ObjectNode headersNode = objectMapper.createObjectNode();
            for (org.apache.hc.core5.http.Header header : response.getHeaders()) {
                headersNode.put(header.getName(), header.getValue());
            }
            result.set("headers", headersNode);
        }
        
        return result;
    }
    
    private ObjectNode createErrorResponse(String message) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("success", false);
        result.put("error", message);
        return result;
    }

    public void stop() throws Exception {
        logger.info("Stopping servers");
        if (mcpServer != null) {
            mcpServer.close();
        }
        if (restClient != null) {
            restClient.close();
        }
    }    
    
}