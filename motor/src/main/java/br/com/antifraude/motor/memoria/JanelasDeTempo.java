package br.com.antifraude.motor.memoria;

import java.time.Duration;

public final class JanelasDeTempo {

    public static final Duration CINCO_MINUTOS = Duration.ofMinutes(5);

    public static final Duration UMA_HORA = Duration.ofMinutes(60);

    public static final Duration LEMBRANCA_DE_IDENTIFICADORES = Duration.ofHours(1);

    public static final Duration TOLERANCIA_DE_ATRASO = Duration.ofSeconds(60);

    public static final Duration RETENCAO_DE_EVENTOS = UMA_HORA.plus(TOLERANCIA_DE_ATRASO);

    private JanelasDeTempo() {
    }
}
