package com.example.agentsuite.tools;

import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class McpJsonSchemaConverter {

    static JsonObjectSchema convert(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return JsonObjectSchema.builder()
                    .required(List.of())
                    .build();
        }

        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        if (schema.properties() != null) {
            for (Map.Entry<String, Object> entry : schema.properties().entrySet()) {
                String desc = null;
                String type = "string";
                if (entry.getValue() instanceof Map<?, ?> propMap) {
                    Object t = propMap.get("type");
                    if (t instanceof String s) type = s;
                    Object d = propMap.get("description");
                    if (d instanceof String s) desc = s;
                }
                properties.put(entry.getKey(), toElement(type, desc));
            }
        }

        List<String> required = schema.required() != null ? schema.required() : List.of();

        return JsonObjectSchema.builder()
                .addProperties(properties)
                .required(required)
                .build();
    }

    private static JsonSchemaElement toElement(String type, String description) {
        return switch (type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number"  -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            default        -> JsonStringSchema.builder().description(description).build();
        };
    }
}
