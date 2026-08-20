package br.com.antifraude.contrato;

import java.time.Instant;
import java.util.Map;

public record Alerta(
        String alertaId,
        String transacaoId,
        String clienteId,
        String cartaoToken,
        String ultimosQuatro,
        long valorCentavos,
        String regraId,
        int regraVersao,
        String janela,
        Severidade severidade,
        boolean notificarCliente,
        Map<String, Object> valoresEntrada,
        Instant horarioEventoTransacao,
        Instant horarioAvaliacao) {
}
