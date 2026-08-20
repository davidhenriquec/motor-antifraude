package br.com.antifraude.motor.deteccao;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.AlertaEmitido;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import br.com.antifraude.motor.memoria.RepositorioDeMemoria;
import br.com.antifraude.motor.regra.FonteDeRegras;
import br.com.antifraude.motor.regra.Regra;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class AvaliadorDeTransacao {

    private final RepositorioDeMemoria repositorio;
    private final FonteDeRegras fonteDeRegras;

    public AvaliadorDeTransacao(RepositorioDeMemoria repositorio, FonteDeRegras fonteDeRegras) {
        this.repositorio = repositorio;
        this.fonteDeRegras = fonteDeRegras;
    }

    public ResultadoDaAvaliacao avaliar(Transacao transacao) {
        MemoriaDoCliente memoriaAntesDaTransacao = repositorio.buscar(transacao.clienteId());

        if (memoriaAntesDaTransacao.jaViu(transacao.transacaoId())) {
            return ResultadoDaAvaliacao.duplicada();
        }

        MemoriaDoCliente memoriaComATransacao = memoriaAntesDaTransacao.registrarEvento(transacao);

        List<Alerta> alertas = new ArrayList<>();
        List<FalhaDeRegra> falhas = new ArrayList<>();

        Map<String, Boolean> resultadosPorRegra = new LinkedHashMap<>();

        for (Regra regra : fonteDeRegras.regrasAtivas()) {
            try {
                Optional<Alerta> alerta =
                        regra.avaliar(transacao, memoriaComATransacao, resultadosPorRegra);
                resultadosPorRegra.put(regra.id(), alerta.isPresent());
                alerta.ifPresent(alertas::add);
            } catch (RuntimeException problema) {
                resultadosPorRegra.put(regra.id(), false);
                falhas.add(new FalhaDeRegra(regra.id(), problema.toString()));
            }
        }

        List<Alerta> alertasParaPublicar = new ArrayList<>(alertas.size());
        List<String> alertasSuprimidos = new ArrayList<>();
        MemoriaDoCliente memoriaComAlertas = memoriaComATransacao;

        for (Alerta alerta : alertas) {
            if (jaAvisouNestaJanela(memoriaComAlertas, alerta, transacao.horarioEvento())) {
                alertasSuprimidos.add(alerta.regraId());
                continue;
            }
            alertasParaPublicar.add(alerta);
            memoriaComAlertas = memoriaComAlertas.registrandoAlerta(
                    JanelaDoAlerta.chave(alerta.regraId(), alerta.janela()),
                    alerta.severidade(),
                    transacao.horarioEvento());
        }

        boolean deveAtualizarOTicketMedio =
                alertas.isEmpty() || memoriaAntesDaTransacao.aindaFormandoOTicketMedio();

        MemoriaDoCliente memoriaParaSalvar = deveAtualizarOTicketMedio
                ? memoriaComAlertas.comTicketMedio(memoriaAntesDaTransacao.ticketMedioApos(transacao))
                : memoriaComAlertas;

        repositorio.salvar(transacao.clienteId(), memoriaParaSalvar);

        return ResultadoDaAvaliacao.avaliada(
                List.copyOf(alertasParaPublicar),
                memoriaParaSalvar.atingiuLimiteDeEventos(),
                List.copyOf(falhas),
                List.copyOf(alertasSuprimidos));
    }

    private boolean jaAvisouNestaJanela(
            MemoriaDoCliente memoria, Alerta alerta, Instant horarioDaTransacao) {
        Optional<Duration> janelaDeSupressao = JanelaDoAlerta.duracao(alerta.janela());

        if (janelaDeSupressao.isEmpty()) {
            return false;
        }

        AlertaEmitido anterior =
                memoria.ultimoAlertaDe(JanelaDoAlerta.chave(alerta.regraId(), alerta.janela()));

        if (anterior == null) {
            return false;
        }

        if (anterior.horario().isBefore(horarioDaTransacao.minus(janelaDeSupressao.get()))) {
            return false;
        }

        return alerta.severidade().compareTo(anterior.severidade()) <= 0;
    }
}
