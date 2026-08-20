package br.com.antifraude.motor.regra;

import br.com.antifraude.contrato.Alerta;
import br.com.antifraude.contrato.Transacao;
import br.com.antifraude.motor.memoria.MemoriaDoCliente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OrdenadorDeRegrasTest {

    @Test
    @DisplayName("a dependencia e avaliada antes de quem depende dela")
    void dependenciaVemPrimeiro() {
        OrdenadorDeRegras.Resultado resultado = OrdenadorDeRegras.ordenar(
                List.of(regra("composta", "base"), regra("base")));

        assertThat(resultado.ordenadas()).extracting(Regra::id).containsExactly("base", "composta");
        assertThat(resultado.recusadas()).isEmpty();
    }

    @Test
    @DisplayName("cadeia de tres niveis e resolvida na ordem certa")
    void cadeiaDeTresNiveis() {
        OrdenadorDeRegras.Resultado resultado = OrdenadorDeRegras.ordenar(
                List.of(regra("topo", "meio"), regra("meio", "base"), regra("base")));

        assertThat(resultado.ordenadas())
                .extracting(Regra::id)
                .containsExactly("base", "meio", "topo");
    }

    @Test
    @DisplayName("ciclo e recusado inteiro, em vez de travar o motor")
    void cicloEhRecusado() {
        OrdenadorDeRegras.Resultado resultado = OrdenadorDeRegras.ordenar(
                List.of(regra("a", "b"), regra("b", "a"), regra("sozinha")));

        assertThat(resultado.ordenadas()).extracting(Regra::id).containsExactly("sozinha");
        assertThat(resultado.recusadas()).containsOnlyKeys("a", "b");
        assertThat(resultado.recusadas().get("a")).contains("ciclo");
    }

    @Test
    @DisplayName("desligar a base desliga a composta em cascata")
    void cascataAoDesligarADependencia() {
        OrdenadorDeRegras.Resultado resultado =
                OrdenadorDeRegras.ordenar(List.of(regra("composta", "base")));

        assertThat(resultado.ordenadas()).isEmpty();
        assertThat(resultado.recusadas().get("composta"))
                .as("uma composta sem insumo nao significa nada, entao cai junto")
                .contains("base");
    }

    @Test
    @DisplayName("a cascata percorre dois niveis")
    void cascataEmDoisNiveis() {
        OrdenadorDeRegras.Resultado resultado =
                OrdenadorDeRegras.ordenar(List.of(regra("topo", "meio"), regra("meio", "base")));

        assertThat(resultado.ordenadas()).isEmpty();
        assertThat(resultado.recusadas()).containsOnlyKeys("meio", "topo");
    }

    @Test
    @DisplayName("as dependencias sao lidas da propria condicao")
    void dependenciasSaoExtraidasDaCondicao() {
        assertThat(DependenciasDaCondicao.extrair(
                "regras['velocidade-alta'] && regras[\"geografia\"] && transacao.valorCentavos > 1"))
                .containsExactly("velocidade-alta", "geografia");
        assertThat(DependenciasDaCondicao.extrair("transacao.valorCentavos > 1")).isEmpty();
    }

    private static Regra regra(String id, String... dependencias) {
        return new Regra() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int versao() {
                return 1;
            }

            @Override
            public List<String> dependeDe() {
                return List.of(dependencias);
            }

            @Override
            public Optional<Alerta> avaliar(Transacao transacao, MemoriaDoCliente memoria) {
                return Optional.empty();
            }
        };
    }
}
