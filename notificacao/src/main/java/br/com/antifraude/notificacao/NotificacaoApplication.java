package br.com.antifraude.notificacao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Servico de notificacao — a unica integracao sincrona do sistema.
 *
 * <p>Consome {@code alertas}, respeita o campo {@code notificarCliente} decidido pelo motor, e
 * entrega push e e-mail. Nao decide nada: quem tem o estado para decidir e o motor.
 *
 * <p>E aqui que moram o disjuntor, o timeout curto, a fila morta e a deduplicacao de entrega no
 * Redis — porque e o unico ponto onde a garantia do Kafka acaba.
 */
@SpringBootApplication
public class NotificacaoApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificacaoApplication.class, args);
    }
}
