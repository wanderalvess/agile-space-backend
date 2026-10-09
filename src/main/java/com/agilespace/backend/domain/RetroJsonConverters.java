package com.agilespace.backend.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.LinkedHashMap;
import java.util.Map;

/** Conversores Jackson -> coluna text para campos JSON livres do RetroBoard. */
public final class RetroJsonConverters {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RetroJsonConverters() { }

    @Converter
    public static class BooleanMapConverter implements AttributeConverter<Map<String, Boolean>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, Boolean> attribute) {
            if (attribute == null) return null;
            try {
                return MAPPER.writeValueAsString(attribute);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Falha ao serializar columnSorts", e);
            }
        }

        @Override
        public Map<String, Boolean> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) return null;
            try {
                return MAPPER.readValue(dbData, new TypeReference<LinkedHashMap<String, Boolean>>() { });
            } catch (JsonProcessingException e) {
                return null;
            }
        }
    }

    @Converter
    public static class JsonNodeConverter implements AttributeConverter<JsonNode, String> {
        @Override
        public String convertToDatabaseColumn(JsonNode attribute) {
            if (attribute == null || attribute.isNull()) return null;
            return attribute.toString();
        }

        @Override
        public JsonNode convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) return null;
            try {
                return MAPPER.readTree(dbData);
            } catch (JsonProcessingException e) {
                return null;
            }
        }
    }
}
