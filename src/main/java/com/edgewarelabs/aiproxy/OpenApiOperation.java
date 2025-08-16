package com.edgewarelabs.aiproxy;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public class OpenApiOperation {
    private final String path;
    private final String method;
    private final String operationId;
    private final String summary;
    private final String description;
    private final List<OpenApiParameter> parameters;
    private final JsonNode requestBodySchema;
    private final Map<String, JsonNode> responses;

    public OpenApiOperation(String path, String method, String operationId, String summary, 
                           String description, List<OpenApiParameter> parameters, 
                           JsonNode requestBodySchema, Map<String, JsonNode> responses) {
        this.path = path;
        this.method = method;
        this.operationId = operationId;
        this.summary = summary;
        this.description = description;
        this.parameters = parameters;
        this.requestBodySchema = requestBodySchema;
        this.responses = responses;
    }

    public String getPath() {
        return path;
    }

    public String getMethod() {
        return method;
    }

    public String getOperationId() {
        return operationId;
    }

    public String getSummary() {
        return summary;
    }

    public String getDescription() {
        return description;
    }

    public List<OpenApiParameter> getParameters() {
        return parameters;
    }

    public JsonNode getRequestBodySchema() {
        return requestBodySchema;
    }

    public Map<String, JsonNode> getResponses() {
        return responses;
    }

    public static class OpenApiParameter {
        private final String name;
        private final String in;
        private final boolean required;
        private final String type;
        private final String description;
        private final JsonNode schema;

        public OpenApiParameter(String name, String in, boolean required, String type, 
                               String description, JsonNode schema) {
            this.name = name;
            this.in = in;
            this.required = required;
            this.type = type;
            this.description = description;
            this.schema = schema;
        }

        public String getName() {
            return name;
        }

        public String getIn() {
            return in;
        }

        public boolean isRequired() {
            return required;
        }

        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }

        public JsonNode getSchema() {
            return schema;
        }
    }
}