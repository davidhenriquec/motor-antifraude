package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.JanelasDeTempo;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ContextoDaRegra {

    public static final String TRANSACAO = "transacao";
    public static final String JANELA_5M = "janela5m";
    public static final String JANELA_60M = "janela60m";
    public static final String PERFIL = "perfil";
    public static final String ULTIMO = "ultimo";
    public static final String REGRAS = "regras";

    private ContextoDaRegra() {
    }

    public static Map<String, Object> exemplo() {
        Transacao transacaoDeExemplo = new Transacao(
                "tx-exemplo",
                "cli-exemplo",
                "tok-exemplo",
                "411111",
                "1234",
                10_000L,
                "est-exemplo",
                "5411",
                "Sao Paulo",
                "BR",
                br.com.antifraude.contrato.Canal.POS,
                Instant.EPOCH);
        return montar(transacaoDeExemplo, MemoriaDoCliente.vazia(), Map.of());
    }

    public static Map<String, Object> exemploCom(java.util.Set<String> identificadoresDeRegras) {
        Map<String, Object> contexto = new LinkedHashMap<>(exemplo());
        Map<String, Boolean> resultados = new LinkedHashMap<>();
        identificadoresDeRegras.forEach(id -> resultados.put(id, false));
        contexto.put(REGRAS, resultados);
        return contexto;
    }

    public static Map<String, Object> montar(Transacao transacao, MemoriaDoCliente memoria) {
        return montar(transacao, memoria, Map.of());
    }

    public static Map<String, Object> montar(
            Transacao transacao, MemoriaDoCliente memoria, Map<String, Boolean> resultadosAnteriores) {
        Instant horarioDaTransacao = transacao.horarioEvento();

        Map<String, Object> contexto = new LinkedHashMap<>();
        contexto.put(TRANSACAO, dadosDaTransacao(transacao));
        contexto.put(JANELA_5M, dadosDaJanela(memoria, JanelasDeTempo.CINCO_MINUTOS, horarioDaTransacao));
        contexto.put(JANELA_60M, dadosDaJanela(memoria, JanelasDeTempo.UMA_HORA, horarioDaTransacao));
        contexto.put(PERFIL, dadosDoPerfil(memoria));
        contexto.put(ULTIMO, dadosDoUltimoEvento(memoria, horarioDaTransacao));
        contexto.put(REGRAS, Map.copyOf(resultadosAnteriores));
        return contexto;
    }

    private static Map<String, Object> dadosDaTransacao(Transacao transacao) {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("valorCentavos", transacao.valorCentavos());
        dados.put("cidade", textoOuVazio(transacao.cidade()));
        dados.put("pais", textoOuVazio(transacao.pais()));
        dados.put("categoria", textoOuVazio(transacao.categoriaEstabelecimento()));
        dados.put("canal", transacao.canal().name());
        return dados;
    }

    private static Map<String, Object> dadosDaJanela(
            MemoriaDoCliente memoria, java.time.Duration janela, Instant horarioDaTransacao) {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("contagem", memoria.contagemNaJanela(janela, horarioDaTransacao));
        dados.put("somaCentavos", memoria.somaNaJanelaCentavos(janela, horarioDaTransacao));
        return dados;
    }

    private static Map<String, Object> dadosDoPerfil(MemoriaDoCliente memoria) {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("ticketMedioCentavos", memoria.ticketMedioCentavos());
        dados.put("contagemHistorica", memoria.contagemHistorica());
        return dados;
    }

    private static Map<String, Object> dadosDoUltimoEvento(
            MemoriaDoCliente memoria, Instant horarioDaTransacao) {
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("cidade", textoOuVazio(memoria.cidadeAntesDe(horarioDaTransacao)));
        return dados;
    }

    private static String textoOuVazio(String valor) {
        return valor == null ? "" : valor;
    }
}
