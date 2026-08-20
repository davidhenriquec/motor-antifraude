package br.com.antifraude.auditoria.kafka;

import br.com.antifraude.auditoria.registro.RepositorioDeAuditoria;
import br.com.antifraude.contrato.Alerta;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConsumidorDeAlertas {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDeAlertas.class);

    private final RepositorioDeAuditoria repositorio;
    private final Counter alertasGravados;
    private final Counter alertasJaGravados;

    public ConsumidorDeAlertas(RepositorioDeAuditoria repositorio, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.alertasGravados = Counter.builder("antifraude.auditoria.gravados")
                .description("Alertas gravados na trilha de auditoria")
                .register(metricas);
        this.alertasJaGravados = Counter.builder("antifraude.auditoria.ja.gravados")
                .description("Alertas reentregues que o banco recusou por chave duplicada")
                .register(metricas);
    }

    @KafkaListener(topics = "${auditoria.topico-de-alertas}", groupId = "${auditoria.grupo}")
    public void receber(Alerta alerta) {
        if (repositorio.gravar(alerta)) {
            alertasGravados.increment();
        } else {
            alertasJaGravados.increment();
        }
    }
}
