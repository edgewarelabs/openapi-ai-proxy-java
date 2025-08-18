package com.edgewarelabs.aiproxy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "openapi-mcp-proxy",
    description = "Creates an MCP server from an OpenAPI/Swagger specification",
    version = "1.0.0",
    mixinStandardHelpOptions = true
)
public class OpenApiMcpProxy implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(OpenApiMcpProxy.class);

    @Option(
        names = {"-s", "--swagger"},
        description = "Path to OpenAPI/Swagger YAML file",
        required = true
    )
    private String swaggerFilePath;

    @Option(
        names = {"-p", "--port"},
        description = "Port to run MCP server on (default: 8081)",
        defaultValue = "8081"
    )
    private int port;

    @Option(
        names = {"-t", "--target"},
        description = "Target root URL for proxying requests",
        required = true
    )
    private String targetUrl;

    @Option(
        names = {"-m", "--message-endpoint"},
        description = "Message endpoint URL for MCP communication (default: http://localhost:PORT/)"
    )
    private String messageEndpoint;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new OpenApiMcpProxy()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try {
            validateInputs();
            
            // Set default message endpoint if not provided
            if (messageEndpoint == null || messageEndpoint.trim().isEmpty()) {
                messageEndpoint = "http://localhost:" + port + "/";
            }
            
            logger.info("Starting OpenAPI MCP Proxy");
            logger.info("Swagger file: {}", swaggerFilePath);
            logger.info("MCP server port: {}", port);
            logger.info("Target URL: {}", targetUrl);
            logger.info("Message endpoint: {}", messageEndpoint);
            
            OpenApiMcpServer server = new OpenApiMcpServer(swaggerFilePath, port, targetUrl, messageEndpoint);
            server.start();
            
            return 0;
        } catch (Exception e) {
            logger.error("Error starting server", e);
            return 1;
        }
    }
    
    private void validateInputs() {
        Path swaggerPath = Paths.get(swaggerFilePath);
        if (!Files.exists(swaggerPath)) {
            throw new IllegalArgumentException("Swagger file does not exist: " + swaggerFilePath);
        }
        
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            throw new IllegalArgumentException("Target URL must start with http:// or https://");
        }
    }
}