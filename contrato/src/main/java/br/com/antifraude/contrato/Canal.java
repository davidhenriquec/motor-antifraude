package br.com.antifraude.contrato;

/** Origem da transacao. Regras podem se aplicar apenas a canais especificos. */
public enum Canal {
    /** Cartao presente, maquininha. */
    POS,
    /** Cartao nao presente, compra online. */
    ECOMMERCE,
    /** Saque em terminal. */
    ATM,
    PIX,
    TED
}
