package br.com.antifraude.simulador;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controle do simulador durante a demonstracao. */
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
        var resposta = new LinkedHashMap<String, Object>();
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

    /** Dispara uma sequencia que deve acionar a deteccao. E o gatilho da demonstracao. */
    @PostMapping("/fraude")
    public Map<String, Object> fraude(
            @RequestParam(required = false) String cliente,
            @RequestParam(defaultValue = "5") int quantidade) {

        String alvo = cliente != null ? cliente : gerador.clienteQualquer();
        var sequencia = gerador.sequenciaSuspeita(alvo, quantidade);
        publicador.publicarTodas(sequencia);

        var resposta = new LinkedHashMap<String, Object>();
        resposta.put("cliente", alvo);
        resposta.put("transacoesPublicadas", sequencia.size());
        resposta.put("valores", sequencia.stream().map(t -> t.valorCentavos() / 100.0).toList());
        return resposta;
    }

    /**
     * Verificacao do roteamento por particao — o checkpoint do dia 1.
     *
     * <p>Publica varias transacoes do mesmo cliente e devolve a particao de cada uma. Todas devem
     * cair na mesma, porque {@code hash(clienteId) % 64} e deterministico.
     */
    @PostMapping("/verificar-particao")
    public Map<String, Object> verificarParticao(
            @RequestParam(required = false) String cliente,
            @RequestParam(defaultValue = "5") int repeticoes) {

        String alvo = cliente != null ? cliente : gerador.clienteQualquer();
        var particoes = new java.util.ArrayList<Integer>();
        for (int i = 0; i < repeticoes; i++) {
            particoes.add(publicador.publicarEDevolverParticao(gerador.normal()));
        }

        var particoesDoAlvo = new java.util.ArrayList<Integer>();
        for (int i = 0; i < repeticoes; i++) {
            var transacao = gerador.sequenciaSuspeita(alvo, 1).getFirst();
            particoesDoAlvo.add(publicador.publicarEDevolverParticao(transacao));
        }

        var resposta = new LinkedHashMap<String, Object>();
        resposta.put("cliente", alvo);
        resposta.put("particoesDoMesmoCliente", particoesDoAlvo);
        resposta.put("todasIguais", particoesDoAlvo.stream().distinct().count() == 1);
        resposta.put("particoesDeClientesVariados", particoes);
        return resposta;
    }
}
