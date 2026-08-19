package br.com.antifraude.motor.regra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RegrasConfig {

    @Bean
    public Regra regraVelocidadeAlta() {
        return new RegraVelocidadeAlta();
    }

    @Bean
    public Regra regraValorAbsoluto(
            @Value("${motor.regras.valor-absoluto.limiar-centavos}") long limiarDeValorCentavos) {
        return new RegraValorAbsoluto(limiarDeValorCentavos);
    }

    @Bean
    public Regra regraSomaNaHora(
            @Value("${motor.regras.soma-na-hora.limite-centavos}") long limiteDaSomaCentavos) {
        return new RegraSomaNaHora(limiteDaSomaCentavos);
    }
}
