package br.com.antifraude.motor.regra;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.types.CelType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

public class CompiladorCel {

    private static final CelType MAPA_DE_DADOS = MapType.create(SimpleType.STRING, SimpleType.DYN);

    private final CelCompiler compilador;
    private final CelRuntime execucao;

    public CompiladorCel() {
        this.compilador = CelCompilerFactory.standardCelCompilerBuilder()
                .addVar(ContextoDaRegra.TRANSACAO, MAPA_DE_DADOS)
                .addVar(ContextoDaRegra.JANELA_5M, MAPA_DE_DADOS)
                .addVar(ContextoDaRegra.JANELA_60M, MAPA_DE_DADOS)
                .addVar(ContextoDaRegra.PERFIL, MAPA_DE_DADOS)
                .addVar(ContextoDaRegra.ULTIMO, MAPA_DE_DADOS)
                .addVar(ContextoDaRegra.REGRAS, MAPA_DE_DADOS)
                .setResultType(SimpleType.BOOL)
                .build();
        this.execucao = CelRuntimeFactory.standardCelRuntimeBuilder().build();
    }

    public CelRuntime.Program compilar(String regraId, String condicao) {
        return compilar(regraId, condicao, java.util.Set.of());
    }

    public CelRuntime.Program compilar(
            String regraId, String condicao, java.util.Set<String> outrasRegras) {
        CelRuntime.Program programa;
        try {
            CelAbstractSyntaxTree arvore = compilador.compile(condicao).getAst();
            programa = execucao.createProgram(arvore);
        } catch (Exception problema) {
            throw new ExpressaoInvalidaException(regraId, condicao, problema);
        }

        verificarContraContextoDeExemplo(regraId, condicao, programa, outrasRegras);
        return programa;
    }

    private void verificarContraContextoDeExemplo(
            String regraId,
            String condicao,
            CelRuntime.Program programa,
            java.util.Set<String> outrasRegras) {
        Object resultado;
        try {
            resultado = programa.eval(ContextoDaRegra.exemploCom(outrasRegras));
        } catch (Exception problema) {
            throw new ExpressaoInvalidaException(regraId, condicao, problema);
        }

        if (!(resultado instanceof Boolean)) {
            throw new ExpressaoInvalidaException(
                    regraId,
                    condicao,
                    new IllegalStateException(
                            "a condicao devolveu " + resultado + ", esperado booleano"));
        }
    }
}
