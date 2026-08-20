# Motor de Detecção de Transações Suspeitas — Decisões de Arquitetura

> Documento construído tópico a tópico a partir dos requisitos não funcionais do case.
> Destino final: `docs/architecture.md` no repositório da solução.
>
> **Todos os seis tópicos concluídos:** 1 — Vazão e latência · 2 — Segurança e conformidade ·
> 3 — Consistência e idempotência · 4 — Integração com sistemas internos ·
> 5 — Extensibilidade de regras · 6 — Monitoramento operacional
>
> O plano de implementação dos 7 dias está no fim do documento.

---

## Contexto

Take-home do processo seletivo do Itaú (Engenharia de Software Backend). Prazo de 7 dias,
apresentação de 1h — 30 minutos de exposição e 30 de aprofundamento técnico.

O enunciado avalia **profundidade técnica, clareza arquitetural e maturidade nos trade-offs**, e
declara explicitamente que *não* espera implementação em nível produtivo: simplificações devem ser
explicadas. Um núcleo pequeno e bem feito, com raciocínio documentado, vale mais que um sistema
grande e raso.

Exigência fácil de esquecer (página 4 do PDF): **declarar explicitamente como e quando a IA foi
usada**. Vira seção do README.

---

## Arquitetura consolidada

### Aplicações

| Aplicação | Consome | Produz |
|---|---|---|
| `motor` | `transacoes` (Kafka) + regras (Mongo) | `alertas` |
| `notificacao` | `alertas` | Push e e-mail ao cliente |
| `auditoria` | `alertas` | Registros no Postgres |
| *`simulador`* | — | *Ferramenta de teste e demo. Fora da arquitetura de produção* |

A equipe antifraude consome `alertas` diretamente — o tópico é tratado como **contrato versionado**.
A proteção contra acoplamento vem do registro de esquemas com compatibilidade retroativa, não de um
serviço de indireção.

### Tópicos e bases

| Peça | Papel |
|---|---|
| Tópico `transacoes` | Entrada. 64 partições, chave = identificador do cliente |
| Tópico `alertas` | Saída do motor. Contrato versionado, consumido por três destinos |
| Mongo | Definição das regras de detecção |
| Redis | Deduplicação de **entrega** no serviço de notificação (apenas hashes) |
| Postgres | Auditoria dos alertas gerados |

### Como cada peça responde ao desafio

| Item do PDF | Quem atende |
|---|---|
| 1. Receber eventos em tempo real | Tópico `transacoes`, alimentado direto pelos sistemas de origem |
| 2. Aplicar lógica de detecção | `motor` |
| 3. Alertas para canais **internos** | Tópico `alertas` |
| 3. Alertas para canais **externos** | `notificacao`, com push e e-mail |
| 4. Continuar funcionando com auxiliar fora | Disjuntor, repetição e fila morta na notificação; degradação no motor |

### Stack

Java 21 · Spring Boot 3 · Kafka Streams · CEL-Java · Resilience4j ·
Micrometer + Prometheus + Grafana · OpenTelemetry ·
JUnit 5 + TopologyTestDriver + Testcontainers + k6 · Docker Compose

JVM com ZGC geracional — pausas abaixo de 1 ms protegem o p99.

### Decisões de produto

- **O sistema detecta e notifica. Não bloqueia.**
- **A severidade decide se o cliente é notificado** e com que prioridade o antifraude recebe.
- **A origem publica direto no Kafka.** Não existe API REST de entrada.
- **A decisão de roteamento viaja no alerta.** O motor grava `notificarCliente` e
  `prioridadeAntifraude`; os consumidores obedecem sem precisar entender regra nenhuma.
- **O motor calcula sozinho o perfil comportamental do cliente.**

---

# Tópico 1 — Vazão e Latência

## 1. A dor

### 1.1 O que o requisito exige

| Métrica | Valor |
|---|---|
| Vazão média | 8.000 transações/segundo |
| Vazão de pico | 25.000 transações/segundo |
| Latência máxima | 500 ms do recebimento do evento até o alerta |

8 mil por segundo são **~690 milhões de transações por dia**. O pico de 25 mil está acima do volume
do PIX brasileiro inteiro nos momentos de maior movimento.

Os 500 ms não são arbitrários: o valor do sistema está em interromper a fraude **enquanto ela
acontece**.

### 1.2 Por que é difícil

> Nenhuma máquina processa 25 mil eventos por segundo com folga, então é preciso dividir o trabalho.
> **E a detecção de fraude não é divisível de forma ingênua.**

Fraude se detecta comparando com o padrão da pessoa. Isso exige **memória do cliente**, e memória
fragmentada entre máquinas quebra a detecção.

**A dor tem duas faces que se contradizem:** o volume exige muitas máquinas; a detecção exige
memória coerente por cliente.

## 2. A estratégia

### 2.1 Desacoplar origem e processamento

A origem publica no tópico e não espera resposta. Se o motor cai, o Kafka retém e nada se perde;
vários consumidores leem em paralelo; e o histórico retido permite reprocessar.

**Fronteira explícita:** este desenho detecta e avisa. Não bloqueia a transação de forma síncrona.

### 2.2 Particionamento por cliente

```
partição = hash(identificadorDoCliente) % 64
```

Como cada partição é lida por **no máximo um consumidor por vez**, todas as transações de um cliente
são processadas pela mesma máquina. A lógica vive inteiramente na publicação, ao informar a chave.

| Ligação | Fixa? | Consequência |
|---|---|---|
| Cliente → partição | **Sim** | Garante memória coerente |
| Partição → máquina | **Não** | O Kafka redistribui quando uma máquina entra ou sai |

**Uma decisão que paga quatro vezes:**

| Requisito | Como o particionamento resolve |
|---|---|
| Vazão | Paralelismo entre instâncias |
| Memória do cliente | Estado local co-locado com quem processa |
| Ordenação (tópico 3) | Uma partição, uma thread, ordem preservada |
| Deduplicação de alerta (tópico 3) | A chave cabe inteira dentro de uma partição |

Esse é o argumento arquitetural mais forte do projeto.

### 2.3 As regras seguem o caminho oposto

| | Transações | Regras |
|---|---|---|
| Origem | Tópico Kafka | Mongo |
| Distribuição | **Divididas** entre instâncias | **Replicadas** — cada instância carrega todas |
| Volume | 25.000/s | ~30 documentos |

Qualquer instância precisa de todas as regras para avaliar os clientes dela. Cliente é dividido;
regra não.

### 2.4 Por que 64 partições

Partição é uma divisão lógica dos dados dentro do Kafka, **não uma máquina**.

| Configuração | Threads | Capacidade bruta | Com transação atômica |
|---|---|---|---|
| 4 máquinas × 4 threads | 16 | ~40.000/s | ~28.000/s |
| **5 máquinas × 4 threads** | **20** | **~50.000/s** | **~35.000/s** ← escolhido |
| 8 máquinas × 4 threads | 32 | ~80.000/s | ~56.000/s |
| 16 máquinas × 4 threads | 64 | ~160.000/s | ~112.000/s ← teto |
| 20 máquinas × 4 threads | 80 | — | 16 threads ociosas |

Com 20 threads, as 64 partições **não dividem por igual** — algumas ficam com 3, outras com 4.

**Por que 64 e não 20?** Migrar depois muda o divisor: `hash % 20` ≠ `hash % 64`, então **todo
cliente muda de partição e a memória dele fica para trás**.

### 2.5 A conta de capacidade

| Operação | Tempo de CPU |
|---|---|
| Desserializar | ~50–100 µs |
| Ler o estado do cliente | ~10–50 µs |
| Avaliar ~20 regras | ~100–400 µs |
| Atualizar o estado | ~50 µs |
| Publicar o alerta (quando há) | ~50 µs |
| **Total** | **~400 µs** |

≈ **2.500 transações/s por thread**. Logo 5 máquinas × 4 threads ≈ 50.000/s brutos, ~35.000/s
descontando a transação atômica — folga de ~1,4x sobre o pico.

**Estimativa de ordem de grandeza, não medição.**

### 2.6 O orçamento de latência

| Etapa | Custo estimado (p99) |
|---|---|
| Publicação no tópico pela origem (`acks=all`) | 5–15 ms |
| Busca pelo motor | 10–30 ms |
| Leitura do estado + avaliação das regras | 1–5 ms |
| Publicação do alerta | 5–15 ms |
| Busca pelo serviço de notificação | 10–30 ms |
| Deduplicação de entrega no Redis | 1–3 ms |
| **Total** | **~80 ms** |

**A descoberta mais útil deste tópico:** o sistema *pensa* em ~5 ms e o dado *anda* em ~75 ms.

Não há linha de enriquecimento porque **o motor não busca dado nenhum** — o perfil comportamental é
calculado por ele mesmo (tópico 4). A linha de avaliação das regras é o que limita a quantidade de
regras suportadas (tópico 5). Sobram ~420 ms de folga.

### 2.7 Estratégia de capacidade

**Provisionar para o pico**, com teto alto como rede de segurança: 5 máquinas rodando, capacidade
máxima configurada em 16.

**O sinal para escalar é o tamanho da fila, nunca o uso de CPU.**

**Por que não autoscaling agressivo:** com estado local, subir uma máquina dispara redistribuição de
partições e reconstrução de estado.

### 2.8 Ajustes finos e o que cada um custa

| Ajuste | Ganho | Custo |
|---|---|---|
| Lote com prazo máximo (`linger.ms=5`) | Quase todo o ganho de vazão do agrupamento | ~5 ms |
| `acks=all` com 2 réplicas em sincronia | Nenhum evento de fraude perdido | ~10 ms |
| Compressão `lz4` | Menos rede e disco | CPU baixo |
| ZGC geracional | Pausas de coleta abaixo de 1 ms | Um pouco mais de memória |

### 2.9 Medição: p99, nunca média

A 25 mil por segundo, o 1% do p99 são **250 transações por segundo** com experiência ruim. O
diferencial mais barato do tópico é rodar teste de carga real e trocar a estimativa por medição.

### 2.10 Memória por máquina

Consequência das janelas decididas no tópico 4:

| Estrutura | Por máquina |
|---|---|
| Janela de 5 minutos | ~24 MB |
| Janela de 60 minutos | ~120 MB |
| Janela de 30 dias (perfil comportamental) | ~480 MB |
| Último valor (cidade, horário) | ~120 MB |
| Registro de transações já vistas (1h) | ~180 MB |
| **Total** | **~920 MB** |
| **Com a cópia morna** | **~1,9 GB** |

Somando espaço da JVM e a parte fora do heap, **16 GB por máquina é o número confortável.** A janela
de 30 dias sozinha custa metade da memória — e é ela que compra o threshold dinâmico.

## 3. Ferramentas escolhidas e seus trade-offs

### Apache Kafka
**Por quê:** única opção que entrega simultaneamente aguentar o volume, **guardar o histórico** e
particionar por chave de forma nativa. Padrão de fato em instituições financeiras brasileiras.
**Trade-off aceito:** complexo de operar. Mitigado com versão gerenciada em produção.

