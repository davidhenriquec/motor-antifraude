# motor-antifraude

Motor de detecção de transações suspeitas com resposta em tempo real.

Case técnico do processo seletivo de Engenharia de Software Backend do Itaú.

---

## O que o sistema faz

Recebe eventos de transações financeiras, aplica regras de detecção sobre o histórico de cada
cliente e publica alertas para a equipe antifraude e para o cliente final — em menos de 500 ms.

**O sistema detecta e notifica. Não bloqueia transações** — bloquear é papel de outro sistema.

## Arquitetura em uma tela

```
sistemas de origem
        │  publicam direto no Kafka (sem API REST intermediária)
        ▼
  tópico transacoes ──── 64 partições, chave = identificador do cliente
        │
        ▼
  motor ──────────────── janelas por cliente em memória local + regras em CEL
        │
        ▼
  tópico alertas ─────── contrato versionado
        │
        ├──► notificacao ──► push e e-mail
        ├──► auditoria ────► Postgres
        └──► equipe antifraude
```

O raciocínio completo, com os trade-offs de cada decisão e as alternativas descartadas, está em
[docs/architecture.md](docs/architecture.md).

## Módulos

| Módulo | Papel |
|---|---|
| `contrato` | Classes de evento compartilhadas |
| `motor` | Janelas por cliente, avaliação de regras, publicação de alertas |
| `notificacao` | Entrega externa. Única integração síncrona do sistema |
| `auditoria` | Trilha de auditoria no Postgres |
| `simulador` | Ferramenta de teste e demo — **fora da arquitetura de produção** |

> Em produção, cada aplicação teria repositório e ciclo de release próprios. Aqui estão no mesmo
> repositório para facilitar a avaliação — os módulos são independentes e geram artefatos separados.

## Como executar

**Pré-requisitos:** JDK 21 e Docker.

```bash
docker compose up -d
```

Sobe Kafka (KRaft), Mongo, Postgres, Redis, Prometheus e Grafana, e cria os tópicos com as
partições corretas.

```bash
./mvnw clean install
```

Depois, cada aplicação sobe pela IDE ou por linha de comando:

```bash
java -jar simulador/target/simulador-0.1.0-SNAPSHOT.jar
```

| Serviço | Porta |
|---|---|
| Kafka | 9092 |
| Mongo | 27017 |
| Postgres | 5432 |
| Redis | 6379 |
| Prometheus | 9090 |
| Grafana | 3000 |
| motor | 8081 |
| notificacao | 8082 |
| auditoria | 8083 |
| simulador | 8084 |

## Como testar a detecção

O identificador do cliente é opcional em todos os endpoints — sem ele, o simulador sorteia um dos 200.000 perfis.

**1. Construir o histórico.** Sem isso a regra relativa não dispara: ela exige cinco transações de base, e um cliente
novo teria a própria fraude como linha de base.

```bash
curl -X POST "http://localhost:8084/simulador/historico?cliente=cli-000500&quantidade=10"
```

**2. Disparar fraude por velocidade** — muitas transações acima do dobro do padrão do cliente.

```bash
curl -X POST "http://localhost:8084/simulador/fraude?cliente=cli-000500&quantidade=5"
```

**3. Disparar a regra de valor absoluto**, que independe de histórico.

```bash
curl -X POST "http://localhost:8084/simulador/transacao?cliente=cli-000500&valor=50000"
```

**4. Carga contínua**, para observar vazão.

```bash
curl -X POST "http://localhost:8084/simulador/carga/ligar?taxa=500"
```

```bash
curl -X POST "http://localhost:8084/simulador/carga/desligar"
```

> **Use um cliente diferente a cada rodada de teste.** Repetir o ataque no mesmo cliente eleva o
> ticket médio dele e muda o resultado — é o envenenamento de linha de base descrito no tópico 4.

### Onde ver o resultado

