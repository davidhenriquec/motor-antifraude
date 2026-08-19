package br.com.antifraude.motor.memoria;

import java.time.Instant;

public record EventoRecente(Instant horario, long valorCentavos, String cidade) {
}
