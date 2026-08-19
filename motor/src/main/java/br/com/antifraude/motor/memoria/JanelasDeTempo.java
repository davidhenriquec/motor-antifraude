package br.com.antifraude.motor.memoria;

import java.time.Duration;

public final class JanelasDeTempo {
    public static final Duration CURTA = Duration.ofMinutes(5);

    public static final Duration MEDIA = Duration.ofMinutes(60);

    public static final Duration MEMORIA_DE_DEDUPLICACAO = Duration.ofHours(1);

    public static final Duration TOLERANCIA_DE_ATRASO = Duration.ofSeconds(60);

    public static final Duration RETENCAO_DE_EVENTOS = MEDIA.plus(TOLERANCIA_DE_ATRASO);

    public static final Duration MEIA_VIDA_DA_LINHA_DE_BASE = Duration.ofDays(30);

    public static final double CONSTANTE_DE_TEMPO_EM_SEGUNDOS =
            MEIA_VIDA_DA_LINHA_DE_BASE.toSeconds() / Math.log(2);

    public static final double PESO_MINIMO_POR_TRANSACAO = 0.05;

    private JanelasDeTempo() {
    }
}
