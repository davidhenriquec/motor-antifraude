package br.com.antifraude.motor.kafka;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.regra.FonteDeRegras;
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


@Configuration
@EnableKafkaStreams
public class TopologiaConfig {

    private static final Logger log = LoggerFactory.getLogger(TopologiaConfig.class);

    public static final String MEMORIA_DO_CLIENTE = "memoria-do-cliente";

    private final ObjectMapper conversorJson;
    private final MeterRegistry metricas;
    private final FonteDeRegras fonteDeRegras;

    @Value("${motor.topico-de-entrada}")
    private String topicoDeEntrada;

    @Value("${motor.topico-de-saida}")
    private String topicoDeSaida;

    public TopologiaConfig(
            ObjectMapper conversorJson, MeterRegistry metricas, FonteDeRegras fonteDeRegras) {
        this.conversorJson = conversorJson;
        this.metricas = metricas;
        this.fonteDeRegras = fonteDeRegras;
    }

    @Bean
    public KStream<String, Alerta> fluxoDeDeteccao(StreamsBuilder construtorDaTopologia) {
        return montarTopologia(
                construtorDaTopologia,
                conversorJson,
                metricas,
                fonteDeRegras,
                topicoDeEntrada,
                topicoDeSaida,
                true);
    }

    public static KStream<String, Alerta> montarTopologia(
            StreamsBuilder construtorDaTopologia,
            ObjectMapper conversorJson,
            MeterRegistry metricas,
            FonteDeRegras fonteDeRegras,
            String topicoDeEntrada,
            String topicoDeSaida,
            boolean memoriaEmDisco) {
        Serde<Transacao> serdeTransacao = SerdeJson.de(Transacao.class, conversorJson);
        Serde<Alerta> serdeAlerta = SerdeJson.de(Alerta.class, conversorJson);
        Serde<MemoriaDoCliente> serdeMemoria = SerdeJson.de(MemoriaDoCliente.class, conversorJson);

        KeyValueBytesStoreSupplier armazenamentoFisico = memoriaEmDisco
                ? Stores.persistentKeyValueStore(MEMORIA_DO_CLIENTE)
                : Stores.inMemoryKeyValueStore(MEMORIA_DO_CLIENTE);

        StoreBuilder<KeyValueStore<String, MemoriaDoCliente>> construtorDoArmazenamento =
                Stores.keyValueStoreBuilder(armazenamentoFisico, Serdes.String(), serdeMemoria);
        construtorDaTopologia.addStateStore(construtorDoArmazenamento);

        KStream<String, Alerta> alertas = construtorDaTopologia
                .stream(topicoDeEntrada, Consumed.with(Serdes.String(), serdeTransacao))
                .process(() -> new ProcessadorDeTransacoes(fonteDeRegras, metricas), MEMORIA_DO_CLIENTE);

        alertas.to(topicoDeSaida, Produced.with(Serdes.String(), serdeAlerta));

        log.info(
                "Topologia montada: entrada={} saida={} memoria={} regras={} memoriaEmDisco={}",
                topicoDeEntrada, topicoDeSaida, MEMORIA_DO_CLIENTE, fonteDeRegras.regrasAtivas().size(), memoriaEmDisco);

        return alertas;
    }
}
