package br.com.antifraude.motor.kafka;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.deteccao.AvaliadorDeTransacao;
import br.com.antifraude.motor.deteccao.ResultadoDaAvaliacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.memoria.MemoriaNoKafkaStreams;
import br.com.antifraude.motor.regra.Regra;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ProcessadorDeTransacoes implements Processor<String, Transacao, String, Alerta> {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorDeTransacoes.class);

    private final List<Regra> regras;
    private final MeterRegistry metricas;

    private ProcessorContext<String, Alerta> contexto;
    private AvaliadorDeTransacao avaliador;
    private Counter avaliadas;
    private Counter duplicadasDescartadas;
    private Counter alertasGerados;
    private Counter memoriaNoLimite;

    public ProcessadorDeTransacoes(List<Regra> regras, MeterRegistry metricas) {
        this.regras = regras;
        this.metricas = metricas;
    }

    @Override
    public void init(ProcessorContext<String, Alerta> contexto) {
        log.info("Iniciando init do processador de transacoes");
        this.contexto = contexto;

        KeyValueStore<String, MemoriaDoCliente> armazenamento = contexto.getStateStore(TopologiaConfig.MEMORIA_DO_CLIENTE);

        this.avaliador = new AvaliadorDeTransacao(new MemoriaNoKafkaStreams(armazenamento), regras);

        this.avaliadas = Counter.builder("antifraude.transacoes.avaliadas")
                .description("Transacoes que passaram pela avaliacao de regras")
                .register(metricas);

        this.duplicadasDescartadas = Counter.builder("antifraude.transacoes.duplicadas")
                .description("Transacoes descartadas por ja terem sido processadas")
                .register(metricas);

        this.alertasGerados = Counter.builder("antifraude.alertas.gerados")
                .description("Alertas publicados")
                .register(metricas);
        this.memoriaNoLimite = Counter.builder("antifraude.memoria.no.limite")
                .description("Transacoes de clientes cuja memoria atingiu o teto de eventos")
                .register(metricas);

        log.info("Fim do init do processador de transacoes");
    }

    @Override
    public void process(Record<String, Transacao> registro) {
        log.info("Iniciando process do processador de transacoes");
        Transacao transacao = registro.value();
        if (transacao == null) {
            return;
        }

        ResultadoDaAvaliacao resultado = avaliador.avaliar(transacao);

        if (resultado.ehDuplicada()) {
            duplicadasDescartadas.increment();
            return;
        }

        avaliadas.increment();

        if (resultado.memoriaNoLimite()) {
            memoriaNoLimite.increment();
        }

        for (Alerta alerta : resultado.alertas()) {
            alertasGerados.increment();
            log.info(
                    "ALERTA regra={} cliente={} valor={} contagem5m={} ticketMedio={}",
                    alerta.regraId(),
                    alerta.clienteId(),
                    alerta.valorCentavos() / 100.0,
                    alerta.valoresEntrada().get("contagemJanela5m"),
                    ((Number) alerta.valoresEntrada().get("ticketMedioCentavos")).longValue() / 100.0);
            contexto.forward(registro.withValue(alerta));
        }
        log.info("Fim do process do processador de transacoes");
    }
}
