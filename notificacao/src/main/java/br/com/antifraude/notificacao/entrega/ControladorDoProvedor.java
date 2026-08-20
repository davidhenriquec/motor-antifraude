package br.com.antifraude.notificacao.entrega;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/provedor")
public class ControladorDoProvedor {

    private final ProvedorSimulado provedor;

    public ControladorDoProvedor(ProvedorSimulado provedor) {
        this.provedor = provedor;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("noAr", provedor.estaNoAr());
    }

    @PostMapping("/derrubar")
    public Map<String, Object> derrubar() {
        provedor.derrubar();
        return Map.of("noAr", false);
    }

    @PostMapping("/levantar")
    public Map<String, Object> levantar() {
        provedor.levantar();
        return Map.of("noAr", true);
    }
}
