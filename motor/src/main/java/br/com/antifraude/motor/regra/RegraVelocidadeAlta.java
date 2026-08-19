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

    private static final long TRANSACOES_NA_JANELA = 3;
    private static final double FATOR_SOBRE_O_TICKET = 2.0;
    private static final int MINIMO_DE_HISTORICO = 5;

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
        if (!memoria.temLinhaDeBase(MINIMO_DE_HISTORICO)) {
            return Optional.empty();
        }

        Instant referencia = transacao.horarioEvento();
        long contagem = memoria.contagemNaJanela(JanelasDeTempo.CURTA, referencia);
        long ticketMedio = memoria.ticketMedioCentavos();
        long limiar = Math.round(ticketMedio * FATOR_SOBRE_O_TICKET);

        boolean muitasTransacoes = contagem > TRANSACOES_NA_JANELA;
        boolean valorAcimaDoPadrao = transacao.valorCentavos() > limiar;

        if (!muitasTransacoes || !valorAcimaDoPadrao) {
            return Optional.empty();
        }

        Map<String, Object> valoresEntrada = new LinkedHashMap<>();
        valoresEntrada.put("contagemJanela5m", contagem);
        valoresEntrada.put("limiteContagem", TRANSACOES_NA_JANELA);
        valoresEntrada.put("valorCentavos", transacao.valorCentavos());
        valoresEntrada.put("ticketMedioCentavos", ticketMedio);
        valoresEntrada.put("limiarCentavos", limiar);
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
