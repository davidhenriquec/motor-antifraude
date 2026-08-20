package br.com.antifraude.notificacao.kafka;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.notificacao.entrega.CanalDeEntrega;
import br.com.antifraude.notificacao.entrega.DecisaoDeEntrega;
import br.com.antifraude.notificacao.entrega.ServicoDeEntrega;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ConsumidorDeAlertas {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDeAlertas.class);

    private final ServicoDeEntrega servicoDeEntrega;
    private final KafkaTemplate<String, Alerta> produtor;
    private final String topicoDaFilaMorta;
    private final Counter recebidos;
    private final Counter semNotificacao;
    private final Counter enviadosParaFilaMorta;
    private final Timer latenciaDoAlertaAteAEntrega;
    private final IdadeDaFilaMorta idadeDaFilaMorta;
    private final MeterRegistry metricas;

    public ConsumidorDeAlertas(
            ServicoDeEntrega servicoDeEntrega,
            KafkaTemplate<String, Alerta> produtor,
            @Value("${notificacao.topico-fila-morta}") String topicoDaFilaMorta,
            IdadeDaFilaMorta idadeDaFilaMorta,
            MeterRegistry metricas) {
        this.idadeDaFilaMorta = idadeDaFilaMorta;
        this.metricas = metricas;
        this.servicoDeEntrega = servicoDeEntrega;
        this.produtor = produtor;
        this.topicoDaFilaMorta = topicoDaFilaMorta;
        this.recebidos = Counter.builder("antifraude.notificacao.recebidos")
                .description("Alertas consumidos do topico")
                .register(metricas);
        this.semNotificacao = Counter.builder("antifraude.notificacao.sem.notificacao")
                .description("Alertas que nao pedem aviso ao cliente")
                .register(metricas);
        this.enviadosParaFilaMorta = Counter.builder("antifraude.notificacao.fila.morta")
                .description("Alertas que nao puderam ser entregues e foram para a fila morta")
                .register(metricas);
        this.latenciaDoAlertaAteAEntrega = Timer.builder("antifraude.notificacao.latencia.entrega")
                .description("Da avaliacao da regra ate a notificacao chegar ao provedor")
                .publishPercentileHistogram()
                .minimumExpectedValue(java.time.Duration.ofMillis(1))
                .maximumExpectedValue(java.time.Duration.ofSeconds(30))
                .register(metricas);
    }

    @KafkaListener(topics = "${notificacao.topico-de-alertas}", groupId = "${notificacao.grupo}")
    public void receber(Alerta alerta) {
        recebidos.increment();
        metricas.counter(
                        "antifraude.notificacao.alertas.recebidos.por.regra",
                        "regra", alerta.regraId(),
                        "severidade", alerta.severidade().name())
                .increment();

        List<CanalDeEntrega> canais = DecisaoDeEntrega.canaisPara(alerta);
        if (canais.isEmpty()) {
            semNotificacao.increment();
            return;
        }

        for (CanalDeEntrega canal : canais) {
            try {
                servicoDeEntrega.entregar(alerta, canal);
                latenciaDoAlertaAteAEntrega.record(
                        java.time.Duration.between(alerta.horarioAvaliacao(), java.time.Instant.now()).abs());
            } catch (RuntimeException problema) {
                enviadosParaFilaMorta.increment();
                idadeDaFilaMorta.registrarEntrada();
                log.error(
                        "Entrega por {} falhou para o alerta {}. Indo para a fila morta: {}",
                        canal,
                        alerta.alertaId(),
                        problema.toString());
                produtor.send(topicoDaFilaMorta, alerta.clienteId(), alerta);
            }
        }
    }
}
