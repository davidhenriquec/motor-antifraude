package br.com.antifraude.motor.kafka;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.deteccao.AvaliadorDeTransacao;
import br.com.antifraude.motor.deteccao.FalhaDeRegra;
import br.com.antifraude.motor.deteccao.ResultadoDaAvaliacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.memoria.RepositorioNoKafkaStreams;
import br.com.antifraude.motor.regra.FonteDeRegras;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public class ProcessadorDeTransacoes implements Processor<String, Transacao, String, Alerta> {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorDeTransacoes.class);

    private final FonteDeRegras fonteDeRegras;
    private final MeterRegistry metricas;

    private ProcessorContext<String, Alerta> contexto;
    private AvaliadorDeTransacao avaliador;
    private Counter transacoesAvaliadas;
    private Counter transacoesDuplicadas;
    private Counter alertasGerados;
    private Counter memoriasNoTeto;
    private Counter transacoesComAlerta;
    private Timer latenciaDeProcessamento;
    private Timer latenciaPontaAPonta;
    private final Set<String> regrasJaRelatadas = new HashSet<>();

    public ProcessadorDeTransacoes(FonteDeRegras fonteDeRegras, MeterRegistry metricas) {
        this.fonteDeRegras = fonteDeRegras;
        this.metricas = metricas;
    }

    @Override
    public void init(ProcessorContext<String, Alerta> contexto) {
        this.contexto = contexto;

        KeyValueStore<String, MemoriaDoCliente> memoriasPorCliente = contexto.getStateStore(TopologiaConfig.MEMORIA_DO_CLIENTE);

        this.avaliador = new AvaliadorDeTransacao(new RepositorioNoKafkaStreams(memoriasPorCliente), fonteDeRegras);

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

        this.transacoesComAlerta = Counter.builder("antifraude.transacoes.com.alerta")
                .description("Transacoes que produziram ao menos um alerta. Denominador da taxa de disparo")
                .register(metricas);

        // Quanto o MOTOR leva. Nao inclui espera em fila: mede so a avaliacao.
        this.latenciaDeProcessamento = Timer.builder("antifraude.latencia.processamento")
                .description("Tempo dentro do motor: buscar memoria, avaliar regras, salvar")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofNanos(100_000))
                .maximumExpectedValue(Duration.ofSeconds(1))
                .register(metricas);

        // O SLA do enunciado. Inclui o tempo que a transacao esperou na fila, que e onde o
        // orcamento de 500ms costuma ir embora. Teto alto de proposito: com teto baixo, tudo
        // que estoura cai no ultimo balde e os percentis mentem, todos iguais ao teto.
        this.latenciaPontaAPonta = Timer.builder("antifraude.latencia.ponta.a.ponta")
                .description("Do horario do evento na origem ate a decisao das regras")
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(100),
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1))
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .register(metricas);

        log.info("Tarefa {} assumida com {} regra(s) ativa(s)", contexto.taskId(), fonteDeRegras.regrasAtivas().size());
    }

    @Override
    public void process(Record<String, Transacao> registro) {
        Transacao transacao = registro.value();
        if (transacao == null) {
            return;
        }

        long inicioDoProcessamento = System.nanoTime();
        ResultadoDaAvaliacao resultado = avaliador.avaliar(transacao);
        latenciaDeProcessamento.record(
                System.nanoTime() - inicioDoProcessamento, java.util.concurrent.TimeUnit.NANOSECONDS);

        if (resultado.ehDuplicada()) {
            transacoesDuplicadas.increment();
            return;
        }

        transacoesAvaliadas.increment();
        latenciaPontaAPonta.record(
                Duration.between(transacao.horarioEvento(), Instant.now()).abs());

        if (resultado.memoriaAtingiuOTeto()) {
            memoriasNoTeto.increment();
        }

        for (String regraId : resultado.alertasSuprimidos()) {
            metricas.counter("antifraude.alertas.suprimidos", "regra", regraId).increment();
        }

        for (FalhaDeRegra falha : resultado.falhas()) {
            metricas.counter("antifraude.regras.falhas", "regra", falha.regraId()).increment();
            if (regrasJaRelatadas.add(falha.regraId())) {
                log.warn(
                        "Regra {} lancou excecao e foi ignorada nesta transacao: {}",
                        falha.regraId(),
                        falha.motivo());
            }
        }

        if (resultado.temAlertas()) {
            transacoesComAlerta.increment();
        }

        for (Alerta alerta : resultado.alertas()) {
            alertasGerados.increment();
            metricas.counter(
                            "antifraude.alertas.por.regra",
                            "regra", alerta.regraId(),
                            "severidade", alerta.severidade().name())
                    .increment();
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
