package br.com.antifraude.motor.memoria;

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
        Map<String, Instant> transacoesVistas) {

    public static MemoriaDoCliente vazia() {
        return new MemoriaDoCliente(List.of(), 0L, 0L, null, null, Map.of());
    }

    public boolean jaViu(String transacaoId) {
        return transacoesVistas.containsKey(transacaoId);
    }

    public MemoriaDoCliente registrar(Transacao transacao) {
        Instant agora = transacao.horarioEvento();

        List<EventoRecente> eventos = new ArrayList<>(eventosRecentes);
        eventos.add(new EventoRecente(agora, transacao.valorCentavos(), transacao.cidade()));

        Map<String, Instant> vistas = new LinkedHashMap<>(transacoesVistas);
        vistas.put(transacao.transacaoId(), agora);

        Instant ultimo = ultimoHorario == null || agora.isAfter(ultimoHorario) ? agora : ultimoHorario;
        String cidade = ultimo.equals(agora) ? transacao.cidade() : ultimaCidade;

        return new MemoriaDoCliente(
                eventos,
                contagemHistorica + 1,
                proximoTicketMedio(agora, transacao.valorCentavos()),
                cidade,
                ultimo,
                vistas)
                .podar(agora);
    }

    private long proximoTicketMedio(Instant agora, long valorCentavos) {
        if (contagemHistorica == 0 || ultimoHorario == null) {
            return valorCentavos;
        }

        long segundosDecorridos = Math.max(0, Duration.between(ultimoHorario, agora).toSeconds());
        double pesoPorTempo =
                1 - Math.exp(-(double) segundosDecorridos / JanelasDeTempo.CONSTANTE_DE_TEMPO_EM_SEGUNDOS);
        double peso = Math.max(pesoPorTempo, JanelasDeTempo.PESO_MINIMO_POR_TRANSACAO);

        return Math.round(ticketMedioCentavos * (1 - peso) + valorCentavos * peso);
    }

    public MemoriaDoCliente podar(Instant referencia) {
        Instant corteEventos = referencia.minus(JanelasDeTempo.RETENCAO_DE_EVENTOS);
        Instant corteDeduplicacao = referencia.minus(JanelasDeTempo.MEMORIA_DE_DEDUPLICACAO);

        List<EventoRecente> porIdade = eventosRecentes.stream()
                .filter(e -> e.horario().isAfter(corteEventos))
                .toList();
        List<EventoRecente> eventos = maisRecentes(porIdade, LimitesDaMemoria.MAXIMO_DE_EVENTOS);

        Map<String, Instant> vistas = new LinkedHashMap<>();
        transacoesVistas.forEach((id, quando) -> {
            if (quando.isAfter(corteDeduplicacao)) {
                vistas.put(id, quando);
            }
        });
        descartarMaisAntigos(vistas, LimitesDaMemoria.MAXIMO_DE_IDENTIFICADORES);

        return new MemoriaDoCliente(
                eventos, contagemHistorica, ticketMedioCentavos, ultimaCidade, ultimoHorario, vistas);
    }

    private static List<EventoRecente> maisRecentes(List<EventoRecente> eventos, int maximo) {
        if (eventos.size() <= maximo) {
            return eventos;
        }
        return List.copyOf(eventos.subList(eventos.size() - maximo, eventos.size()));
    }

    private static void descartarMaisAntigos(Map<String, Instant> vistas, int maximo) {
        Iterator<String> antigos = vistas.keySet().iterator();
        int excedente = vistas.size() - maximo;
        while (excedente > 0 && antigos.hasNext()) {
            antigos.next();
            antigos.remove();
            excedente--;
        }
    }

    public boolean atingiuLimiteDeEventos() {
        return eventosRecentes.size() >= LimitesDaMemoria.MAXIMO_DE_EVENTOS;
    }

    public long contagemNaJanela(Duration janela, Instant referencia) {
        Instant corte = referencia.minus(janela);
        return eventosRecentes.stream().filter(e -> e.horario().isAfter(corte)).count();
    }

    public long somaNaJanelaCentavos(Duration janela, Instant referencia) {
        Instant corte = referencia.minus(janela);
        return eventosRecentes.stream()
                .filter(e -> e.horario().isAfter(corte))
                .mapToLong(EventoRecente::valorCentavos)
                .sum();
    }

    public boolean temLinhaDeBase(int minimoDeTransacoes) {
        return contagemHistorica >= minimoDeTransacoes;
    }
}
