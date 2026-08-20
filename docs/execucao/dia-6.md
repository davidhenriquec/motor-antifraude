# Dia 6 — Observabilidade e carga

> O dia que troca estimativa por número medido. Duas coisas saíram diferentes do esperado: o modelo
> de capacidade estava incompleto, e o gargalo não era onde eu procurava.

---

## Métricas

As técnicas já existiam desde o dia 2. Faltavam as de negócio, que respondem *"está acertando?"*.

| Métrica                                                | Responde                                                  |
|--------------------------------------------------------|-----------------------------------------------------------|
| `antifraude_alertas_por_regra_total{regra,severidade}` | Taxa de disparo por regra — a métrica central do tópico 6 |
| `antifraude_transacoes_com_alerta_total`               | Denominador correto da taxa de alerta                     |
| `antifraude_latencia_processamento_seconds`            | Quanto o motor leva, sem fila                             |
| `antifraude_latencia_ponta_a_ponta_seconds`            | O SLA do enunciado, com fila                              |
| `antifraude_alertas_suprimidos_total{regra}`           | Quanto ruído o controle de repetição contém               |
| `antifraude_regras_ativas`                             | Cai a zero se a recarga quebrar                           |
| `antifraude_notificacao_fila_morta_idade_segundos`     | Fila que não drena é um segundo incidente                 |
| `antifraude_notificacao_latencia_entrega_seconds`      | Da avaliação até o provedor                               |

**Cardinalidade:** nenhuma usa identificador de cliente ou transação como rótulo. O máximo é 5 regras × 3 severidades.

### Um erro de instrumentação que enganou a medição

A primeira versão usou `publishPercentiles`, que gera um `summary` com os percentis calculados **no cliente**. Dois
problemas: a consulta do painel não funcionava, e — pior — **percentil calculado por instância não pode ser agregado**.
Média de p99 de cinco máquinas não é o p99 do conjunto.

Trocado por `publishPercentileHistogram()`, que publica baldes e deixa o Prometheus calcular o percentil sobre o total.

### Um segundo erro, que fez os percentis mentirem

O histograma tinha teto de 5 segundos. Quando toda observação estoura o teto, o cálculo devolve o limite superior para
qualquer percentil — e p50 e p99 aparecem iguais a 5.000 ms. Passei a interpretar isso como "processamento lento",
quando era simplesmente o instrumento cego acima do teto.

Teto subido para 5 minutos na métrica ponta a ponta.

### E um terceiro, na semântica

O painel marcava **200% de transações alertadas**, o que é impossível. A causa é legítima: **uma transação pode acionar
várias regras**, então contar alertas e dividir por transações não dá percentual. Criada
`antifraude_transacoes_com_alerta_total`, que conta a transação uma vez independente de quantas regras dispararam, e o
painel separado em dois: taxa de alerta (0,11% medido) e alertas por transação alertada.

---

## Painéis

Dois, provisionados por arquivo em `infra/grafana/dashboards/` e versionados.

| Painel                | Público           | Conteúdo                                                                                        |
|-----------------------|-------------------|-------------------------------------------------------------------------------------------------|
| Plantão SRE           | "está de pé?"     | Vazão, as duas latências, fila, disjuntor, fila morta com idade, regras ativas, % dentro do SLA |
| Qualidade da detecção | "está acertando?" | Taxa por regra, taxa de alerta, alertas contidos, reenvios, trilha de auditoria                 |

**Dois detalhes de provisionamento que custaram tempo:** o painel não renderiza sem o campo `id` em cada painel, e o
`uid` da fonte de dados precisa ser fixado no arquivo — o Grafana gera um aleatório, e o painel referenciaria algo
inexistente em máquina nova.

---

## Alertas

Sete regras em `infra/prometheus/regras-de-alerta.yml`, cada uma com o procedimento de plantão na anotação `acao`.

O mais importante é o **alerta de ausência**: se há tráfego entrando e nenhum alerta saindo por 10 minutos, isso é
incidente. É a falha que nenhum indicador técnico mostra — fila normal, latência boa, log limpo, sistema cego.

A condição é **relativa ao tráfego** (`transacoes_avaliadas > 10`), senão dispararia toda madrugada, quando o volume cai
naturalmente.

**Testado de propósito:** desliguei as cinco regras com a carga ligada. 188 transações por segundo entrando, zero
alertas saindo, condição verdadeira.

---

