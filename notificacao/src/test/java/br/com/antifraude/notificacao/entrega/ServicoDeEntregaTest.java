package br.com.antifraude.notificacao.entrega;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Severidade;
import br.com.antifraude.notificacao.deduplicacao.RegistroDeEntregas;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ServicoDeEntregaTest {

    private ProvedorSimulado provedor;
    private RegistroDeEntregas registro;
    private ServicoDeEntrega servico;

    @BeforeEach
    void preparar() {
        provedor = mock(ProvedorSimulado.class);
        registro = mock(RegistroDeEntregas.class);
        servico = new ServicoDeEntrega(provedor, registro, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("entrega nova: reserva, envia e so entao confirma por 24 horas")
    void entregaNovaSegueAOrdem() {
        when(registro.reservar(anyString())).thenReturn(true);

        servico.entregar(alerta(), CanalDeEntrega.PUSH);

        verify(provedor).enviar(any(), any());
        verify(registro).confirmar(anyString());
        verify(registro, never()).liberar(anyString());
    }

    @Test
    @DisplayName("reentrega: se a chave ja estava reservada, nao envia de novo")
    void reentregaNaoEnvia() {
        when(registro.reservar(anyString())).thenReturn(false);

        servico.entregar(alerta(), CanalDeEntrega.PUSH);

        verify(provedor, never()).enviar(any(), any());
    }

    @Test
    @DisplayName("se o envio falha, a reserva e liberada: senao a fraude nunca seria avisada")
    void falhaNoEnvioLiberaAReserva() {
        when(registro.reservar(anyString())).thenReturn(true);
        doThrow(new FalhaNaEntregaException("provedor fora")).when(provedor).enviar(any(), any());

        assertThatThrownBy(() -> servico.entregar(alerta(), CanalDeEntrega.PUSH))
                .isInstanceOf(FalhaNaEntregaException.class);

        verify(registro).liberar(anyString());
        verify(registro, never()).confirmar(anyString());
    }

    @Test
    @DisplayName("a confirmacao so acontece depois do envio, nunca antes")
    void confirmacaoDepoisDoEnvio() {
        when(registro.reservar(anyString())).thenReturn(true);

        servico.entregar(alerta(), CanalDeEntrega.PUSH);

        org.mockito.InOrder ordem = org.mockito.Mockito.inOrder(registro, provedor);
        ordem.verify(registro).reservar(anyString());
        ordem.verify(provedor).enviar(any(), any());
        ordem.verify(registro).confirmar(anyString());
    }

    @Test
    @DisplayName("cada canal tem sua propria chave: push e e-mail nao se cancelam")
    void canaisTemChavesDiferentes() {
        Alerta alerta = alerta();

        assertThat(br.com.antifraude.notificacao.deduplicacao.ChaveDeEntrega
                .de(alerta, CanalDeEntrega.PUSH))
                .isNotEqualTo(br.com.antifraude.notificacao.deduplicacao.ChaveDeEntrega
                        .de(alerta, CanalDeEntrega.EMAIL));
    }

    private Alerta alerta() {
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
                Severidade.ALTA,
                true,
                Map.of(),
                Instant.now(),
                Instant.now());
    }
}
