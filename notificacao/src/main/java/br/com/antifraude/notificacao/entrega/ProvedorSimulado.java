package br.com.antifraude.notificacao.entrega;

import br.com.antifraude.contrato.Alerta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ProvedorSimulado {

    private static final Logger log = LoggerFactory.getLogger(ProvedorSimulado.class);

    private final AtomicBoolean noAr = new AtomicBoolean(true);

    @Value("${notificacao.provedor.latencia-minima-ms}")
    private long latenciaMinimaMs;

    @Value("${notificacao.provedor.latencia-maxima-ms}")
    private long latenciaMaximaMs;

    public void enviar(CanalDeEntrega canal, Alerta alerta) {
        if (!noAr.get()) {
            throw new FalhaNaEntregaException(
                    "provedor de %s indisponivel".formatted(canal.name().toLowerCase()));
        }

        esperarComoUmProvedorReal();

        log.info(
                "ENTREGUE {} cliente={} cartao=****{} regra={} severidade={} valor={}",
                canal,
                alerta.clienteId(),
                alerta.ultimosQuatro(),
                alerta.regraId(),
                alerta.severidade(),
                alerta.valorCentavos() / 100.0);
    }

    public void derrubar() {
        noAr.set(false);
        log.warn("Provedor simulado DERRUBADO: as entregas passarao a falhar");
    }

    public void levantar() {
        noAr.set(true);
        log.warn("Provedor simulado restabelecido");
    }

    public boolean estaNoAr() {
        return noAr.get();
    }

    private void esperarComoUmProvedorReal() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(latenciaMinimaMs, latenciaMaximaMs));
        } catch (InterruptedException interrupcao) {
            Thread.currentThread().interrupt();
            throw new FalhaNaEntregaException("envio interrompido");
        }
    }
}