### Kafka Streams

**Por quê:** memória local por partição com backup automático e atomicidade entre leitura, atualização de memória e
escrita. **Trade-offs aceitos:** redistribuição exige reconstruir memória; máquinas deixam de ser
descartáveis; teto rígido nas 64 partições; curva de aprendizado na serialização. **Nota de mecanismo:** a memória
sobrevive a falhas pelo *changelog*. O disco persistente é
otimização de tempo de recuperação, **não** requisito de correção.

**Duas correções que a implementação impôs a este item:**

*As janelas prontas não se aplicam ao nosso caso.* Elas resolvem agregação de janela única. Como uma regra precisa
consultar várias janelas ao mesmo tempo, mais o último valor, mais o registro de deduplicação, optamos por um **registro
único por cliente** — o que descarta o janelamento pronto e mantém apenas a memória gerenciada e o changelog.

*Existe amplificação de escrita.* O `KeyValueStore` grava **o valor inteiro** no changelog a cada
`put()`, e fazemos um `put` por transação. O custo de gravação é proporcional ao tamanho da memória, não ao tamanho da
mudança — e é aqui que as agregações de janela levariam vantagem, porque gravam só o agregado. Mitigado pelo teto de
tamanho descrito nas limitações.

### Java 21 + Spring Boot 3
**Por quê:** ecossistema majoritário do Itaú. Integração nativa com Kafka Streams.
**Trade-off aceito:** pausas de coleta, mitigadas pelo ZGC geracional.

### k6 (ou Gatling)
**Por quê:** transforma estimativa de capacidade em número medido.
**Trade-off aceito:** medição local não reproduz produção.

## 4. Ferramentas e abordagens descartadas

### API REST de entrada
**Adiciona uma aplicação inteira e um salto de rede no orçamento**, quando a origem já fala Kafka.
Para demo e teste de carga existe um **simulador**, marcado como ferramenta de teste.

### RabbitMQ
**Não guarda bem o histórico** — desenhada para distribuir tarefas, não para reter o log. Elimina o
reprocessamento, base do modo sombra.

### Apache Pulsar
**Risco desproporcional:** comunidade menor, muito menos gente sabe operar, e nenhum ganho concreto.

### AWS Kinesis / Google Pub/Sub
**Acoplamento a fornecedor e custo no volume alto.**

### Chamada HTTP síncrona entre origem e motor
**Sem retenção, sem reprocessamento**, e a falha do consumidor vira problema do produtor.

### Kafka comum + Redis para o estado das janelas
**O concorrente real, e a decisão mais disputada do projeto.** Descartada por três razões, nenhuma
delas latência:

1. **Corretude sob concorrência.** Duas transações do mesmo cliente processadas simultaneamente por
   máquinas diferentes: ambas leem "2", ambas gravam "3", e uma some.
2. **Acoplamento operacional.** Redis no caminho urgente obriga a escolher, quando degrada, entre
   ficar cego para fraude ou parar de processar.
3. **Reprocessamento.** Estado derivado do log é reconstruível; estado mutável externo, não.

**O que foi descartado é o Redis como memória de janelas, não o Redis** — ele permanece para
deduplicação de entrega (tópico 3).

### Camada de abstração sobre o acesso ao estado
**Abstração inútil:** Kafka Streams é declarativo e Redis é imperativo. Uma interface cobrindo os
dois operaria no menor denominador comum, **impedindo o uso das janelas prontas que motivam a
escolha** — e o teste de carga mediria um sistema que não é o que se pretende defender.

### Go
**Ferramental de processamento com estado é fraco:** seria necessário construir à mão janelamento,
backup e recuperação.

### Node.js / TypeScript
**A mais difícil de defender** no requisito de vazão com estado por cliente.

## 5. Limitações conhecidas

### Partição quente — medida

Um cliente de altíssimo volume sobrecarrega uma partição e **não pode ser dividido**, porque a co-locação é o que faz o
desenho funcionar.

**Onde quebra, medido na implementação:** cada transação acrescenta ~155 bytes à memória do cliente (85 do evento, 70 do
identificador de deduplicação). Contra o limite de 1 MB por mensagem do Kafka:

```
1.048.576 ÷ 155 ≈ 6.700 transações na mesma hora
6.700 ÷ 3.600 s  ≈ 2 transações por segundo no mesmo cliente
```

**Como quebrava:** não degradava devagar. Estourava com `RecordTooLargeException` e a partição parava de processar.

**Mitigação implementada:** teto de tamanho além do corte por idade — 200 eventos e 500 identificadores por cliente. Se
um cliente já tem 200 eventos na janela, toda regra de contagem já disparou; guardar o 201º não muda decisão nenhuma. O
cliente quente passa a **degradar** em vez de derrubar a partição.

**E virou sinal:** a métrica `antifraude.memoria.no.limite` conta transações de clientes que atingiram o teto. É, na
prática, **o detector de partição quente** — se sair de zero em produção, há um cliente concentrando volume.

**O que continua:** a amplificação de escrita está limitada, não eliminada. Cada transação de um cliente no teto ainda
grava ~52 KB no changelog. A solução estrutural seria trocar a lista de eventos por contadores por minuto, ao custo dos
valores individuais que a regra de teste de cartão precisa.

### Demais limitações

- **Teto de 64 partições.** Ultrapassar exige reparticionamento, destrutivo para a memória local.
- **Custo do rebalanceamento.** Medido de forma indireta: reconstruir a memória de 520 mil transações a partir do
  changelog levou ~90 segundos no ambiente local.
- **Os números de capacidade são estimativa** até o teste de carga.

## 6. Pendente de validação

- [ ] Medir o custo real de CPU por transação e recalibrar o número de máquinas
- [ ] Medir p99 de ponta a ponta sob carga de pico sustentada
- [ ] Identificar o gargalo real que aparece primeiro — a amplificação de escrita é candidata
- [ ] Validar o tempo de reconstrução da memória após queda de uma máquina, com e sem cópia morna

---
---

# Tópico 2 — Segurança e Conformidade

## 1. A dor

O enunciado pede *"segurança de ponta a ponta para cada transação, incluindo criptografia em
trânsito e em repouso, autenticação entre serviços e conformidade com LGPD"*.

**1. Vazamento externo.** Número de cartão é dinheiro direto. O padrão de consumo é pior a longo
prazo: revela deslocamento, hábitos, saúde, religião, ausência de casa.

**2. Abuso interno.** Em instituição financeira esse risco supera o externo. É o caso mais comum de
sanção interna no setor.

**3. Conformidade.** LGPD (multa até 2% do faturamento, teto de R$ 50 milhões) e BACEN.

**4. Decisão automatizada — mitigado pela decisão de escopo.** Com o bloqueio removido, o sistema
apenas produz um sinal. A trilha de auditoria permanece, mas por rastreabilidade.

**5. O plano de controle de regras é uma arma.** Quem publica regra altera o comportamento
antifraude do banco sem escrever código. Uma regra maliciosa não precisa ser evidente: basta uma
condição que exclua silenciosamente uma conta.

## 2. A estratégia

### 2.1 Minimização antes de criptografia

**O controle de melhor relação custo-benefício é não ter o dado.** O evento carrega um **token**,
mais o BIN e os quatro últimos dígitos. A chave de particionamento é um **identificador interno**,
nunca o CPF. A tokenização acontece **na origem**.

### 2.2 Duas camadas de autenticação

| Camada | O que prova |
|---|---|
| mTLS | **Qual máquina** está falando |
| Token OAuth2 | **Qual aplicação** e **o que ela pode fazer** |

**Sem API REST, o OAuth2 não desaparece — muda de lugar.** O Kafka aceita autenticação por token no
próprio broker (`SASL/OAUTHBEARER`).

### 2.3 Permissões por tópico, menor privilégio

| Componente | Permissão |
|---|---|
| Sistema de origem | Escreve **apenas** em `transacoes` |
| `motor` | Lê `transacoes`; escreve **apenas** em `alertas` |
| `notificacao` | Lê `alertas` |
| `auditoria` | Lê `alertas` |
| Equipe antifraude | Lê `alertas` |
| Plano de controle | Único que escreve as regras no Mongo; o motor apenas lê |

### 2.4 Criptografia nos dois momentos

**Em trânsito:** TLS 1.3 em todos os saltos, **inclusive dentro do cluster**.

**Em repouso:** disco cifrado **não é suficiente** — enquanto o sistema está no ar, o sistema
operacional descriptografa de forma transparente.

A defesa real é **criptografia em nível de campo com envelope**: gera-se uma chave nova por registro,
cifra-se o dado com ela, cifra-se a chave com a chave-mestra que vive dentro do KMS.

**Nota importante:** o KMS **não cifra o seu dado**. Sua aplicação cifra o dado com a chave pequena;
o KMS cifra a chave pequena.

**Onde se aplica:** apenas na **base de auditoria**.

### 2.5 Mascaramento de log por lista de permitidos

**Lista de proibidos sempre esquece o campo novo; lista de permitidos falha para o lado seguro.**

### 2.6 LGPD — os quatro pontos que decidem

**Pseudonimização não é anonimização.** O dado continua pessoal e continua sob a lei.

**A base legal não é consentimento** — seria revogável, e um cliente não pode desligar a prevenção a
fraude do banco. As bases corretas são **obrigação legal e regulatória** e **legítimo interesse**.

**Art. 20 — aliviado pela remoção do bloqueio.** Permanece a exigência de registrar qual versão de
qual regra disparou e com quais valores.

**Retenção tem duas forças opostas.** `transacoes` com 7 dias; auditoria em base própria pelo prazo
regulatório.

### 2.7 Auditoria

**O que guarda:** apenas os alertas gerados.

| O que gravar | Por dia | Em 5 anos |
|---|---|---|
| Toda transação avaliada | ~691 milhões de linhas | Mais de um petabyte |
| Apenas os alertas gerados | ~3,5 milhões de linhas | ~9 TB |

Para *"por que não pegamos essa fraude?"*, a resposta é **reprocessar** o fluxo retido no Kafka.

**Quem grava:** um consumidor do tópico, **não o motor**.

**O ponto que vale na entrevista:** a base não guarda nada que o tópico já não tenha. O que falta ao
Kafka é **consulta**. O banco é **índice de consulta**, reconstruível por reprocessamento.

**E é o ativo mais sensível do sistema.** Exige criptografia de campo, acesso estrito e **auditoria
da auditoria**.

### 2.8 Governança das regras via Git

| Necessidade | Quem resolve, sem escrever código |
|---|---|
| Autenticação e autorização | Permissões do repositório |
| Aprovação de duas pessoas | Proteção de branch, com o autor bloqueado de aprovar o próprio |
| Versão imutável | Cada commit é imutável — a hash **é** a versão |
| Trilha de auditoria | `git log`: quem, quando, o quê |
| **E por quê** | Descrição e discussão do pull request |
| Validação e testes | Esteira de CI |
| Reverter regra ruim | `git revert`, que também passa por revisão |

