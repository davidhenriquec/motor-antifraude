package br.com.antifraude.motor.deteccao;

import br.com.antifraude.contrato.Canal;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.memoria.RepositorioDeMemoria;
import br.com.antifraude.motor.regra.Regra;
import br.com.antifraude.motor.regra.RegraVelocidadeAlta;
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

class AvaliadorDeTransacaoTest {

    private static final String CLIENTE = "cli-000001";

    private RepositorioEmMapa repositorio;
    private AvaliadorDeTransacao avaliador;

    @BeforeEach
    void preparar() {
        repositorio = new RepositorioEmMapa();
        avaliador = new AvaliadorDeTransacao(repositorio, List.of(new RegraVelocidadeAlta()));
    }

    @Test
    @DisplayName("transacao nova e avaliada e a memoria do cliente e atualizada")
    void avaliaTransacaoNova() {
        Transacao transacao = transacao(CLIENTE, 10_000, Instant.now());

        ResultadoDaAvaliacao resultado = avaliador.avaliar(transacao);

        assertThat(resultado.ehDuplicada()).isFalse();
        assertThat(repositorio.buscar(CLIENTE).contagemHistorica()).isEqualTo(1);
    }

    @Test
    @DisplayName("transacao repetida e marcada como duplicada e nao altera a memoria")
    void reconheceDuplicada() {
        Transacao transacao = transacao(CLIENTE, 10_000, Instant.now());
        avaliador.avaliar(transacao);

        ResultadoDaAvaliacao resultado = avaliador.avaliar(transacao);

        assertThat(resultado.ehDuplicada()).isTrue();
        assertThat(resultado.temAlertas()).isFalse();
        assertThat(repositorio.buscar(CLIENTE).contagemHistorica())
                .as("o reenvio nao pode inflar a contagem")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("sem regras configuradas nenhum alerta e produzido, mas a memoria continua sendo mantida")
    void mantemMemoriaSemRegras() {
        AvaliadorDeTransacao semRegras = new AvaliadorDeTransacao(repositorio, List.of());

        ResultadoDaAvaliacao resultado = semRegras.avaliar(transacao(CLIENTE, 10_000, Instant.now()));

        assertThat(resultado.temAlertas()).isFalse();
        assertThat(repositorio.buscar(CLIENTE).contagemHistorica()).isEqualTo(1);
    }

    @Test
    @DisplayName("todas as regras sao consultadas e cada disparo vira um alerta")
    void consultaTodasAsRegras() {
        AvaliadorDeTransacao comDuas = new AvaliadorDeTransacao(
                repositorio, List.of(new RegraQueSempreDispara("a"), new RegraQueSempreDispara("b")));

        ResultadoDaAvaliacao resultado = comDuas.avaliar(transacao(CLIENTE, 10_000, Instant.now()));

        assertThat(resultado.alertas()).hasSize(2);
        assertThat(resultado.alertas()).extracting("regraId").containsExactly("a", "b");
    }

    @Test
    @DisplayName("a regra recebe a memoria ja contendo a transacao atual")
    void regraRecebeMemoriaAtualizada() {
        Instant base = Instant.now().minus(1, ChronoUnit.MINUTES);
        for (int i = 0; i < 3; i++) {
            avaliador.avaliar(transacao(CLIENTE, 10_000, base.plusSeconds(i)));
        }

        RegraEspia espia = new RegraEspia();
        AvaliadorDeTransacao comEspia = new AvaliadorDeTransacao(repositorio, List.of(espia));

        comEspia.avaliar(transacao(CLIENTE, 10_000, Instant.now()));

        assertThat(espia.contagemVista)
                .as("a quarta transacao deve enxergar as quatro, nao tres")
                .isEqualTo(4);
    }

    private Transacao transacao(String cliente, long valorCentavos, Instant horario) {
        return new Transacao(
                UUID.randomUUID().toString(),
                cliente,
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

    private static final class RegraQueSempreDispara implements Regra {
        private final String identificador;

        private RegraQueSempreDispara(String identificador) {
            this.identificador = identificador;
        }

        @Override
        public String id() {
            return identificador;
        }

        @Override
        public int versao() {
            return 1;
        }

        @Override
        public java.util.Optional<br.com.antifraude.contrato.Alerta> avaliar(
                Transacao transacao, MemoriaDoCliente memoria) {
            return java.util.Optional.of(new br.com.antifraude.contrato.Alerta(
                    UUID.randomUUID().toString(),
                    transacao.transacaoId(),
                    transacao.clienteId(),
                    transacao.cartaoToken(),
                    transacao.ultimosQuatro(),
                    transacao.valorCentavos(),
                    identificador,
                    1,
                    "5m",
                    br.com.antifraude.contrato.Severidade.MEDIA,
                    true,
                    Map.of(),
                    transacao.horarioEvento(),
                    Instant.now()));
        }
    }

    private static final class RegraEspia implements Regra {
        private long contagemVista;

        @Override
        public String id() {
            return "espia";
        }

        @Override
        public int versao() {
            return 1;
        }

        @Override
        public java.util.Optional<br.com.antifraude.contrato.Alerta> avaliar(
                Transacao transacao, MemoriaDoCliente memoria) {
            contagemVista = memoria.contagemHistorica();
            return java.util.Optional.empty();
        }
    }
}
