import com.edgewarelabs.aiproxy.OpenApiParser;
import com.edgewarelabs.aiproxy.OpenApiOperation;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public class TestSchemaResolution {
    public static void main(String[] args) {
        try {
            OpenApiParser parser = new OpenApiParser();
            List<OpenApiOperation> operations = parser.parseOpenApiFile("geographicAddressManagement.api.yaml");
            
            // Find the createGeographicAddressValidation operation
            for (OpenApiOperation operation : operations) {
                if ("createGeographicAddressValidation".equals(operation.getOperationId())) {
                    JsonNode requestBodySchema = operation.getRequestBodySchema();
                    if (requestBodySchema != null) {
                        System.out.println("Schema for createGeographicAddressValidation:");
                        System.out.println(requestBodySchema.toPrettyString());
                        
                        // Check if it has resolved properties
                        JsonNode properties = requestBodySchema.get("properties");
                        if (properties != null) {
                            System.out.println("\nFound properties:");
                            properties.fieldNames().forEachRemaining(System.out::println);
                        } else {
                            System.out.println("\nNo properties found - schema may not be fully resolved");
                        }
                    } else {
                        System.out.println("No request body schema found for createGeographicAddressValidation");
                    }
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