O que se constrói: um script de ~50 linhas que lê os YAMLs e escreve no Mongo, e um passo de esteira
que valida sintaxe e roda os testes das regras.

**Dois ganhos que só o Git dá:** a regra e seus casos de teste vivem no mesmo commit; e o modo
sombra encaixaria na esteira, com a CI rodando a regra nova contra uma amostra e comentando no pull
request qual seria a taxa de disparo — *este último ficou documentado, não implementado (tópico 5)*.

**O contra-argumento forte e a saída.** Às 3h da manhã, abrir um pull request é lento demais:

| Ação | Caminho | Velocidade |
|---|---|---|
| **Mudar a definição** da regra | Git, com revisão | Minutos a horas |
| **Desligar** uma regra | Endpoint operacional que vira o campo `habilitada` | Segundos |

**Propagação:** o motor consulta o Mongo a cada 30 segundos, perguntando apenas por regras alteradas
desde a última checagem. Intervalos longos foram descartados por inviabilizarem o interruptor de
emergência.

### 2.9 Onde cada controle atua

| Trecho | Controle |
|---|---|
| Origem → Kafka | mTLS + OAuth2 via `SASL/OAUTHBEARER`; escrita restrita a `transacoes` |
| Tópico `transacoes` | TLS 1.3, disco cifrado, retenção de 7 dias |
| `motor` | Log mascarado por lista de permitidos; **disco do estado local cifrado** |
| Tópico `alertas` | Carrega versão da regra e valores de entrada |
| `notificacao` | Segredos vindos do cofre; conteúdo mínimo na notificação |
| Base de auditoria | Criptografia de campo com KMS, acesso estrito, auditoria da auditoria |

Dois pontos fáceis de esquecer: **o estado local do motor é gravado em disco e contém histórico de
clientes**; e **a notificação ao cliente é superfície de ataque**, pois aparece na tela bloqueada.

**O Redis não guarda dado pessoal** — apenas hashes.

## 3. Ferramentas escolhidas e seus trade-offs

### mTLS com rotação automática (cert-manager / SPIFFE)
**Por quê:** identidade forte por máquina, com certificados de vida curta.
**Trade-off aceito:** exige montar e operar infraestrutura de certificados.

### OAuth2 via `SASL/OAUTHBEARER` no broker
**Por quê:** carrega escopos e validade, sem exigir uma API intermediária.
**Trade-off aceito:** mais um serviço (emissor de tokens) para operar.

### AWS KMS ou HashiCorp Vault (Transit)
**Por quê:** a chave-mestra nunca sai do cofre e todo uso é auditado separadamente.
**Trade-off aceito:** cada chamada custa 10–50 ms, mitigado mantendo a chave aberta em memória.
**Como ficou restrito à auditoria, que é assíncrona, esse custo saiu do caminho quente.**

### AWS Secrets Manager ou HashiCorp Vault
**Por quê:** segredo buscado em tempo de execução, com rotação e auditoria.
**Trade-off aceito:** dependência adicional na inicialização.

### Listas de permissão do Kafka (ACLs)
**Por quê:** menor privilégio por tópico.
**Trade-off aceito:** erro de ACL só aparece em tempo de execução.

### Git + esteira de CI como plano de controle das regras
**Por quê:** entrega versionamento, aprovação de duas pessoas, trilha com justificativa, validação,
testes e reversão **sem construir aplicação administrativa**.
**Trade-off aceito:** analista de fraude tipicamente não usa Git. Mitigado pelo endpoint de
desligamento e pela interface amigável documentada como evolução.

### Postgres dedicado para auditoria
**Por quê:** consulta estruturada, particionamento mensal maduro.
**Trade-off aceito:** mais uma tecnologia na stack. Partições antigas migram para armazenamento frio.

## 4. Ferramentas e abordagens descartadas

### API de gerenciamento de regras
**Custo desproporcional:** exigiria endpoints, autorização, máquina de estados, bloqueio de
auto-aprovação, versionamento, trilha, validação, testes, reversão e **uma interface de usuário**.
Realisticamente 3 a 5 dias, o que come um case de 7.

### Criptografia de campo no evento de transação
**Campo a campo, nenhum precisa:**

| Campo | Precisa? |
|---|---|
| `transactionId` | Não é dado pessoal |
| identificador do cliente | **Não pode ser cifrado** — é a chave de partição |
| token do cartão | Não precisa — **o token já é a proteção** |
| valor, estabelecimento, cidade, canal | Metadados |

### Chave de API simples
**Senha eterna:** não expira, não carrega escopo, vaza em log e em repositório.

### JWT sem mTLS
**Um token roubado basta para se passar pelo serviço de qualquer lugar.**

### Criptografia apenas de disco
**Não protege contra o medo nº 2.** Permanece como camada complementar.

### Consentimento como base legal
**Enquadramento juridicamente errado:** consentimento é revogável.

### Anonimização
**Incompatível com a função:** a detecção precisa identificar o cliente.

### Lista de proibidos para mascaramento de log
**Falha para o lado inseguro.**

### Service mesh (Istio, Linkerd)
**Desproporção:** traz um plano de controle inteiro para um case de 7 dias.

### pgcrypto ou criptografia nativa do banco
**A chave acaba perto do banco**, enfraquecendo contra o abuso interno.

### Biblioteca local com chave em arquivo de configuração
**Aparência de proteção sem a propriedade que importa.**

## 5. Ordem de implementação

| Ordem | Item | Esforço | Nota |
|---|---|---|---|
| 1 | Tokenização (no simulador, representando a origem) | ~2h | Maior ganho; decisão de desenho |
| 2 | Trilha de auditoria dos alertas | ~3h | Responde "por que este cliente foi sinalizado" |
| 3 | Mascaramento de log por lista de permitidos | ~2h | Fecha o vazamento mais comum |
| 4 | Validação de entrada e ACLs de tópico | ~2h | Barato e visível |
| 5 | Endpoint de desligamento de regra, **com cascata** | ~2h | Interruptor de emergência (ver tópico 5) |
| 6 | mTLS no Docker Compose | ~4h | *Se sobrar tempo.* Infra pura |
| 7 | KMS/Vault na base de auditoria | ~4h | *Se sobrar tempo.* Não muda contrato |

Parar no item 5 já é entrega defensável, desde que declarada.

## 6. Limitações conhecidas

- **mTLS e KMS provavelmente ficarão desenhados, não implementados.**
- **A tokenização é simulada.**
- **A chave em memória por alguns minutos** é janela de exposição aceita conscientemente.
- **A aprovação de duas pessoas é processo, não código.** O sistema confia no que chegou ao Mongo.
- **Adoção do Git pelo time de fraude é barreira real.**

## 7. Pendente de validação

- [ ] Medir o custo do mTLS no estabelecimento de conexão sob carga
- [ ] Validar, com teste automatizado, que nenhum campo sensível escapa para o log
- [ ] Confirmar o tempo de vida do cache de chave
- [ ] Revisar o enquadramento de base legal com quem entende de jurídico
- [ ] Estimar a taxa real de alerta, que dimensiona a base de auditoria

---
---

# Tópico 3 — Consistência e Idempotência

## 1. A dor

*"Como garantir que cada evento de transação seja processado uma única vez (idempotência) e que
alertas não sejam duplicados, mesmo em cenários de rede instável ou falhas temporárias."*

A última parte define o escopo: não é como evitar duplicatas — é o que fazer **quando** elas
acontecem.

### 1.1 São dois problemas, não um

| | **Idempotência** | **Consistência** |
|---|---|---|
| A pergunta | "Isso aconteceu duas vezes?" | "Isso aconteceu na ordem certa?" |
| A causa | A rede **reenviou** | A rede **atrasou**, ou uma máquina caiu |
| O sintoma | Contagem inflada, alerta repetido | Contagem errada, ordem trocada, estado incompleto |

### 1.2 Por que a duplicata é inevitável

Quando um sistema manda uma mensagem e **não recebe resposta**, ele não consegue distinguir entre
"não chegou" e "chegou e a resposta se perdeu". Os dois mundos são idênticos de fora e exigem ações
opostas.

> **"Exatamente uma vez" não existe como garantia de entrega em rede.** O que existe é *efeito*
> exatamente-uma-vez: entrega ao-menos-uma-vez **mais** deduplicação em quem recebe.

Para fraude, perder evento é inaceitável. Então escolhemos sempre reenviar e assumimos a
responsabilidade de tratar duplicata. **É decisão consciente, não padrão herdado.**

### 1.3 Onde dói a idempotência

As três operações principais **não são naturalmente idempotentes**: contar uma transação na janela,
enviar uma notificação e gravar um registro de auditoria.

**Exemplo 1 — alerta falso fabricado pela infraestrutura.** Duas compras legítimas às 14h00 e 14h02.
Um problema de rede faz a origem reenviar a das 14h00. A contagem marca 3 em vez de 2. Na compra
seguinte, marca 4 — e a regra "mais de 3 em 5 minutos" dispara.

O agravante: **de fora isso é idêntico a fraude real.** Olham a taxa de falso positivo, concluem que
a regra está apertada e **afrouxam** — o que faz fraude real passar.

**Exemplo 2 — o canal de aviso é queimado.** O serviço de notificação envia o push e morre antes de
registrar. O cliente recebe cinco notificações às 3h e desliga as notificações do banco. No mês
seguinte, uma fraude real chega num canal desligado. **O dano não é o incômodo — é queimar o canal
necessário para o evento real.**

**Exemplo 3 — a duplicata cega quem deveria vigiar.** O consumidor de auditoria grava o mesmo alerta
três vezes. No painel do plantão a regra aparece disparando três vezes acima do normal, e o
plantonista **desliga uma regra que funcionava**.

### 1.4 Onde dói a consistência

**Frente A — ordem das transações.** "Comprou em São Paulo e 10 minutos depois no Recife" só faz
sentido se o sistema souber qual veio primeiro.

**Frente B — qual relógio.** Quatro compras em três minutos — fraude real — mas uma fica presa na
rede e chega dois minutos depois. Usando o horário de processamento, o sistema "vê" as compras
espalhadas por cinco minutos, e a regra **não dispara. A fraude passa.**

**Frente C — estado depois de uma queda.** Se a máquina substituta começasse a processar **antes**
de terminar a reconstrução, veria a contagem zerada.

## 2. A estratégia

### 2.1 As cinco defesas contra duplicação

| # | Defesa | Contra qual causa | Onde |
|---|---|---|---|
| 1 | Produtor idempotente | Repetição do próprio produtor | Publicação pela origem |
| 2 | Registro de transações já vistas | Reenvio pela origem | Entrada do motor |
| 3 | Transação atômica | Reprocessamento e rebalanceamento | Processamento |
| 4 | Impressão digital + Redis | Reentrega ao consumidor de notificação | Entrega externa |
| 5 | Chave única no banco | Reprocessamento da auditoria | Gravação |

**As três primeiras estão dentro da garantia do Kafka; as duas últimas, fora dela.**

### 2.2 O registro de transações já vistas

