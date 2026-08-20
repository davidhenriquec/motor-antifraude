package br.com.antifraude.motor.regra;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DependenciasDaCondicao {

    private static final Pattern REFERENCIA_A_OUTRA_REGRA =
            Pattern.compile("regras\\[\\s*['\"]([^'\"]+)['\"]\\s*\\]");

    private DependenciasDaCondicao() {
    }

    public static List<String> extrair(String condicao) {
        Set<String> encontradas = new LinkedHashSet<>();
        Matcher busca = REFERENCIA_A_OUTRA_REGRA.matcher(condicao);
        while (busca.find()) {
            encontradas.add(busca.group(1));
        }
        return List.copyOf(encontradas);
    }
}
