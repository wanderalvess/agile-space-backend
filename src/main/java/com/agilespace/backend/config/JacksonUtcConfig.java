package com.agilespace.backend.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Datas do servidor (createdAt, updatedAt, lastLoginAt...) são {@link LocalDateTime} gravados com {@code now()} na
 * JVM, que roda em UTC em produção. Enviadas sem fuso, o navegador as lê como horário local e mostra 3 h adiantado
 * (Brasília). Aqui o servidor passa a enviar o INSTANTE com {@code Z} (UTC), que o navegador converte certo.
 * O banco e o código não mudam: o valor continua sendo o horário UTC da JVM.
 *
 * <p>Campos que guardam uma data/hora DIGITADA por pessoa (horário "de parede", sem fuso) não podem ganhar {@code Z}:
 * ficam com {@code @JsonSerialize(using = LocalDateTimeSerializer.class)} e {@code @JsonDeserialize(using =
 * LocalDateTimeDeserializer.class)} (ver {@code UserKanbanCard.dueDate} e {@code WorkItem.target*}).
 *
 * <p>Na entrada aceita os dois formatos: com {@code Z}/offset (converte para UTC) ou sem fuso (como antes).
 */
@Configuration
public class JacksonUtcConfig {

    private static final Pattern HAS_OFFSET = Pattern.compile(".*T.*(Z|[+-]\\d{2}:?\\d{2})$");

    public static class UtcLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(DateTimeFormatter.ISO_INSTANT.format(value.toInstant(ZoneOffset.UTC)));
        }
    }

    public static class TolerantLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                String text = p.getText().trim();
                if (HAS_OFFSET.matcher(text).matches()) {
                    return OffsetDateTime.parse(normalizeOffset(text)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                }
                if (text.length() == 10) {
                    return LocalDate.parse(text).atStartOfDay();
                }
            }
            return LocalDateTimeDeserializer.INSTANCE.deserialize(p, ctxt);
        }

        /** "+0300" vira "+03:00" (OffsetDateTime.parse exige os dois pontos). */
        private static String normalizeOffset(String text) {
            return text.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2");
        }
    }

    @Bean
    public com.fasterxml.jackson.databind.Module utcLocalDateTimeModule() {
        SimpleModule module = new SimpleModule("UtcLocalDateTimeModule");
        module.addSerializer(LocalDateTime.class, new UtcLocalDateTimeSerializer());
        module.addDeserializer(LocalDateTime.class, new TolerantLocalDateTimeDeserializer());
        return module;
    }
}
