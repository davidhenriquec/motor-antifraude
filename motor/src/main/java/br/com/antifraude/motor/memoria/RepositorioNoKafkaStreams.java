package br.com.antifraude.motor.memoria;

import org.apache.kafka.streams.state.KeyValueStore;

public class RepositorioNoKafkaStreams implements RepositorioDeMemoria {

    private final KeyValueStore<String, MemoriaDoCliente> memoriasPorCliente;

    public RepositorioNoKafkaStreams(KeyValueStore<String, MemoriaDoCliente> memoriasPorCliente) {
        this.memoriasPorCliente = memoriasPorCliente;
    }

    @Override
    public MemoriaDoCliente buscar(String clienteId) {
        MemoriaDoCliente memoriaEncontrada = memoriasPorCliente.get(clienteId);
        return memoriaEncontrada == null ? MemoriaDoCliente.vazia() : memoriaEncontrada;
    }

    @Override
    public void salvar(String clienteId, MemoriaDoCliente memoria) {
        memoriasPorCliente.put(clienteId, memoria);
    }
}
