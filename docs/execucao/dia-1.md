# Dia 1 — Fundação

> Diário de execução. Um documento por dia, registrando o que foi entregue, o que mudou em
> relação ao plano e o que foi verificado com número.
>
> O raciocínio arquitetural está em [../architecture.md](../architecture.md); aqui fica o que
> aconteceu na prática.

**Objetivo do dia, conforme o plano:** repositório, infraestrutura local, contrato do evento e simulador — nada pode ser
testado sem transações chegando, então o simulador vem antes de qualquer aplicação de produção.

---

## O que foi entregue

**Repositório** — `motor-antifraude`, privado, com Maven multi-módulo e wrapper incluído. Cinco módulos compilando em
JDK 21: `contrato`, `motor`, `notificacao`, `auditoria` e `simulador`. Os quatro últimos com esqueleto de aplicação
Spring Boot; o `contrato` já completo.

**Infraestrutura local** — sete contêineres no `docker-compose.yml`:

| Serviço       | Papel                                       |
|---------------|---------------------------------------------|
| Kafka (KRaft) | Backbone de mensageria, sem ZooKeeper       |
| Mongo         | Definição das regras (a partir do dia 4)    |
| Postgres      | Auditoria dos alertas (a partir do dia 5)   |
| Redis         | Deduplicação de entrega (a partir do dia 5) |
| Prometheus    | Coleta de métricas (a partir do dia 6)      |
| Grafana       | Painéis (a partir do dia 6)                 |
| Kafka UI      | Inspeção de tópicos, mensagens e lag        |

**Tópicos** criados automaticamente na subida, com as partições definidas na arquitetura:
`transacoes` com 64, `alertas` com 16, `notificacoes-dlq` com 4.

**Contrato do evento** — `Transacao`, `Alerta`, `Canal` e `Severidade`, com as decisões de arquitetura documentadas no
próprio código.

**Simulador** — gera transações com padrão por cliente, publica com o identificador do cliente como chave, e expõe
controle por REST para ligar e desligar a carga, ajustar a taxa, disparar uma sequência suspeita e verificar o
roteamento por partição.

---

## Verificações

O checkpoint do dia era: *o mesmo cliente cai sempre na mesma partição, e clientes distintos se distribuem*.

| Verificação                      | Método                                   | Resultado                                                              |
|----------------------------------|------------------------------------------|------------------------------------------------------------------------|
| Mesmo cliente, mesma partição    | 6 publicações de `cli-000042`            | **Partição 60 em 6 de 6**                                              |
| Clientes distintos se espalham   | 1.632 mensagens de clientes sorteados    | **64 de 64 partições com dados**                                       |
| Distribuição equilibrada         | Contagem por partição                    | Média de **25,5**, variando entre 18 e 31                              |
| Roteamento é o hash, não sorteio | Reimplementação do murmur2 fora do Kafka | `hash("cli-000042") & 0x7fffffff % 64 = 60` — **bate com o observado** |

A última linha merece destaque: o cálculo foi refeito por fora, reproduzindo o algoritmo que o Kafka usa, e chegou à
mesma partição que o broker escolheu. Isso confirma que o roteamento é determinístico e não coincidência estatística.

**A variação entre 18 e 31 mensagens por partição é ruído do sorteio de clientes, não do hash.** O hash garante
*consistência* — mesmo cliente, mesma partição — e não *equilíbrio*. O equilíbrio veio de os clientes terem sido
sorteados uniformemente. Em produção, um cliente de altíssimo volume concentraria mensagens numa única partição, que é a
limitação de **partição quente** registrada no tópico 1.

---

## Decisões tomadas durante a implementação

Coisas que não estavam definidas no plano e foram decididas ao escrever o código.

### Valor monetário como inteiro em centavos

`Transacao.valorCentavos` é `long`, não `double` nem `BigDecimal`.

Ponto flutuante não representa valores decimais exatamente, e as janelas do motor vão somar e comparar valores
continuamente — erro de arredondamento acumulado num sistema que decide sobre dinheiro é inaceitável. `BigDecimal` seria
exato, mas aloca objeto a cada operação, e o motor faz isso 25 mil vezes por segundo.

