package br.com.antifraude.motor.kafka;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.deteccao.AvaliadorDeTransacao;
import br.com.antifraude.motor.deteccao.ResultadoDaAvaliacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.memoria.RepositorioNoKafkaStreams;
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
    private Counter transacoesAvaliadas;
    private Counter transacoesDuplicadas;
    private Counter alertasGerados;
    private Counter memoriasNoTeto;

    public ProcessadorDeTransacoes(List<Regra> regras, MeterRegistry metricas) {
        this.regras = regras;
        this.metricas = metricas;
    }

    @Override
    public void init(ProcessorContext<String, Alerta> contexto) {
        this.contexto = contexto;

        KeyValueStore<String, MemoriaDoCliente> memoriasPorCliente = contexto.getStateStore(TopologiaConfig.MEMORIA_DO_CLIENTE);

        this.avaliador = new AvaliadorDeTransacao(new RepositorioNoKafkaStreams(memoriasPorCliente), regras);

        this.transacoesAvaliadas = Counter.builder("antifraude.transacoes.avaliadas")
                .description("Transacoes que passaram pela avaliacao de regras")
                .register(metricas);
        this.transacoesDuplicadas = Counter.builder("antifraude.transacoes.duplicadas")
                .description("Transacoes descartadas por ja terem sido processadas")
                .register(metricas);
        this.alertasGerados = Counter.builder("antifraude.alertas.gerados")
                .description("Alertas publicados")
                .register(metricas);
        this.memoriasNoTeto = Counter.builder("antifraude.memoria.no.limite")
                .description("Transacoes de clientes cuja memoria atingiu o teto de eventos")
                .register(metricas);

        log.info("Tarefa {} assumida com {} regra(s) ativa(s)", contexto.taskId(), regras.size());
    }

    @Override
    public void process(Record<String, Transacao> registro) {
        Transacao transacao = registro.value();
        if (transacao == null) {
            return;
        }

        ResultadoDaAvaliacao resultado = avaliador.avaliar(transacao);

        if (resultado.ehDuplicada()) {
            transacoesDuplicadas.increment();
            return;
        }

        transacoesAvaliadas.increment();

        if (resultado.memoriaAtingiuOTeto()) {
            memoriasNoTeto.increment();
        }

        for (Alerta alerta : resultado.alertas()) {
            alertasGerados.increment();
            log.info(
                    "ALERTA regra={} v{} severidade={} cliente={} valor={} entradas={}",
                    alerta.regraId(),
                    alerta.regraVersao(),
                    alerta.severidade(),
                    alerta.clienteId(),
                    alerta.valorCentavos() / 100.0,
                    alerta.valoresEntrada());
            contexto.forward(registro.withValue(alerta));
        }
    }
}
