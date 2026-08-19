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
        MemoriaDoCliente memoriaAntesDaTransacao = repositorio.buscar(transacao.clienteId());

        if (memoriaAntesDaTransacao.jaViu(transacao.transacaoId())) {
            return ResultadoDaAvaliacao.duplicada();
        }

        MemoriaDoCliente memoriaComATransacao = memoriaAntesDaTransacao.registrarEvento(transacao);

        List<Alerta> alertas = regras.stream()
                .map(regra -> regra.avaliar(transacao, memoriaComATransacao))
                .flatMap(Optional::stream)
                .toList();

        boolean deveAtualizarOTicketMedio =
                alertas.isEmpty() || memoriaAntesDaTransacao.aindaFormandoOTicketMedio();

        MemoriaDoCliente memoriaParaSalvar = deveAtualizarOTicketMedio
                ? memoriaComATransacao.comTicketMedio(memoriaAntesDaTransacao.ticketMedioApos(transacao))
                : memoriaComATransacao;

        repositorio.salvar(transacao.clienteId(), memoriaParaSalvar);

        return ResultadoDaAvaliacao.avaliada(alertas, memoriaParaSalvar.atingiuLimiteDeEventos());
    }
}
