package br.com.antifraude.motor.kafka;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

public final class SerdeJson {

    private SerdeJson() {
    }

    public static <T> Serde<T> de(Class<T> tipo, ObjectMapper mapper) {
        return new SerdeSimples<>(mapper, mapper.getTypeFactory().constructType(tipo));
    }

    public static <T> Serde<T> de(JavaType tipo, ObjectMapper mapper) {
        return new SerdeSimples<>(mapper, tipo);
    }

    private record SerdeSimples<T>(ObjectMapper mapper, JavaType tipo) implements Serde<T> {

        @Override
        public Serializer<T> serializer() {
            return (topico, objeto) -> {
                if (objeto == null) {
                    return null;
                }
                try {
                    return mapper.writeValueAsBytes(objeto);
                } catch (Exception e) {
                    throw new SerializationException(
                            "falha ao serializar para o topico " + topico, e);
                }
            };
        }

        @Override
        public Deserializer<T> deserializer() {
            return (topico, bytes) -> {
                if (bytes == null || bytes.length == 0) {
                    return null;
                }
                try {
                    return mapper.readValue(bytes, tipo);
                } catch (Exception e) {
                    throw new SerializationException(
                            "falha ao desserializar do topico " + topico, e);
                }
            };
        }

        @Override
        public void configure(Map<String, ?> configuracoes, boolean ehChave) {
        }

        @Override
        public void close() {
        }
    }
}
