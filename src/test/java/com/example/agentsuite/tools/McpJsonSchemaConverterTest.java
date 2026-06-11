package com.example.agentsuite.tools;

import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpJsonSchemaConverterTest {

    @Test
    void convert_nullSchema_returnsEmptyObjectSchema() {
        JsonObjectSchema result = McpJsonSchemaConverter.convert(null);
        assertThat(result.properties()).isEmpty();
        assertThat(result.required()).isEmpty();
    }

    @Test
    void convert_schemaWithStringProperty_buildsStringSchema() {
        Map<String, Object> props = Map.of("path", Map.of("type", "string", "description", "File path"));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", props, List.of("path"), null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        assertThat(result.properties()).containsKey("path");
        assertThat(result.properties().get("path")).isInstanceOf(JsonStringSchema.class);
        assertThat(result.required()).containsExactly("path");
    }

    @Test
    void convert_schemaWithNumberProperty_buildsNumberSchema() {
        Map<String, Object> props = Map.of("count", Map.of("type", "number"));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", props, null, null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        assertThat(result.properties()).containsKey("count");
        assertThat(result.properties().get("count")).isInstanceOf(JsonNumberSchema.class);
        assertThat(result.required()).isEmpty();
    }

    @Test
    void convert_schemaWithBooleanProperty_buildsBooleanSchema() {
        Map<String, Object> props = Map.of("flag", Map.of("type", "boolean"));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", props, null, null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        assertThat(result.properties().get("flag")).isInstanceOf(JsonBooleanSchema.class);
    }

    @Test
    void convert_unknownPropertyType_treatedAsString() {
        Map<String, Object> props = Map.of("data", Map.of("type", "array"));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", props, null, null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        assertThat(result.properties()).containsKey("data");
        assertThat(result.properties().get("data")).isInstanceOf(JsonStringSchema.class);
    }

    @Test
    void convert_noProperties_returnsSchemaWithRequiredList() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", null, List.of("a"), null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        assertThat(result.properties()).isEmpty();
        assertThat(result.required()).containsExactly("a");
    }
}
