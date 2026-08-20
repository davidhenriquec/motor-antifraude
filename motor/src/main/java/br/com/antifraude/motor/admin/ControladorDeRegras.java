package br.com.antifraude.motor.admin;

import br.com.antifraude.motor.regra.Regra;
import br.com.antifraude.motor.regra.RegrasNoMongo;
import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/regras")
public class ControladorDeRegras {

    private static final Logger log = LoggerFactory.getLogger(ControladorDeRegras.class);

    private final MongoTemplate mongo;
    private final RegrasNoMongo fonteDeRegras;
    private final String colecao;

    public ControladorDeRegras(
            MongoTemplate mongo,
            RegrasNoMongo fonteDeRegras,
            @Value("${motor.regras.colecao}") String colecao) {
        this.mongo = mongo;
        this.fonteDeRegras = fonteDeRegras;
        this.colecao = colecao;
    }

    @GetMapping
    public List<Map<String, Object>> ativas() {
        return fonteDeRegras.regrasAtivas().stream()
                .map(regra -> {
                    Map<String, Object> resumo = new LinkedHashMap<>();
                    resumo.put("id", regra.id());
                    resumo.put("versao", regra.versao());
                    return resumo;
                })
                .toList();
    }

    @PostMapping("/{id}/desligar")
    public ResponseEntity<Map<String, Object>> desligar(@PathVariable String id) {
        return alternar(id, false);
    }

    @PostMapping("/{id}/ligar")
    public ResponseEntity<Map<String, Object>> ligar(@PathVariable String id) {
        return alternar(id, true);
    }

    private ResponseEntity<Map<String, Object>> alternar(String id, boolean habilitada) {
        UpdateResult resultado = mongo.updateFirst(
                Query.query(Criteria.where("_id").is(id)),
                new Update().set("habilitada", habilitada),
                colecao);

        if (resultado.getMatchedCount() == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("erro", "regra inexistente: " + id));
        }

        log.warn("Regra {} foi {} pelo endpoint operacional", id, habilitada ? "LIGADA" : "DESLIGADA");
        fonteDeRegras.recarregar();

        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("regra", id);
        corpo.put("habilitada", habilitada);
        corpo.put("regrasAtivasAgora", fonteDeRegras.regrasAtivas().stream().map(Regra::id).toList());
        return ResponseEntity.ok(corpo);
    }
}
