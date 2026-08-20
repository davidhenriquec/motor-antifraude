# O que falta e como será feito

> Estado em: fim do dia 4. Ordem de prioridade, com o **como** de cada item.
>
> O raciocínio das decisões está em [../architecture.md](../architecture.md).

---

## Decisões abertas

### Comentários nos arquivos `.yml`

Os `.java` estão sem comentários. Os `.yml` ainda têm, e alguns carregam decisão de arquitetura —
`processing.guarantee: exactly_once_v2` vem com a explicação de que é ela que torna o registro de já vistos confiável, e
`intervalo-de-recarga-ms` explica por que 30 segundos. Remover deixa a justificativa só no documento.

**Resolvidas:** a `FonteDeRegras` entrou no dia 4, e o padrão de nome das classes de configuração ficou no sufixo
`Config`.

---

## Já concluído depois do dia 2

Itens que estavam nesta lista e saíram.

| Item                                 | Como ficou                                                                                                                                                |
|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Testes de `MemoriaDoCliente`         | 11 testes cobrindo descarte, janelas, deduplicação, última cidade e ticket médio                                                                          |
| Linha de base que esquece            | Média móvel exponencial com meia-vida de 30 dias, mais peso mínimo de 5% por transação                                                                    |
| Teto de memória por cliente          | 200 eventos e 500 identificadores, com métrica sinalizando cliente quente                                                                                 |
| Segunda regra                        | Limiar absoluto, que provou a abstração `Regra` ao entrar sem tocar no motor                                                                              |
| Envenenamento pelo ataque agressivo  | Transação que gerou alerta não atualiza mais o ticket médio. A regra emudecia na 12ª transação; agora não emudece                                         |
| Guarda de partida a frio             | Nas 5 primeiras transações tudo é absorvido, senão a base trava no valor da primeira                                                                      |
| Janela de 60 minutos sem uso         | Regra `soma-na-hora`, limite absoluto de R$ 10.000, pega o fraudador de ritmo controlado                                                                  |
| Repetição de alerta                  | Um alerta por janela; regra sem janela alerta sempre; escalada de severidade fura a supressão. O café de R$ 5 depois do alerta ficou em silêncio          |
| Regra quebrada derrubando a thread   | `try/catch` por regra, métrica `antifraude.regras.falhas{regra}` e log único por regra                                                                    |
| `ultimo.cidade` sempre igual à atual | Corrigido com `cidadeAntesDe(horario)`. A regra de geografia era silenciosamente inútil                                                                   |
| Nomes vagos no motor                 | Auditoria completa de nomes: `anterior` → `memoriaAntesDaTransacao`, `CURTA`/`MEDIA` → `CINCO_MINUTOS`/`UMA_HORA`, vocabulário unificado em `ticketMedio` |

---

## Dívidas ainda abertas

### Mediana no lugar de média — rebaixada a melhoria marginal

**O que a média móvel resolveu:** o esquecimento. Comportamento antigo desaparece sozinho.

**O que a exclusão da transação alertada resolveu depois:** o ataque agressivo. Antes disso a regra emudecia na 12ª
transação fraudulenta; agora não emudece.

**Por que a mediana perdeu importância.** Ela resiste a **valor extremo isolado** — e um valor extremo isolado hoje
dispara alerta, logo já não entra na base. O que sobraria para ela é a rampa lenta abaixo do limiar; só que **a mediana
também cede a isso**, porque ela resiste a poucos outliers, não a um deslocamento gradual da distribuição inteira. Se o
fraudador vira a maioria das transações, a mediana acompanha, apenas mais devagar.

**Conclusão:** deixa de ser dívida a pagar e vira melhoria marginal. A defesa real contra a rampa lenta é a regra
`soma-na-hora`, cujo limite é absoluto e não consulta a linha de base.

**Esforço se um dia valer:** ~3 h com um estimador de quantil (P-quadrado, ~40 bytes por cliente).

### Amplificação de escrita no changelog

**O que foi resolvido:** o teto impede o estouro de 1 MB e limita a escrita a ~52 KB por transação de cliente quente.

**O que continua:** a escrita ainda é proporcional ao tamanho da memória, não ao tamanho da mudança.

**Solução estrutural:** trocar a lista de eventos por **contadores por minuto** — 60 baldes de contagem e soma dão
tamanho fixo independente da taxa, algo em torno de 1,5 KB.

**O que quebraria:** a regra de teste de cartão precisa dos valores individuais em sequência. Ficaria uma lista pequena
e limitada (últimos 10) ao lado dos baldes.

**Esforço:** ~4 h. Só vale se o teste de carga do dia 6 mostrar que a escrita é gargalo.

---

## Dia 4 — Regras sem redeploy — **concluído**

Registro completo em [dia-4.md](dia-4.md).

Regras em CEL no Mongo com recarga de 30s, grafo de compostas com cascata, interruptor operacional, proteção contra
regra que lança exceção, e controle de repetição de alerta. As três classes Java de regra foram removidas.

---

## Dia 5 — As saídas

### 5.1 `notificacao`

**Estrutura**, seguindo o padrão do motor:

```
notificacao/
├── entrega/      DecisaoDeEntrega, ProvedorSimulado
├── deduplicacao/ ChaveDeEntrega, RegistroNoRedis
└── kafka/        ConsumidorDeAlertas
```

