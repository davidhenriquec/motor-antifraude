package br.com.antifraude.simulador;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class GeradorDeCarga {
    private static final Logger log = LoggerFactory.getLogger(GeradorDeCarga.class);

    private final GeradorDeTransacoes gerador;
    private final PublicadorDeTransacoes publicador;
    private final AtomicBoolean ligado = new AtomicBoolean(false);
    private final AtomicInteger taxaPorSegundo = new AtomicInteger();

    private ScheduledExecutorService agendador;

    @Value("${simulador.taxa-por-segundo}")
    private int taxaInicial;

    @Value("${simulador.ligado}")
    private boolean ligarNoInicio;

    public GeradorDeCarga(GeradorDeTransacoes gerador, PublicadorDeTransacoes publicador) {
        this.gerador = gerador;
        this.publicador = publicador;
    }

    @PostConstruct
    void iniciarAgendador() {
        taxaPorSegundo.set(taxaInicial);
        agendador = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("gerador-de-carga").unstarted(r));

        agendador.scheduleAtFixedRate(this::emitirLote, 500, 100, TimeUnit.MILLISECONDS);
        if (ligarNoInicio) {
            ligar();
        }
    }

    private void emitirLote() {
        if (!ligado.get()) {
            return;
        }
        try {
            int porLote = Math.max(1, taxaPorSegundo.get() / 10);
            for (int i = 0; i < porLote; i++) {
                publicador.publicar(gerador.normal());
            }
        } catch (Exception e) {
            log.error("erro ao emitir lote", e);
        }
    }

    public void ligar() {
        if (ligado.compareAndSet(false, true)) {
            log.info("carga LIGADA a {} transacoes por segundo", taxaPorSegundo.get());
        }
    }

    public void desligar() {
        if (ligado.compareAndSet(true, false)) {
            log.info("carga DESLIGADA. Total publicado: {}", publicador.totalPublicadas());
        }
    }

    public void ajustarTaxa(int novaTaxa) {
        taxaPorSegundo.set(Math.max(1, novaTaxa));
        log.info("taxa ajustada para {} transacoes por segundo", taxaPorSegundo.get());
    }

    public boolean estaLigado() {
        return ligado.get();
    }

    public int taxaAtual() {
        return taxaPorSegundo.get();
    }

    @PreDestroy
    void encerrar() {
        desligar();
        if (agendador != null) {
            agendador.shutdownNow();
        }
    }
}
