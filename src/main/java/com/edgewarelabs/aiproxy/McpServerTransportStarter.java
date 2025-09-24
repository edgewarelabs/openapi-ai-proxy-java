package com.edgewarelabs.aiproxy;

import java.io.IOException;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.edgewarelabs.mcp.transport.CommonMcpServerTransportProvider;
import com.edgewarelabs.mcp.transport.McpCommunicationProvider;
import com.edgewarelabs.mcp.transport.stlsai.QuantumKeyConfig;
import com.edgewarelabs.mcp.transport.stlsai.StlsAiProvider;

import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;

public class McpServerTransportStarter {
    private static final Logger logger = LoggerFactory.getLogger(McpServerTransportStarter.class);


    public static void startTransport(String transportType, int port, String configFile, OpenApiMcpServer server) throws Exception {
        switch (transportType.toLowerCase()) {
            case "sse":
            {
                var provider = HttpServletSseServerTransportProvider.builder()
                        .messageEndpoint("/mcp/message")
                        .sseEndpoint("/mcp/sse")
                        .build();

                server.initialize(provider);
                attachServlet(provider, port);
            }

            case "stls-ai":
            {
                QuantumKeyConfig quantumKeyConfig;
                try {
                    quantumKeyConfig = QuantumKeyConfig.fromJsonFile(configFile);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to load device config file: " + configFile, e);
                }
                McpCommunicationProvider provider = new StlsAiProvider(quantumKeyConfig);
                CommonMcpServerTransportProvider transportProvider = new CommonMcpServerTransportProvider(provider, port);
                server.initialize(transportProvider);
                transportProvider.start();
            }
            
            default:
                throw new IllegalArgumentException("Unsupported transport type: " + transportType);
        }
    }

    private static void attachServlet(HttpServletSseServerTransportProvider transportProvider, int port) throws Exception {

       // Create and configure Jetty server
        Server jettyServer = new Server(port);
        
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
        
        // Keep the server running
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                stop(jettyServer);
            } catch (Exception e) {
                logger.error("Error stopping server", e);
            }
        }));
        
        // Block to keep the server running
        jettyServer.join();
    }

    public static void stop(Server jettyServer) throws Exception {
        logger.info("Stopping servers");
        if (jettyServer != null) {
            jettyServer.stop();
        }
    }

    
}
