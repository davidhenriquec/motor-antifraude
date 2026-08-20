package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import dev.cel.runtime.CelRuntime;

import java.time.Instant;
import java.util.*;

public class RegraDeclarativa implements Regra {

    private final DefinicaoDeRegra definicao;
    private final CelRuntime.Program condicaoCompilada;

    public RegraDeclarativa(DefinicaoDeRegra definicao, CelRuntime.Program condicaoCompilada) {
        this.definicao = definicao;
        this.condicaoCompilada = condicaoCompilada;
    }

    @Override
    public String id() {
        return definicao.id();
    }

    @Override
    public int versao() {
        return definicao.versao();
    }

    @Override
    public List<String> dependeDe() {
        return DependenciasDaCondicao.extrair(definicao.condicao());
    }

    @Override
    public Optional<Alerta> avaliar(Transacao transacao, MemoriaDoCliente memoria) {
        return avaliar(transacao, memoria, Map.of());
    }

    @Override
    public Optional<Alerta> avaliar(
            Transacao transacao, MemoriaDoCliente memoria, Map<String, Boolean> resultadosAnteriores) {
        Map<String, Object> contexto = ContextoDaRegra.montar(transacao, memoria, resultadosAnteriores);

        Object resultado;
        try {
            resultado = condicaoCompilada.eval(contexto);
        } catch (Exception problema) {
            throw new ExpressaoInvalidaException(definicao.id(), definicao.condicao(), problema);
        }

        if (!(resultado instanceof Boolean disparou)) {
            throw new ExpressaoInvalidaException(
                    definicao.id(),
                    definicao.condicao(),
                    new IllegalStateException("a condicao devolveu " + resultado + ", esperado booleano"));
        }

        if (!disparou) {
            return Optional.empty();
        }

        return Optional.of(new Alerta(
                UUID.randomUUID().toString(),
                transacao.transacaoId(),
                transacao.clienteId(),
                transacao.cartaoToken(),
                transacao.ultimosQuatro(),
                transacao.valorCentavos(),
                definicao.id(),
                definicao.versao(),
                definicao.janela(),
                definicao.severidade(),
                definicao.notificarCliente(),
                valoresEntrada(contexto),
                transacao.horarioEvento(),
                Instant.now()));
    }

    private Map<String, Object> valoresEntrada(Map<String, Object> contexto) {
        Map<String, Object> valores = new LinkedHashMap<>();
        valores.put("condicao", definicao.condicao());
        valores.put(ContextoDaRegra.JANELA_5M, contexto.get(ContextoDaRegra.JANELA_5M));
        valores.put(ContextoDaRegra.JANELA_60M, contexto.get(ContextoDaRegra.JANELA_60M));
        valores.put(ContextoDaRegra.PERFIL, contexto.get(ContextoDaRegra.PERFIL));
        return valores;
    }
}
