package br.com.antifraude.motor.memoria;

public interface RepositorioDeMemoria {

    MemoriaDoCliente buscar(String clienteId);

    void salvar(String clienteId, MemoriaDoCliente memoria);
}
