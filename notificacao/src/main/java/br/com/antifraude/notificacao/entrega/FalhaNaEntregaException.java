package br.com.antifraude.notificacao.entrega;

public class FalhaNaEntregaException extends RuntimeException {

    public FalhaNaEntregaException(String mensagem) {
        super(mensagem);
    }
}
