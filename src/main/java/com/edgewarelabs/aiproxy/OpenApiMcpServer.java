package com.edgewarelabs.aiproxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class OpenApiMcpServer {
    private static final Logger logger = LoggerFactory.getLogger(OpenApiMcpServer.class);
    
    private final String swaggerFilePath;
    private final int port;
    private final String targetUrl;
    private final String messageEndpoint;
    private final OpenApiParser parser;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private List<OpenApiOperation> operations;
    private Server jettyServer;
    private McpSyncServer mcpServer;

    public OpenApiMcpServer(String swaggerFilePath, int port, String targetUrl, String messageEndpoint) {
        this.swaggerFilePath = swaggerFilePath;
        this.port = port;
        this.targetUrl = targetUrl;
        this.messageEndpoint = messageEndpoint;
        this.parser = new OpenApiParser();
        this.restClient = new RestClient(targetUrl);
        this.objectMapper = new ObjectMapper();
    }

    public void start() throws Exception {
        logger.info("Loading OpenAPI operations from: {}", swaggerFilePath);
        operations = parser.parseOpenApiFile(swaggerFilePath);
        
        if (operations.isEmpty()) {
            throw new IllegalStateException("No operations found in OpenAPI specification");
        }
        
        logger.info("Building MCP server with {} tools", operations.size());
        
        // Create HTTP transport provider
        var transportProvider = HttpServletSseServerTransportProvider.builder()
                .messageEndpoint("/mcp/message")
                .sseEndpoint("/mcp/sse")
                .build();

        mcpServer = McpServer.sync(transportProvider)
                .serverInfo("OpenAPI MCP Proxy", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();
        
        for (OpenApiOperation operation : operations) {
            registerOperation(mcpServer, operation);
        }
        
        // Create and configure Jetty server
        jettyServer = new Server(port);
        
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        jettyServer.setHandler(context);
        
        // Add the MCP servlet - the transport provider is the servlet
        ServletHolder servletHolder = new ServletHolder(transportProvider);
        context.addServlet(servletHolder, "/mcp/sse");
        context.addServlet(servletHolder, "/mcp/message");
        
        // Add a simple health check endpoint
        context.addServlet(new ServletHolder(new HealthCheckServlet()), "/health");
        
        logger.info("Starting HTTP server on port {}", port);
        jettyServer.start();
        
        logger.info("MCP server started and ready");
        logger.info("MCP endpoint: {}sse", messageEndpoint);
        logger.info("Health check: {}health", messageEndpoint);
        
        // Keep the server running
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                stop();
            } catch (Exception e) {
                logger.error("Error stopping server", e);
            }
        }));
        
        // Block to keep the server running
        jettyServer.join();
    }
    
    private void registerOperation(McpSyncServer server, OpenApiOperation operation) throws IOException {
        String toolName = operation.getOperationId();
        String description = buildToolDescription(operation);
        
        // Build input schema
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        
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
        
        if (operation.getRequestBodySchema() != null) {
            Map<String, Object> bodySchema = new HashMap<>();
            bodySchema.put("type", "object");
            bodySchema.put("description", "Request body");
            properties.put("requestBody", bodySchema);
        }
        
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) {
            inputSchema.put("required", required);
        }
        
        // Convert inputSchema to JSON string
        String inputSchemaJson = objectMapper.writeValueAsString(inputSchema);
        
        var tool = new McpSchema.Tool(toolName, description, inputSchemaJson);
        
        var toolSpec = new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    try {
                        var result = executeOperation(operation, (JsonNode) arguments);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(objectMapper.writeValueAsString(result))),
                                false
                        );
                    } catch (Exception e) {
                        logger.error("Error executing operation: " + operation.getOperationId(), e);
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
                }
        );
        
        server.addTool(toolSpec);
        logger.debug("Registered tool: {} - {}", toolName, description);
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
    
    private ObjectNode executeOperation(OpenApiOperation operation, JsonNode args) throws IOException {
        Map<String, String> pathParams = new HashMap<>();
        Map<String, String> queryParams = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        JsonNode requestBody = null;
        
        for (OpenApiOperation.OpenApiParameter param : operation.getParameters()) {
            JsonNode value = args.get(param.getName());
            if (value != null && !value.isNull()) {
                String stringValue = value.asText();
                
                switch (param.getIn().toLowerCase()) {
                    case "path" -> pathParams.put(param.getName(), stringValue);
                    case "query" -> queryParams.put(param.getName(), stringValue);
                    case "header" -> headers.put(param.getName(), stringValue);
                }
            }
        }
        
        JsonNode requestBodyArg = args.get("requestBody");
        if (requestBodyArg != null && !requestBodyArg.isNull()) {
            requestBody = requestBodyArg;
        }
        
        RestClient.RestResponse response = restClient.executeRequest(
                operation.getMethod(),
                operation.getPath(),
                pathParams,
                queryParams,
                headers,
                requestBody
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
        if (jettyServer != null) {
            jettyServer.stop();
        }
        if (mcpServer != null) {
            mcpServer.close();
        }
        if (restClient != null) {
            restClient.close();
        }
    }
}