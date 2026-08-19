package br.com.antifraude.motor.deteccao;

import br.com.antifraude.contrato.Alerta;

import java.util.List;

public record ResultadoDaAvaliacao(boolean ehDuplicada, List<Alerta> alertas, boolean memoriaNoLimite) {

    private static final ResultadoDaAvaliacao DUPLICADA =
            new ResultadoDaAvaliacao(true, List.of(), false);

    public static ResultadoDaAvaliacao duplicada() {
        return DUPLICADA;
    }

    public static ResultadoDaAvaliacao avaliada(List<Alerta> alertas, boolean memoriaNoLimite) {
        return new ResultadoDaAvaliacao(false, alertas, memoriaNoLimite);
    }

    public boolean temAlertas() {
        return !alertas.isEmpty();
    }
}
