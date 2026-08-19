package br.com.antifraude.motor.memoria;

import java.time.Duration;

public final class ParametrosDoTicketMedio {

    public static final Duration MEIA_VIDA = Duration.ofDays(30);

    public static final double CONSTANTE_DE_DECAIMENTO_EM_SEGUNDOS = MEIA_VIDA.toSeconds() / Math.log(2);

    public static final double PESO_MINIMO_POR_TRANSACAO = 0.05;

    public static final int TRANSACOES_PARA_FORMAR_O_TICKET_MEDIO = 5;

    private ParametrosDoTicketMedio() {
    }
}
