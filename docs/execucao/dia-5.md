# Dia 5 — As saídas

> `notificacao` e `auditoria`. O checkpoint do dia é o item 4 do desafio: provar que a queda de um
> serviço auxiliar não interrompe a detecção.

---

## `notificacao`

Consumidor **explícito**, com `@KafkaListener` recebendo `Alerta`. Diferente do motor: aqui não há memória por cliente
para gerenciar, então o Kafka Streams não se pagaria.

É a **única integração síncrona do sistema inteiro** — a chamada ao provedor de push e e-mail.

### A ordem da deduplicação de entrega

```
reserva no Redis (60s)  ─►  envia  ─►  estende para 24h
       │                       │
   já existe?               falhou?
   descarta              libera a reserva
```

Cada passo responde a uma falha específica:

| Passo                               | Por quê                                                                             |
|-------------------------------------|-------------------------------------------------------------------------------------|
| Reserva **antes** de enviar         | Se duas instâncias pegarem o mesmo alerta, só uma envia                             |
| Validade curta de 60s               | Se cair entre reservar e enviar, a reserva expira e a fraude é avisada na reentrega |
| Libera a reserva se o envio falha   | Sem isso o cliente **nunca** seria avisado daquela fraude                           |
| Estende para 24h só depois do envio | Confirma o que de fato aconteceu                                                    |

Gravar direto com 24 horas seria mais simples e trocaria duplicata por **perda** — inaceitável para fraude. Há teste
verificando a ordem com `InOrder`.

**Se o Redis cair, envia assim mesmo.** Melhor duplicar que silenciar, com
`antifraude_notificacao_redis_indisponivel_total` registrando.

### Roteamento

A decisão viaja no alerta: o `notificacao` não conhece regra nenhuma.

| Severidade                | Canais            |
|---------------------------|-------------------|
| ALTA                      | push **e** e-mail |
| MÉDIA                     | só push           |
| `notificarCliente: false` | nenhum            |

### Resiliência

Disjuntor e repetição do Resilience4j sobre a chamada ao provedor: janela de 20 chamadas, abre em 50% de falha, espera
20s, e repetição com espera crescente e **variação aleatória** — sem ela, todas as instâncias repetiriam no mesmo
instante e derrubariam de novo o serviço que estava voltando.

O que não entrega vai para o tópico `notificacoes-dlq`.

---

## `auditoria`

Consumidor comum gravando no Postgres. **Troquei JPA por JDBC**: o serviço só escreve, não precisa de mapeamento de
entidade nem de contexto de persistência.

Tabela **particionada por mês** com `PRIMARY KEY (alerta_id, horario_avaliacao)` e
`INSERT ... ON CONFLICT DO NOTHING`.

**A idempotência sai de graça:** o banco recusa a segunda linha e nenhuma lógica precisa existir. O contador
`antifraude_auditoria_ja_gravados_total` mede quantas reentregas foram contidas.

`valores_entrada` vai em `JSONB`, então dá para consultar por dentro — *"quais alertas dispararam com ticket médio
abaixo de X"* é uma query, não um reprocessamento.

---

## O checkpoint: derrubar o provedor

`POST /provedor/derrubar` e três transações de R$ 6.000:

|                            | Resultado                                                  |
|----------------------------|------------------------------------------------------------|
| Motor continuou detectando | 4 alertas gravados na auditoria                            |
| Disjuntor                  | abriu e passou a `half_open`                               |
| Fila morta                 | 5 alertas retidos — `valor-absoluto` ×3, `soma-na-hora` ×2 |
| Ao levantar o provedor     | entrega voltou na transação seguinte                       |

**A queda do provedor externo não interrompeu a detecção nem a trilha de auditoria.** É o item 4 do desafio, demonstrado
em execução.

---

## Um problema encontrado depois

O arquivo de migração do Flyway foi **reformatado depois de aplicado**, e o checksum deixou de bater — a `auditoria`
parou de subir. Conferi que as 15 colunas no banco eram idênticas às do arquivo e reparei o checksum, preservando a
trilha.

**A lição é geral: migração aplicada nunca se edita, nem a formatação.** Em produção isso trava o deploy de todos os
ambientes que já rodaram a versão. O correto é sempre uma migração nova.

---

## O que ficou de fora

- **A fila morta acumula e ninguém a drena.** O reprocessamento automático está descrito no tópico 4 e não implementado.
  A métrica de idade existe e o alerta dispara aos 30 minutos, mas a ação ainda é manual.
- **O provedor é simulado.** Latência artificial de 5 a 40 ms e um interruptor para derrubá-lo.
- **Sem criptografia de campo na auditoria.** É o ativo mais sensível do sistema e está declarado no tópico 2 como
  pendente. Hoje o que protege é o token no lugar do número do cartão.
