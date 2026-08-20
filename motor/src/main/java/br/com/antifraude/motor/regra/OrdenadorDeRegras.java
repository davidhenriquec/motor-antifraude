package br.com.antifraude.motor.regra;

import java.util.*;

public final class OrdenadorDeRegras {

    public record Resultado(List<Regra> ordenadas, Map<String, String> recusadas) {
    }

    private OrdenadorDeRegras() {
    }

    public static Resultado ordenar(List<Regra> regras) {
        Map<String, Regra> porId = new LinkedHashMap<>();
        Map<String, String> recusadas = new LinkedHashMap<>();

        for (Regra regra : regras) {
            porId.put(regra.id(), regra);
        }

        descartarComDependenciaAusente(porId, recusadas);

        List<Regra> ordenadas = ordenarPorDependencia(porId, recusadas);

        return new Resultado(List.copyOf(ordenadas), Map.copyOf(recusadas));
    }

    private static void descartarComDependenciaAusente(
            Map<String, Regra> porId, Map<String, String> recusadas) {
        boolean removeuAlguma = true;
        while (removeuAlguma) {
            removeuAlguma = false;
            for (Regra regra : List.copyOf(porId.values())) {
                for (String dependencia : regra.dependeDe()) {
                    if (!porId.containsKey(dependencia)) {
                        porId.remove(regra.id());
                        recusadas.put(
                                regra.id(),
                                "depende de %s, que nao esta ativa".formatted(dependencia));
                        removeuAlguma = true;
                        break;
                    }
                }
            }
        }
    }

    private static List<Regra> ordenarPorDependencia(
            Map<String, Regra> porId, Map<String, String> recusadas) {
        Map<String, Integer> dependenciasPendentes = new LinkedHashMap<>();
        Map<String, List<String>> dependentesDe = new LinkedHashMap<>();

        for (Regra regra : porId.values()) {
            dependenciasPendentes.put(regra.id(), regra.dependeDe().size());
            for (String dependencia : regra.dependeDe()) {
                dependentesDe.computeIfAbsent(dependencia, id -> new ArrayList<>()).add(regra.id());
            }
        }

        Deque<String> prontas = new ArrayDeque<>();
        dependenciasPendentes.forEach((id, pendentes) -> {
            if (pendentes == 0) {
                prontas.add(id);
            }
        });

        List<Regra> ordenadas = new ArrayList<>();
        while (!prontas.isEmpty()) {
            String id = prontas.poll();
            ordenadas.add(porId.get(id));
            for (String dependente : dependentesDe.getOrDefault(id, List.of())) {
                if (dependenciasPendentes.merge(dependente, -1, Integer::sum) == 0) {
                    prontas.add(dependente);
                }
            }
        }

        Set<String> ordenadasPorId = new java.util.HashSet<>(ordenadas.stream().map(Regra::id).toList());
        for (String id : porId.keySet()) {
            if (!ordenadasPorId.contains(id)) {
                recusadas.put(id, "participa de um ciclo de dependencia entre regras");
            }
        }

        return ordenadas;
    }
}
