package br.com.antifraude.notificacao.kafka;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class IdadeDaFilaMorta {

    private final AtomicReference<Instant> maisAntigaNaoDrenada = new AtomicReference<>();

    public IdadeDaFilaMorta(MeterRegistry metricas) {
        Gauge.builder("antifraude.notificacao.fila.morta.idade.segundos", this, IdadeDaFilaMorta::idadeEmSegundos)
                .description("Idade da mensagem mais antiga que entrou na fila morta e nao foi drenada")
                .register(metricas);
    }

    public void registrarEntrada() {
        maisAntigaNaoDrenada.compareAndSet(null, Instant.now());
    }

    public void registrarDrenagemCompleta() {
        maisAntigaNaoDrenada.set(null);
    }

    private double idadeEmSegundos() {
        Instant maisAntiga = maisAntigaNaoDrenada.get();
        return maisAntiga == null ? 0 : Duration.between(maisAntiga, Instant.now()).toSeconds();
    }
}