Toda transação vem com identificador único da origem; o motor anota e consulta antes de processar a
seguinte. Identificadores mais velhos que 1 hora somem sozinhos. Mora na mesma memória local das
janelas, com o mesmo changelog. **Não é peça nova nem banco novo.**

| Janela | Memória por instância |
|---|---|
| 15 minutos | ~45 MB |
| **1 hora** | **~180 MB** ← escolhido |
| 24 horas | ~4,3 GB |

### 2.3 A transação atômica não é redundante — ela é o que faz o registro funcionar

Sem ela: lê a mensagem → consulta o registro → **anota** → atualiza → publica → confirma a leitura.
Se cair antes de confirmar, **a anotação sobreviveu?** Sem atomicidade, não há garantia.

**A defesa nº 3 é pré-requisito da nº 2, não alternativa a ela.**

### 2.4 A deduplicação de alerta acontece em duas camadas

| Onde | Contra o quê | Como |
|---|---|---|
| **Motor** | O mesmo alerta *lógico* ser publicado duas vezes | Memória local |
| **Serviço de notificação** | O mesmo alerta *publicado* ser entregue duas vezes | Redis |

**No motor** a chave é `regra + janela`, guardada dentro da memória do cliente. Isso só é possível porque todas as
transações de um cliente caem na mesma partição.

**Vale distinguir dois problemas que o nome "deduplicação" confundia:**

|                         | O que é                                        | Como é tratado                           |
|-------------------------|------------------------------------------------|------------------------------------------|
| **Reenvio**             | A mesma mensagem chega duas vezes              | `jaViu(transacaoId)` na entrada do motor |
| **Repetição de alerta** | Transações **diferentes**, mesmo acontecimento | Controle por janela, descrito abaixo     |

O segundo só apareceu quando o sistema entrou em operação, e é o mais grave dos dois.

**O sintoma medido.** Uma regra baseada em janela continua verdadeira enquanto a janela não esvazia, e é reavaliada a
cada transação. Num ataque de 30 transações, a `velocidade-alta` publicou 16 alertas e a `soma-na-hora` publicou 11 —
todos descrevendo o mesmo acontecimento.

O caso que expôs o absurdo: a regra `soma-na-hora` não olha o valor da transação atual, só o acumulado. Depois que a
soma cruza o limite, **um café de R$ 5 gera um alerta de severidade ALTA**. Isso foi reproduzido e medido.

**Por que importa mais do que parece.** Um falso positivo que repete 16 vezes é 16 vezes pior que um que dispara uma
vez. E o gatilho pode ser inteiramente legítimo — quem compra notebook, monitor e teclado em cinco minutos aciona a
`velocidade-alta` em cada compra. A repetição multiplica o dano de todo erro do motor.

**A regra adotada:**

| Tipo de regra                  | Comportamento                  | Por quê                                            |
|--------------------------------|--------------------------------|----------------------------------------------------|
| Com janela (`5m`, `60m`)       | Um alerta por janela           | O acontecimento é um só                            |
| Sem janela (`valor-absoluto`)  | Alerta em toda ocorrência      | Cada compra grande é um evento próprio a confirmar |
| Severidade sobe (MÉDIA → ALTA) | Publica mesmo já tendo avisado | Escalada é informação nova para o antifraude       |

A memória do cliente guarda `alertasEmitidos`: uma entrada por `regra + janela`, com horário e severidade, podada junto
com os eventos. Nenhuma peça nova, nenhum Redis.

**Interação com o congelamento da linha de base.** Um cliente legítimo que dispara a regra sofre duas coisas ao mesmo
tempo: recebe vários alertas *e* tem o ticket médio congelado, porque transação alertada não alimenta a base. O controle
de repetição resolve metade. A outra metade depende da resposta *"fui eu"* do cliente, que continua documentada e não
implementada.

### 2.5 A ordem da deduplicação na entrega externa

| Ordem | Se cair no meio |
|---|---|
| Enviar, depois registrar | Envia de novo na reentrega. **Duplicata** |
| Registrar com validade longa, depois enviar | Nunca envia. **Notificação perdida** |

**Perder um aviso de fraude é pior que mandar dois.** O desenho adotado:

1. Chega o alerta
2. Tenta gravar a chave no Redis com **"só grave se ainda não existir"**, validade de 60 segundos
3. Envia o push ou e-mail
4. Estende a validade da chave para 24 horas

A validade curta existe para que uma queda entre gravar e enviar **se conserte sozinha**.

**O que o Redis previne, concretamente:**

| Causa de envio duplicado | Frequência | Pega? |
|---|---|---|
| Rebalanceamento do consumidor (**todo deploy**) | Rotineira | **Sim** |
| Repetição após o provedor retornar erro que deu certo | Comum | **Sim** |
| Reprocessamento do tópico | Ocasional | **Sim** |
| Alerta duplicado chegando no tópico | Rara | **Sim** |
| Queda exatamente entre enviar e confirmar | Rara | **Não** |

### 2.6 Consistência: as três frentes

**Ordem** — resolvida pelo particionamento por cliente, já pago no tópico 1.

**Relógio** — as janelas usam o **horário do evento**, nunca o de processamento.

**Estado após queda** — o Kafka Streams espera a reconstrução terminar antes de processar. O sistema
escolheu **estar correto e não estar disponível**. Mitigado pela cópia morna.

### 2.7 A tensão entre esperar o atrasado e os 500 ms

1. O motor **decide na hora** → cumpre os 500 ms
2. A janela **continua aberta** por 60 segundos para receber atrasados
3. Se um atrasado mudar a conclusão, o motor **emite uma avaliação corrigida**

**E as duas metades do tópico se encontram aqui:** essa correção geraria um alerta duplicado, que a
impressão digital impede de chegar ao cliente — mas deixa passar para o antifraude quando representa
escalada.

**Os 60 segundos são ponto de partida, não medição.**

## 3. Ferramentas escolhidas e seus trade-offs

### Produtor idempotente do Kafka
**Por quê:** elimina a repetição gerada pelo próprio produtor com uma linha de configuração.
**Limite:** vale apenas dentro de uma sessão do produtor.

### Estado local do Kafka Streams para o registro e a impressão digital
**Por quê:** os dados cabem dentro de uma partição, então não precisam de armazenamento externo.
**Trade-off aceito:** ~180 MB por instância, dobrados pela cópia morna.

### Transação atômica (`exactly_once_v2`)
**Por quê:** é o que torna o registro de já vistos confiável.
**Trade-off aceito:** custa de 10% a 30% de vazão. Compensado subindo de 4 para 5 máquinas.

### Cópia morna do estado (uma réplica)
**Por quê:** reduz a janela de degradação de minutos para segundos. O ganho principal é o **deploy**,
que é evento agendado. E a janela de 30 dias do tópico 4 torna a reconstrução lenta o bastante para
que a réplica deixe de ser conforto e vire necessidade.
**Trade-off aceito:** dobra memória e disco de estado.

### Redis no serviço de notificação
**Por quê:** deduplicação de entrega na fronteira externa. Mantém a correção **dentro do nosso
sistema**, sem delegá-la ao contrato de um terceiro.
**Trade-offs aceitos:** mais uma peça; 1–3 ms; exige alta disponibilidade. **Se o Redis cair, envia
assim mesmo** — melhor duplicar que silenciar — e isso precisa de métrica.

### Chave única no Postgres da auditoria
**Trade-off:** nenhum. É a defesa mais barata do sistema.

### Horário do evento com tolerância de 60 segundos
**Trade-off aceito:** tolerância menor descarta mais eventos, e descartar significa **contar a
menos**, o que deixa fraude passar.

## 4. Ferramentas e abordagens descartadas

### Filtro probabilístico (Bloom, Cuckoo)
**O erro vai na direção errada:** o filtro afirma "já vi" para algo que nunca viu, e o sistema
**descartaria uma transação legítima**, perdendo um sinal de fraude.

### Chave de idempotência do provedor de push e e-mail
Seria grátis. **Descartada por decisão de propriedade:** delegaria a correção do nosso sistema ao
contrato de um terceiro. **Fica registrada como complemento possível.**

### Confiar apenas no exactly-once do Kafka
**A resposta mais comum e a mais incompleta.** A garantia acaba na fronteira do sistema.

### Registrar a entrega antes de enviar, com validade longa
**Troca duplicata por perda.**

### Enviar primeiro e registrar depois
**Duplicaria em toda reentrega** — inclusive no rebalanceamento de todo deploy.

### Trava distribuída
**O particionamento já elimina o problema.**

### Horário de processamento em vez do horário do evento
**Produz janelas erradas quando a rede atrasa** — fraude real espalhada artificialmente no tempo.

### Tolerância proporcional ao tamanho da janela
**O atraso da rede não depende de qual janela está sendo calculada.**

### Registro de já vistos com 24 horas
**~4,3 GB por instância** para cobrir um cenário raro que o reprocessamento resolve melhor.

## 5. Limitações conhecidas

- **A fresta entre enviar e confirmar é irredutível.**
- **Durante uma queda do Redis, notificações duplicadas passam.**
- **A transação atômica custa vazão e uma máquina a mais.**
- **A cópia morna dobra o armazenamento de estado.**
- **A tolerância de 60 segundos é estimativa.**
- **Eventos muito atrasados são descartados.**
- **A auditoria é eventualmente consistente com o tópico.** **O tópico é a prova**, mesmo antes de o
  banco alcançar.

## 6. Pendente de validação

- [ ] Medir a distribuição de atraso e recalibrar a tolerância para o p99,9
- [ ] Medir o custo real da transação atômica na vazão
- [ ] Validar o tempo de reconstrução do estado com e sem cópia morna
- [ ] Testar rebalanceamento forçado, verificando que nenhum alerta duplicado chega ao cliente
- [ ] Verificar a taxa de eventos descartados por atraso sob carga de pico

---
---

# Tópico 4 — Integração com Sistemas Internos

## 1. A dor

*"A arquitetura deve oferecer mecanismos de integração robustos e flexíveis com os sistemas internos
do banco (canais de notificação, bases de dados de clientes, sistemas antifraude existentes)."*

Este item conversa direto com o item 4 do desafio. **Integrar bem é o que sustenta a resiliência.**

### Dor 1 — a lentidão do outro vira a sua

Se o motor chamar um serviço que normalmente responde em 20 ms mas hoje responde em 2 segundos, a
thread fica **parada esperando**. A 25 mil transações por segundo, as threads se esgotam e o motor
trava.

**Uma dependência lenta é pior que uma dependência morta.** Morta, a chamada falharia em
milissegundos e você seguiria em frente. Lenta, ela consome os seus recursos sem entregar nada.

### Dor 2 — o modelo do legado contamina o seu

Sistemas legados de banco têm modelos hostis: campos posicionais, valores em centavos sem separador,
códigos numéricos, datas como texto. Em seis meses as regras estão cheias de `if (campo5 == "07")`.

### Dor 3 — quem consome você te congela

Renomear um campo de `alertas` e fazer deploy quebra três times às 2h da manhã.

