package br.com.antifraude.contrato;

import java.time.Instant;

public record Transacao(
        String transacaoId,
        String clienteId,
        String cartaoToken,
        String bin,
        String ultimosQuatro,
        long valorCentavos,
        String estabelecimentoId,
        String categoriaEstabelecimento,
        String cidade,
        String pais,
        Canal canal,
        Instant horarioEvento) {
    public double valorEmReais() {
        return valorCentavos / 100.0;
    }
}
