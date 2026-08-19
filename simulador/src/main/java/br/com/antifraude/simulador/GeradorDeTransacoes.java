package br.com.antifraude.simulador;

import br.com.antifraude.contrato.Canal;
import br.com.antifraude.contrato.Transacao;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class GeradorDeTransacoes {
    private static final List<String> CIDADES =
            List.of("Sao Paulo", "Rio de Janeiro", "Belo Horizonte", "Curitiba", "Recife", "Porto Alegre");

    private static final List<String> CATEGORIAS =
            List.of("5411", "5541", "5812", "5912", "5732", "7011");

    private final List<PerfilSimulado> clientes = new ArrayList<>();

    @Value("${simulador.quantidade-de-clientes}")
    private int quantidadeDeClientes;

    @PostConstruct
    void criarClientes() {
        ThreadLocalRandom aleatorio = ThreadLocalRandom.current();
        for (int i = 0; i < quantidadeDeClientes; i++) {
            long ticketMedio = aleatorio.nextLong(3_000, 200_000);
            clientes.add(new PerfilSimulado(
                    "cli-%06d".formatted(i),
                    "tok-" + UUID.randomUUID().toString().substring(0, 12),
                    String.valueOf(aleatorio.nextInt(400000, 599999)),
                    "%04d".formatted(aleatorio.nextInt(0, 10000)),
                    ticketMedio,
                    CIDADES.get(aleatorio.nextInt(CIDADES.size()))));
        }
    }

    public Transacao normal() {
        ThreadLocalRandom aleatorio = ThreadLocalRandom.current();
        PerfilSimulado cliente = clientes.get(aleatorio.nextInt(clientes.size()));
        return construir(cliente, variarEmTornoDoTicket(cliente), cliente.cidadeHabitual(), Canal.POS);
    }

    public List<Transacao> historicoPara(String clienteId, int quantidade) {
        PerfilSimulado cliente = buscar(clienteId);
        List<Transacao> historico = new ArrayList<>(quantidade);
        for (int i = 0; i < quantidade; i++) {
            historico.add(construir(
                    cliente, variarEmTornoDoTicket(cliente), cliente.cidadeHabitual(), Canal.POS));
        }
        return historico;
    }

    public List<Transacao> sequenciaSuspeita(String clienteId, int quantidade) {
        PerfilSimulado cliente = buscar(clienteId);
        ThreadLocalRandom aleatorio = ThreadLocalRandom.current();
        String cidadeDistante = CIDADES.stream()
                .filter(c -> !c.equals(cliente.cidadeHabitual()))
                .findFirst()
                .orElseThrow();

        List<Transacao> sequencia = new ArrayList<>(quantidade);
        for (int i = 0; i < quantidade; i++) {
            long valorAlto = cliente.ticketMedioCentavos() * aleatorio.nextLong(6, 12);
            sequencia.add(construir(cliente, valorAlto, cidadeDistante, Canal.ECOMMERCE));
        }
        return sequencia;
    }

    public Transacao comValor(String clienteId, long valorCentavos) {
        PerfilSimulado cliente = buscar(clienteId);
        return construir(cliente, valorCentavos, cliente.cidadeHabitual(), Canal.ECOMMERCE);
    }

    private PerfilSimulado buscar(String clienteId) {
        return clientes.stream()
                .filter(c -> c.clienteId().equals(clienteId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("cliente inexistente: " + clienteId));
    }

    public String clienteQualquer() {
        return clientes.get(ThreadLocalRandom.current().nextInt(clientes.size())).clienteId();
    }

    private long variarEmTornoDoTicket(PerfilSimulado cliente) {
        ThreadLocalRandom aleatorio = ThreadLocalRandom.current();
        double fator = 0.4 + aleatorio.nextDouble() * 1.2;
        return Math.max(500L, Math.round(cliente.ticketMedioCentavos() * fator));
    }

    private Transacao construir(PerfilSimulado cliente, long valorCentavos, String cidade, Canal canal) {
        ThreadLocalRandom aleatorio = ThreadLocalRandom.current();
        return new Transacao(
                UUID.randomUUID().toString(),
                cliente.clienteId(),
                cliente.cartaoToken(),
                cliente.bin(),
                cliente.ultimosQuatro(),
                valorCentavos,
                "est-%05d".formatted(aleatorio.nextInt(0, 20000)),
                CATEGORIAS.get(aleatorio.nextInt(CATEGORIAS.size())),
                cidade,
                "BR",
                canal,
                Instant.now());
    }
}
