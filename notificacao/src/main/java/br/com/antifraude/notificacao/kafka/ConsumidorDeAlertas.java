package br.com.antifraude.notificacao.kafka;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.notificacao.entrega.CanalDeEntrega;
import br.com.antifraude.notificacao.entrega.DecisaoDeEntrega;
import br.com.antifraude.notificacao.entrega.ServicoDeEntrega;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

    public ConsumidorDeAlertas(
            ServicoDeEntrega servicoDeEntrega,
            KafkaTemplate<String, Alerta> produtor,
            @Value("${notificacao.topico-fila-morta}") String topicoDaFilaMorta,
            MeterRegistry metricas) {
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
    }

    @KafkaListener(topics = "${notificacao.topico-de-alertas}", groupId = "${notificacao.grupo}")
    public void receber(Alerta alerta) {
        recebidos.increment();

        List<CanalDeEntrega> canais = DecisaoDeEntrega.canaisPara(alerta);
        if (canais.isEmpty()) {
            semNotificacao.increment();
            return;
        }

        for (CanalDeEntrega canal : canais) {
            try {
                servicoDeEntrega.entregar(alerta, canal);
            } catch (RuntimeException problema) {
                enviadosParaFilaMorta.increment();
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
