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
    @DisplayName("linha de base")
    class LinhaDeBase {

        @Test
        @DisplayName("a primeira transacao define a linha de base")
        void primeiraTransacaoDefineABase() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia().registrar(transacao(10_000, AGORA));

            assertThat(memoria.ticketMedioCentavos()).isEqualTo(10_000);
            assertThat(memoria.contagemHistorica()).isEqualTo(1);
        }

        @Test
        @DisplayName("valores repetidos mantem a linha de base estavel")
        void valoresRepetidosMantemABase() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
            for (int i = 0; i < 20; i++) {
                memoria = memoria.registrar(transacao(10_000, AGORA.plus(i, ChronoUnit.MINUTES)));
            }

            assertThat(memoria.ticketMedioCentavos()).isEqualTo(10_000);
        }

        @Test
        @DisplayName("uma transacao isolada de valor alto move pouco a linha de base")
        void valorAltoIsoladoMovePouco() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
            for (int i = 0; i < 20; i++) {
                memoria = memoria.registrar(transacao(10_000, AGORA.plus(i, ChronoUnit.MINUTES)));
            }

            MemoriaDoCliente depois =
                    memoria.registrar(transacao(1_000_000, AGORA.plus(21, ChronoUnit.MINUTES)));

            assertThat(depois.ticketMedioCentavos())
                    .as("o peso minimo por transacao e 5%, entao um pico nao vira o novo normal")
                    .isLessThan(70_000);
        }

        @Test
        @DisplayName("comportamento antigo desaparece: apos meses, a base segue o padrao novo")
        void comportamentoAntigoDesaparece() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
            for (int i = 0; i < 20; i++) {
                memoria = memoria.registrar(transacao(10_000, AGORA.plus(i, ChronoUnit.MINUTES)));
            }

            Instant seisMesesDepois = AGORA.plus(180, ChronoUnit.DAYS);
            MemoriaDoCliente depois = memoria.registrar(transacao(200_000, seisMesesDepois));

            assertThat(depois.ticketMedioCentavos())
                    .as("com meia-vida de 30 dias, seis meses apagam quase toda a memoria antiga")
                    .isGreaterThan(150_000);
        }
    }

    @Nested
    @DisplayName("janelas e poda")
    class JanelasEPoda {

        @Test
        @DisplayName("a contagem por janela enxerga periodos diferentes sobre a mesma lista")
        void contagemPorJanela() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia()
                    .registrar(transacao(1_000, AGORA.minus(50, ChronoUnit.MINUTES)))
                    .registrar(transacao(1_000, AGORA.minus(30, ChronoUnit.MINUTES)))
                    .registrar(transacao(1_000, AGORA.minus(2, ChronoUnit.MINUTES)))
                    .registrar(transacao(1_000, AGORA));

            assertThat(memoria.contagemNaJanela(JanelasDeTempo.CURTA, AGORA)).isEqualTo(2);
            assertThat(memoria.contagemNaJanela(JanelasDeTempo.MEDIA, AGORA)).isEqualTo(4);
        }

        @Test
        @DisplayName("eventos mais velhos que a retencao saem da lista")
        void podaEventosAntigos() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia()
                    .registrar(transacao(1_000, AGORA.minus(5, ChronoUnit.HOURS)))
                    .registrar(transacao(1_000, AGORA));

            assertThat(memoria.eventosRecentes())
                    .as("a retencao e de 61 minutos: o evento de 5 horas atras nao pode continuar")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a contagem historica nao e afetada pela poda dos eventos")
        void podaNaoAfetaAContagemHistorica() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia()
                    .registrar(transacao(1_000, AGORA.minus(5, ChronoUnit.HOURS)))
                    .registrar(transacao(1_000, AGORA));

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

            MemoriaDoCliente memoria =
                    MemoriaDoCliente.vazia().registrar(antiga).registrar(transacao(1_000, AGORA));

            assertThat(memoria.jaViu(antiga.transacaoId()))
                    .as("depois de uma hora o reenvio deixa de ser reconhecido")
                    .isFalse();
        }

        @Test
        @DisplayName("evento que chega atrasado nao sobrescreve a ultima cidade")
        void atrasadoNaoSobrescreveUltimaCidade() {
            MemoriaDoCliente memoria = MemoriaDoCliente.vazia()
                    .registrar(transacao(1_000, AGORA, "Recife"))
                    .registrar(transacao(1_000, AGORA.minus(10, ChronoUnit.MINUTES), "Curitiba"));

            assertThat(memoria.ultimaCidade())
                    .as("vale o maior horario de evento, nao a ordem de chegada")
                    .isEqualTo("Recife");
        }
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
