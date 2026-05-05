package thingai.edge.aigateway.agent.mcp;

import com.google.gson.Gson;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;

import java.util.Map;

public class GsonJsonSchemaValidatorSupplier implements JsonSchemaValidatorSupplier {
    private final JsonSchemaValidator validator = new GsonJsonSchemaValidator();

    @Override
    public JsonSchemaValidator get() {
        return validator;
    }

    private static class GsonJsonSchemaValidator implements JsonSchemaValidator {
        private final Gson gson = new Gson();

        @Override
        public ValidationResponse validate(Map<String, Object> schema, Object structuredContent) {
            try {
                return ValidationResponse.asValid(gson.toJson(structuredContent));
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : "invalid structured content";
                return ValidationResponse.asInvalid(message);
            }
        }
    }
}
