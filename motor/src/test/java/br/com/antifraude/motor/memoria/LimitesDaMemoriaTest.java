package br.com.antifraude.motor.memoria;

import br.com.antifraude.contrato.Canal;
import br.com.antifraude.contrato.Transacao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LimitesDaMemoriaTest {

    private static final Instant AGORA = Instant.parse("2026-08-19T12:00:00Z");

    @Test
    @DisplayName("a lista de eventos para de crescer no teto")
    void eventosParamDeCrescerNoTeto() {
        MemoriaDoCliente memoria = clienteQuente(LimitesDaMemoria.MAXIMO_DE_EVENTOS + 300);

        assertThat(memoria.eventosRecentes())
                .as("sem teto, a memoria cresceria ate estourar o limite de 1 MB do Kafka")
                .hasSize(LimitesDaMemoria.MAXIMO_DE_EVENTOS);
    }

    @Test
    @DisplayName("ao truncar, os eventos mantidos sao os mais recentes")
    void mantemOsMaisRecentes() {
        MemoriaDoCliente memoria = clienteQuente(LimitesDaMemoria.MAXIMO_DE_EVENTOS + 100);

        Instant maisAntigoMantido = memoria.eventosRecentes().getFirst().horario();
        Instant maisNovo = memoria.eventosRecentes().getLast().horario();

        assertThat(maisAntigoMantido).isAfter(AGORA);
        assertThat(maisNovo).isAfterOrEqualTo(maisAntigoMantido);
    }

    @Test
    @DisplayName("o registro de identificadores tambem para de crescer")
    void identificadoresParamDeCrescer() {
        MemoriaDoCliente memoria = clienteQuente(LimitesDaMemoria.MAXIMO_DE_IDENTIFICADORES + 200);

        assertThat(memoria.transacoesVistas())
                .hasSize(LimitesDaMemoria.MAXIMO_DE_IDENTIFICADORES);
    }

    @Test
    @DisplayName("cliente quente e sinalizado, para que a particao quente seja detectavel")
    void clienteQuenteEhSinalizado() {
        MemoriaDoCliente normal = clienteQuente(10);
        MemoriaDoCliente quente = clienteQuente(LimitesDaMemoria.MAXIMO_DE_EVENTOS + 50);

        assertThat(normal.atingiuLimiteDeEventos()).isFalse();
        assertThat(quente.atingiuLimiteDeEventos()).isTrue();
    }

    @Test
    @DisplayName("a contagem historica continua exata mesmo com a lista truncada")
    void contagemHistoricaNaoEhTruncada() {
        int total = LimitesDaMemoria.MAXIMO_DE_EVENTOS + 300;

        MemoriaDoCliente memoria = clienteQuente(total);

        assertThat(memoria.eventosRecentes()).hasSize(LimitesDaMemoria.MAXIMO_DE_EVENTOS);
        assertThat(memoria.contagemHistorica())
                .as("truncar a lista nao pode falsear quantas transacoes o cliente ja fez")
                .isEqualTo(total);
    }

    private MemoriaDoCliente clienteQuente(int quantidade) {
        MemoriaDoCliente memoria = MemoriaDoCliente.vazia();
        for (int i = 0; i < quantidade; i++) {
            Transacao transacao = transacao(AGORA.plus(i, ChronoUnit.SECONDS));
            memoria = memoria.registrarEvento(transacao)
                    .comTicketMedio(memoria.ticketMedioApos(transacao));
        }
        return memoria;
    }

    private Transacao transacao(Instant horario) {
        return new Transacao(
                UUID.randomUUID().toString(),
                "cli-quente",
                "tok-teste",
                "411111",
                "1234",
                10_000L,
                "est-001",
                "5411",
                "Sao Paulo",
                "BR",
                Canal.POS,
                horario);
    }
}
