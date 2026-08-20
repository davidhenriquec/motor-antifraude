package br.com.antifraude.notificacao.deduplicacao;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RegistroDeEntregas {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeEntregas.class);

    private final StringRedisTemplate redis;
    private final Counter redisIndisponivel;
    private final Duration prazoDaReserva;
    private final Duration prazoDaConfirmacao;

    public RegistroDeEntregas(
            StringRedisTemplate redis,
            MeterRegistry metricas,
            @Value("${notificacao.deduplicacao.reserva-em-segundos}") long reservaEmSegundos,
            @Value("${notificacao.deduplicacao.confirmacao-em-horas}") long confirmacaoEmHoras) {
        this.redis = redis;
        this.prazoDaReserva = Duration.ofSeconds(reservaEmSegundos);
        this.prazoDaConfirmacao = Duration.ofHours(confirmacaoEmHoras);
        this.redisIndisponivel = Counter.builder("antifraude.notificacao.redis.indisponivel")
                .description("Vezes que o Redis falhou e a entrega seguiu sem protecao contra duplicata")
                .register(metricas);
    }

    public boolean reservar(String chave) {
        try {
            Boolean reservou = redis.opsForValue()
                    .setIfAbsent(chave, "reservada", prazoDaReserva);
            return Boolean.TRUE.equals(reservou);
        } catch (RuntimeException problema) {
            redisIndisponivel.increment();
            log.warn(
                    "Redis indisponivel na reserva. Enviando assim mesmo: melhor duplicar que silenciar. {}",
                    problema.toString());
            return true;
        }
    }

    public void confirmar(String chave) {
        try {
            redis.expire(chave, prazoDaConfirmacao);
        } catch (RuntimeException problema) {
            redisIndisponivel.increment();
            log.warn("Redis indisponivel na confirmacao da chave {}: {}", chave, problema.toString());
        }
    }

    public void liberar(String chave) {
        try {
            redis.delete(chave);
        } catch (RuntimeException problema) {
            redisIndisponivel.increment();
            log.warn("Redis indisponivel ao liberar a chave {}: {}", chave, problema.toString());
        }
    }
}
