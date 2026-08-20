package br.com.antifraude.motor.memoria;

import br.com.antifraude.contrato.Severidade;

import java.time.Instant;

public record AlertaEmitido(Instant horario, Severidade severidade) {
}
