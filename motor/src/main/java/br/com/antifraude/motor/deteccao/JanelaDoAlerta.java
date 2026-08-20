package br.com.antifraude.motor.deteccao;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JanelaDoAlerta {

    private static final Pattern MINUTOS = Pattern.compile("^(\\d+)m$");
    private static final Pattern HORAS = Pattern.compile("^(\\d+)h$");

    private JanelaDoAlerta() {
    }

    public static Optional<Duration> duracao(String janela) {
        if (janela == null) {
            return Optional.empty();
        }

        Matcher emMinutos = MINUTOS.matcher(janela.trim());
        if (emMinutos.matches()) {
            return Optional.of(Duration.ofMinutes(Long.parseLong(emMinutos.group(1))));
        }

        Matcher emHoras = HORAS.matcher(janela.trim());
        if (emHoras.matches()) {
            return Optional.of(Duration.ofHours(Long.parseLong(emHoras.group(1))));
        }

        return Optional.empty();
    }

    public static String chave(String regraId, String janela) {
        return regraId + "|" + janela;
    }
}
