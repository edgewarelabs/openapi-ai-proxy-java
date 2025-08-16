# OpenAPI MCP Proxy

A Java application that creates a Model Context Protocol (MCP) server from an OpenAPI/Swagger specification. The server exposes tools for each operation in the OpenAPI spec and proxies requests to a target REST API.

## Features

- Parses OpenAPI/Swagger YAML files to extract REST API operations
- Dynamically creates MCP tools for each API operation
- Handles parameter mapping (path, query, header parameters)
- Supports request body for POST/PUT/PATCH operations
- Proxies requests to target REST API and returns responses
- **HTTP transport with Server-Sent Events (SSE)** for MCP communication
- Built-in health check endpoint
- Embedded Jetty server for easy deployment

## Requirements

- Java 21 or higher
- Gradle 8.4+ (included via wrapper)

## Building

```bash
./gradlew build
```

## Usage

The application runs as an HTTP server that exposes an MCP endpoint via Server-Sent Events (SSE). MCP clients can connect to the HTTP endpoint to discover and use the available tools.

### Command Line Arguments

**Parameters:**
- `--swagger, -s`: Path to the OpenAPI/Swagger YAML file (required)
- `--target, -t`: Base URL of the target REST API (required) 
- `--port, -p`: Port number for the HTTP server (default: 8081)
- `--message-endpoint, -m`: Message endpoint URL for MCP communication (default: http://localhost:PORT/)
- `--help, -h`: Show help message
- `--version, -V`: Show version information

### Running the Application

#### Option 1: Using Gradle (Development)

```bash
# Using default port 8081
./gradlew run --args="--swagger sample-api.yaml --target https://jsonplaceholder.typicode.com"

# Using custom port
./gradlew run --args="--swagger sample-api.yaml --target https://jsonplaceholder.typicode.com --port 9090"

# Using custom message endpoint
./gradlew run --args="--swagger sample-api.yaml --target https://jsonplaceholder.typicode.com --message-endpoint https://example.com/mcp/"

# Show help
./gradlew run --args="--help"
```

#### Option 2: Using Fat JAR (Recommended for Production)

First, build the fat JAR:
```bash
./gradlew shadowJar
```

Then run with Java 21:
```bash
# Using default port 8081
java -jar build/libs/openapi-ai-proxy-java.jar --swagger sample-api.yaml --target https://jsonplaceholder.typicode.com

# Using custom port
java -jar build/libs/openapi-ai-proxy-java.jar --swagger sample-api.yaml --target https://jsonplaceholder.typicode.com --port 9090

# Using custom message endpoint
java -jar build/libs/openapi-ai-proxy-java.jar --swagger sample-api.yaml --target https://jsonplaceholder.typicode.com --message-endpoint https://example.com/mcp/

# Show help
java -jar build/libs/openapi-ai-proxy-java.jar --help
```

#### Option 3: Using Distribution Package

Create a distribution:
```bash
./gradlew installDist
```

Run using the generated script:
```bash
# Unix/Linux/Mac
./build/install/openapi-ai-proxy-java/bin/openapi-ai-proxy-java --swagger sample-api.yaml --target https://jsonplaceholder.typicode.com

# Windows
./build/install/openapi-ai-proxy-java/bin/openapi-ai-proxy-java.bat --swagger sample-api.yaml --target https://jsonplaceholder.typicode.com
```

#### Option 4: Using ZIP/TAR Distribution

Create distributable archives:
```bash
./gradlew distZip distTar
```

Extract `build/distributions/openapi-ai-proxy-java.zip` or `.tar` and run the executable from the `bin/` directory.

## Sample OpenAPI File

A sample OpenAPI specification is included (`sample-api.yaml`) that defines a simple API with the following operations:

- `getPosts`: GET /posts - Get all posts (with optional userId filter)
- `getPost`: GET /posts/{id} - Get a specific post by ID
- `createPost`: POST /posts/{id} - Create a new post

## MCP Integration

This server runs as an HTTP server and exposes MCP endpoints for clients to connect to. Each OpenAPI operation becomes an MCP tool that can be discovered and invoked by AI models through the MCP protocol.

### Endpoints

- **MCP SSE Endpoint**: `http://localhost:<port>/sse` - Main MCP communication endpoint using Server-Sent Events
- **Health Check**: `http://localhost:<port>/health` - Simple health check endpoint that returns server status

### Tool Schema

Each API operation is converted to an MCP tool with:
- **Name**: The `operationId` from the OpenAPI spec
- **Description**: Combination of `summary` and `description` from the operation
- **Input Schema**: JSON schema derived from operation parameters and request body

### Request/Response Flow

1. MCP client discovers available tools
2. Client invokes a tool with parameters  
3. Server maps parameters to REST API format (path params, query params, headers, body)
4. Server makes HTTP request to target API
5. Server returns response data to MCP client

## Architecture

```
MCP Client (AI) <--HTTP SSE--> MCP Server <--HTTP--> Target REST API
                              (this application)
                              Port: <configured>
                              Endpoints: /sse, /health
```

## Development

### Project Structure

```
src/main/java/com/edgewarelabs/aiproxy/
├── OpenApiMcpProxy.java      # Main application class
├── OpenApiMcpServer.java     # MCP server implementation  
├── OpenApiParser.java        # OpenAPI specification parser
├── OpenApiOperation.java     # Data model for API operations
└── RestClient.java           # HTTP client for API calls
```

### Dependencies

- MCP Java SDK 0.11.2
- Jackson (JSON/YAML processing)
- Apache HttpClient 5
- Eclipse Jetty 12 (HTTP server)
- SLF4J + Logback (logging)
- PicoCLI (command line parsing)
- Shadow plugin (fat JAR creation)

## Notes

- The application uses HTTP transport with Server-Sent Events for MCP communication
- MCP clients can connect to the `/sse` endpoint to discover and invoke tools
- The server runs continuously and can handle multiple concurrent MCP client connections
- Health status can be checked via the `/health` endpoint
- The server will automatically shut down gracefully on SIGTERM/SIGINT

## License

MIT License