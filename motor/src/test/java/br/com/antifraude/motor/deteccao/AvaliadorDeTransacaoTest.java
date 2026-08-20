package br.com.antifraude.motor.deteccao;

import br.com.antifraude.contrato.Canal;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.memoria.RepositorioDeMemoria;
import br.com.antifraude.motor.regra.FonteDeRegras;
import br.com.antifraude.motor.regra.Regra;
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

class AvaliadorDeTransacaoTest {

    private static final String CLIENTE = "cli-000001";

    private RepositorioEmMapa repositorio;
    private AvaliadorDeTransacao avaliador;

    @BeforeEach
    void preparar() {
        repositorio = new RepositorioEmMapa();
        avaliador = new AvaliadorDeTransacao(repositorio, FonteDeRegras.fixa(List.of(RegrasDeTeste.velocidadeAlta())));
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
        AvaliadorDeTransacao semRegras = new AvaliadorDeTransacao(repositorio, FonteDeRegras.fixa(List.of()));

        ResultadoDaAvaliacao resultado = semRegras.avaliar(transacao(CLIENTE, 10_000, Instant.now()));

        assertThat(resultado.temAlertas()).isFalse();
        assertThat(repositorio.buscar(CLIENTE).contagemHistorica()).isEqualTo(1);
    }

    @Test
    @DisplayName("todas as regras sao consultadas e cada disparo vira um alerta")
    void consultaTodasAsRegras() {
        AvaliadorDeTransacao comDuas = new AvaliadorDeTransacao(
                repositorio,
                FonteDeRegras.fixa(List.of(new RegraQueSempreDispara("a"), new RegraQueSempreDispara("b"))));

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
        AvaliadorDeTransacao comEspia = new AvaliadorDeTransacao(repositorio, FonteDeRegras.fixa(List.of(espia)));

        comEspia.avaliar(transacao(CLIENTE, 10_000, Instant.now()));

        assertThat(espia.contagemVista)
                .as("a quarta transacao deve enxergar as quatro, nao tres")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("transacao sem alerta continua alimentando o ticket medio")
    void transacaoSemAlertaAlimentaOTicketMedio() {
        avaliador.avaliar(transacao(CLIENTE, 10_000, Instant.now()));

        assertThat(repositorio.buscar(CLIENTE).ticketMedioCentavos()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("durante a formacao do ticket medio o alerta nao impede a atualizacao")
    void naFormacaoDoTicketMedioTudoEhAbsorvido() {
        AvaliadorDeTransacao sempreAlerta = new AvaliadorDeTransacao(
                repositorio, FonteDeRegras.fixa(List.of(new RegraQueSempreDispara("a"))));
        Instant base = Instant.now();

        for (int i = 0; i < 4; i++) {
            sempreAlerta.avaliar(transacao(CLIENTE, 10_000, base.plusSeconds(i)));
        }

        assertThat(repositorio.buscar(CLIENTE).ticketMedioCentavos())
                .as("senao a base trava no valor da primeira transacao e o cliente alerta para sempre")
                .isEqualTo(10_000);
    }

    @Test
    @DisplayName("ataque sustentado nao emudece a regra: quem gerou alerta nao vira o novo normal")
    void ataqueSustentadoNaoEnvenenaOTicketMedio() {
        Instant base = Instant.now().minus(10, ChronoUnit.MINUTES);
        for (int i = 0; i < 10; i++) {
            avaliador.avaliar(transacao(CLIENTE, 6_800, base.plusSeconds(i)));
        }
        long ticketMedioLegitimo = repositorio.buscar(CLIENTE).ticketMedioCentavos();

        int vezesQueARegraCasou = 0;
        for (int i = 0; i < 30; i++) {
            ResultadoDaAvaliacao resultado =
                    avaliador.avaliar(transacao(CLIENTE, 80_000, base.plusSeconds(60 + i)));
            boolean casou = resultado.temAlertas()
                    || resultado.alertasSuprimidos().contains(RegrasDeTeste.VELOCIDADE_ALTA);
            if (casou) {
                vezesQueARegraCasou++;
            }
        }

        assertThat(vezesQueARegraCasou)
                .as("a regra precisa continuar reconhecendo a fraude ate a 30a transacao; "
                        + "antes da correcao ela emudecia por volta da 12a")
                .isEqualTo(30);
        assertThat(repositorio.buscar(CLIENTE).ticketMedioCentavos())
                .as("nenhuma transacao alertada pode mover a linha de base")
                .isEqualTo(ticketMedioLegitimo);
    }

    @Test
    @DisplayName("regra que lanca excecao nao derruba a avaliacao das demais")
    void regraQuebradaNaoDerrubaAsOutras() {
        AvaliadorDeTransacao comRegraQuebrada = new AvaliadorDeTransacao(
                repositorio,
                FonteDeRegras.fixa(List.of(new RegraQueQuebra(), new RegraQueSempreDispara("boa"))));

        ResultadoDaAvaliacao resultado =
                comRegraQuebrada.avaliar(transacao(CLIENTE, 10_000, Instant.now()));

        assertThat(resultado.alertas())
                .as("a regra boa precisa continuar produzindo alerta")
                .hasSize(1);
        assertThat(resultado.falhas())
                .as("e a falha precisa ser reportada, nao engolida")
                .extracting(FalhaDeRegra::regraId)
                .containsExactly("quebrada");
    }

    @Test
    @DisplayName("a fonte de regras e consultada a cada transacao, permitindo troca sem reinicio")
    void fonteDeRegrasEhConsultadaACadaTransacao() {
        List<Regra> regrasAtivas = new java.util.ArrayList<>();
        AvaliadorDeTransacao comFonteMutavel =
                new AvaliadorDeTransacao(repositorio, () -> List.copyOf(regrasAtivas));

        ResultadoDaAvaliacao semRegras =
                comFonteMutavel.avaliar(transacao(CLIENTE, 10_000, Instant.now()));
        regrasAtivas.add(new RegraQueSempreDispara("nova"));
        ResultadoDaAvaliacao comRegraNova =
                comFonteMutavel.avaliar(transacao(CLIENTE, 10_000, Instant.now()));

        assertThat(semRegras.temAlertas()).isFalse();
        assertThat(comRegraNova.temAlertas())
                .as("a regra entrou sem reiniciar o avaliador: e isso que o dia 4 exige")
                .isTrue();
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

    private static final class RegraQueQuebra implements Regra {
        @Override
        public String id() {
            return "quebrada";
        }

        @Override
        public int versao() {
            return 1;
        }

        @Override
        public java.util.Optional<br.com.antifraude.contrato.Alerta> avaliar(
                Transacao transacao, MemoriaDoCliente memoria) {
            throw new IllegalStateException("campo inexistente na regra");
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
