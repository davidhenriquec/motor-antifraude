package br.com.antifraude.notificacao.entrega;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.notificacao.deduplicacao.ChaveDeEntrega;
import br.com.antifraude.notificacao.deduplicacao.RegistroDeEntregas;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ServicoDeEntrega {

    private static final Logger log = LoggerFactory.getLogger(ServicoDeEntrega.class);

    private final ProvedorSimulado provedor;
    private final RegistroDeEntregas registro;
    private final Counter entregues;
    private final Counter jaEntregues;

    public ServicoDeEntrega(
            ProvedorSimulado provedor, RegistroDeEntregas registro, MeterRegistry metricas) {
        this.provedor = provedor;
        this.registro = registro;
        this.entregues = Counter.builder("antifraude.notificacao.entregues")
                .description("Notificacoes efetivamente enviadas ao cliente")
                .register(metricas);
        this.jaEntregues = Counter.builder("antifraude.notificacao.ja.entregues")
                .description("Reentregas descartadas porque a chave ja estava reservada no Redis")
                .register(metricas);
    }

    @CircuitBreaker(name = "provedorDeNotificacao")
    @Retry(name = "provedorDeNotificacao")
    public void entregar(Alerta alerta, CanalDeEntrega canal) {
        String chave = ChaveDeEntrega.de(alerta, canal);

        if (!registro.reservar(chave)) {
            jaEntregues.increment();
            return;
        }

        try {
            provedor.enviar(canal, alerta);
        } catch (RuntimeException problema) {
            registro.liberar(chave);
            throw problema;
        }

        registro.confirmar(chave);
        entregues.increment();
    }
}
