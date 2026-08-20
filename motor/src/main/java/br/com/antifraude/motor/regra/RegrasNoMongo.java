package br.com.antifraude.motor.regra;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Component
public class RegrasNoMongo implements FonteDeRegras {

    private static final Logger log = LoggerFactory.getLogger(RegrasNoMongo.class);

    private final MongoTemplate mongo;
    private final CompiladorCel compilador;
    private final String colecao;

    private final AtomicReference<List<Regra>> regrasCompiladas = new AtomicReference<>(List.of());

    public RegrasNoMongo(
            MongoTemplate mongo,
            CompiladorCel compilador,
            MeterRegistry metricas,
            @Value("${motor.regras.colecao}") String colecao) {
        this.mongo = mongo;
        this.compilador = compilador;
        this.colecao = colecao;

        Gauge.builder("antifraude.regras.ativas", regrasCompiladas, referencia -> referencia.get().size())
                .description("Quantas regras estao no conjunto ativo do motor")
                .register(metricas);
    }

    @Override
    public List<Regra> regrasAtivas() {
        return regrasCompiladas.get();
    }

    @PostConstruct
    public void carregarNaSubida() {
        recarregar();
        if (regrasAtivas().isEmpty()) {
            log.error(
                    "O motor subiu com ZERO regras ativas: nada sera detectado ate a proxima recarga. "
                            + "Verifique a colecao {} no Mongo.",
                    colecao);
        }
    }

    @Scheduled(
            initialDelayString = "${motor.regras.intervalo-de-recarga-ms}",
            fixedDelayString = "${motor.regras.intervalo-de-recarga-ms}")
    public void recarregar() {
        List<DefinicaoDeRegra> definicoes;
        try {
            definicoes = mongo.find(
                    Query.query(Criteria.where("habilitada").is(true)), DefinicaoDeRegra.class, colecao);
        } catch (RuntimeException problema) {
            log.warn(
                    "Falha ao consultar as regras no Mongo. Seguindo com as {} regra(s) em memoria: {}",
                    regrasAtivas().size(),
                    problema.toString());
            return;
        }

        Set<String> identificadoresConhecidos =
                definicoes.stream().map(DefinicaoDeRegra::id).collect(Collectors.toSet());

        List<Regra> compiladas = new ArrayList<>(definicoes.size());
        int recusadasNaCompilacao = 0;

        for (DefinicaoDeRegra definicao : definicoes) {
            try {
                compiladas.add(new RegraDeclarativa(
                        definicao,
                        compilador.compilar(
                                definicao.id(), definicao.condicao(), identificadoresConhecidos)));
            } catch (ExpressaoInvalidaException problema) {
                recusadasNaCompilacao++;
                log.error(
                        "Regra {} recusada e mantida fora do conjunto ativo: {}",
                        definicao.id(),
                        problema.getMessage());
            }
        }

        OrdenadorDeRegras.Resultado ordenacao = OrdenadorDeRegras.ordenar(compiladas);
        ordenacao.recusadas().forEach((id, motivo) ->
                log.error("Regra {} recusada: {}", id, motivo));

        List<Regra> ativas = ordenacao.ordenadas();
        int totalRecusado = recusadasNaCompilacao + ordenacao.recusadas().size();
        List<Regra> anteriores = regrasCompiladas.getAndSet(ativas);

        if (mudou(anteriores, ativas) || totalRecusado > 0) {
            log.info(
                    "Regras recarregadas: {} ativa(s), {} recusada(s). Antes eram {}. Ordem: {}",
                    ativas.size(),
                    totalRecusado,
                    anteriores.size(),
                    ativas.stream().map(Regra::id).toList());
        }
    }

    private boolean mudou(List<Regra> anteriores, List<Regra> novas) {
        if (anteriores.size() != novas.size()) {
            return true;
        }
        for (int i = 0; i < novas.size(); i++) {
            Regra antiga = anteriores.get(i);
            Regra nova = novas.get(i);
            if (!antiga.id().equals(nova.id()) || antiga.versao() != nova.versao()) {
                return true;
            }
        }
        return false;
    }
}
