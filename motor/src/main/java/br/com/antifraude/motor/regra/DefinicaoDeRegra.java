package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Severidade;

public record DefinicaoDeRegra(
        String id,
        int versao,
        String descricao,
        boolean habilitada,
        Severidade severidade,
        String janela,
        String condicao,
        boolean notificarCliente) {
}
