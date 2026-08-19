package br.com.antifraude.motor.kafka;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.regra.Regra;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueBytesStoreSupplier;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;

import java.util.List;

@Configuration
@EnableKafkaStreams
public class TopologiaConfig {

    private static final Logger log = LoggerFactory.getLogger(TopologiaConfig.class);

    public static final String MEMORIA_DO_CLIENTE = "memoria-do-cliente";

    private final ObjectMapper mapper;
    private final MeterRegistry metricas;
    private final List<Regra> regras;

    @Value("${motor.topico-de-entrada}")
    private String topicoDeEntrada;

    @Value("${motor.topico-de-saida}")
    private String topicoDeSaida;

    public TopologiaConfig(ObjectMapper mapper, MeterRegistry metricas, List<Regra> regras) {
        this.mapper = mapper;
        this.metricas = metricas;
        this.regras = regras;
    }

    @Bean
    public KStream<String, Alerta> fluxoDeDeteccao(StreamsBuilder construtor) {
        return montar(construtor, mapper, metricas, regras, topicoDeEntrada, topicoDeSaida, true);
    }

    public static KStream<String, Alerta> montar(
            StreamsBuilder construtor,
            ObjectMapper mapper,
            MeterRegistry metricas,
            List<Regra> regras,
            String topicoDeEntrada,
            String topicoDeSaida,
            boolean persistente) {
        log.info("Iniciando consumo do topico: {}", topicoDeEntrada);

        Serde<Transacao> serdeTransacao = SerdeJson.de(Transacao.class, mapper);
        Serde<Alerta> serdeAlerta = SerdeJson.de(Alerta.class, mapper);
        Serde<MemoriaDoCliente> serdeMemoria = SerdeJson.de(MemoriaDoCliente.class, mapper);

        log.info("serdeTransacao: {}, serdeAlerta: {}, serdeMemoria: {}", serdeTransacao, serdeAlerta, serdeMemoria);

        KeyValueBytesStoreSupplier suporte = persistente
                ? Stores.persistentKeyValueStore(MEMORIA_DO_CLIENTE)
                : Stores.inMemoryKeyValueStore(MEMORIA_DO_CLIENTE);

        log.info("suporte: {}", suporte);

        StoreBuilder<KeyValueStore<String, MemoriaDoCliente>> memoria = Stores.keyValueStoreBuilder(suporte, Serdes.String(), serdeMemoria);
        log.info("memoria: {}", memoria);

        construtor.addStateStore(memoria);

        KStream<String, Alerta> alertas = construtor
                .stream(topicoDeEntrada, Consumed.with(Serdes.String(), serdeTransacao))
                .process(() -> new ProcessadorDeTransacoes(regras, metricas), MEMORIA_DO_CLIENTE);

        log.info("alertas: {}", alertas);

        alertas.to(topicoDeSaida, Produced.with(Serdes.String(), serdeAlerta));

        log.info("Fim do consumo topico de entrada: {}", topicoDeSaida);

        return alertas;
    }
}
