# Dia 4 — Regras sem redeploy

> O dia que atende o requisito mais visível do enunciado: *"altamente configurável e extensível
> para suportar novos padrões de fraude, thresholds dinâmicos e regras compostas **sem necessidade
> de redeploy**"*.
>
> O raciocínio das decisões está em [../architecture.md](../architecture.md), tópico 5.

---

## O que existia no fim do dia 3

Três classes Java implementando `Regra`, registradas como beans e injetadas **uma vez, na subida**. Mudar um limiar
exigia editar, compilar, empacotar e subir de novo.

## O que existe agora

As regras são documentos no Mongo, escritos em CEL, recarregados a cada 30 segundos. As três classes Java foram
**removidas** — o comportamento delas vive em `regras/regras.yml`.

---

## O caminho de uma regra

```
regras/regras.yml   ──►   infra/carregar-regras.sh   ──►   Mongo   ──►   motor (a cada 30s)
   texto                   valida e grava                 coleção        compila, ordena, troca
```

Nenhum passo desse caminho reinicia o motor.

### O que o script valida antes de gravar

Campos obrigatórios presentes, `id` sem repetição, e o YAML convertido para JSON. Erro aparece aqui, não no banco.

### O que o motor faz na recarga

1. Consulta o Mongo por `habilitada: true`
2. Compila cada condição em CEL
3. **Avalia cada condição contra um contexto de exemplo** — regra que falha é recusada e as demais continuam
4. Monta o grafo de dependências, ordena topologicamente e recusa ciclos
5. Troca a referência da lista com `AtomicReference.getAndSet`

O passo 5 é o que torna a troca segura sob carga: a lista é imutável e a troca é atômica, então quem está avaliando vê o
conjunto antigo inteiro ou o novo inteiro, nunca metade de cada.

A carga inicial é **síncrona**, no `@PostConstruct`. Sem isso a topologia começaria a consumir com zero regras.

---

## O contexto que a condição enxerga

| Variável                | Campos                                                       |
|-------------------------|--------------------------------------------------------------|
| `transacao`             | `valorCentavos`, `cidade`, `pais`, `categoria`, `canal`      |
| `janela5m`, `janela60m` | `contagem`, `somaCentavos`                                   |
| `perfil`                | `ticketMedioCentavos`, `contagemHistorica`                   |
| `ultimo`                | `cidade` — a cidade **anterior** a esta transação            |
| `regras`                | `regras['id']` — resultado de outra regra, para as compostas |

Nada além disso está acessível: sem rede, sem arquivo, sem reflexão.

**Um bug encontrado aqui.** `ultimo.cidade` devolvia a cidade da *própria* transação em avaliação, porque a memória é
atualizada antes das regras rodarem. A comparação `transacao.cidade !=
ultimo.cidade` nunca podia ser verdadeira, e a regra de geografia era silenciosamente inútil. A suíte unitária passava;
só o teste de ponta a ponta revelou. Corrigido com `cidadeAntesDe(horario)`, que busca o evento mais recente
estritamente anterior.

---

## Regras compostas

```yaml
condicao: regras['velocidade-alta'] && regras['cidade-diferente-no-ecommerce']
```

As dependências **não são declaradas** — são extraídas da própria condição por expressão regular. O grafo é derivado,
não mantido à mão.

| Situação              | O que acontece                                               |
|-----------------------|--------------------------------------------------------------|
| Ordem de avaliação    | Resolvida uma vez, no carregamento, por ordenação topológica |
| Ciclo entre regras    | As envolvidas são recusadas; as demais continuam             |
| Dependência desligada | A composta **cai em cascata**, com o motivo no log           |

A cascata foi escolhida porque uma composta sem insumo não significa nada, e tratar a dependência como falsa mudaria o
comportamento dela em silêncio.

---

## Interruptor operacional

`POST /regras/{id}/desligar` e `/ligar` gravam no Mongo e forçam a recarga na hora, sem esperar os 30 segundos. É o
remédio de plantão do tópico 6: desligar primeiro, investigar depois.

