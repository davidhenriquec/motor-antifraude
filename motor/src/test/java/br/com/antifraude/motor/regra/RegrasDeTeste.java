package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Severidade;

public final class RegrasDeTeste {

    public static final String VELOCIDADE_ALTA = "velocidade-alta";

    private static final CompiladorCel COMPILADOR = new CompiladorCel();

    private RegrasDeTeste() {
    }

    public static Regra velocidadeAlta() {
        return declarativa(
                VELOCIDADE_ALTA,
                Severidade.ALTA,
                "5m",
                """
                        perfil.contagemHistorica >= 5 && \
                        janela5m.contagem > 3 && \
                        transacao.valorCentavos > perfil.ticketMedioCentavos * 2""");
    }

    public static Regra declarativa(
            String id, Severidade severidade, String janela, String condicao) {
        DefinicaoDeRegra definicao = new DefinicaoDeRegra(
                id, 1, "regra usada nos testes", true, severidade, janela, condicao, true);
        return new RegraDeclarativa(definicao, COMPILADOR.compilar(id, condicao));
    }
}
