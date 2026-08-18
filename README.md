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

## Simulador

```bash
# ligar carga contínua
curl -X POST "http://localhost:8084/simulador/carga/ligar?taxa=200"

# disparar uma sequência que aciona a detecção
curl -X POST "http://localhost:8084/simulador/fraude?quantidade=5"

# verificar o roteamento por partição
curl -X POST "http://localhost:8084/simulador/verificar-particao?cliente=cli-000042"

# desligar
curl -X POST "http://localhost:8084/simulador/carga/desligar"
```

## Estado atual

Dia 1 de 7 concluído: infraestrutura, contrato do evento e simulador.

**Verificado:** o mesmo cliente cai sempre na mesma partição (6 de 6 publicações do `cli-000042`
foram para a partição 60), e clientes distintos se distribuem por todas as 64 partições, com média
de 25,5 mensagens por partição em uma amostra de 1.632.

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
