# Diário de execução

O que foi construído a cada dia, o que deu errado e o que isso ensinou.

O raciocínio das decisões está em [solucao.md](../solucao.md). Aqui fica o processo.

---

## Dia 1 — Fundação

Repositório, projeto em módulos, e o ambiente subindo com um comando: Kafka, MongoDB, PostgreSQL, Redis, Prometheus e
Grafana.

O formato do evento de transação e o **simulador**, que gera carga a partir de 200 mil clientes fictícios. Ele veio
antes de qualquer aplicação de produção porque nada podia ser testado sem transações chegando.

**Verificado:** o mesmo cliente cai sempre na mesma parte do tópico, e clientes diferentes se espalham pelas 64.

**O que deu errado:** o Grafana ignorava a configuração de fonte de dados. O contêiner tinha sido criado antes da pasta
existir, então montou uma pasta vazia. Reiniciar não resolve — foi preciso recriar. Lição pequena e cara: montagem de
volume acontece na criação, não no restart.

---

## Dias 2 e 3 — O motor

O núcleo, e onde estava o maior risco do projeto.

A topologia do Kafka Streams com memória por cliente, garantia de atomicidade e cópia morna. As janelas de 5 e 60
minutos, o registro de transações já vistas, e as primeiras regras — ainda fixas em código, de propósito: provar que as
janelas funcionam antes de somar o motor de expressões.

**Checkpoint atingido:** o simulador dispara uma sequência suspeita e o alerta correto sai do outro lado.

### O erro que a operação revelou

Depois de meio milhão de transações, o motor começou a quebrar com "registro grande demais".

A memória de cada cliente era regravada por inteiro a cada transação, e para clientes muito ativos ela passou de 1 MB —
o limite de uma mensagem do Kafka. Um cliente que fizesse duas transações por segundo estourava.

**A correção:** teto de 200 eventos e 500 identificadores por cliente, com um indicador sinalizando quem chegou no
teto — que é como se detecta cliente concentrando carga.

**Verificado:** 520 mil transações reprocessadas sem uma única falha.

### Uma reestruturação por crítica

A primeira organização das pastas seguia camadas técnicas. Ficou ruim de ler: abrir o projeto não dizia nada sobre o
problema.

Reorganizado por assunto — `memoria`, `regra`, `deteccao`, `kafka` — com um teste automatizado que falha o build se o
núcleo importar framework. A pureza é verificada, não confiada.

---

## Dia 4 — Regras sem novo deploy

O requisito mais visível do enunciado.

As três regras que estavam em código Java foram **removidas**. Agora elas vivem em
`regras/regras.yml`, viram documentos no MongoDB e são recarregadas a cada 30 segundos.

Entraram também: as regras combinadas com desligamento em cascata, o interruptor operacional, e proteção para que uma
regra com defeito não derrube a máquina.

**Demonstrado com o sistema no ar:** baixar o limite de uma regra fez a versão nova valer em 20 segundos. Adicionar uma
regra que não existia levou 19 segundos até ela disparar.

### Uma afirmação minha que os testes derrubaram

A documentação dizia que a linguagem das regras, por ser tipada, recusaria campo inexistente na esteira. **Escrevi dois
testes para provar isso e os dois falharam.**

Do jeito que o contexto é declarado, o compilador aceita qualquer nome de campo. O erro só apareceria em produção.

A correção foi melhor que a afirmação original: **toda regra é executada contra um exemplo antes de entrar em uso**.
Isso pega campo inexistente, condição que não devolve sim ou não, e referência a regra que não existe.

### Um bug que só o teste de ponta a ponta encontraria

A regra de geografia comparava a cidade da transação com "a cidade anterior". Só que a memória é atualizada antes das
regras rodarem — então "a cidade anterior" era a cidade da **própria transação sendo avaliada**.

A comparação nunca podia ser verdadeira. **A regra era silenciosamente inútil**, e a suíte de testes unitários passava.

---

## Dia 5 — As saídas

`notificacao` e `auditoria`.

O serviço de notificação tem a única chamada síncrona do sistema inteiro. A ordem da entrega é o ponto delicado:

```
reserva (60s)  →  envia  →  confirma por 24h
     │              │
 já existe?     falhou?
  descarta    libera a reserva
```