No **Grafana** (http://localhost:3000 → Explore → Prometheus):

| O que ver                     | Consulta                                                                                             |
|-------------------------------|------------------------------------------------------------------------------------------------------|
| Total de transações avaliadas | `antifraude_transacoes_avaliadas_total`                                                              |
| Transações por segundo        | `rate(antifraude_transacoes_avaliadas_total[1m])`                                                    |
| Total de fraudes detectadas   | `antifraude_alertas_gerados_total`                                                                   |
| **Taxa de disparo (%)**       | `100 * rate(antifraude_alertas_gerados_total[5m]) / rate(antifraude_transacoes_avaliadas_total[5m])` |
| Reenvios descartados          | `antifraude_transacoes_duplicadas_total`                                                             |
| Cliente quente                | `antifraude_memoria_no_limite_total`                                                                 |

A taxa de disparo é a métrica central do tópico 6: um salto significa regra quebrada **ou** ataque em curso, e a árvore
de diagnóstico que separa os dois está documentada lá.

No **Kafka UI** (http://localhost:8090): `alertas → Messages` mostra o JSON de cada alerta com o
`valoresEntrada` que explica a decisão; `Consumers → motor-de-deteccao` mostra o lag por partição.

E no log do motor, `grep ALERTA`.

## As regras de detecção

Vivem em [regras/regras.yml](regras/regras.yml), são carregadas no Mongo e **recarregadas pelo motor a cada 30
segundos — sem redeploy**.

| Regra                           | Severidade | Dispara quando                                                                                                   |
|---------------------------------|------------|------------------------------------------------------------------------------------------------------------------|
| `velocidade-alta`               | ALTA       | Cliente com 5+ transações de histórico faz mais de 3 em 5 minutos, e o valor passa do dobro do ticket médio dele |
| `valor-absoluto`                | MÉDIA      | Uma única transação passa de R$ 5.000                                                                            |
| `soma-na-hora`                  | ALTA       | A soma dos últimos 60 minutos passa de R$ 10.000                                                                 |
| `cidade-diferente-no-ecommerce` | MÉDIA      | Compra online de cidade diferente da anterior, acima do triplo do ticket médio                                   |
| `combinacao-critica`            | ALTA       | As duas primeiras condições acima na mesma transação                                                             |

Para alterar ou adicionar, edite o YAML e rode:

```bash
./infra/carregar-regras.sh
```

Para desligar uma regra na hora, sem esperar os 30 segundos:

```bash
curl -X POST "http://localhost:8081/regras/velocidade-alta/desligar"
```

Desligar uma regra **desliga em cascata** as compostas que dependem dela.

## Observabilidade

Dois painéis provisionados por arquivo, em `infra/grafana/dashboards/`:

| Painel                | Pergunta que responde | Endereço                                    |
|-----------------------|-----------------------|---------------------------------------------|
| Plantão SRE           | "está de pé?"         | http://localhost:3000/d/antifraude-plantao  |
| Qualidade da detecção | "está acertando?"     | http://localhost:3000/d/antifraude-deteccao |

E sete regras de alerta em `infra/prometheus/regras-de-alerta.yml`, cada uma com o procedimento de plantão. A mais
importante é a de **ausência de alertas**: se há tráfego entrando e nada saindo por 10 minutos, isso é incidente — é a
falha que nenhum indicador técnico mostra.

## Números medidos

Todos em uma instância, MacBook de 10 núcleos, tudo em Docker. **Não representam produção** — o Kafka divide o mesmo SSD
com o estado local, sob virtualização.

|                                    |                                                 |
|------------------------------------|-------------------------------------------------|
| Vazão sustentada, estado limpo     | **8.000 tx/s** — a média exigida pelo enunciado |
| Pico momentâneo                    | 28.000 tx/s                                     |
| Latência de **processamento**      | p50 **0,43 ms** · p99 11 ms                     |
| Latência **ponta a ponta** (o SLA) | p50 453 ms · p99 8,0 s                          |
| Taxa de alerta                     | 0,11%                                           |

**A descoberta mais útil:** o motor decide em menos de meio milissegundo, e mais de 99% da latência é espera em fila.
Acompanhar a vazão cumpre a capacidade; **cumprir a latência exige folga**.

**O gargalo não é CPU.** No teste, 90% dos núcleos ficaram ociosos enquanto a fila crescia — as threads esperavam o
broker aceitar escrita. Cada transação de 192 bytes provoca ~1.400 bytes de changelog, uma amplificação de **7,5×**, que
cresce com o histórico do cliente. O raciocínio completo está em [docs/execucao/dia-6.md](docs/execucao/dia-6.md).

## Estado atual

Dias 1 a 6 de 7 concluídos, com 69 testes automatizados — 60 no motor e 9 no `notificacao`.

| Verificado em execução      |                                                                                   |
|-----------------------------|-----------------------------------------------------------------------------------|
| Particionamento             | O mesmo cliente cai sempre na mesma partição; 64 partições usadas                 |
| Teto de memória             | 520 mil transações sem `RecordTooLargeException`                                  |
| Resistência a envenenamento | Ataque de 30 transações: a regra reconheceu as 30. Antes emudecia na 12ª          |
| Repetição de alerta         | Ataque que gerava 16 alertas gera 1. Café de R$ 5 após o alerta ficou em silêncio |
| Regras sem redeploy         | Regra nova valendo em 19 s, com o sistema no ar                                   |
| Desligamento em cascata     | Desligar `velocidade-alta` derrubou junto a regra composta                        |
| Regra quebrada              | Recusada no carregamento; as demais continuaram                                   |
| Queda do provedor           | Detecção e auditoria seguiram; disjuntor abriu; fila morta acumulou               |
| Alerta de ausência          | 188 tx/s entrando, zero alertas: condição verdadeira                              |

**Falta:** documentação final e roteiro de apresentação (dia 7).

## Uso de inteligência artificial

Conforme solicitado no enunciado, o uso de IA neste case está declarado abaixo.

**Onde foi usada:** todo o processo de análise do enunciado, exploração de alternativas
arquiteturais e redação da documentação foi conduzido em diálogo com o Claude (Anthropic), assim
como parte da escrita de código.

**Como foi usada:** como interlocutor técnico para levantar trade-offs, questionar decisões e
produzir texto. Cada decisão registrada em `docs/architecture.md` passou por questionamento e
validação minha — várias propostas iniciais da ferramenta foram recusadas e estão documentadas como
alternativas descartadas, entre elas a camada de abstração sobre o estado, o tópico de perfis
co-particionado e a camada anticorrupção para legado.

**O que não foi delegado:** as decisões de arquitetura, o recorte de escopo e a validação do que
faz sentido para o problema.