## 2. A estratégia

### 2.1 O princípio

> **Nenhuma integração síncrona no caminho crítico.**

**Sobra exatamente uma integração síncrona no sistema inteiro** — a chamada ao provedor de push e
e-mail. É lá, e só lá, que mora o disjuntor.

| Sistema | Como integramos | Por quê |
|---|---|---|
| Perfil do cliente | **O motor calcula sozinho** | Ver 2.2 |
| Sistema antifraude | Consome `alertas` | Se cair, o tópico retém |
| Mongo (regras) | Consulta a cada 30s, fora do caminho quente | Não bloqueia processamento |
| Postgres (auditoria) | Consumidor separado | Não bloqueia o motor |
| **Provedor de push e e-mail** | **Chamada síncrona com disjuntor** | **Única integração síncrona** |

### 2.2 O perfil comportamental o motor calcula sozinho

| Tipo | Exemplos | De onde vem |
|---|---|---|
| **Comportamental** | Ticket médio, cidades habituais, horários de uso | **Das próprias transações** |
| **Cadastral** | Limite do cartão, cidade de residência, aviso de viagem | De outro sistema |

**O que cada regra realmente precisa:**

| Regra | Do que precisa | De onde vem |
|---|---|---|
| Muitas transações em 5 minutos | Contagem recente | **O próprio motor** |
| Valor muito acima do normal dele | Ticket médio | **O próprio motor** |
| Geografia impossível | Última cidade e horário | **O próprio motor** |
| Teste de cartão (valores crescentes) | Sequência recente | **O próprio motor** |
| Horário fora do padrão | Horários habituais | **O próprio motor** |
| Acima do limite do cartão | Limite | Sistema de cartões |
| Compra no exterior sem aviso de viagem | Aviso de viagem | Cadastro |
| Conta aberta há poucos dias | Data de abertura | Cadastro |

**Cinco das oito regras não precisam de nada externo** — e são as cinco mais eficazes contra fraude
de cartão.

### Uma nona regra, de natureza diferente

À lista acima somou-se um **limiar absoluto**: acima de um valor configurável (R$ 30.000), alerta independentemente de
qualquer histórico.

Isso não contradiz o argumento contra limiares absolutos da seção 2.4 — responde outra pergunta:

| Regra    | Pergunta que responde                                               |
|----------|---------------------------------------------------------------------|
| Relativa | "Isso é incomum **para essa pessoa**?"                              |
| Absoluta | "Isso é grande o bastante para errar sair caro, **seja quem for**?" |

A primeira **detecta anomalia**; a segunda **limita perda**. Por isso o número é alto: R$ 5.000 dispararia em toda
compra de cliente de ticket alto, concentrando incômodo em quem gasta mais — que é exatamente o problema que a regra
relativa existe para evitar.

**Onde ela é indispensável:**

- **Cobre a partida a frio.** A regra relativa exige histórico mínimo; quem abriu conta ontem ficaria desprotegido.
- **Não pode ser envenenada.** Se um fraudador deslocou a linha de base com transações pequenas ao longo do tempo, a
  regra relativa deixa de funcionar. A absoluta continua valendo — e é a mitigação parcial da dívida da mediana.

**Severidade média**, não alta: pergunta ao cliente sem acionar o antifraude, porque não há indício de fraude, apenas
valor alto.

Calcular internamente é melhor por dois motivos: **está sempre atualizado** e **não depende de
ninguém**.

### 2.3 O cardápio de janelas

**A armadilha que decide a questão:** janela longa **não pode ser criada sob demanda**. Uma regra
publicada hoje pedindo "média dos últimos 30 dias" encontraria uma janela **vazia**. Logo, janelas
longas precisam estar **declaradas e rodando desde antes** — são infraestrutura, não configuração de
regra.

**Decisão: cardápio fixo.**

| Janela | Para quê |
|---|---|
| **5 minutos** | Velocidade, teste de cartão |
| **60 minutos** | Acumulado da hora, fraude espalhada de propósito |
| **30 dias** | Linha de base comportamental — o threshold dinâmico |
| *Último valor* | *Última cidade e horário. **Não é janela** — é só o registro mais recente* |

Geografia impossível não precisa de agregação nenhuma, só do registro mais recente.

### 2.4 Por que a janela de 30 dias reduz falso positivo

Regra com limiar absoluto: *"alerta se passar de R$ 1.000"*.

| Cliente | Gasto típico | O que acontece |
|---|---|---|
| Maria | ~R$ 80 | Uma fraude de R$ 900 **não dispara**. Falso negativo |
| João | ~R$ 900, compras de R$ 1.500 são rotina | Compras legítimas **disparam sempre** |
| Empresa X | Compras de R$ 20.000 são normais | **Toda compra dispara** |

**Não existe valor que resolva os dois.** Com linha de base, *"alerta se passar de 3x o que essa
pessoa costuma gastar"*:

| Cliente | Ticket médio | Limiar calculado | Resultado |
|---|---|---|---|
| Maria | R$ 80 | R$ 240 | A fraude de R$ 900 **dispara** |
| João | R$ 900 | R$ 2.700 | A compra de R$ 1.500 **não dispara** |
| Empresa X | R$ 20.000 | R$ 60.000 | Rotina **não dispara** |

**O agravante comercial:** os falsos positivos do limiar absoluto **se concentram em quem gasta
mais** — o cliente de alto valor é o que mais recebe alerta indevido.

> **Limiar absoluto trata "diferente da média" como "suspeito".** E ninguém é a média em todas as
> dimensões ao mesmo tempo — então todo mundo acaba sendo suspeito de alguma coisa.

**Mediana em vez de média**, porque a mediana resiste a valores extremos e a manipulação gradual.

### O ataque que o limiar dinâmico sozinho não resiste

O limiar relativo tem um ponto cego que só aparece quando se olha o ciclo completo: **a transação fraudulenta alimenta a
linha de base contra a qual ela própria é julgada.**

Cada transação desloca o ticket médio em pelo menos 5% (o peso mínimo). Como o limiar é o dobro do ticket médio, cada
fraude eleva o próprio limiar. O ataque **treina o detector a aceitá-lo**.

Simulação com cliente de ticket R$ 68 e fraudador repetindo R$ 800:

| Transação fraudulenta | Ticket médio | Limiar (2×) | Dispara? |
|-----------------------|--------------|-------------|----------|
| 1                     | R$ 104,60    | R$ 209,20   | sim      |
| 5                     | R$ 233,59    | R$ 467,18   | sim      |
| 11                    | R$ 383,64    | R$ 767,28   | sim      |
| **12**                | R$ 404,46    | R$ 808,91   | **não**  |

**A regra emudece na décima segunda transação** e o fraudador opera invisível a partir daí.

E há a variante paciente, que nem chega a disparar: transações sempre logo abaixo do dobro, subindo a base devagar. É o
envenenamento descrito na seção 5, agora com número medido.

**Duas defesas, porque são dois atacantes diferentes.**

**Primeira — quem gerou alerta não define o normal.** A transação continua contando nas janelas (sem isso a regra de
velocidade cegaria), mas não atualiza o ticket médio. Uma transação sob suspeita não pode ser usada para definir o que é
não-suspeito.

Isso exigiu inverter a ordem de processamento: antes o motor atualizava a memória inteira e depois avaliava; agora
registra o evento, avalia, e só então decide se absorve o valor na linha de base.

**Segunda — soma acumulada em 60 minutos, com limite absoluto.** O fraudador paciente escapa da regra de velocidade se
ficar em 3 transações por janela de 5 minutos — mas em uma hora acumula 36 transações. Como o limite é fixo (R$ 10.000)
e não referencia a linha de base, **envenenar a base não ajuda em nada**. É a primeira regra a usar a janela de 60
minutos, que até então existia como infraestrutura sem consumidor.

|                                  | Fraudador **agressivo**     | Fraudador **paciente** |
|----------------------------------|-----------------------------|------------------------|
| Velocidade em 5 min              | pegava, e emudecia na 12ª   | nunca pega             |
| **Exclusão do alertado da base** | **pega sempre**             | não ajuda              |
| Valor absoluto R$ 30.000         | só transação isolada enorme | não pega               |
| **Soma em 60 min**               | pega                        | **pega**               |

**A guarda de partida a frio.** A primeira versão da defesa introduziu um problema pior que o original. Na primeira
transação a linha de base nasce igual a ela mesma; se vier um valor baixo, o limiar fica **abaixo do gasto normal do
cliente**, transações legítimas passam a alertar — e, com a exclusão ativa, elas nunca mais corrigem a base. Ela trava
permanentemente no valor errado.

Medido em cliente real do simulador: base congelada em **R$ 496** para quem gastava **R$ 1.076**.

A correção é não aplicar a exclusão enquanto a base está se formando: **nas 5 primeiras transações tudo é absorvido, com
alerta ou sem**. Depois disso a exclusão vale. Após a guarda, o mesmo teste produziu base de
R$ 476 para um cliente que gasta entre R$ 275 e R$ 833 — limiar de R$ 952, acima de todo o gasto legítimo, e **nenhum
alerta espúrio**.

**Resultado medido em 30 transações fraudulentas contra cliente novo:**

|                                        | Antes                | Depois                     |
|----------------------------------------|----------------------|----------------------------|
| Alertas de velocidade                  | ~11, depois silêncio | **30**                     |
| Ticket médio durante o ataque          | subia a cada fraude  | **congelado em R$ 476,01** |
| Alertas espúrios no histórico legítimo | 5                    | **0**                      |

### 2.5 A única integração síncrona

O disjuntor: depois de N falhas seguidas, **para de chamar** por um período. Passado o tempo, deixa
passar uma chamada de teste.

**Timeout explícito e curto.** Sem timeout, o disjuntor nunca dispara, porque nenhuma chamada chega
a falhar.

**Pool de threads separado por integração.** Se todas compartilham o mesmo pool, a lenta ocupa tudo.

**Repetição com espera crescente e variação aleatória.** Sem a variação, **todas as instâncias
repetem no mesmo instante** e derrubam de novo o serviço que estava se recuperando.

### 2.6 O que acontece quando cada dependência cai

| Cai | O que acontece |
|---|---|
| Mongo (regras) | O motor continua com as regras em memória |
| Provedor de push e e-mail | Disjuntor abre, fila morta acumula, reprocessa quando voltar |
| Postgres (auditoria) | Alertas acumulam no Kafka, gravados depois |
| Redis | Envia assim mesmo, com métrica de duplicata |
| Equipe antifraude fora do ar | Não é problema nosso — o tópico retém |
| **Kafka** | **Aí sim tudo para** |

O Kafka é o ponto onde não há degradação graciosa possível. A resposta é redundância, não fallback.

### 2.7 Contratos de evento

O registro de esquemas recusa publicar uma versão que quebre consumidor: pode-se adicionar campo
opcional, nunca remover ou renomear. É uma **trava mecânica** contra erro humano.

## 3. Ferramentas escolhidas e seus trade-offs

