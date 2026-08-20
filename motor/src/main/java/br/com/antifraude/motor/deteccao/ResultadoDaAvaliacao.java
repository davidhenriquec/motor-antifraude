package br.com.antifraude.motor.deteccao;

import br.com.antifraude.contrato.Alerta;

import java.util.List;

public record ResultadoDaAvaliacao(
        boolean ehDuplicada,
        List<Alerta> alertas,
        boolean memoriaAtingiuOTeto,
        List<FalhaDeRegra> falhas,
        List<String> alertasSuprimidos) {

    private static final ResultadoDaAvaliacao DUPLICADA =
            new ResultadoDaAvaliacao(true, List.of(), false, List.of(), List.of());

    public static ResultadoDaAvaliacao duplicada() {
        return DUPLICADA;
    }

    public static ResultadoDaAvaliacao avaliada(
            List<Alerta> alertas,
            boolean memoriaAtingiuOTeto,
            List<FalhaDeRegra> falhas,
            List<String> alertasSuprimidos) {
        return new ResultadoDaAvaliacao(false, alertas, memoriaAtingiuOTeto, falhas, alertasSuprimidos);
    }

    public boolean temAlertas() {
        return !alertas.isEmpty();
    }
}
