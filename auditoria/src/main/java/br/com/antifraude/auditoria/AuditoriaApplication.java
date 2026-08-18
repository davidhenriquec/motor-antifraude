package br.com.antifraude.auditoria;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Consumidor de auditoria.
 *
 * <p>Consome {@code alertas} e grava no Postgres com chave unica, o que torna a gravacao
 * idempotente sem escrever logica nenhuma — o banco recusa a segunda linha.
 *
 * <p>E um consumidor separado de proposito: se o motor gravasse direto, perderia a propriedade
 * de ter uma unica dependencia, e voltaria o dilema de parar ou nao parar quando o banco
 * degradasse. Aqui, se o Postgres cair, os alertas se acumulam no Kafka e sao gravados depois.
 *
 * <p>O topico e a fonte da verdade; esta base e um indice de consulta, reconstruivel por
 * reprocessamento.
 */
@SpringBootApplication
public class AuditoriaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditoriaApplication.class, args);
    }
}
