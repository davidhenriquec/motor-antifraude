package br.com.antifraude.motor.regra;

import java.util.List;

@FunctionalInterface
public interface FonteDeRegras {

    List<Regra> regrasAtivas();

    static FonteDeRegras fixa(List<Regra> regras) {
        List<Regra> imutaveis = List.copyOf(regras);
        return () -> imutaveis;
    }
}
