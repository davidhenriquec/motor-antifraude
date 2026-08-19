package br.com.antifraude.simulador;

public record PerfilSimulado(
        String clienteId,
        String cartaoToken,
        String bin,
        String ultimosQuatro,
        long ticketMedioCentavos,
        String cidadeHabitual) {
}
