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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RegraSomaNaHoraTest {

    private static final Instant AGORA = Instant.parse("2026-08-19T12:00:00Z");
    private static final long LIMITE = 1_000_000L;

    private final RegraSomaNaHora regra = new RegraSomaNaHora(LIMITE);

    @Test
    @DisplayName("soma abaixo do limite nao dispara")
    void naoDisparaAbaixoDoLimite() {
        MemoriaDoCliente memoria = comTransacoes(10, 50_000L, 1);

        assertThat(regra.avaliar(transacao(50_000L, AGORA), memoria)).isEmpty();
    }

    @Test
    @DisplayName("soma acima do limite dispara")
    void disparaAcimaDoLimite() {
        MemoriaDoCliente memoria = comTransacoes(21, 50_000L, 1);

        assertThat(regra.avaliar(transacao(50_000L, AGORA), memoria)).isPresent();
    }

    @Test
    @DisplayName("pega o fraudador paciente que a regra de velocidade deixa passar")
    void pegaOFraudadorPaciente() {
        MemoriaDoCliente memoria = comTransacoes(36, 80_000L, 100);

        Optional<Alerta> alerta = regra.avaliar(transacao(80_000L, AGORA), memoria);

        assertThat(memoria.contagemNaJanela(java.time.Duration.ofMinutes(5), AGORA))
                .as("ritmo controlado: no maximo 3 em 5 minutos, entao a regra de velocidade nao ve nada")
                .isLessThanOrEqualTo(3);
        assertThat(alerta)
                .as("mas o acumulado da hora denuncia")
                .isPresent();
    }

    @Test
    @DisplayName("so conta o que esta dentro da hora")
    void ignoraOQueSaiuDaJanela() {
        MemoriaDoCliente antiga = comTransacoes(30, 80_000L, 300);

        assertThat(regra.avaliar(transacao(80_000L, AGORA), antiga))
                .as("transacoes espalhadas por mais de uma hora nao somam")
                .isEmpty();
    }

    @Test
    @DisplayName("o limite nao depende do historico, entao envenenar o ticket medio nao ajuda")
    void naoDependeDoTicketMedio() {
        MemoriaDoCliente memoriaComTicketMedioEnvenenado = comTransacoes(21, 50_000L, 1).comTicketMedio(90_000_000L);

        Alerta alerta = regra.avaliar(transacao(50_000L, AGORA), memoriaComTicketMedioEnvenenado).orElseThrow();

        assertThat(alerta.valoresEntrada()).containsEntry("dependeDeHistorico", false);
        assertThat(alerta.severidade()).isEqualTo(Severidade.ALTA);
    }

    @Test
    @DisplayName("o alerta registra a soma e o limite aplicado, para auditoria")
    void registraOsValoresDeEntrada() {
        MemoriaDoCliente memoria = comTransacoes(21, 50_000L, 1);

        Alerta alerta = regra.avaliar(transacao(50_000L, AGORA), memoria).orElseThrow();

        assertThat(alerta.valoresEntrada())
                .containsEntry("somaCentavos", 1_050_000L)
                .containsEntry("limiteCentavos", LIMITE)
                .containsEntry("transacoesNaJanela", 21L);
        assertThat(alerta.janela()).isEqualTo("60m");
    }

    private MemoriaDoCliente comTransacoes(int quantidade, long valorCentavos, int intervaloEmSegundos) {
        MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
        for (int i = quantidade; i > 0; i--) {
            Transacao transacao =
                    transacao(valorCentavos, AGORA.minus((long) i * intervaloEmSegundos, ChronoUnit.SECONDS));
            memoria = memoria.registrarEvento(transacao);
        }
        return memoria;
    }

    private Transacao transacao(long valorCentavos, Instant horario) {
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
                horario);
    }
}
