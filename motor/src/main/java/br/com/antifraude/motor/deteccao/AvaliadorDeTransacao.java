package br.com.antifraude.motor.deteccao;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.memoria.RepositorioDeMemoria;
import br.com.antifraude.motor.regra.Regra;

import java.util.List;
import java.util.Optional;

public class AvaliadorDeTransacao {

    private final RepositorioDeMemoria repositorio;
    private final List<Regra> regras;

    public AvaliadorDeTransacao(RepositorioDeMemoria repositorio, List<Regra> regras) {
        this.repositorio = repositorio;
        this.regras = List.copyOf(regras);
    }

    public ResultadoDaAvaliacao avaliar(Transacao transacao) {
        MemoriaDoCliente anterior = repositorio.buscar(transacao.clienteId());

        if (anterior.jaViu(transacao.transacaoId())) {
            return ResultadoDaAvaliacao.duplicada();
        }

        MemoriaDoCliente atualizada = anterior.registrar(transacao);
        repositorio.salvar(transacao.clienteId(), atualizada);

        List<Alerta> alertas = regras.stream()
                .map(regra -> regra.avaliar(transacao, atualizada))
                .flatMap(Optional::stream)
                .toList();

        return ResultadoDaAvaliacao.avaliada(alertas, atualizada.atingiuLimiteDeEventos());
    }
}
