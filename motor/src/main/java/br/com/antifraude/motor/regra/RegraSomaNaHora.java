package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Severidade;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.JanelasDeTempo;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RegraSomaNaHora implements Regra {

    public static final String ID = "soma-na-hora";
    public static final int VERSAO = 1;

    private final long limiteDaSomaCentavos;

    public RegraSomaNaHora(long limiteDaSomaCentavos) {
        this.limiteDaSomaCentavos = limiteDaSomaCentavos;
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
        Instant horarioDaTransacao = transacao.horarioEvento();
        long somaNaUltimaHoraCentavos =
                memoria.somaNaJanelaCentavos(JanelasDeTempo.UMA_HORA, horarioDaTransacao);

        if (somaNaUltimaHoraCentavos <= limiteDaSomaCentavos) {
            return Optional.empty();
        }

        Map<String, Object> valoresEntrada = new LinkedHashMap<>();
        valoresEntrada.put("somaCentavos", somaNaUltimaHoraCentavos);
        valoresEntrada.put("limiteCentavos", limiteDaSomaCentavos);
        valoresEntrada.put(
                "transacoesNaJanela",
                memoria.contagemNaJanela(JanelasDeTempo.UMA_HORA, horarioDaTransacao));
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
                "60m",
                Severidade.ALTA,
                true,
                valoresEntrada,
                transacao.horarioEvento(),
                Instant.now()));
    }
}
