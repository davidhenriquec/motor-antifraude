package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Canal;
import br.com.antifraude.contrato.Severidade;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegraDeclarativaTest {

    private static final Instant AGORA = Instant.parse("2026-08-19T12:00:00Z");

    private final CompiladorCel compilador = new CompiladorCel();

    @Test
    @DisplayName("condicao sobre o valor da transacao dispara")
    void condicaoSobreValor() {
        RegraDeclarativa regra = regra("transacao.valorCentavos > 100000");

        assertThat(regra.avaliar(transacao(200_000), MemoriaDoCliente.vazia())).isPresent();
        assertThat(regra.avaliar(transacao(50_000), MemoriaDoCliente.vazia())).isEmpty();
    }

    @Test
    @DisplayName("limiar dinamico: a condicao le o perfil do cliente")
    void limiarDinamicoSobreOPerfil() {
        RegraDeclarativa regra =
                regra("transacao.valorCentavos > perfil.ticketMedioCentavos * 2");
        MemoriaDoCliente memoria = comTicketMedio(10_000);

        assertThat(regra.avaliar(transacao(30_000), memoria))
                .as("R$ 300 contra ticket medio de R$ 100")
                .isPresent();
        assertThat(regra.avaliar(transacao(15_000), memoria)).isEmpty();
    }

    @Test
    @DisplayName("condicao composta combina janela e perfil")
    void condicaoComposta() {
        RegraDeclarativa regra = regra(
                "janela5m.contagem > 3 && transacao.valorCentavos > perfil.ticketMedioCentavos * 2");

        MemoriaDoCliente comMuitasTransacoes = comEventos(5, 10_000);
        MemoriaDoCliente comPoucas = comEventos(2, 10_000);

        assertThat(regra.avaliar(transacao(90_000), comMuitasTransacoes)).isPresent();
        assertThat(regra.avaliar(transacao(90_000), comPoucas))
                .as("a contagem sozinha reprova a condicao inteira")
                .isEmpty();
    }

    @Test
    @DisplayName("condicao sobre a soma da hora, sem olhar o historico")
    void condicaoSobreSomaDaHora() {
        RegraDeclarativa regra = regra("janela60m.somaCentavos > 1000000");

        assertThat(regra.avaliar(transacao(10_000), comEventos(30, 50_000))).isPresent();
        assertThat(regra.avaliar(transacao(10_000), comEventos(3, 50_000))).isEmpty();
    }

    @Test
    @DisplayName("condicao sobre texto: canal e cidade")
    void condicaoSobreTexto() {
        RegraDeclarativa regra =
                regra("transacao.canal == 'ECOMMERCE' && transacao.cidade != ultimo.cidade");

        MemoriaDoCliente memoria = comEventos(1, 10_000);

        assertThat(regra.avaliar(transacaoEm("Recife", Canal.ECOMMERCE), memoria)).isPresent();
        assertThat(regra.avaliar(transacaoEm("Sao Paulo", Canal.ECOMMERCE), memoria)).isEmpty();
    }

    @Test
    @DisplayName("limiar absoluto dispara para cliente sem historico nenhum")
    void limiarAbsolutoCobreAPartidaAFria() {
        RegraDeclarativa regra = regra("transacao.valorCentavos > 3000000");
        MemoriaDoCliente semHistorico = MemoriaDoCliente.vazia();

        assertThat(semHistorico.contagemHistorica()).isZero();
        assertThat(regra.avaliar(transacao(5_000_000), semHistorico))
                .as("e justamente a partida a frio que a regra relativa nao cobre")
                .isPresent();
    }

    @Test
    @DisplayName("limiar absoluto ignora o ticket medio, entao envenenar a base nao ajuda")
    void limiarAbsolutoNaoDependeDoTicketMedio() {
        RegraDeclarativa regra = regra("transacao.valorCentavos > 3000000");
        MemoriaDoCliente baseEnvenenada = MemoriaDoCliente.vazia().comTicketMedio(90_000_000L);

        assertThat(regra.avaliar(transacao(5_000_000), baseEnvenenada)).isPresent();
    }

    @Test
    @DisplayName("a soma da hora ignora o que saiu da janela")
    void somaDaHoraIgnoraOQueExpirou() {
        RegraDeclarativa regra = regra("janela60m.somaCentavos > 1000000");

        assertThat(regra.avaliar(transacao(10_000), comEventos(30, 80_000)))
                .as("30 transacoes de R$ 800 em 30 segundos somam R$ 24.000")
                .isPresent();
        assertThat(regra.avaliar(transacao(10_000), comEventosEspacados(30, 80_000, 300)))
                .as("as mesmas 30 espalhadas por mais de uma hora nao somam")
                .isEmpty();
    }

    @Test
    @DisplayName("o alerta carrega a condicao e os valores que a alimentaram")
    void alertaExplicaADecisao() {
        RegraDeclarativa regra = regra("transacao.valorCentavos > 100000");

        Alerta alerta = regra.avaliar(transacao(200_000), comTicketMedio(5_000)).orElseThrow();

        assertThat(alerta.valoresEntrada())
                .containsEntry("condicao", "transacao.valorCentavos > 100000")
                .containsKey("janela5m")
                .containsKey("perfil");
        assertThat(alerta.regraId()).isEqualTo("regra-de-teste");
        assertThat(alerta.severidade()).isEqualTo(Severidade.ALTA);
    }

    @Test
    @DisplayName("erro de sintaxe e recusado na compilacao, nao em producao")
    void sintaxeInvalidaFalhaNaCompilacao() {
        assertThatThrownBy(() -> regra("transacao.valorCentavos >"))
                .isInstanceOf(ExpressaoInvalidaException.class)
                .hasMessageContaining("regra-de-teste");
    }

    @Test
    @DisplayName("campo inexistente e recusado no carregamento, contra um contexto de exemplo")
    void campoInexistenteFalhaNoCarregamento() {
        assertThatThrownBy(() -> regra("transacao.campoQueNaoExiste > 1"))
                .isInstanceOf(ExpressaoInvalidaException.class);
    }

    @Test
    @DisplayName("condicao que nao devolve booleano e recusada no carregamento")
    void condicaoNaoBooleanaEhRecusada() {
        assertThatThrownBy(() -> regra("transacao.valorCentavos"))
                .isInstanceOf(ExpressaoInvalidaException.class);
    }

    @Test
    @DisplayName("nao existe laco na linguagem: toda condicao termina")
    void naoExisteLaco() {
        assertThatThrownBy(() -> regra("while (true) { 1 }"))
                .isInstanceOf(ExpressaoInvalidaException.class);
    }

    private RegraDeclarativa regra(String condicao) {
        DefinicaoDeRegra definicao = new DefinicaoDeRegra(
                "regra-de-teste", 1, "regra usada nos testes", true, Severidade.ALTA, "5m", condicao, true);
        return new RegraDeclarativa(definicao, compilador.compilar(definicao.id(), condicao));
    }

    private MemoriaDoCliente comTicketMedio(long ticketMedioCentavos) {
        return MemoriaDoCliente.vazia().comTicketMedio(ticketMedioCentavos);
    }

    private MemoriaDoCliente comEventos(int quantidade, long valorCentavos) {
        return comEventosEspacados(quantidade, valorCentavos, 1);
    }

    private MemoriaDoCliente comEventosEspacados(
            int quantidade, long valorCentavos, int intervaloEmSegundos) {
        MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
        for (int i = quantidade; i > 0; i--) {
            memoria = memoria.registrarEvento(transacaoEm(
                    "Sao Paulo",
                    Canal.POS,
                    valorCentavos,
                    AGORA.minus((long) i * intervaloEmSegundos, ChronoUnit.SECONDS)));
        }
        return memoria.comTicketMedio(10_000);
    }

    private Transacao transacao(long valorCentavos) {
        return transacaoEm("Sao Paulo", Canal.POS, valorCentavos, AGORA);
    }

    private Transacao transacaoEm(String cidade, Canal canal) {
        return transacaoEm(cidade, canal, 10_000, AGORA);
    }

    private Transacao transacaoEm(String cidade, Canal canal, long valorCentavos, Instant horario) {
        return new Transacao(
                UUID.randomUUID().toString(),
                "cli-000001",
                "tok-teste",
                "411111",
                "1234",
                valorCentavos,
                "est-001",
                "5411",
                cidade,
                "BR",
                canal,
                horario);
    }
}
