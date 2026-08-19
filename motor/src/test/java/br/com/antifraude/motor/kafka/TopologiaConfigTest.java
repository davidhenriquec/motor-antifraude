package br.com.antifraude.motor.kafka;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Canal;
import br.com.antifraude.contrato.Severidade;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.regra.RegraVelocidadeAlta;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TopologiaConfigTest {
    private static final String ENTRADA = "transacoes";
    private static final String SAIDA = "alertas";
    private static final String CLIENTE = "cli-000001";

    private TopologyTestDriver driver;
    private TestInputTopic<String, Transacao> entrada;
    private TestOutputTopic<String, Alerta> saida;
    private ObjectMapper mapper;

    @BeforeEach
    void montarTopologia() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        StreamsBuilder construtor = new StreamsBuilder();
        TopologiaConfig.montar(
                construtor,
                mapper,
                new SimpleMeterRegistry(),
                List.of(new RegraVelocidadeAlta()),
                ENTRADA,
                SAIDA,
                false);

        Properties configuracao = new Properties();
        configuracao.put(StreamsConfig.APPLICATION_ID_CONFIG, "teste-motor");
        configuracao.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "inexistente:9092");

        driver = new TopologyTestDriver(construtor.build(), configuracao);
        entrada = driver.createInputTopic(
                ENTRADA, Serdes.String().serializer(), SerdeJson.de(Transacao.class, mapper).serializer());
        saida = driver.createOutputTopic(
                SAIDA, Serdes.String().deserializer(), SerdeJson.de(Alerta.class, mapper).deserializer());
    }

    @AfterEach
    void encerrar() {
        driver.close();
    }

    @Test
    @DisplayName("transacoes dentro do padrao do cliente nao geram alerta")
    void naoAlertaComportamentoNormal() {
        publicarHistorico(10, 10_000);

        publicar(transacao(CLIENTE, 11_000, "Sao Paulo", Instant.now()));

        assertThat(saida.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("cliente sem historico nao gera alerta, mesmo com valor alto e muitas transacoes")
    void naoAlertaSemLinhaDeBase() {
        Instant agora = Instant.now();
        for (int i = 0; i < 4; i++) {
            publicar(transacao(CLIENTE, 500_000, "Recife", agora.plusSeconds(i)));
        }

        assertThat(saida.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("muitas transacoes em 5 minutos acima do padrao do cliente geram alerta")
    void alertaVelocidadeAlta() {
        publicarHistorico(10, 10_000);

        Instant agora = Instant.now();
        for (int i = 0; i < 4; i++) {
            publicar(transacao(CLIENTE, 80_000, "Recife", agora.plusSeconds(i * 10L)));
        }

        List<Alerta> alertas = saida.readValuesToList();
        assertThat(alertas).isNotEmpty();

        Alerta primeiro = alertas.getFirst();
        assertThat(primeiro.clienteId()).isEqualTo(CLIENTE);
        assertThat(primeiro.regraId()).isEqualTo(RegraVelocidadeAlta.ID);
        assertThat(primeiro.severidade()).isEqualTo(Severidade.ALTA);
        assertThat(primeiro.notificarCliente()).isTrue();
    }

    @Test
    @DisplayName("o alerta carrega os valores que levaram a decisao, para auditoria")
    void alertaCarregaValoresDeEntrada() {
        publicarHistorico(10, 10_000);

        Instant agora = Instant.now();
        for (int i = 0; i < 4; i++) {
            publicar(transacao(CLIENTE, 80_000, "Recife", agora.plusSeconds(i * 10L)));
        }

        Alerta alerta = saida.readValuesToList().getFirst();

        assertThat(alerta.valoresEntrada())
                .containsKeys(
                        "contagemJanela5m",
                        "limiteContagem",
                        "valorCentavos",
                        "ticketMedioCentavos",
                        "limiarCentavos",
                        "historicoConsiderado");

        long limiar = ((Number) alerta.valoresEntrada().get("limiarCentavos")).longValue();
        assertThat(alerta.valorCentavos()).isGreaterThan(limiar);
    }

    @Test
    @DisplayName("transacao reenviada pela origem e descartada e nao infla a contagem")
    void descartaTransacaoDuplicada() {
        publicarHistorico(10, 10_000);

        Instant agora = Instant.now();

        List<Transacao> distintas = List.of(
                transacao(CLIENTE, 80_000, "Recife", agora),
                transacao(CLIENTE, 80_000, "Recife", agora.plusSeconds(10)),
                transacao(CLIENTE, 80_000, "Recife", agora.plusSeconds(20)));
        distintas.forEach(this::publicar);
        assertThat(saida.isEmpty()).isTrue();

        publicar(distintas.getFirst());

        assertThat(saida.isEmpty())
                .as("reenvio nao pode gerar alerta — seria falso positivo criado pela infraestrutura")
                .isTrue();
    }

    @Test
    @DisplayName("o limiar e relativo ao cliente: o mesmo valor alerta um e nao alerta outro")
    void limiarEhRelativoAoCliente() {
        String clienteDeTicketBaixo = "cli-baixo";
        String clienteDeTicketAlto = "cli-alto";

        publicarHistorico(clienteDeTicketBaixo, 10, 5_000);
        publicarHistorico(clienteDeTicketAlto, 10, 500_000);

        Instant agora = Instant.now();
        long mesmoValor = 60_000;

        for (int i = 0; i < 4; i++) {
            publicar(transacao(clienteDeTicketBaixo, mesmoValor, "Recife", agora.plusSeconds(i * 10L)));
            publicar(transacao(clienteDeTicketAlto, mesmoValor, "Recife", agora.plusSeconds(i * 10L)));
        }

        List<Alerta> alertas = saida.readValuesToList();

        assertThat(alertas)
                .as("o mesmo valor deve alertar so para quem ele foge do padrao")
                .isNotEmpty()
                .allSatisfy(a -> assertThat(a.clienteId()).isEqualTo(clienteDeTicketBaixo));
    }

    private void publicarHistorico(int quantidade, long valorCentavos) {
        publicarHistorico(CLIENTE, quantidade, valorCentavos);
    }

    private void publicarHistorico(String cliente, int quantidade, long valorCentavos) {
        Instant base = Instant.now().minus(3, ChronoUnit.HOURS);
        for (int i = 0; i < quantidade; i++) {
            publicar(transacao(cliente, valorCentavos, "Sao Paulo", base.plusSeconds(i * 60L)));
        }
        saida.readValuesToList();
    }

    private void publicar(Transacao transacao) {
        entrada.pipeInput(transacao.clienteId(), transacao);
    }

    private Transacao transacao(String cliente, long valorCentavos, String cidade, Instant horario) {
        return new Transacao(
                UUID.randomUUID().toString(),
                cliente,
                "tok-teste",
                "411111",
                "1234",
                valorCentavos,
                "est-001",
                "5411",
                cidade,
                "BR",
                Canal.POS,
                horario);
    }
}
