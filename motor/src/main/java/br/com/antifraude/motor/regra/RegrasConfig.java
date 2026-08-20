package br.com.antifraude.motor.regra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RegrasConfig {

    @Bean
    public CompiladorCel compiladorCel() {
        return new CompiladorCel();
    }
}
