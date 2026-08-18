package br.com.antifraude.motor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Motor de deteccao — o nucleo do sistema.
 *
 * <p>Consome {@code transacoes}, mantem em memoria local as janelas por cliente (5 min, 60 min
 * e 30 dias, mais o ultimo valor), avalia as regras carregadas do Mongo e publica em
 * {@code alertas}.
 *
 * <p>Nao chama nenhum sistema externo de forma sincrona: essa e a propriedade que impede a
 * lentidao de um terceiro de virar lentidao da deteccao.
 */
@SpringBootApplication
public class MotorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MotorApplication.class, args);
    }
}
