package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Severidade;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RegraValorAbsoluto implements Regra {

    public static final String ID = "valor-absoluto";
    public static final int VERSAO = 1;

    private final long limiarCentavos;

    public RegraValorAbsoluto(long limiarCentavos) {
        this.limiarCentavos = limiarCentavos;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int versao() {
        return VERSAO;
    }

    @Override
    public Optional<Alerta> avaliar(Transacao transacao, MemoriaDoCliente memoria) {
        if (transacao.valorCentavos() <= limiarCentavos) {
            return Optional.empty();
        }

        Map<String, Object> valoresEntrada = new LinkedHashMap<>();
        valoresEntrada.put("valorCentavos", transacao.valorCentavos());
        valoresEntrada.put("limiarCentavos", limiarCentavos);
        valoresEntrada.put("dependeDeHistorico", false);

        return Optional.of(new Alerta(
                UUID.randomUUID().toString(),
                transacao.transacaoId(),
                transacao.clienteId(),
                transacao.cartaoToken(),
                transacao.ultimosQuatro(),
                transacao.valorCentavos(),
                ID,
                VERSAO,
                "sem-janela",
                Severidade.MEDIA,
                true,
                valoresEntrada,
                transacao.horarioEvento(),
                Instant.now()));
    }
}
