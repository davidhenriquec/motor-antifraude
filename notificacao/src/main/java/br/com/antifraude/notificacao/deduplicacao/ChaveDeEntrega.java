package br.com.antifraude.notificacao.deduplicacao;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.notificacao.entrega.CanalDeEntrega;

public final class ChaveDeEntrega {

    private ChaveDeEntrega() {
    }

    public static String de(Alerta alerta, CanalDeEntrega canal) {
        return "entrega:%s:%s".formatted(alerta.alertaId(), canal.name());
    }
}
