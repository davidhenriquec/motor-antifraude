package br.com.antifraude.motor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ArquiteturaTest {

    private static final Path CODIGO = Path.of("src/main/java/br/com/antifraude/motor");

    private static final Set<String> PACOTES_DE_NEGOCIO = Set.of("memoria", "regra", "deteccao");

    private static final Set<String> ADAPTADORES = Set.of(
            "RepositorioNoKafkaStreams.java",
            "RegrasConfig.java",
            "RegrasNoMongo.java");

    private static final Set<String> FRAMEWORKS = Set.of(
            "org.springframework",
            "org.apache.kafka",
            "com.fasterxml.jackson",
            "io.micrometer",
            "jakarta.");

    @Test
    @DisplayName("as classes de negocio nao dependem de framework")
    void negocioNaoDependeDeFramework() throws IOException {
        List<String> violacoes = new ArrayList<>();

        for (String pacote : PACOTES_DE_NEGOCIO) {
            try (Stream<Path> arquivos = Files.walk(CODIGO.resolve(pacote))) {
                for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (ADAPTADORES.contains(arquivo.getFileName().toString())) {
                        continue;
                    }
                    for (String linha : Files.readAllLines(arquivo)) {
                        if (!linha.startsWith("import ")) {
                            continue;
                        }
                        FRAMEWORKS.stream()
                                .filter(linha::contains)
                                .findFirst()
                                .ifPresent(framework -> violacoes.add(
                                        "%s importa %s".formatted(arquivo.getFileName(), framework)));
                    }
                }
            }
        }

        assertThat(violacoes)
                .as("regra, memoria e deteccao devem ser testaveis sem subir infraestrutura")
                .isEmpty();
    }

    @Test
    @DisplayName("os adaptadores declarados existem e continuam sendo os unicos com framework")
    void adaptadoresDeclaradosExistem() throws IOException {
        for (String adaptador : ADAPTADORES) {
            try (Stream<Path> arquivos = Files.walk(CODIGO)) {
                boolean existe = arquivos.anyMatch(p -> p.getFileName().toString().equals(adaptador));
                assertThat(existe)
                        .as("%s esta na lista de excecoes mas nao existe mais", adaptador)
                        .isTrue();
            }
        }
    }
}