### Resilience4j
**Por quê:** disjuntor, isolamento de pool, repetição e timeout numa biblioteca só. Sucessor do
Hystrix, descontinuado.
**Trade-off aceito:** valores errados são piores que nenhum — um disjuntor sensível demais abre em
oscilação normal e derruba entrega que funcionaria.

### Registro de esquemas (Avro ou JSON Schema)
**Por quê:** trava mecânica contra quebra de contrato.
**Trade-off aceito:** mais uma peça e cerimônia extra no desenvolvimento.

### Janela longa dentro do próprio motor
**Por quê:** elimina uma integração inteira. Buscar fora seria pedir a outro sistema um número
calculável localmente, com a desvantagem de estar sempre defasado.
**Trade-off aceito:** ~480 MB por máquina, metade da memória de estado. E reconstruí-la após uma
queda leva minutos, o que torna a cópia morna obrigatória.

### `auditoria` como aplicação própria
**Por quê:** permite transformar o alerta antes de gravar e aplicar criptografia de campo.
**Trade-off aceito:** uma aplicação pequena a mais, quando um conector faria o básico sem código.

## 4. Ferramentas e abordagens descartadas

### Chamada síncrona ao serviço de cadastro por transação
**25 mil chamadas por segundo derrubam o cadastro**, e a lentidão dele vira a sua — a Dor 1.

### Tópico de perfis co-particionado (construído)
Chegou a ser desenhado. **Descartado porque a maior parte do que se buscaria é calculável
internamente.** Restaria apenas o cadastral, que sustenta três regras secundárias, ao custo de ~1,5
GB por máquina e uma dependência de outro time publicar.
**O padrão fica documentado** como a forma correta de integrar quando alguma regra precisar.

### Cache preguiçoso com validade para o perfil
**Fica vazio depois de todo deploy**, gerando enxurrada de chamadas ao cadastro exatamente quando o
sistema já está redistribuindo partições.

### Camada de tradução para legado (construída)
**Não há integração com legado no desenho atual.** **Fica como ponto de apresentação** — como você
trataria se houvesse — não como componente.

### Repetição sem disjuntor
**Só empurra o problema.**

### Repetição sem variação aleatória
**A onda sincronizada mata o serviço que estava voltando.**

### Pool de threads compartilhado entre integrações
**Uma integração lenta consome tudo.**

### Hystrix
**Descontinuado.**

### Service mesh para resiliência
**Desproporção** — plano de controle inteiro para um case de 7 dias.

### Versionamento de contrato por convenção
**Funciona até alguém esquecer**, e aí o erro só aparece em produção nos consumidores.

### REST entre os nossos próprios componentes
**Reintroduziria acoplamento síncrono** onde o Kafka já resolve.

### Kafka Connect para a auditoria
**Dificulta a criptografia de campo** e deixa menos coisa visível para apresentar.

## 5. Limitações conhecidas

- **Cliente novo não tem linha de base.** A guarda de partida a frio resolve o caso patológico — a base travar no valor
  da primeira transação —, mas as 5 primeiras transações continuam sendo avaliadas contra uma base formada por elas
  mesmas. Média do segmento como partida seria melhor.
- **A linha de base pode ser envenenada — mitigado, não eliminado.** Excluir da base as transações já sinalizadas está
  implementado, e fecha o ataque agressivo (medido: a regra emudecia na 12ª transação, agora não emudece). A soma
  horária fecha o paciente. **Falta a mediana**, que é a defesa contra o deslocamento gradual abaixo do limiar de
  alerta.
- **A soma horária inunda.** Passado o limite, ela alerta em *toda* transação seguinte dentro da hora — 29 alertas
  medidos onde deveria haver 1. A correção é a deduplicação por
  `cliente + regra + janela` descrita no tópico 3, ainda não implementada.
- **Comportamento legítimo muda, e agora a base pode travar.** Cliente que mudou de padrão depois de formada a base
  passa a alertar, e como alerta não alimenta a base, ela não acompanha — o alerta se perpetua. O destravamento correto
  é a resposta *"fui eu"* do cliente, documentada no tópico 6 e não implementada.
- **Adicionar janela ao cardápio exige aquecimento** — **não é operação de minutos, é planejamento
  de mês**.
- **As três regras que dependem de dado cadastral não serão implementadas**, apenas descritas.
- **O Kafka não tem degradação graciosa.**

## 6. Pendente de validação

- [ ] Definir a política para cliente sem linha de base suficiente
- [ ] Calibrar os limiares do disjuntor para não abrir em oscilação normal do provedor
- [ ] Medir o tempo de reconstrução da janela de 30 dias
- [ ] Validar que a mediana é calculável com memória aceitável no volume esperado
- [ ] Calibrar o limite da soma horária (R$ 10.000 é chute) contra volume real por segmento
- [ ] Deduplicar o alerta da soma horária por `cliente + regra + janela`
- [ ] Medir quantos clientes legítimos ficam com a base travada por alertarem cedo demais

---
---

# Tópico 5 — Extensibilidade de Regras

## 1. A dor

*"O motor de regras de detecção deve ser altamente configurável e extensível para suportar novos
padrões de fraude, thresholds dinâmicos e regras compostas sem necessidade de redeploy."*

Parte da mecânica já foi decidida antes: regras no Mongo publicadas via Git (tópico 2), consulta a
cada 30 segundos, interruptor de desligamento, threshold dinâmico pela janela de 30 dias e cardápio
fixo de janelas (tópico 4). **O que este tópico resolve é a regra em si** — como ela se parece, o
que pode fazer, e o que impede alguém de derrubar a produção com ela.

### 1.1 Dois lados que se opõem

**De um lado, velocidade.** Fraudador muda tática em dias. Um ciclo de release leva semanas. Se a
regra vive no código, o banco está **estruturalmente atrasado** em relação ao crime. Não é
ineficiência; é uma corrida perdida por construção.

**Do outro lado, perigo.** Dar a alguém o poder de mudar o comportamento do sistema sem passar por
deploy abre cinco portas:

| O que uma regra ruim pode fazer | Consequência |
|---|---|
| Entrar em laço infinito | Trava a thread, a partição para de processar |
| Fazer cálculo caro | Estoura o orçamento de 500 ms para todo mundo |
| Referenciar campo que não existe | Erro em tempo de execução, no meio da produção |
| Disparar para todo mundo | Cem mil alertas falsos e o call center lotado |
| Nunca disparar para uma conta específica | Fraude interna, silenciosa e difícil de detectar |

**A extensibilidade só é boa se essas cinco portas estiverem fechadas.** É isso que separa uma
solução pensada de uma que só moveu o problema do código para um arquivo.

## 2. A estratégia

### 2.1 A regra como artefato declarativo

```yaml
id: velocidade-alta
versao: 3
descricao: Múltiplas transações em curto intervalo acima do padrão do cliente
habilitada: true
modo: producao          # sombra | canario | producao
severidade: alta
condicao: >
  janela5m.contagem > 3 &&
  transacao.valor > perfil.medianaTicket * 2
acoes:
  notificarCliente: true
  prioridadeAntifraude: alta
testes:
  - nome: dispara com 4 transações acima do dobro da mediana
    esperado: true
  - nome: não dispara com 3 transações
    esperado: false
```

Dois detalhes carregam decisões inteiras. **`perfil.medianaTicket * 2`** é o threshold dinâmico — o
limiar é relativo, e é o motivo de a janela de 30 dias existir. E **`testes`** faz a regra carregar
os próprios casos de teste, no mesmo arquivo e no mesmo commit: se alguém mudar a condição sem mexer
nos testes, o revisor vê no diff.

### 2.2 O que a regra pode enxergar

A expressão avalia contra um contexto fixo. Nada além disso está acessível:

| Fonte | Conteúdo |
|---|---|
| `transacao` | O evento atual |
| `janela5m`, `janela60m` | Contagens e somas do período |
| `perfil` | Linha de base de 30 dias |
| `ultimo` | Última cidade e horário |
| `regras` | Resultado de outras regras, para as compostas |

Não há acesso a rede, arquivo, banco ou reflexão. O contexto **é** a fronteira de segurança.

### 2.3 Regras compostas e o grafo que elas criam

```yaml
id: combinacao-critica
condicao: regras.velocidadeAlta && regras.geografiaImpossivel
severidade: alta
```

O valor é real: velocidade alta sozinha pode ser compras de Natal; geografia estranha sozinha pode
ser uma viagem. **Juntas, é quase certamente cartão clonado.** A composição expressa "a combinação é
pior que as partes" sem duplicar lógica.

Três problemas técnicos decorrem disso:

**Ordem de avaliação.** O motor monta um grafo de dependências e resolve a ordem topológica **no
carregamento**, não a cada transação.

**Ciclos.** A esteira de CI detecta e **recusa** ciclo antes do merge. É validação estática, barata.

**Dependência desabilitada.** Decisão tomada: **desligar uma regra desliga em cascata as compostas
que dependem dela.** Uma regra composta sem insumo não significa nada, e a alternativa — tratar a
dependência como falsa — mudaria o comportamento da composta **silenciosamente**. A terceira opção,
recusar o desligamento enquanto houver dependente, foi descartada porque **quebraria o interruptor
de emergência** justamente no momento em que ele é necessário.

Consequência de implementação: ao desligar uma regra, o motor **percorre o grafo e informa quais
compostas caem junto**. Desligar A e descobrir depois que C parou sem aviso seria pior que o
problema original.

### 2.4 O ciclo de vida seguro

| Etapa | O que faz | Estado |
|---|---|---|
| Pull request | Revisão de duas pessoas | Implementado (tópico 2) |
| Esteira de CI | Valida sintaxe, verifica tipos, recusa ciclo, roda os testes | **Implementado** |
| Modo sombra | Avalia contra tráfego real e produz **apenas métrica** | Documentado |
| Canário | Libera para percentual determinístico de clientes | Documentado |
| Produção | Todos os clientes | **Implementado** |
| Desligamento | Interruptor com cascata, em segundos | **Implementado** |

**Modo sombra** é o portão mais importante que ficou de fora: a regra roda contra o tráfego real sem
gerar alerta, e você compara a taxa de disparo observada com a esperada. Uma regra que deveria
disparar 50 vezes por dia e está disparando 50 mil é detectada **antes** de qualquer cliente ser
incomodado.

**Canário** usaria `hash(cliente) % 100 < percentual`. Ser determinístico importa: os mesmos clientes
ficam no canário o tempo todo, em vez de entrarem e saírem a cada transação.

**A divisão de responsabilidade entre prevenir e remediar:**

| Papel | Mecanismo | Estado |
|---|---|---|
| **Prevenir** | Modo sombra, canário | Documentado |
| **Remediar** | Interruptor de desligamento com cascata | **Implementado** |

O enunciado pede regra alterável sem redeploy, e isso está implementado. O modo sombra é prática de
qualidade **acima** do requisito, e está desenhado.

### 2.5 A propriedade que faz tudo isso funcionar

> **O estado é independente das regras.** As janelas contam transações; elas não sabem que regras
> existem.

