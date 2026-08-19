package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Severidade;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.JanelasDeTempo;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RegraVelocidadeAlta implements Regra {

    public static final String ID = "velocidade-alta";
    public static final int VERSAO = 1;

    private static final long LIMITE_DE_TRANSACOES_NA_JANELA = 3;
    private static final double FATOR_SOBRE_O_TICKET_MEDIO = 2.0;
    private static final int MINIMO_DE_TRANSACOES_NO_HISTORICO = 5;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int versao() {
        return VERSAO;
    }

    @Override
    public Optional<Alerta> avaliar(Transacao transacao, MemoriaDoCliente memoria) {
        if (!memoria.temHistoricoSuficiente(MINIMO_DE_TRANSACOES_NO_HISTORICO)) {
            return Optional.empty();
        }

        Instant horarioDaTransacao = transacao.horarioEvento();
        long transacoesNaJanela =
                memoria.contagemNaJanela(JanelasDeTempo.CINCO_MINUTOS, horarioDaTransacao);
        long ticketMedioCentavos = memoria.ticketMedioCentavos();
        long limiarDeValorCentavos =
                Math.round(ticketMedioCentavos * FATOR_SOBRE_O_TICKET_MEDIO);

        boolean transacoesDemaisNaJanela = transacoesNaJanela > LIMITE_DE_TRANSACOES_NA_JANELA;
        boolean valorAcimaDoPadraoDoCliente = transacao.valorCentavos() > limiarDeValorCentavos;

        if (!transacoesDemaisNaJanela || !valorAcimaDoPadraoDoCliente) {
            return Optional.empty();
        }

        Map<String, Object> valoresEntrada = new LinkedHashMap<>();
        valoresEntrada.put("contagemJanela5m", transacoesNaJanela);
        valoresEntrada.put("limiteContagem", LIMITE_DE_TRANSACOES_NA_JANELA);
        valoresEntrada.put("valorCentavos", transacao.valorCentavos());
        valoresEntrada.put("ticketMedioCentavos", ticketMedioCentavos);
        valoresEntrada.put("limiarCentavos", limiarDeValorCentavos);
        valoresEntrada.put("historicoConsiderado", memoria.contagemHistorica());

        return Optional.of(new Alerta(
                UUID.randomUUID().toString(),
                transacao.transacaoId(),
                transacao.clienteId(),
                transacao.cartaoToken(),
                transacao.ultimosQuatro(),
                transacao.valorCentavos(),
                ID,
                VERSAO,
                "5m",
                Severidade.ALTA,
                true,
                valoresEntrada,
                transacao.horarioEvento(),
                Instant.now()));
    }
}
