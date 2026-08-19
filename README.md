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

## Estado atual

Dias 1 a 3 de 7 concluídos: infraestrutura, contrato do evento, simulador e o motor com memória por cliente, três regras
e 44 testes.

**Particionamento verificado:** o mesmo cliente cai sempre na mesma partição (6 de 6 publicações do
`cli-000042` foram para a partição 60), e clientes distintos se distribuem por todas as 64 partições, com média de 25,5
mensagens por partição em uma amostra de 1.632.

**Teto de memória verificado:** 520.153 transações reprocessadas sem `RecordTooLargeException` após a introdução do
limite de 200 eventos e 500 identificadores por cliente.

**Resistência a envenenamento verificada:** num ataque de 30 transações fraudulentas contra cliente novo, a regra de
velocidade disparou nas 30 e o ticket médio permaneceu congelado. Antes da correção, ela emudecia na 12ª. O raciocínio
está no tópico 4 de
[docs/architecture.md](docs/architecture.md).

**Ainda fixo em código:** as regras são beans do Spring injetados na subida. Torná-las configuráveis sem redeploy é o
dia 4.

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