Cada passo responde a uma falha específica. A reserva curta existe para que uma queda entre reservar e enviar **se
conserte sozinha**. Gravar direto com 24 horas seria mais simples e trocaria repetição por **perda** — inaceitável para
fraude.

A auditoria grava no PostgreSQL com chave única. **A proteção contra gravação repetida sai de graça:**
o banco recusa a segunda linha, sem uma linha de lógica.

**Checkpoint atingido:** derrubei o provedor de push e e-mail. A detecção continuou, os alertas continuaram sendo
gravados, o disjuntor abriu, a fila morta acumulou, e quando o provedor voltou a entrega retomou sozinha.

### Um problema chato e instrutivo

O arquivo de migração do banco foi **reformatado depois de já ter sido aplicado**, e a assinatura dele deixou de bater.
A aplicação parou de subir.

Conferi que as colunas no banco eram idênticas às do arquivo e reparei a assinatura, preservando a trilha.

**A lição vale para qualquer projeto: migração aplicada nunca se edita, nem a formatação.** Em produção isso trava o
deploy de todo ambiente que já rodou aquela versão.

---

## Dia 6 — Monitoração e teste de carga

Os indicadores de negócio, dois painéis versionados junto do código, e sete alertas — cada um com o procedimento de
plantão escrito na própria definição.

O mais importante é o **alerta de ausência**: se há tráfego entrando e nada saindo por 10 minutos, isso é incidente.
Testado de propósito, desligando todas as regras com a carga ligada.

### Três erros de instrumentação

**Primeiro:** usei um tipo de indicador que calcula os percentis dentro de cada máquina. Além de quebrar a consulta do
painel, é errado em produção — **média de percentis de cinco máquinas não é o percentil do conjunto**.

**Segundo:** o indicador de tempo tinha teto de 5 segundos. Quando tudo passa do teto, o cálculo devolve o próprio teto
para qualquer percentil — e eu li isso como "processamento lento", quando era o instrumento cego.

**Terceiro:** um painel marcava 200% de transações alertadas, o que é impossível. A causa é legítima:
**uma transação pode acionar várias regras**. Contar alertas e dividir por transações não dá percentual.

### O teste de carga

Metade do problema era o ambiente: o Kafka não tinha volume de dados configurado, gravando na camada mais lenta do
Docker. Corrigido, a capacidade de escrita foi de 19 mil para **175 mil mensagens por segundo**.

Com isso, o motor sustentou **8 mil transações por segundo** — a média que o enunciado pede — em uma única máquina.

O gargalo não era processador: durante a queda, 90% dos núcleos estavam ociosos. Era gravação. Cada transação de 192
bytes faz o sistema escrever cerca de 1.400 bytes, porque a memória do cliente é regravada por inteiro.

**A descoberta mais útil:** o motor decide em menos de meio milésimo de segundo, e o tempo total até a decisão é de
quase meio segundo. **Praticamente tudo é espera em fila.**

Os números completos estão em [solucao.md](../solucao.md), seção 9.

---

## Um experimento que valeu a pena

Em algum momento a pergunta apareceu: *"o Kafka Streams não ficou complexo demais?"*

Em vez de discutir, fiz uma cópia do motor trocando o Kafka Streams por Redis. Mesmas regras, mesmo comportamento —
conferi que os dois davam resultado idêntico para as mesmas transações.

O Kafka Streams entregou 8.000 transações por segundo decidindo em 0,20 ms; a versão com Redis, 5.000 por segundo em
1,17 ms. E a tentativa de melhorar o Redis gravando só o que mudou o deixou **mais lento ainda**, porque o custo dele
não são os bytes, é o número de idas até o servidor.

A cópia foi apagada depois de medida. O que ficou foi a comparação com números, em
[solucao.md](../solucao.md), seção 4.

---

## O que continua em aberto

| Item                           | Situação                                                                     |
|--------------------------------|------------------------------------------------------------------------------|
| Gravação amplificada           | Medida em 7,5 vezes. A correção estrutural está desenhada                    |
| Testes dentro de cada regra    | Não existem. Foi assim que uma regra que nunca disparava passou despercebida |
| Reprocessar a fila morta       | O alerta avisa; a ação é manual                                              |
| Resposta do cliente ("fui eu") | O sinal mais direto de acerto, e o que destrava perfil congelado             |
| Certificado entre serviços     | Desenhado, não implementado                                                  |
| Terraform                      | O ambiente sobe com Docker Compose, que não serve para produção              |
