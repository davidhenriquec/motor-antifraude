package br.com.antifraude.motor.regra;

public class ExpressaoInvalidaException extends RuntimeException {

    public ExpressaoInvalidaException(String regraId, String condicao, Throwable causa) {
        super("regra %s tem condicao invalida: %s".formatted(regraId, condicao), causa);
    }
}
