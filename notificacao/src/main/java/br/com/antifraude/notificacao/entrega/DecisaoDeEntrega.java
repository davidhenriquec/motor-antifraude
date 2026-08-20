package br.com.antifraude.notificacao.entrega;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Severidade;

import java.util.List;

public final class DecisaoDeEntrega {

    private DecisaoDeEntrega() {
    }

    public static List<CanalDeEntrega> canaisPara(Alerta alerta) {
        if (!alerta.notificarCliente()) {
            return List.of();
        }

        if (alerta.severidade() == Severidade.ALTA) {
            return List.of(CanalDeEntrega.PUSH, CanalDeEntrega.EMAIL);
        }

        return List.of(CanalDeEntrega.PUSH);
    }
}
