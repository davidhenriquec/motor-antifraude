package br.com.antifraude.simulador;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gerador de carga e de cenarios para demonstracao.
 *
 * <p><b>Esta aplicacao nao faz parte da arquitetura de producao.</b> Em producao, os sistemas
 * de origem (autorizador de cartao, PIX, TED) publicam direto no topico {@code transacoes}.
 * O simulador existe para tornar possiveis o teste de carga e a demonstracao.
 */
@SpringBootApplication
public class SimuladorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimuladorApplication.class, args);
    }
}
