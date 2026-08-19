package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Canal;
import br.com.antifraude.contrato.Severidade;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RegraValorAbsolutoTest {

    private static final long LIMIAR = 3_000_000L;

    private final RegraValorAbsoluto regra = new RegraValorAbsoluto(LIMIAR);

    @Test
    @DisplayName("valor acima do limiar dispara")
    void disparaAcimaDoLimiar() {
        Optional<Alerta> alerta = regra.avaliar(transacao(LIMIAR + 1), MemoriaDoCliente.vazia());

        assertThat(alerta).isPresent();
    }

    @Test
    @DisplayName("valor exatamente no limiar nao dispara")
    void naoDisparaNoLimiar() {
        Optional<Alerta> alerta = regra.avaliar(transacao(LIMIAR), MemoriaDoCliente.vazia());

        assertThat(alerta).isEmpty();
    }

    @Test
    @DisplayName("dispara para cliente sem historico nenhum")
    void disparaSemLinhaDeBase() {
        MemoriaDoCliente semHistorico = MemoriaDoCliente.vazia();

        Optional<Alerta> alerta = regra.avaliar(transacao(5_000_000L), semHistorico);

        assertThat(semHistorico.contagemHistorica()).isZero();
        assertThat(alerta)
                .as("e justamente a partida a frio que a regra relativa nao cobre")
                .isPresent();
    }

    @Test
    @DisplayName("o alerta e de severidade media: pergunta ao cliente sem acionar o antifraude")
    void severidadeMedia() {
        Alerta alerta = regra.avaliar(transacao(5_000_000L), MemoriaDoCliente.vazia()).orElseThrow();

        assertThat(alerta.severidade()).isEqualTo(Severidade.MEDIA);
        assertThat(alerta.notificarCliente()).isTrue();
    }

    @Test
    @DisplayName("o limiar usado fica registrado no alerta, para auditoria")
    void registraOLimiarAplicado() {
        Alerta alerta = regra.avaliar(transacao(5_000_000L), MemoriaDoCliente.vazia()).orElseThrow();

        assertThat(alerta.valoresEntrada())
                .containsEntry("limiarCentavos", LIMIAR)
                .containsEntry("dependeDeHistorico", false);
    }

    @Test
    @DisplayName("limiares diferentes produzem decisoes diferentes para o mesmo valor")
    void limiarEhConfiguravel() {
        Transacao transacao = transacao(4_000_000L);

        assertThat(new RegraValorAbsoluto(3_000_000L).avaliar(transacao, MemoriaDoCliente.vazia()))
                .isPresent();
        assertThat(new RegraValorAbsoluto(5_000_000L).avaliar(transacao, MemoriaDoCliente.vazia()))
                .isEmpty();
    }

    private Transacao transacao(long valorCentavos) {
        return new Transacao(
                UUID.randomUUID().toString(),
                "cli-000001",
                "tok-teste",
                "411111",
                "1234",
                valorCentavos,
                "est-001",
                "5411",
                "Sao Paulo",
                "BR",
                Canal.POS,
                Instant.now());
    }
}