Por isso trocar uma regra é instantâneo: nada precisa ser recalculado. Se o estado fosse derivado
das regras — se cada regra mantivesse a própria estrutura — mudar uma regra exigiria reconstruir
estado, o que leva minutos e mataria o "sem redeploy" na prática.

O corolário, vindo do tópico 4: **regra que usa janela existente entra em segundos; regra que precisa
de janela nova é planejamento de mês**, porque a janela começa vazia.

### 2.6 Quantas regras cabem

Cada avaliação custa dezenas de microssegundos, contra ~5 ms reservados no orçamento de latência:

| Quantidade | Custo por transação | Cabe? |
|---|---|---|
| 20 regras | ~0,4 ms | Confortável |
| 100 regras | ~2 ms | Aceitável |
| 250 regras | ~5 ms | No limite |
| 500 regras | ~10 ms | Estoura |

**O teto prático é da ordem de 200 a 250 regras** sem otimização. Passando disso, as saídas
documentadas são indexar por gatilho (só avaliar regras relevantes ao canal ou tipo) e ordenar por
custo com avaliação curto-circuitada.

## 3. Ferramentas escolhidas e seus trade-offs

### CEL-Java (Common Expression Language)
**Por quê:** criada pelo Google e usada nas políticas de admissão do Kubernetes — cenário idêntico ao
nosso, de expressões escritas por usuários e avaliadas dentro de um sistema crítico.

A propriedade que decide é contraintuitiva: **CEL não é Turing-completa.** Sem laço, sem recursão,
sem definição de função. Parece limitação e é a virtude central — **toda expressão termina, e termina
rápido**. A primeira das cinco portas de perigo fecha por construção da linguagem, não por cuidado de
quem escreve.

| Porta de perigo   | Como CEL fecha                                           |
|-------------------|----------------------------------------------------------|
| Laço infinito     | Impossível na linguagem                                  |
| Cálculo caro      | Sem laço, o custo é proporcional ao tamanho da expressão |
| Campo inexistente | **Não é a tipagem** — ver a correção abaixo              |
| Acesso indevido   | Sem rede, sem arquivo, sem reflexão                      |

**Correção após a implementação.** O documento afirmava que a tipagem do CEL rejeitaria campo inexistente na CI. **Isso
se mostrou falso na prática, e dois testes escritos para provar a afirmação falharam.** O contexto é declarado como
`map(string, dyn)`, e sobre um mapa desse tipo o compilador aceita qualquer chave: o erro só apareceria em tempo de
execução, dentro do `process()`.

O que fechou a porta foi outra coisa, mais forte: **toda condição é avaliada contra um contexto de exemplo no
carregamento**, antes de entrar no conjunto ativo. Isso recusa três classes de erro que a tipagem sozinha não pegaria —
campo inexistente, condição que não devolve booleano, e referência a uma regra que não existe. Regra recusada fica de
fora e as demais continuam rodando.

Obter a verificação em tempo de compilação exigiria declarar o contexto como tipo estruturado via protobuf, o que foi
considerado custo desproporcional para o ganho.

**Trade-off aceito:** menos expressiva que um motor completo. Regras que exigissem encadeamento
complexo entre múltiplos fatos não são expressáveis — mas nenhuma das nossas exige.

### Grafo de dependências com ordenação topológica
**Por quê:** resolve a ordem de avaliação das compostas uma vez, no carregamento.
**Trade-off aceito:** código próprio para montar o grafo, detectar ciclo e propagar desligamento em
cascata. É pouco código, mas é código que precisa estar certo.

### Casos de teste embutidos na regra
**Por quê:** transforma regra em artefato de software com qualidade, e não em configuração solta. É o
que torna defensável dar esse poder a quem não é desenvolvedor.
**Trade-off aceito:** quem escreve a regra precisa escrever os testes, o que aumenta a barreira de
entrada — justamente para o público não técnico que se quer atender.

## 4. Ferramentas e abordagens descartadas

### Drools
Motor de regras completo, com encadeamento, algoritmo RETE e interface para o time de negócio. É a
opção corporativa madura do mundo Java.
**Descartado por peso e previsibilidade:** dependência grande, latência menos previsível, curva de
aprendizado alta, e a rede interna dele precisa ser reconstruída a cada mudança de regra — o que
conflita com a recarga a quente a cada 30 segundos.

### Groovy ou JavaScript embarcado
Expressividade total e familiar para desenvolvedores.
**Descartado por ser Turing-completo:** laço infinito é possível, e isolar motor de script dentro da
JVM é notoriamente difícil de acertar. Seria trocar uma porta de perigo fechada por uma aberta.

### SpEL (Spring Expression Language)
**A armadilha mais tentadora**, porque já vem no Spring e não adiciona dependência.
**Descartado por segurança:** SpEL consegue chamar método arbitrário e acessar beans da aplicação.
Para expressão vinda de usuário, é buraco difícil de fechar — e o público-alvo dessa funcionalidade é
justamente quem não deveria ter esse poder.

### JSON Logic
Simples, seguro e sem dependência pesada.
**Descartado por ser limitado demais** para expressar regras com aritmética sobre linha de base e
composição.

### Regras escritas em código Java
Máxima expressividade, testabilidade total, desempenho ótimo.
**Descartado por falhar frontalmente o requisito**: mudar regra exigiria redeploy, que é exatamente o
que o enunciado pede para evitar.

### DSL própria com parser escrito do zero
Controle total sobre sintaxe e semântica.
**Descartado por custo:** semanas reinventando análise sintática, sistema de tipos e mensagens de
erro decentes — e mensagem de erro ruim inviabiliza o uso por não desenvolvedores.

### Janela livre declarada por regra
**Descartado por dois motivos:** memória imprevisível, já que cada regra nova pode criar uma
estrutura cara sem que ninguém perceba; e **não funciona para janelas longas**, que começam vazias.

### CI recusar desligar regra que tenha dependente
Parece mais seguro. **Descartado porque quebraria o interruptor de emergência** — às 3h da manhã não
se conseguiria desligar a regra que está causando o incidente.

### Composta tratar dependência desligada como falsa
Manteria mais cobertura ativa. **Descartado porque muda o comportamento silenciosamente** — a regra
continua rodando, mas virou outra coisa, e ninguém foi avisado.

## 5. Limitações conhecidas

- **Modo sombra e canário ficam documentados, não implementados.** A prevenção fica no papel; a
  remediação (interruptor com cascata) está implementada.
- **Teto de ~250 regras** sem indexação por gatilho.
- **Quem escreve a regra precisa escrever os testes**, o que é barreira para o público não técnico.
- **CEL não expressa encadeamento complexo** entre múltiplos fatos. Nenhuma regra atual precisa,
  mas um padrão de fraude futuro pode exigir e forçar reavaliação do motor de expressões.
- **Regra nova que precise de janela inexistente leva um mês** para ser confiável.
- **A aprovação de duas pessoas continua sendo processo, não código** — o motor confia no que chegou
  ao Mongo.

## 6. Pendente de validação

- [ ] Medir o custo real de avaliação de uma regra CEL e confirmar o teto de quantidade
- [ ] Validar que a detecção de ciclo cobre grafos com mais de dois níveis
- [ ] Testar o desligamento em cascata com uma composta de dois níveis de profundidade
- [ ] Confirmar que a recarga a quente troca o conjunto de regras de forma atômica, sem avaliar
      metade com a versão antiga e metade com a nova

---
---

# Tópico 6 — Monitoramento Operacional

## 1. A dor

*"Descreva como a equipe de SRE monitoraria a disponibilidade e o desempenho do sistema, e como
responderia a incidentes de alta prioridade (ex.: aumento súbito de falsos positivos, queda de
throughput, indisponibilidade de dependências)."*

Repare que o PDF **entrega os três incidentes**. Não é pergunta aberta — é uma lista para responder
uma a uma, com procedimento concreto. Quem responder só com "usaria Prometheus e Grafana" deixou a
pergunta em branco.

### 1.1 A falha que ninguém vê

Se a aplicação cair, todo mundo sabe em segundos — a fila explode, os alertas param, o painel fica
vermelho. **Essa é a falha fácil.**

A perigosa é outra: o sistema processa 25 mil por segundo, a latência está em 40 ms, não há erro no
log — e uma regra está quebrada há três dias deixando fraude passar. **Todo indicador técnico está
verde.**

| Falha | Quão visível nas métricas técnicas |
|---|---|
| Aplicação cai | Imediata — a fila explode |
| Kafka cai | Imediata — tudo para |
| Latência sobe | Visível, se você mede p99 |
| Provedor de push falha | Visível — disjuntor abre, fila morta cresce |
| **Regra parou de disparar** | **Invisível** |
| **Regra dispara demais** | Parcialmente — o call center reclama antes do painel |
| **Contagem corrompida por duplicata** | **Invisível** |

As três últimas são as caras, e nenhuma aparece em métrica de infraestrutura.

> **Monitorar o sistema não basta; é preciso monitorar a qualidade da decisão.**

## 2. A estratégia

### 2.1 Métricas técnicas — "está de pé?"

| Métrica | Por que importa |
|---|---|
| **Fila acumulada por partição** | O número mais importante. Diz se está acompanhando o fluxo |
| Latência p50/p95/p99 ponta a ponta | O SLA do case |
| Taxa de erro por dependência | Saúde das integrações |
| Estado do disjuntor | Aberto significa provedor fora |
| Profundidade **e idade** da fila morta | Idade importa: fila que não drena é um segundo incidente |
| Tempo de reconstrução de estado | Saúde do rebalanceamento |
| Pausas de coleta de memória | A armadilha invisível do p99 |

### 2.2 Métricas de negócio — "está acertando?"

| Métrica | O que detecta |
|---|---|
| **Taxa de disparo por regra** | A mais importante. Salto = regra quebrada **ou** ataque real |
| Alertas por cliente | Fadiga de alerta |
| Resposta do cliente ("fui eu" × "não fui eu") | **Proxy direto de falso positivo** — documentada, não implementada |
| Taxa de deduplicação | Salto significa que a origem começou a reenviar |
| Eventos descartados por atraso | Degradação a montante |

A quarta merece atenção: **a própria taxa de deduplicação é um sinal.** Se o motor começa a descartar
dez vezes mais duplicatas, ninguém do lado de cá quebrou — mas alguém do lado de lá sim.

### 2.3 O alerta que quase ninguém escreve

**Ausência de alertas é um sintoma.** Um sistema saudável sempre produz alguma coisa. Se o motor
passa 10 minutos sem gerar nenhum alerta, isso não é sucesso — é cegueira. Um bug que faz o motor
parar de disparar é completamente silencioso.

A calibragem precisa ser **relativa ao tráfego esperado**, não absoluta: às 3h da manhã o volume é
naturalmente menor, e um limiar fixo dispararia todo dia de madrugada.

### 2.4 Rastreamento: amostrar pelo resultado, não por sorteio

O identificador da transação viaja nos cabeçalhos do Kafka e permite responder *"o que aconteceu com
a transação X?"* em segundos.

