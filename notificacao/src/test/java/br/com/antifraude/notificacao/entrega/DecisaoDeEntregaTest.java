package br.com.antifraude.notificacao.entrega;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Severidade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DecisaoDeEntregaTest {

    @Test
    @DisplayName("alerta que nao pede aviso ao cliente nao vai para canal nenhum")
    void semAvisoAoCliente() {
        assertThat(DecisaoDeEntrega.canaisPara(alerta(Severidade.ALTA, false))).isEmpty();
    }

    @Test
    @DisplayName("severidade alta vai por push e e-mail")
    void severidadeAltaUsaOsDoisCanais() {
        assertThat(DecisaoDeEntrega.canaisPara(alerta(Severidade.ALTA, true)))
                .containsExactly(CanalDeEntrega.PUSH, CanalDeEntrega.EMAIL);
    }

    @Test
    @DisplayName("severidade media vai so por push, para nao encher a caixa de e-mail")
    void severidadeMediaUsaSoPush() {
        assertThat(DecisaoDeEntrega.canaisPara(alerta(Severidade.MEDIA, true)))
                .containsExactly(CanalDeEntrega.PUSH);
    }

    @Test
    @DisplayName("a decisao vem do alerta: o notificacao nao conhece regra nenhuma")
    void decisaoViajaNoAlerta() {
        Alerta comAviso = alerta(Severidade.MEDIA, true);
        Alerta semAviso = alerta(Severidade.MEDIA, false);

        assertThat(DecisaoDeEntrega.canaisPara(comAviso)).isNotEmpty();
        assertThat(DecisaoDeEntrega.canaisPara(semAviso)).isEmpty();
    }

    private Alerta alerta(Severidade severidade, boolean notificarCliente) {
        return new Alerta(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "cli-000001",
                "tok-teste",
                "1234",
                50_000L,
                "regra-de-teste",
                1,
                "5m",
                severidade,
                notificarCliente,
                Map.of(),
                Instant.now(),
                Instant.now());
    }
}