**Diferença importante em relação ao motor:** aqui o consumidor é **explícito**, um método anotado com `@KafkaListener`
recebendo `Alerta`. Não há memória para gerenciar, então o Kafka Streams não se paga.

**A ordem da deduplicação**, que é o ponto delicado:

1. Chega o alerta
2. Tenta gravar a chave no Redis com "só grave se ainda não existir", validade de **60 segundos**
3. Falhou → outro já tratou → descarta
4. Deu certo → envia push ou e-mail
5. Estende a validade para **24 horas**

A validade curta existe para que uma queda entre gravar e enviar se conserte sozinha. Gravar direto com 24 horas faria o
cliente **nunca** ser avisado daquela fraude.

**Resiliência:** disjuntor por dependência, timeout curto, repetição com variação aleatória, e fila morta para o que não
entregar.

**Se o Redis cair, envia assim mesmo** — melhor duplicar que silenciar — com métrica registrando.

### 5.2 `auditoria`

**Estrutura:**

```
auditoria/
├── registro/   RegistroDeAlerta, RepositorioDeAuditoria
└── kafka/      ConsumidorDeAlertas
```

**Como:** consumidor comum, gravando no Postgres com **chave única no identificador do alerta**. A idempotência sai de
graça: o banco recusa a segunda linha, sem lógica nenhuma.

Migração com Flyway criando a tabela particionada por mês.

**Checkpoint do dia:** derrubar o provedor de notificação e provar que a detecção não para e que o alerta interno
continua saindo.

---

## Dia 6 — Observabilidade e carga

### 6.1 Métricas

**Já existem:** transações avaliadas, duplicadas descartadas, alertas gerados.

**Faltam as de negócio**, que são as que diferenciam:

| Métrica                                | O que detecta                                            |
|----------------------------------------|----------------------------------------------------------|
| Taxa de disparo **por regra**          | Salto = regra quebrada ou ataque real. A mais importante |
| Alertas por cliente                    | Fadiga de alerta                                         |
| Eventos descartados por atraso         | Degradação a montante                                    |
| Profundidade e **idade** da fila morta | Fila que não drena é um segundo incidente                |

**Cuidado obrigatório:** nenhuma métrica pode usar identificador de cliente ou transação como rótulo. O Prometheus
tentaria criar milhões de séries e morreria.

### 6.2 Painéis

Dois, porque as perguntas são diferentes:

| Público        | Pergunta          | Conteúdo                            |
|----------------|-------------------|-------------------------------------|
| Plantão        | "Está de pé?"     | Fila, latência, erros, disjuntores  |
| Time de fraude | "Está acertando?" | Taxa por regra, alertas por cliente |

Provisionados por arquivo, versionados no repositório — configuração feita pela interface se perde ao recriar o
contêiner.

### 6.3 Alerta de ausência

**O que quase ninguém escreve:** se o motor passa 10 minutos sem gerar alerta nenhum, isso é incidente, não sucesso.
Calibragem **relativa ao tráfego esperado**, porque às 3h o volume cai naturalmente.

### 6.4 Teste de carga

**Como:** script k6 publicando direto no Kafka, medindo de ponta a ponta — da publicação até o alerta aparecer no
tópico.

**O que registrar no README:** vazão sustentada, p99, e **qual gargalo apareceu primeiro**.

É o item de maior retorno do dia: troca a estimativa de ~2.500 transações por segundo por thread por um número medido.
Se o real for muito diferente, o dimensionamento de cinco máquinas precisa ser recalculado — e ter feito a conta e
depois corrigido é uma história melhor que ter acertado por sorte.

---

## Dia 7 — Documentação e apresentação

- README com execução em um comando e o número medido do teste de carga
- `docs/architecture.md` revisado com o que a implementação ensinou
- ADRs das decisões principais
- **Seção de uso de IA** — exigência da página 4 do PDF, já escrita, revisar
- Roteiro dos 30 minutos de apresentação

---

## O que cortar se o prazo apertar

Em ordem — o primeiro é o que menos dói:

1. **Segundo painel do Grafana.** Um resolve.
2. **Mediana.** Manter média e declarar a limitação.
3. **`auditoria`.** O tópico já é a trilha; a base é índice de consulta.
4. **Regras compostas.** Mas o PDF pede explicitamente, então é o último recurso.
5. **Reduzir de cinco para três regras.**

**O núcleo inegociável:** simulador → motor com janelas → regras em YAML sem redeploy → alerta → notificação com
resiliência → teste de carga medido. Isso responde aos quatro itens do desafio.

---

## Já descartado, não fazer

Para não reabrir discussão encerrada:

- **mTLS e KMS implementados** — ficam desenhados, com o ponto de entrada declarado
- **Modo sombra e canário implementados** — documentados; o interruptor de desligamento é a resposta ao incidente de
  falso positivo, e esse está no dia 4
- **Tópico de perfis co-particionado** — o motor calcula o perfil comportamental sozinho
- **Camada anticorrupção** — não há integração com legado no desenho
- **Infraestrutura como código** — só a seção de deploy no documento
- **API de gerenciamento de regras** — Git é o plano de controle
