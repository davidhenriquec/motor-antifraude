package br.com.antifraude.simulador;

import br.com.antifraude.contrato.Transacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PublicadorDeTransacoes {
    private static final Logger log = LoggerFactory.getLogger(PublicadorDeTransacoes.class);

    private final KafkaTemplate<String, Transacao> kafkaTemplate;
    private final AtomicLong publicadas = new AtomicLong();

    @Value("${simulador.topico}")
    private String topico;

    public PublicadorDeTransacoes(KafkaTemplate<String, Transacao> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publicar(Transacao transacao) {
        kafkaTemplate.send(topico, transacao.clienteId(), transacao)
                .whenComplete((resultado, erro) -> {
                    if (erro != null) {
                        log.error("falha ao publicar transacao {}", transacao.transacaoId(), erro);
                        return;
                    }
                    publicadas.incrementAndGet();
                    if (log.isDebugEnabled()) {
                        log.debug("cliente {} -> particao {}",
                                transacao.clienteId(), resultado.getRecordMetadata().partition());
                    }
                });
    }

    public void publicarTodas(List<Transacao> transacoes) {
        transacoes.forEach(this::publicar);
    }

    public int publicarEDevolverParticao(Transacao transacao) {
        try {
            SendResult<String, Transacao> resultado = kafkaTemplate.send(topico, transacao.clienteId(), transacao).get();
            publicadas.incrementAndGet();
            return resultado.getRecordMetadata().partition();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido ao publicar", e);
        } catch (Exception e) {
            throw new IllegalStateException("falha ao publicar", e);
        }
    }

    public long totalPublicadas() {
        return publicadas.get();
    }
}
