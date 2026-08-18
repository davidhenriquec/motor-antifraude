package br.com.antifraude.contrato;

/**
 * Severidade do alerta.
 *
 * <p>Decide se o cliente e notificado e com que prioridade a equipe antifraude recebe.
 * Tratar tudo como emergencia destroi o produto — e o caminho mais rapido para o cliente
 * desligar as notificacoes.
 */
public enum Severidade {
    /** Registra e pontua. Ninguem e incomodado. */
    BAIXA,
    /** Pergunta ao cliente: "foi voce?". */
    MEDIA,
    /** Notifica o cliente e sobe para o antifraude com prioridade. */
    ALTA
}