`GET /regras` lista o que está ativo **na memória do motor** — não o conteúdo do Mongo.

---

## Proteção contra regra quebrada

Cada regra é avaliada dentro de `try/catch`. Uma que lance exceção é ignorada naquela transação, as demais seguem, e a
falha vira métrica `antifraude.regras.falhas{regra}` mais um log único por regra.

Sem isso, uma exceção dentro do `process()` mataria a `StreamThread` e pararia a partição inteira.

---

## Controle de repetição de alerta

Entrou junto com o dia 4 porque as regras novas tornaram o problema visível. Está descrito no tópico 3 do documento de
arquitetura.

Resumo: regra com janela avisa uma vez por janela; regra sem janela avisa em toda ocorrência; escalada de severidade
fura a supressão. Métrica `antifraude.alertas.suprimidos{regra}`.

---

## As regras ativas

| Regra                           | Severidade | Janela | Dispara quando                                                                           |
|---------------------------------|------------|--------|------------------------------------------------------------------------------------------|
| `velocidade-alta`               | ALTA       | 5m     | 5+ de histórico, mais de 3 transações em 5 min, e o valor passa do dobro do ticket médio |
| `valor-absoluto`                | MÉDIA      | —      | Uma única transação passa de R$ 5.000                                                    |
| `soma-na-hora`                  | ALTA       | 60m    | A soma dos últimos 60 min passa de R$ 10.000                                             |
| `cidade-diferente-no-ecommerce` | MÉDIA      | —      | Compra online, cidade diferente da anterior, valor acima do triplo do ticket médio       |
| `combinacao-critica`            | ALTA       | 5m     | `velocidade-alta` **e** `cidade-diferente-no-ecommerce` na mesma transação               |

**Removida:** `teste-de-cartao`, que exigia 5 transações somando menos de R$ 200. Nunca disparou — o simulador não
produz esse padrão, e sem caso de teste não havia como afirmar se a regra estava certa ou errada.

---

## Verificado em execução

| O que                              | Resultado                                                                   |
|------------------------------------|-----------------------------------------------------------------------------|
| Alterar limiar com o sistema no ar | Regra v2 valendo em ~20s, sem redeploy                                      |
| Adicionar regra inédita            | Entrou em 19s, disparou na transação seguinte                               |
| Desligar regra                     | Parou de disparar imediatamente                                             |
| Cascata                            | Desligar `velocidade-alta` derrubou `combinacao-critica`, com motivo no log |
| Regra quebrada                     | Recusada no carregamento; as 5 boas continuaram rodando                     |
| Café depois do alerta              | Silêncio, com o contador de suprimidos em 2                                 |
| Três compras de R$ 6.000           | Três alertas — cada uma é um evento próprio                                 |

60 testes automatizados.

---

## Em produção: dois pipelines, não um

```
regras/regras.yml alterado  ──►  valida  ──►  escreve no DocumentDB   (não é deploy)
motor/src/... alterado      ──►  build   ──►  imagem  ──►  ECS/EKS    (é deploy)
```

Separados por filtro de `paths` no GitHub Actions. O de regras não constrói imagem nem reinicia container — só escreve
num banco que a aplicação já consulta sozinha.

**O argumento para separar em outro repositório não é o pipeline, é a permissão:** para publicar regra, o analista
precisa de escrita no repositório, e aí também consegue abrir PR no código do motor. O custo de separar é que o contexto
do CEL passa a ser um contrato entre dois repositórios.

---

## O que ficou de fora

- **Modo sombra e canário.** A prevenção continua no papel; a remediação (interruptor com cascata)
  está implementada.
- **Casos de teste embutidos na regra.** Nenhuma das 5 regras tem caso de teste próprio. Uma regra errada só aparece em
  produção — foi exatamente assim que a `teste-de-cartao` passou despercebida.
- **Aprovação de duas pessoas continua sendo processo, não código.** Quem tiver credencial de escrita no Mongo publica
  regra sem passar pelo Git. A proteção real seria IAM.
