package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Regra {

    String id();

    int versao();

    Optional<Alerta> avaliar(Transacao transacao, MemoriaDoCliente memoria);

    default Optional<Alerta> avaliar(
            Transacao transacao, MemoriaDoCliente memoria, Map<String, Boolean> resultadosAnteriores) {
        return avaliar(transacao, memoria);
    }

    default List<String> dependeDe() {
        return List.of();
    }
}