## Teste de carga

### Por que não usei k6

O k6 mede carga HTTP, e **este sistema não tem entrada HTTP por decisão de arquitetura** — a origem publica direto no
Kafka. Precisaria do `xk6-kafka`, que exige compilar um binário customizado com Go.

Medi de forma mais direta: o simulador enche o tópico, e o motor é cronometrado drenando. Isso separa a velocidade de
quem produz da de quem processa.

### O ambiente era metade do problema

O `docker-compose.yml` não dava **volume nenhum** ao Kafka: os dados iam para a camada de escrita do container, a opção
mais lenta no Docker Desktop do macOS.

Medido com `kafka-producer-perf-test`, sem nada mais rodando:

| Teto de escrita do broker | Antes                    | Depois de volume nomeado + heap 2 GB + 16 threads de I/O |
|---------------------------|--------------------------|----------------------------------------------------------|
| Vazão                     | 19.269 msg/s (3,68 MB/s) | **175.070 msg/s (33,39 MB/s)**                           |
| p99 do broker             | 15,6 s                   | **2,0 s**                                                |

**Nove vezes.** Boa parte do que eu tinha atribuído ao sistema era o ambiente.

### O que o motor faz

| Cenário                              | Resultado                                      |
|--------------------------------------|------------------------------------------------|
| Pico momentâneo                      | **28.000 tx/s** numa instância                 |
| 15.000 tx/s ao vivo                  | sustentou ~60 s                                |
| **8.000 tx/s ao vivo, estado limpo** | **sustentou 140 s**, fila entre 2 mil e 31 mil |
| 8.000 tx/s com estado acumulado      | caiu para ~3.000                               |

A média exigida pelo enunciado é 8.000 tx/s, e **uma instância dá conta** — com estado limpo.

### O gargalo, isolado

Suspeitei da transação atômica, que o documento estima custar 10–30% da vazão. Rodei a mesma carga com `at_least_once`:
**a queda aconteceu igual**. Não é ela.

A causa é a amplificação de escrita:

|                      |                   |
|----------------------|-------------------|
| Transação de entrada | 192 bytes         |
| Escrita no changelog | 1.400–1.850 bytes |
| Amplificação         | **7,5× a 9,7×**   |

E ela **cresce com o histórico do cliente**, porque cada `put()` grava a memória inteira. Por isso a vazão começa alta e
degrada: no início as memórias são pequenas.

### A descoberta que mais importa

Com as latências separadas, a 2.000 tx/s:

|               | p50         | p99   |
|---------------|-------------|-------|
| Processamento | **0,43 ms** | 11 ms |
| Ponta a ponta | **453 ms**  | 8,0 s |

> O motor decide em menos de meio milissegundo. Mais de 99% da latência é **espera em fila**.

Acompanhar a vazão cumpre a capacidade; **cumprir a latência exige folga**. Um sistema que processa exatamente o que
recebe mantém fila permanente, e fila permanente é SLA estourado com a vazão em dia.

Isso também explica por que escalar o motor não resolveria o problema observado: o gargalo estava na escrita do broker.
Cinco motores escreveriam no mesmo broker e a fila cresceria igual, com quatro máquinas a mais ociosas. **Escalar só
ajuda se o gargalo estiver no que se escala.**

---

## O que estes números provam e o que não provam

**Provam:** a amplificação de 7,5× é propriedade do código e degrada com o tempo. O motor avalia em sub-milissegundo. A
latência do SLA é dominada por fila.

**Não provam:** capacidade em produção. Notebook, uma instância, Kafka e estado local dividindo o mesmo SSD sob uma
camada de virtualização. Os 44 MB/s de changelog no pico são triviais para hardware dedicado, e o broker aqui entrega 33
MB/s no melhor caso.

Uma medição representativa exigiria Kafka fora do Docker, em Linux, com disco separado do estado local. Fora do alcance
de um case — e por isso fica declarado, em vez de virar número inventado.

---

## Correções que entram na fila

- **Reduzir a amplificação.** O maior suspeito é o registro de 500 identificadores de deduplicação, reescrito inteiro
  para mudar um item. Separá-lo em outro state store faria cada escrita custar um identificador em vez de 500.
- **Cache do state store.** Hoje no padrão de 10 MB, que com 200 mil clientes não coalesce nada.
- **Compressão `lz4` no changelog.** Não reduz a amplificação, reduz os bytes que chegam ao disco.
