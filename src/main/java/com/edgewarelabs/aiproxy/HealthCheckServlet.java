package com.edgewarelabs.aiproxy;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class HealthCheckServlet extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckServlet.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        
        String healthCheck = """
        {
            "status": "healthy",
            "service": "OpenAPI MCP Proxy",
            "version": "1.0.0",
            "timestamp": "%s"
        }
        """.formatted(java.time.Instant.now().toString());
        
        response.getWriter().write(healthCheck);
        logger.debug("Health check request processed");
    }
}