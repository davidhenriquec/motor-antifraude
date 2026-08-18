package br.com.antifraude.simulador;

/**
 * Perfil de consumo de um cliente ficticio.
 *
 * <p>Existe para que o gerador produza transacoes com padrao — cada cliente tem o proprio ticket
 * medio e a propria cidade habitual. Sem isso, todas as transacoes seriam estatisticamente iguais
 * e a janela de 30 dias do motor nao teria nada de util para aprender.
 *
 * <p>E tambem o que torna possivel demonstrar o argumento central do threshold dinamico: um valor
 * que e rotina para um cliente de ticket alto e alarme para um de ticket baixo.
 */
public record PerfilSimulado(
        String clienteId,
        String cartaoToken,
        String bin,
        String ultimosQuatro,
        long ticketMedioCentavos,
        String cidadeHabitual) {
}
