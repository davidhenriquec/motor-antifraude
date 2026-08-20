package br.com.antifraude.motor.deteccao;

import br.com.antifraude.contrato.Canal;
import br.com.antifraude.contrato.Severidade;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.memoria.RepositorioDeMemoria;
import br.com.antifraude.motor.regra.FonteDeRegras;
import br.com.antifraude.motor.regra.RegrasDeTeste;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RepeticaoDeAlertaTest {

    private static final String CLIENTE = "cli-000001";
    private static final Instant AGORA = Instant.parse("2026-08-19T12:00:00Z");

    private RepositorioEmMapa repositorio;

    @BeforeEach
    void preparar() {
        repositorio = new RepositorioEmMapa();
    }

    @Test
    @DisplayName("o cafe depois do alerta nao gera um segundo alerta")
    void cafeDepoisDoAlertaNaoAlerta() {
        AvaliadorDeTransacao avaliador = comRegra(
                "soma-na-hora", Severidade.ALTA, "60m", "janela60m.somaCentavos > 1000000");

        for (int i = 0; i < 3; i++) {
            avaliador.avaliar(transacao(400_000, AGORA.plusSeconds(i)));
        }
        ResultadoDaAvaliacao cafe = avaliador.avaliar(transacao(500, AGORA.plusSeconds(10)));

        assertThat(cafe.temAlertas())
                .as("o cliente ja foi avisado nesta janela; o cafe de R$ 5 nao pode alertar de novo")
                .isFalse();
        assertThat(cafe.alertasSuprimidos()).containsExactly("soma-na-hora");
    }

    @Test
    @DisplayName("um ataque inteiro produz um alerta por regra, nao um por transacao")
    void umAlertaPorJanela() {
        AvaliadorDeTransacao avaliador = comRegra(
                "soma-na-hora", Severidade.ALTA, "60m", "janela60m.somaCentavos > 1000000");

        int publicados = 0;
        for (int i = 0; i < 30; i++) {
            if (avaliador.avaliar(transacao(80_000, AGORA.plusSeconds(i))).temAlertas()) {
                publicados++;
            }
        }

        assertThat(publicados)
                .as("antes da correcao eram 17 alertas para o mesmo acontecimento")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("passada a janela, um novo episodio volta a alertar")
    void novoEpisodioAlertaDeNovo() {
        AvaliadorDeTransacao avaliador = comRegra(
                "velocidade-curta", Severidade.ALTA, "5m", "janela5m.contagem > 3");

        int primeiroEpisodio = 0;
        for (int i = 0; i < 5; i++) {
            if (avaliador.avaliar(transacao(10_000, AGORA.plusSeconds(i))).temAlertas()) {
                primeiroEpisodio++;
            }
        }

        Instant duasHorasDepois = AGORA.plus(2, ChronoUnit.HOURS);
        int segundoEpisodio = 0;
        for (int i = 0; i < 5; i++) {
            if (avaliador.avaliar(transacao(10_000, duasHorasDepois.plusSeconds(i))).temAlertas()) {
                segundoEpisodio++;
            }
        }

        assertThat(primeiroEpisodio).isEqualTo(1);
        assertThat(segundoEpisodio)
                .as("silenciar para sempre seria pior que repetir")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("regra sem janela alerta em toda ocorrencia: cada compra grande e um evento proprio")
    void regraSemJanelaNaoEhSuprimida() {
        AvaliadorDeTransacao avaliador = comRegra(
                "valor-absoluto", Severidade.MEDIA, "sem-janela", "transacao.valorCentavos > 500000");

        int publicados = 0;
        for (int i = 0; i < 3; i++) {
            if (avaliador.avaliar(transacao(600_000, AGORA.plusSeconds(i))).temAlertas()) {
                publicados++;
            }
        }

        assertThat(publicados)
                .as("tres compras de R$ 6.000 sao tres eventos distintos a confirmar")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("escalada de severidade fura a supressao")
    void escaladaDeSeveridadeEhPublicada() {
        AvaliadorDeTransacao media = comRegra(
                "acumulo", Severidade.MEDIA, "60m", "janela60m.somaCentavos > 1000000");
        for (int i = 0; i < 3; i++) {
            media.avaliar(transacao(400_000, AGORA.plusSeconds(i)));
        }

        AvaliadorDeTransacao alta = comRegra(
                "acumulo", Severidade.ALTA, "60m", "janela60m.somaCentavos > 1000000");
        ResultadoDaAvaliacao escalada = alta.avaliar(transacao(400_000, AGORA.plusSeconds(20)));

        assertThat(escalada.temAlertas())
                .as("subir de MEDIA para ALTA e informacao nova para o antifraude")
                .isTrue();
    }

    private AvaliadorDeTransacao comRegra(
            String id, Severidade severidade, String janela, String condicao) {
        return new AvaliadorDeTransacao(
                repositorio,
                FonteDeRegras.fixa(List.of(RegrasDeTeste.declarativa(id, severidade, janela, condicao))));
    }

    private Transacao transacao(long valorCentavos, Instant horario) {
        return new Transacao(
                UUID.randomUUID().toString(),
                CLIENTE,
                "tok-teste",
                "411111",
                "1234",
                valorCentavos,
                "est-001",
                "5411",
                "Sao Paulo",
                "BR",
                Canal.POS,
                horario);
    }

    private static final class RepositorioEmMapa implements RepositorioDeMemoria {
        private final Map<String, MemoriaDoCliente> dados = new HashMap<>();

        @Override
        public MemoriaDoCliente buscar(String clienteId) {
            return dados.getOrDefault(clienteId, MemoriaDoCliente.vazia());
        }

        @Override
        public void salvar(String clienteId, MemoriaDoCliente memoria) {
            dados.put(clienteId, memoria);
        }
    }
}
