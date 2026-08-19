package br.com.antifraude.motor.memoria;

import br.com.antifraude.contrato.Canal;
import br.com.antifraude.contrato.Transacao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MemoriaDoClienteTest {

    private static final Instant AGORA = Instant.parse("2026-08-19T12:00:00Z");

    @Nested
    @DisplayName("ticket medio")
    class TicketMedio {

        @Test
        @DisplayName("a primeira transacao define o ticket medio")
        void primeiraTransacaoDefineOTicketMedio() {
            MemoriaDoCliente memoria = registrarTodas(transacao(10_000, AGORA));

            assertThat(memoria.ticketMedioCentavos()).isEqualTo(10_000);
            assertThat(memoria.contagemHistorica()).isEqualTo(1);
        }

        @Test
        @DisplayName("valores repetidos mantem o ticket medio estavel")
        void valoresRepetidosMantemOTicketMedio() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
            for (int i = 0; i < 20; i++) {
                memoria = registrar(memoria, transacao(10_000, AGORA.plus(i, ChronoUnit.MINUTES)));
            }

            assertThat(memoria.ticketMedioCentavos()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("uma transacao isolada de valor alto move pouco o ticket medio")
        void valorAltoIsoladoMovePouco() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
            for (int i = 0; i < 20; i++) {
                memoria = registrar(memoria, transacao(10_000, AGORA.plus(i, ChronoUnit.MINUTES)));
            }

            MemoriaDoCliente depois =
                    registrar(memoria, transacao(1_000_000, AGORA.plus(21, ChronoUnit.MINUTES)));

            assertThat(depois.ticketMedioCentavos())
                    .as("o peso minimo por transacao e 5%, entao um pico nao vira o novo normal")
                    .isLessThan(70_000);
        }

        @Test
        @DisplayName("comportamento antigo desaparece: apos meses, o ticket medio segue o padrao novo")
        void comportamentoAntigoDesaparece() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
            for (int i = 0; i < 20; i++) {
                memoria = registrar(memoria, transacao(10_000, AGORA.plus(i, ChronoUnit.MINUTES)));
            }

            Instant seisMesesDepois = AGORA.plus(180, ChronoUnit.DAYS);
            MemoriaDoCliente depois = registrar(memoria, transacao(200_000, seisMesesDepois));

            assertThat(depois.ticketMedioCentavos())
                    .as("com meia-vida de 30 dias, seis meses apagam quase toda a memoria antiga")
                    .isGreaterThan(150_000);
        }

        @Test
        @DisplayName("registrar o evento sozinho nao move o ticket medio")
        void registrarEventoNaoMoveOTicketMedio() {
            MemoriaDoCliente comTicketMedioFormado = registrarTodas(transacao(10_000, AGORA));

            MemoriaDoCliente depois =
                    comTicketMedioFormado.registrarEvento(transacao(1_000_000, AGORA.plusSeconds(30)));

            assertThat(depois.contagemHistorica())
                    .as("a transacao precisa contar na janela, senao a regra de velocidade cega")
                    .isEqualTo(2);
            assertThat(depois.ticketMedioCentavos())
                    .as("mas nao pode redefinir o que e normal para o cliente")
                    .isEqualTo(10_000);
        }
    }

    @Nested
    @DisplayName("janelas e descarte do que expirou")
    class JanelasEDescarte {

        @Test
        @DisplayName("a contagem por janela enxerga periodos diferentes sobre a mesma lista")
        void contagemPorJanela() {
            MemoriaDoCliente memoria = registrarTodas(
                    transacao(1_000, AGORA.minus(50, ChronoUnit.MINUTES)),
                    transacao(1_000, AGORA.minus(30, ChronoUnit.MINUTES)),
                    transacao(1_000, AGORA.minus(2, ChronoUnit.MINUTES)),
                    transacao(1_000, AGORA));

            assertThat(memoria.contagemNaJanela(JanelasDeTempo.CINCO_MINUTOS, AGORA)).isEqualTo(2);
            assertThat(memoria.contagemNaJanela(JanelasDeTempo.UMA_HORA, AGORA)).isEqualTo(4);
        }

        @Test
        @DisplayName("a soma por janela acompanha a contagem")
        void somaPorJanela() {
            MemoriaDoCliente memoria = registrarTodas(
                    transacao(1_000, AGORA.minus(50, ChronoUnit.MINUTES)),
                    transacao(2_000, AGORA.minus(2, ChronoUnit.MINUTES)),
                    transacao(4_000, AGORA));

            assertThat(memoria.somaNaJanelaCentavos(JanelasDeTempo.CINCO_MINUTOS, AGORA)).isEqualTo(6_000);
            assertThat(memoria.somaNaJanelaCentavos(JanelasDeTempo.UMA_HORA, AGORA)).isEqualTo(7_000);
        }

        @Test
        @DisplayName("eventos mais velhos que a retencao saem da lista")
        void descartaEventosAntigos() {
            MemoriaDoCliente memoria = registrarTodas(
                    transacao(1_000, AGORA.minus(5, ChronoUnit.HOURS)), transacao(1_000, AGORA));

            assertThat(memoria.eventosRecentes())
                    .as("a retencao e de 61 minutos: o evento de 5 horas atras nao pode continuar")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a contagem historica nao e afetada pelo descarte dos eventos")
        void descarteNaoAfetaAContagemHistorica() {
            MemoriaDoCliente memoria = registrarTodas(
                    transacao(1_000, AGORA.minus(5, ChronoUnit.HOURS)), transacao(1_000, AGORA));

            assertThat(memoria.eventosRecentes()).hasSize(1);
            assertThat(memoria.contagemHistorica()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("deduplicacao e ultimo valor")
    class DeduplicacaoEUltimoValor {

        @Test
        @DisplayName("identificadores mais velhos que uma hora saem do registro")
        void esqueceIdentificadoresAntigos() {
            Transacao antiga = transacao(1_000, AGORA.minus(2, ChronoUnit.HOURS));

            MemoriaDoCliente memoria = registrarTodas(antiga, transacao(1_000, AGORA));

            assertThat(memoria.jaViu(antiga.transacaoId()))
                    .as("depois de uma hora o reenvio deixa de ser reconhecido")
                    .isFalse();
        }

        @Test
        @DisplayName("evento que chega atrasado nao sobrescreve a ultima cidade")
        void atrasadoNaoSobrescreveUltimaCidade() {
            MemoriaDoCliente memoria = registrarTodas(
                    transacao(1_000, AGORA, "Recife"),
                    transacao(1_000, AGORA.minus(10, ChronoUnit.MINUTES), "Curitiba"));

            assertThat(memoria.ultimaCidade())
                    .as("vale o maior horario de evento, nao a ordem de chegada")
                    .isEqualTo("Recife");
        }
    }

    private static MemoriaDoCliente registrarTodas(Transacao... transacoes) {
        MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
        for (Transacao transacao : transacoes) {
            memoria = registrar(memoria, transacao);
        }
        return memoria;
    }

    private static MemoriaDoCliente registrar(MemoriaDoCliente memoria, Transacao transacao) {
        return memoria.registrarEvento(transacao).comTicketMedio(memoria.ticketMedioApos(transacao));
    }

    private static Transacao transacao(long valorCentavos, Instant horario) {
        return transacao(valorCentavos, horario, "Sao Paulo");
    }

    private static Transacao transacao(long valorCentavos, Instant horario, String cidade) {
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
                Canal.POS,
                horario);
    }
}
