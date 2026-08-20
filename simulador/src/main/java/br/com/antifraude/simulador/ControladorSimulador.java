package br.com.antifraude.simulador;

import br.com.antifraude.contrato.Transacao;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/simulador")
public class ControladorSimulador {

    private final GeradorDeCarga carga;
    private final GeradorDeTransacoes gerador;
    private final PublicadorDeTransacoes publicador;

    public ControladorSimulador(
            GeradorDeCarga carga, GeradorDeTransacoes gerador, PublicadorDeTransacoes publicador) {
        this.carga = carga;
        this.gerador = gerador;
        this.publicador = publicador;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("ligado", carga.estaLigado());
        resposta.put("taxaPorSegundo", carga.taxaAtual());
        resposta.put("totalPublicadas", publicador.totalPublicadas());
        return resposta;
    }

    @PostMapping("/carga/ligar")
    public Map<String, Object> ligar(@RequestParam(required = false) Integer taxa) {
        if (taxa != null) {
            carga.ajustarTaxa(taxa);
        }
        carga.ligar();
        return status();
    }

    @PostMapping("/carga/desligar")
    public Map<String, Object> desligar() {
        carga.desligar();
        return status();
    }

    @PostMapping("/historico")
    public Map<String, Object> historico(
            @RequestParam(required = false) String cliente,
            @RequestParam(defaultValue = "10") int quantidade) {
        String alvo = cliente != null ? cliente : gerador.clienteQualquer();
        List<Transacao> historico = gerador.historicoPara(alvo, quantidade);
        publicador.publicarTodas(historico);

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("cliente", alvo);
        resposta.put("transacoesPublicadas", historico.size());
        resposta.put("valores", historico.stream().map(t -> t.valorCentavos() / 100.0).toList());
        return resposta;
    }

    @PostMapping("/fraude")
    public Map<String, Object> fraude(@RequestParam(required = false) String cliente, @RequestParam(defaultValue = "5") int quantidade) {
        String alvo = cliente != null ? cliente : gerador.clienteQualquer();
        List<Transacao> sequencia = gerador.sequenciaSuspeita(alvo, quantidade);
        publicador.publicarTodas(sequencia);

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("cliente", alvo);
        resposta.put("transacoesPublicadas", sequencia.size());
        resposta.put("valores", sequencia.stream().map(t -> t.valorCentavos() / 100.0).toList());
        return resposta;
    }

    @PostMapping("/transacao")
    public Map<String, Object> transacao(
            @RequestParam(required = false) String cliente,
            @RequestParam double valor) {
        String alvo = cliente != null ? cliente : gerador.clienteQualquer();
        long centavos = Math.round(valor * 100);
        Transacao transacao = gerador.comValor(alvo, centavos);
        publicador.publicar(transacao);

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("cliente", alvo);
        resposta.put("transacaoId", transacao.transacaoId());
        resposta.put("valor", valor);
        return resposta;
    }

    @PostMapping("/verificar-particao")
    public Map<String, Object> verificarParticao(
            @RequestParam(required = false) String cliente,
            @RequestParam(defaultValue = "5") int repeticoes) {
        String alvo = cliente != null ? cliente : gerador.clienteQualquer();
        List<Integer> particoes = new ArrayList<>();
        for (int i = 0; i < repeticoes; i++) {
            particoes.add(publicador.publicarEDevolverParticao(gerador.normal()));
        }

        List<Integer> particoesDoAlvo = new ArrayList<>();
        for (int i = 0; i < repeticoes; i++) {
            Transacao transacao = gerador.sequenciaSuspeita(alvo, 1).getFirst();
            particoesDoAlvo.add(publicador.publicarEDevolverParticao(transacao));
        }

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("cliente", alvo);
        resposta.put("particoesDoMesmoCliente", particoesDoAlvo);
        resposta.put("todasIguais", particoesDoAlvo.stream().distinct().count() == 1);
        resposta.put("particoesDeClientesVariados", particoes);
        return resposta;
    }
}