A 25 mil por segundo não dá para rastrear tudo. A saída óbvia é amostrar aleatoriamente — e é a
errada, porque **as transações interessantes são 0,5% do total**. A escolha correta é **amostrar
pelo resultado**: rastreia-se sempre quem gerou alerta, e uma fração pequena do resto.

### 2.5 Incidente 1 — aumento súbito de falsos positivos

**Detecção:** taxa de disparo de uma regra 5x acima da linha de base.

**Procedimento:**

1. Identificar a regra pela métrica de disparo
2. **Desligar a regra pelo interruptor** — segundos, sem deploy
3. Confirmar a queda na métrica
4. Investigar a causa e escrever o post-mortem

**O princípio é remediar primeiro e entender depois.** Cada minuto com a regra ligada custa milhares
de clientes incomodados. Investigar antes de desligar inverte a prioridade — e essa inversão é o erro
mais comum em plantão.

**A armadilha de diagnóstico:** um salto na taxa de disparo pode ser **regra quebrada ou ataque real
em curso**. As duas exigem ação imediata, mas opostas — desligar a regra ou acionar o antifraude.

O desempate ideal seria a resposta do cliente ("fui eu" indica falso positivo; "não fui eu" indica
ataque). Como essa métrica ficou documentada e não implementada, o desempate usa o que existe:

| Verificação | O que indica |
|---|---|
| A regra mudou nas últimas horas? (`git log`) | Regra nova mal calibrada |
| A taxa de deduplicação subiu junto? | Duplicata inflando contagem — bug de infraestrutura |
| O volume de entrada mudou? | Pico legítimo de tráfego |
| O salto é numa regra só ou em várias? | Uma → problema dela. Várias → problema sistêmico |

### 2.6 Incidente 2 — queda de throughput

**Detecção:** fila acumulada crescendo de forma consistente.

A fila não diz **por quê**. A árvore de diagnóstico:

| Sintoma adicional | Causa provável | Ação |
|---|---|---|
| Taxa de processamento estável, entrada subiu | Pico legítimo | Escalar até o teto de 64, ou deixar drenar |
| Taxa caiu, pausas de GC subiram | Pressão de memória | Investigar o tamanho do estado |
| Taxa caiu, latência de avaliação subiu | Regra cara | Identificar pela latência por regra e desligar |
| Taxa caiu logo após um deploy | Rebalanceamento em curso | Esperar; verificar a cópia morna |

**Dependência lenta não aparece na lista** — porque não existe integração síncrona no motor. Aquele
modo de falha foi eliminado por desenho no tópico 4, e isso encurta a árvore.

### 2.7 Incidente 3 — indisponibilidade de dependências

**Detecção:** estado do disjuntor, taxa de erro por dependência, profundidade e **idade** da fila
morta.

A resposta é majoritariamente automática — o disjuntor abre, a fila morta acumula, o reprocessamento
acontece quando o provedor volta. A ação do plantão é **verificar se a degradação está se comportando
como desenhada** e comunicar.

O que exige atenção humana é a **fila morta que não drena**. Se ela cresce e a idade da mensagem mais
antiga aumenta, o reprocessamento não está funcionando — e isso é um segundo incidente, escondido
dentro do primeiro.

### 2.8 SLOs e orçamento de erro

| SLO | Alvo |
|---|---|
| Latência p99 do evento ao alerta | < 500 ms em 99% das janelas de 5 minutos |
| Atraso máximo da fila | < 30 segundos |
| Disponibilidade da ingestão | 99,95% |

O orçamento de erro governa **o ritmo de promoção de regras**: estourou, congela promoção até
recuperar. Isso amarra a velocidade de mudança à saúde do sistema.

### 2.9 Painéis por público

| Público | Pergunta | Conteúdo |
|---|---|---|
| Plantão SRE | "Está de pé?" | Fila, latência, erros, disjuntores, fila morta |
| Time de fraude | "Está acertando?" | Taxa por regra, alertas por cliente, deduplicação |

Misturar os dois num painel só é o que faz ninguém olhar nenhum.

## 3. Ferramentas escolhidas e seus trade-offs

### Micrometer
**Por quê:** instrumentação padrão do Spring, sem amarrar ao backend de métricas.
**Trade-off aceito:** uma abstração a mais entre o código e a métrica.

### Prometheus
**Por quê:** padrão de mercado, linguagem de consulta poderosa, alertas no mesmo lugar.
**Trade-off aceito:** modelo de busca ativa e **limite duro de cardinalidade**.

**A pegadinha da cardinalidade merece destaque**, porque é o erro que derruba a monitoração inteira:
se alguém usar o identificador do cliente como rótulo de métrica, o Prometheus tenta criar 50 milhões
de séries e morre. **Regra: rótulo é para dimensão de baixa variedade** — regra, canal, severidade.
Cliente e transação nunca.

### Grafana
**Por quê:** painéis versionáveis como código, junto do repositório.
**Trade-off aceito:** mais uma peça para operar.

### OpenTelemetry
**Por quê:** rastreamento sem amarrar a fornecedor, com o identificador da transação propagado nos
cabeçalhos do Kafka.
**Trade-off aceito:** sobrecarga pequena por transação, controlada pela amostragem por resultado.

## 4. Ferramentas e abordagens descartadas

### Só logs
**Não dá para alertar sobre tendência** nem calcular percentil de forma barata. Log responde "o que
aconteceu com esta transação"; métrica responde "como está o sistema". São perguntas diferentes, e
uma não substitui a outra.

### APM proprietário (Datadog, New Relic)
Excelentes e mais completos que a combinação escolhida. **Descartados por custo no volume e por
amarração** — e, num take-home, não dá para demonstrar sem conta paga.

### Alertar por uso de CPU
**Sinal enganoso para consumidor de fluxo:** pode estar baixo com a fila crescendo, ou alto com tudo
em dia. A fila é a medida certa.

### Média de latência
**Engana por construção.** Se 99 transações levam 10 ms e uma leva 5 segundos, a média dá 60 ms.
Só p99.

### Alerta sem runbook
Um alerta que acorda alguém às 3h sem dizer o que fazer **é ruído com custo humano**. Todo alerta
desta lista tem procedimento associado.

### Amostragem aleatória de rastreamento
**As transações interessantes são 0,5% do total** — o sorteio perderia quase todas.

## 5. Limitações conhecidas

- **A métrica de resposta do cliente fica documentada, não implementada.** É o proxy mais direto de
  falso positivo, e sem ela o desempate entre "regra quebrada" e "ataque real" fica indireto.
- **Não há detecção automática de anomalia**, apenas limiares fixos calibrados. Limiar fixo gera
  falso alarme em variação sazonal legítima.
- **Os limiares dos alertas são estimativa** até haver linha de base real de produção.
- **O alerta de ausência precisa de calibragem relativa ao tráfego**, e essa curva não existe ainda.

## 6. Pendente de validação

- [ ] Estabelecer a linha de base real de taxa de disparo por regra, para calibrar os limiares
- [ ] Validar que nenhuma métrica usa identificador de cliente ou transação como rótulo
- [ ] Testar o alerta de ausência derrubando o motor propositalmente
- [ ] Confirmar a sobrecarga real do rastreamento sob carga de pico

---
---

# Plano de implementação — 7 dias

## Princípio de sequenciamento

**O maior risco entra cedo.** A serialização do Kafka Streams é o ponto onde mais gente perde tempo,
então o motor começa no dia 2 — deixando margem para pivotar se travar.

**Nada pode ser testado sem transações chegando**, então o simulador vem antes de qualquer aplicação
de produção.

**Testes não são fase.** O `TopologyTestDriver` entra junto com a topologia, no dia 2.

## Dia 1 — Fundação

- Repositório, Maven multi-módulo (5 módulos), wrapper incluído
- `docker-compose.yml`: Kafka em KRaft, Mongo, Postgres, Redis
- Contrato do evento de transação, com esquema versionado
- `simulador`: gera carga configurável, **já com token no lugar do número do cartão**
- Criação dos tópicos com 64 partições

**Checkpoint:** uma transação publicada pelo simulador aparece na partição correta, e o mesmo cliente
cai sempre na mesma.

## Dias 2 e 3 — Motor (o núcleo e o maior risco)

- Topologia Kafka Streams com transação atômica e cópia morna
- **Serialização** — reservar tempo; é onde o risco mora
- Janelas do cardápio: 5 min, 60 min, 30 dias, mais o último valor
- Registro de transações já vistas (1 hora)
- **Uma regra fixa em código**, ainda sem CEL

Implementar com regra fixa primeiro é deliberado: prova que as janelas funcionam **antes** de somar o
motor de expressões. Se algo falhar, você sabe de qual metade veio.

**Checkpoint dia 3:** o simulador dispara uma sequência de fraude e o alerta correto sai no tópico.

**Ponto de decisão:** se a serialização consumiu mais de meio dia, avaliar o pivô para Redis. Depois
do dia 3 o pivô fica caro demais.

## Dia 4 — Regras

- Carregamento do Mongo com recarga a cada 30 segundos
- Integração CEL, com contexto fixo
- 5 regras reais em YAML e o script que as carrega
- Grafo de compostas: ordenação topológica e recusa de ciclo
- Casos de teste das regras rodando na CI
- Endpoint de desligamento **com cascata**

**Checkpoint:** editar o YAML, rodar o script, e a regra nova valer em 30 segundos **com o sistema no
ar**. Essa é a demonstração central do requisito de extensibilidade.

## Dia 5 — Saídas

- `notificacao`: dedup no Redis com validade curta convertida em 24 h, disjuntor, fila
  morta, provedor simulado
- `auditoria`: grava no Postgres com chave única
- Degradação demonstrável: derrubar o provedor e mostrar o alerta interno seguindo normalmente

**Checkpoint:** derrubar o provedor de notificação e provar que a detecção não para.

## Dia 6 — Observabilidade e carga

- Micrometer, Prometheus e Grafana no Compose
- Métricas técnicas e de negócio
- Dois painéis, um por público
- Alerta de ausência de alertas
- **Teste de carga com k6 — medir e registrar o número**

**Checkpoint:** o número medido de vazão e p99 anotado no README, junto com o gargalo observado.

## Dia 7 — Documentação e apresentação

- README com execução em um comando
- `docs/architecture.md` (este documento)
- ADRs das decisões principais
- **Seção de uso de IA** — exigência da página 4 do PDF
- Roteiro da apresentação de 30 minutos

## Se atrasar, cortar nesta ordem

1. Segundo painel do Grafana — um só resolve
2. Auditoria em Postgres — o tópico já é a trilha; a base é índice de consulta
3. Regras compostas — mas o PDF pede explicitamente, então é o último recurso
4. Reduzir de 5 para 3 regras

**O núcleo inegociável:** simulador → motor com janelas → regras em CEL sem redeploy → alerta →
notificação com resiliência → teste de carga medido. Isso responde aos quatro itens do desafio.

## Pendências antes do dia 1

- Nome do repositório
- Instalar o GitHub CLI, autenticar e criar o repositório
