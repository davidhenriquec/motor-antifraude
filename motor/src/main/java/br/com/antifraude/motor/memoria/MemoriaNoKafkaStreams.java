package br.com.antifraude.motor.memoria;

import org.apache.kafka.streams.state.KeyValueStore;

public class MemoriaNoKafkaStreams implements RepositorioDeMemoria {

    private final KeyValueStore<String, MemoriaDoCliente> armazenamento;

    public MemoriaNoKafkaStreams(KeyValueStore<String, MemoriaDoCliente> armazenamento) {
        this.armazenamento = armazenamento;
    }

    @Override
    public MemoriaDoCliente buscar(String clienteId) {
        MemoriaDoCliente encontrada = armazenamento.get(clienteId);
        return encontrada == null ? MemoriaDoCliente.vazia() : encontrada;
    }

    @Override
    public void salvar(String clienteId, MemoriaDoCliente memoria) {
        armazenamento.put(clienteId, memoria);
    }
}