Inteiro em centavos é exato **e** barato. O custo é legibilidade: `15000` significa R$ 150,00, o que exige atenção ao
ler o JSON.

### Maven no lugar de Gradle

O plano dizia Gradle. Trocado por Maven porque o parque Java de bancos brasileiros é majoritariamente Maven — o
avaliador vê isso todo dia. Perde-se build incremental mais rápido e configuração menos verbosa; num projeto de cinco
módulos, nenhum dos dois pesa.

### Nomes de módulo curtos

`motor-de-deteccao` virou `motor`, e assim por diante. Dentro de um repositório chamado
`motor-antifraude`, um módulo chamado `motor` não gera ambiguidade — e some a repetição de
`servico-de` e `consumidor-de` em todo import e caminho de arquivo.

### JDK 21 instalado ao lado do 26

A máquina tinha JDK 26. O projeto foi fixado em 21 por ser LTS — que é o que um banco roda — e porque bibliotecas que
fazem manipulação de bytecode, como Spring e Kafka Streams, costumam demorar a acompanhar JDK recém-lançado. Risco de
perder horas com incompatibilidade obscura, justamente no dia 2, que é o mais arriscado do cronograma.

### Kafka UI adicionado ao ambiente

Não estava previsto. Entrou para tornar visível a distribuição entre as 64 partições e, principalmente, o **lag por
grupo de consumo** — que é a métrica número um do tópico 6 e vai ser a principal ferramenta de diagnóstico a partir do
dia 2.

### Repositório privado

O repositório contém o enunciado do case e citações do PDF. Publicar abertamente exporia material de um processo
seletivo em andamento. Fica privado para a entrega; se virar portfólio depois, bastará remover as citações e a
identificação.

---

## Configurações que carregam decisão de arquitetura

Alguns ajustes do plano já estão no código, com comentário explicando o porquê no próprio arquivo:

| Configuração                 | Onde                        | Decisão que ela materializa                         |
|------------------------------|-----------------------------|-----------------------------------------------------|
| `acks=all`                   | `simulador/application.yml` | Nenhum evento de fraude perdido, ao custo de ~10 ms |
| `enable.idempotence=true`    | idem                        | Defesa nº 1 contra duplicação (tópico 3)            |
| `linger.ms=5`                | idem                        | Lote com prazo máximo: vazão sem custo de latência  |
| `compression.type=lz4`       | idem                        | CPU barato, boa razão de compressão                 |
| 64 partições em `transacoes` | `docker-compose.yml`        | Teto de paralelismo escolhido com folga deliberada  |

**Uma simplificação do ambiente local:** o Kafka sobe com réplica única, porque é um broker só. Em produção seriam três
réplicas com `min.insync.replicas=2` — que é o que de fato sustenta a garantia do `acks=all`. Está comentado no
`docker-compose.yml`.

---

## Pendências e riscos

**Nenhum teste automatizado foi escrito.** As verificações do dia foram manuais, por endpoint e por linha de comando. O
plano prevê `TopologyTestDriver` entrando junto com a topologia no dia 2, mas o simulador em si segue sem cobertura —
dívida a pagar.

**A serialização ainda não foi exercitada de verdade.** O simulador publica JSON pelo serializador padrão do Spring, o
que funcionou de primeira. O ponto de risco real é a serialização do lado do Kafka Streams, com os `Serde` do estado
local — e isso só aparece no dia 2. **O ponto de decisão do cronograma continua valendo:** se consumir mais de meio dia,
avaliar o pivô para Redis, porque depois do dia 3 essa troca fica cara demais.

**Formato de serialização ainda em aberto.** Está JSON, que é legível e não exige contêiner extra. O documento de
arquitetura prevê registro de esquemas com compatibilidade retroativa como ferramenta escolhida — decidir se isso entra
ou se fica como simplificação declarada.

---

## Estado ao fim do dia

Infraestrutura de pé, contrato definido, simulador produzindo carga e o roteamento por partição verificado por dois
caminhos independentes. Nada bloqueia o início do dia 2.
