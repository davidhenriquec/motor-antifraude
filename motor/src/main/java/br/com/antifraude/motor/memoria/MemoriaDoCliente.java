package br.com.antifraude.motor.memoria;

import br.com.antifraude.contrato.Severidade;
import br.com.antifraude.contrato.Transacao;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public record MemoriaDoCliente(
        List<EventoRecente> eventosRecentes,
        long contagemHistorica,
        long ticketMedioCentavos,
        String ultimaCidade,
        Instant ultimoHorario,
        Map<String, Instant> transacoesVistas,
        Map<String, AlertaEmitido> alertasEmitidos) {

    public MemoriaDoCliente {
        eventosRecentes = eventosRecentes == null ? List.of() : eventosRecentes;
        transacoesVistas = transacoesVistas == null ? Map.of() : transacoesVistas;
        alertasEmitidos = alertasEmitidos == null ? Map.of() : alertasEmitidos;
    }

    public static MemoriaDoCliente vazia() {
        return new MemoriaDoCliente(List.of(), 0L, 0L, null, null, Map.of(), Map.of());
    }

    public AlertaEmitido ultimoAlertaDe(String chaveDoAlerta) {
        return alertasEmitidos.get(chaveDoAlerta);
    }

    public MemoriaDoCliente registrandoAlerta(
            String chaveDoAlerta, Severidade severidade, Instant horario) {
        Map<String, AlertaEmitido> atualizados = new LinkedHashMap<>(alertasEmitidos);
        atualizados.put(chaveDoAlerta, new AlertaEmitido(horario, severidade));
        return new MemoriaDoCliente(
                eventosRecentes,
                contagemHistorica,
                ticketMedioCentavos,
                ultimaCidade,
                ultimoHorario,
                transacoesVistas,
                atualizados);
    }

    public boolean jaViu(String transacaoId) {
        return transacoesVistas.containsKey(transacaoId);
    }

    public MemoriaDoCliente registrarEvento(Transacao transacao) {
        Instant horarioDaTransacao = transacao.horarioEvento();

        List<EventoRecente> eventosAtualizados = new ArrayList<>(eventosRecentes);
        eventosAtualizados.add(new EventoRecente(horarioDaTransacao, transacao.valorCentavos(), transacao.cidade()));

        Map<String, Instant> identificadoresVistos = new LinkedHashMap<>(transacoesVistas);
        identificadoresVistos.put(transacao.transacaoId(), horarioDaTransacao);

        boolean transacaoEhAMaisRecente =
                ultimoHorario == null || horarioDaTransacao.isAfter(ultimoHorario);
        Instant horarioMaisRecente = transacaoEhAMaisRecente ? horarioDaTransacao : ultimoHorario;
        String cidadeMaisRecente = transacaoEhAMaisRecente ? transacao.cidade() : ultimaCidade;

        return new MemoriaDoCliente(
                eventosAtualizados,
                contagemHistorica + 1,
                ticketMedioCentavos,
                cidadeMaisRecente,
                horarioMaisRecente,
                identificadoresVistos,
                alertasEmitidos)
                .descartarOQueExpirou(horarioDaTransacao);
    }

    public long ticketMedioApos(Transacao transacao) {
        return calcularTicketMedio(transacao.horarioEvento(), transacao.valorCentavos());
    }

    public MemoriaDoCliente comTicketMedio(long novoTicketMedioCentavos) {
        return new MemoriaDoCliente(
                eventosRecentes,
                contagemHistorica,
                novoTicketMedioCentavos,
                ultimaCidade,
                ultimoHorario,
                transacoesVistas,
                alertasEmitidos);
    }

    private long calcularTicketMedio(Instant horarioDaTransacao, long valorCentavos) {
        if (ticketMedioCentavos == 0 || ultimoHorario == null) {
            return valorCentavos;
        }

        long segundosDesdeAUltimaTransacao =
                Math.max(0, Duration.between(ultimoHorario, horarioDaTransacao).toSeconds());
        double pesoPeloTempoDecorrido = 1 - Math.exp(-(double) segundosDesdeAUltimaTransacao
                / ParametrosDoTicketMedio.CONSTANTE_DE_DECAIMENTO_EM_SEGUNDOS);
        double peso = Math.max(
                pesoPeloTempoDecorrido, ParametrosDoTicketMedio.PESO_MINIMO_POR_TRANSACAO);

        return Math.round(ticketMedioCentavos * (1 - peso) + valorCentavos * peso);
    }

    public MemoriaDoCliente descartarOQueExpirou(Instant horarioDeReferencia) {
        Instant eventoMaisAntigoQueVale =
                horarioDeReferencia.minus(JanelasDeTempo.RETENCAO_DE_EVENTOS);
        Instant identificadorMaisAntigoQueVale =
                horarioDeReferencia.minus(JanelasDeTempo.LEMBRANCA_DE_IDENTIFICADORES);

        List<EventoRecente> eventosDentroDaRetencao = eventosRecentes.stream()
                .filter(evento -> evento.horario().isAfter(eventoMaisAntigoQueVale))
                .toList();
        List<EventoRecente> eventosDentroDoTeto =
                manterOsMaisRecentes(eventosDentroDaRetencao, LimitesDaMemoria.MAXIMO_DE_EVENTOS);

        Map<String, Instant> identificadoresDentroDaRetencao = new LinkedHashMap<>();
        transacoesVistas.forEach((transacaoId, quandoFoiVista) -> {
            if (quandoFoiVista.isAfter(identificadorMaisAntigoQueVale)) {
                identificadoresDentroDaRetencao.put(transacaoId, quandoFoiVista);
            }
        });
        removerOsMaisAntigos(
                identificadoresDentroDaRetencao, LimitesDaMemoria.MAXIMO_DE_IDENTIFICADORES);

        Map<String, AlertaEmitido> alertasDentroDaRetencao = new LinkedHashMap<>();
        alertasEmitidos.forEach((chave, emitido) -> {
            if (emitido.horario().isAfter(eventoMaisAntigoQueVale)) {
                alertasDentroDaRetencao.put(chave, emitido);
            }
        });

        return new MemoriaDoCliente(
                eventosDentroDoTeto,
                contagemHistorica,
                ticketMedioCentavos,
                ultimaCidade,
                ultimoHorario,
                identificadoresDentroDaRetencao,
                alertasDentroDaRetencao);
    }

    private static List<EventoRecente> manterOsMaisRecentes(
            List<EventoRecente> eventos, int quantidadeMaxima) {
        if (eventos.size() <= quantidadeMaxima) {
            return eventos;
        }
        return List.copyOf(eventos.subList(eventos.size() - quantidadeMaxima, eventos.size()));
    }

    private static void removerOsMaisAntigos(
            Map<String, Instant> identificadores, int quantidadeMaxima) {
        Iterator<String> doMaisAntigoParaOMaisNovo = identificadores.keySet().iterator();
        int excedente = identificadores.size() - quantidadeMaxima;
        while (excedente > 0 && doMaisAntigoParaOMaisNovo.hasNext()) {
            doMaisAntigoParaOMaisNovo.next();
            doMaisAntigoParaOMaisNovo.remove();
            excedente--;
        }
    }

    public boolean atingiuLimiteDeEventos() {
        return eventosRecentes.size() >= LimitesDaMemoria.MAXIMO_DE_EVENTOS;
    }

    public long contagemNaJanela(Duration janela, Instant horarioDeReferencia) {
        Instant inicioDaJanela = horarioDeReferencia.minus(janela);
        return eventosRecentes.stream()
                .filter(evento -> evento.horario().isAfter(inicioDaJanela))
                .count();
    }

    public long somaNaJanelaCentavos(Duration janela, Instant horarioDeReferencia) {
        Instant inicioDaJanela = horarioDeReferencia.minus(janela);
        return eventosRecentes.stream()
                .filter(evento -> evento.horario().isAfter(inicioDaJanela))
                .mapToLong(EventoRecente::valorCentavos)
                .sum();
    }

    public String cidadeAntesDe(Instant horarioDeReferencia) {
        return eventosRecentes.stream()
                .filter(evento -> evento.horario().isBefore(horarioDeReferencia))
                .max(Comparator.comparing(EventoRecente::horario))
                .map(EventoRecente::cidade)
                .orElse(null);
    }

    public boolean temHistoricoSuficiente(int minimoDeTransacoes) {
        return contagemHistorica >= minimoDeTransacoes;
    }

    public boolean aindaFormandoOTicketMedio() {
        return contagemHistorica < ParametrosDoTicketMedio.TRANSACOES_PARA_FORMAR_O_TICKET_MEDIO;
    }
}
