# Motor de detecção de fraude — a solução

Este documento explica **o que foi construído, por que cada decisão foi tomada e o que ela custou**.

Quando uma escolha teve alternativa razoável, a alternativa está aqui junto com o motivo de não ter sido escolhida. Onde
algo não foi implementado, está dito.

---

## 1. O problema

O sistema recebe transações de cartão, decide se cada uma é suspeita e avisa quem precisa saber.

O enunciado pede quatro coisas:

1. Receber eventos de transação em tempo real
2. Aplicar lógica de detecção
3. Gerar alertas para o time antifraude e para o cliente
4. Continuar funcionando quando um serviço auxiliar cair

E impõe três números: **8.000 transações por segundo em média**, **25.000 no pico**, e **resposta em até meio segundo**.

Oito mil por segundo dão cerca de 690 milhões de transações por dia. O pico de 25 mil é maior que o volume do PIX
brasileiro nos momentos mais movimentados.

### A dificuldade real

Nenhuma máquina sozinha aguenta 25 mil eventos por segundo com folga. Então o trabalho precisa ser dividido entre
várias.

**Só que fraude não se detecta olhando uma transação isolada.** Uma compra de R\$ 800 é normal para uma pessoa e absurda
para outra. Para saber a diferença, o sistema precisa lembrar do histórico daquele cliente.

E aí está o conflito que organiza todo o resto:

> O volume exige **muitas máquinas**. A detecção exige que a **memória de cada cliente esteja
> inteira em um lugar só**.

Se as transações de uma pessoa forem parar em máquinas diferentes, cada máquina vê um pedaço do comportamento dela, e
nenhuma consegue decidir direito.

### Uma decisão de escopo, logo no começo

**O sistema detecta e avisa. Ele não bloqueia a transação.**

Bloquear exige responder de forma síncrona, em milissegundos, e assumir a responsabilidade de recusar uma compra
legítima. Isso é papel do autorizador do cartão, não de um motor de detecção.

Essa escolha simplifica muita coisa: sem bloqueio, o sistema não fica no caminho crítico do pagamento, e um problema
nele não impede ninguém de comprar.

---

## 2. Como o sistema funciona

```
sistemas de origem
        │  publicam direto no Kafka
        ▼
   tópico "transacoes"  ──  dividido em 64 partições, pela identificação do cliente
        │
        ▼
      motor  ──────────  memória de cada cliente + regras vindas do banco
        │
        ▼
   tópico "alertas"
        │
        ├──►  notificação  ──►  push e e-mail para o cliente
        ├──►  auditoria    ──►  banco Postgres
        └──►  time antifraude
```

### A ideia central: dividir por cliente

O tópico de entrada é dividido em 64 **partições**. A partição que cada transação recebe é decidida pela identificação
do cliente.**

#### Como a conta funciona

Quando a transação é publicada, o Kafka pega o identificador do cliente e faz três coisas:

```
1. Transforma o texto num número                murmur2("cli-000042")  →  -1234567890
2. Descarta o sinal de negativo                 & 0x7fffffff           →   1234567890
3. Pega o resto da divisão pelo número de partições  % 64             →   26
```

O `murmur2` é uma função de espalhamento: ela transforma qualquer texto num número, e textos parecidos dão números
completamente diferentes. Isso evita que todos os clientes cujo identificador começa igual caiam no mesmo lugar.

O importante é que **a conta é sempre a mesma**. O `cli-000042` vai dar 26 hoje, amanhã e daqui a um ano. Não há sorteio
nem rodízio.

**Verificado no projeto:** publiquei a mesma transação seis vezes e as seis foram para a partição 60. O endpoint
`/simulador/verificar-particao` existe para você repetir esse teste.

#### Por que isso resolve o problema difícil

Cada partição é lida por **no máximo uma máquina de cada vez** — é garantia do Kafka. Somando as duas coisas:

> Se o cliente sempre cai na mesma partição, e a partição é lida por uma máquina só, então **todas as
> transações daquela pessoa passam sempre pela mesma máquina**.

E aí a memória dela pode ficar ali, dentro da máquina, sem consultar banco nenhum.

Essa única decisão resolve quatro problemas de uma vez:

| Problema                  | Como se resolve                                                 |
|---------------------------|-----------------------------------------------------------------|
| Volume alto               | Cada máquina cuida de uma fatia dos clientes                    |
| Memória do cliente        | Fica junto de quem processa                                     |
| Ordem dos eventos         | Uma partição é lida por uma máquina só, então a ordem se mantém |
| Não repetir o mesmo aviso | A informação necessária está toda na mesma máquina              |

### Por que 64 partições

**Partição não é máquina.** É uma divisão lógica dentro do Kafka. Uma máquina cuida de várias partições ao mesmo tempo.

#### Como partições, máquinas e threads se encaixam

Cada máquina roda **4 threads** de processamento. A distribuição é assim:

```
5 máquinas  ×  4 threads cada   =  20 threads no total
64 partições  ÷  20 threads     =  3 ou 4 partições por thread
```

Três regras governam isso:

|                                                   |                                                |
|---------------------------------------------------|------------------------------------------------|
| Uma partição é lida por **uma thread só**         | é o que garante a ordem e a memória coerente   |
| Uma thread cuida de **várias partições**          | 3 ou 4, no nosso caso                          |
| **Não adianta ter mais threads do que partições** | com 64 partições, o máximo útil são 64 threads |

**Não é uma thread por partição.** Se fosse, precisaríamos de 64 threads — 16 máquinas — para usar todas. Com 20
threads, cada uma se reveza entre 3 ou 4 partições, processando as transações de cada uma em sequência.

#### A conta de capacidade

Medimos que **cada thread processa cerca de 2.500 transações por segundo**. A partir daí:

| Máquinas | Threads | Capacidade    | Cobre o pico de 25 mil?                   |
|----------|---------|---------------|-------------------------------------------|
| 3        | 12      | ~30 mil/s     | sim, sem folga                            |
| 4        | 16      | ~40 mil/s     | sim                                       |
| **5**    | **20**  | **~50 mil/s** | **sim, com o dobro de folga** ← escolhido |
| 16       | 64      | ~160 mil/s    | teto útil: uma thread por partição        |
| 20       | 80      | ~160 mil/s    | **16 threads ficariam ociosas**           |

A última linha mostra por que 16 máquinas é o teto prático: passando disso, as threads a mais não recebem partição
nenhuma e ficam paradas.

#### Por que 4 threads, e o que a medição disse

O número está em um lugar só, em `motor/src/main/resources/application.yml`:

```yaml
num.stream.threads: 4
```

O raciocínio era: o trabalho é de processador, então threads devem acompanhar o número de núcleos — e assumi uma máquina
de produção com 4 núcleos.

Então medi, na mesma máquina e com a mesma carga:

| Threads | Vazão             | Processador usado |
|---------|-------------------|-------------------|
| 4       | 9.778 por segundo | 152% de 1.000%    |
| 12      | 9.830 por segundo | 237% de 1.000%    |

**Triplicar as threads não mudou nada** — meio por cento de diferença, dentro da variação normal — e gastou 56% mais
processador em coordenação.

Isso reforça o diagnóstico do teste de carga por outro caminho: se o problema fosse capacidade de processar, 12 threads
teriam entregado mais.

#### Por que não 20 partições, se só vamos usar 5 máquinas

Porque **mudar esse número depois é destrutivo.**

O número de partições está dentro da conta: `% 20` e `% 64` dão resultados diferentes. Se você criar 20 hoje e precisar
de 64 amanhã:

```
hoje:   cli-000042  →  1234567890 % 20  =  10
amanhã: cli-000042  →  1234567890 % 64  =  26
```

**Todo cliente muda de lugar.** E como a memória dele está guardada na máquina que cuidava da partição 10, ela fica para
trás — a máquina da partição 26 não sabe nada sobre aquela pessoa. Na prática, o sistema perde o histórico
comportamental de todo mundo de uma vez.

Criar 64 desde o início custa quase nada: partições ociosas não consomem recursos relevantes. Criar poucas e precisar
aumentar custa um dia muito ruim.

**O custo dessa escolha:** 64 vira o teto. Passar disso exige exatamente a operação que estamos evitando.

### Por que 5 máquinas e não 4

Quatro máquinas dariam 40 mil por segundo, o que já cobre o pico de 25 mil. A quinta existe por dois motivos que não
aparecem na conta de capacidade.

**Primeiro: uma máquina vai cair.** Com 4 máquinas, perder uma deixa 30 mil por segundo — ainda acima do pico, mas sem
folga nenhuma, e no pior momento possível. Com 5, perder uma deixa 40 mil, que é o mesmo que as 4 dariam em condição
normal.

**Segundo: a cópia morna.** Cada máquina mantém, além da memória dos clientes que ela atende, uma **cópia da memória de
outra máquina**, atualizada em tempo real.

Isso importa porque a memória local tem um problema: se a máquina morre, quem assume precisa reconstruir tudo lendo o
histórico do Kafka — e isso leva minutos, durante os quais aquela fatia de clientes fica sem detecção.

Com a cópia morna, a máquina que assume **já tem a memória quase pronta** e volta a operar em segundos.

|             | Sem cópia morna        | Com cópia morna       |
|-------------|------------------------|-----------------------|
| Máquina cai | minutos sem detecção   | segundos              |
| Deploy      | mesma espera, toda vez | rápido                |
| Custo       | —                      | dobra a memória usada |

E o deploy é o caso que mais importa: ele acontece toda semana, de propósito. Sem a cópia morna, todo deploy teria
minutos de detecção degradada.

### As regras seguem o caminho contrário

|            | Transações                      | Regras                |
|------------|---------------------------------|-----------------------|
| Vêm de     | Kafka                           | Banco de dados        |
| São        | **divididas** entre as máquinas | **copiadas** em todas |
| Quantidade | 25 mil por segundo              | cerca de 5 documentos |

Cada máquina precisa de todas as regras para avaliar os clientes dela. **Cliente se divide; regra não.**

---

## 3. A stack

| Peça                        | Para quê                              | Por que essa                                                                                |
|-----------------------------|---------------------------------------|---------------------------------------------------------------------------------------------|
| **Java 21 + Spring Boot 3** | As quatro aplicações                  | Ver abaixo                                                                                  |
| **Apache Kafka**            | Transporte e divisão por cliente      | Única opção que aguenta o volume, **guarda o histórico** e divide por chave de forma nativa |
| **Kafka Streams**           | Memória por cliente                   | Dá memória local com cópia de segurança automática, sem banco externo                       |
| **MongoDB**                 | Onde as regras ficam                  | Documento flexível; a regra tem forma livre                                                 |
| **CEL**                     | Linguagem das regras                  | Não tem laço nem recursão — **toda expressão termina**                                      |
| **PostgreSQL**              | Trilha de auditoria                   | Consulta estruturada e divisão por mês madura                                               |
| **Redis**                   | Não avisar duas vezes o mesmo cliente | Rápido e com validade automática por chave                                                  |
| **Resilience4j**            | Proteção contra o provedor externo    | Disjuntor, repetição e tempo limite numa biblioteca só                                      |
| **Prometheus + Grafana**    | Monitoração                           | Padrão de mercado, painel versionado junto do código                                        |

### O que o Java 21 entrega aqui

Não é escolha por costume. Três coisas da versão 21 são usadas de verdade neste projeto:

**Coletor de lixo com pausas curtas.** Toda aplicação Java para de vez em quando para limpar memória não usada. Nas
versões antigas essas pausas chegavam a centenas de milissegundos — o que, num sistema com prazo de 500 ms, come metade
do orçamento de uma vez. O coletor moderno mantém as pausas **abaixo de 1 milissegundo**. Medimos: 780 pausas somaram
2,4 segundos ao longo de todo um teste de carga.

**`record`.** A memória do cliente e o alerta são declarados como registros — objetos que não mudam depois de criados.
Isso não é enfeite: com vários threads processando ao mesmo tempo, objeto que não muda **não pode ser corrompido por
concorrência**. O compilador garante o que antes dependia de disciplina.

**Texto em bloco e `switch` moderno.** As condições em SQL e as mensagens ficam legíveis, sem concatenação. Reduz erro
bobo.

### O que o Spring Boot 3 entrega

**A parte chata pronta.** Consumir de fila, expor métricas, ler configuração, ligar no banco, subir servidor — nada
disso é o problema que estamos resolvendo, e nada disso precisou ser escrito.

**Injeção de dependência, que é o que torna a troca de regras possível.** O motor recebe uma
`FonteDeRegras` sem saber de onde as regras vêm. Trocar "regras fixas no código" por "regras vindas do banco" foi trocar
uma implementação, sem tocar em quem usa.

**Métricas de graça.** O `actuator` já expõe memória, threads, pausas do coletor e saúde das conexões. As métricas de
negócio foram as únicas que precisei escrever.

**O custo:** o Spring esconde muita coisa. Quando algo dá errado, o rastro de erro tem 50 linhas de framework antes de
chegar ao seu código. É o preço de não escrever a parte chata.

### As quatro aplicações

| Aplicação     | Lê                  | Escreve                                                   |
|---------------|---------------------|-----------------------------------------------------------|
| `motor`       | transações + regras | alertas                                                   |
| `notificacao` | alertas             | push e e-mail                                             |
| `auditoria`   | alertas             | Postgres                                                  |
| `simulador`   | —                   | transações *(ferramenta de teste, não vai para produção)* |

---

## 4. As ferramentas: o que foi escolhido e o que foi descartado

### Para transportar as transações

**Escolhido: Apache Kafka.**

Três coisas precisavam acontecer juntas: aguentar o volume, guardar o que passou para poder reprocessar, e dividir as
mensagens por cliente. O Kafka faz as três nativamente.

Guardar o histórico importa mais do que parece. É o que permite responder *"por que não pegamos essa fraude?"* — você
volta no tempo e reprocessa com a regra corrigida.

**O custo:** é complexo de operar. Em produção usaríamos uma versão gerenciada.

**RabbitMQ — descartado.** Foi feito para distribuir tarefas, não para guardar histórico. Sem histórico, some o
reprocessamento, que é uma das capacidades mais valiosas aqui.

**Chamada HTTP direta entre origem e motor — descartada.** Sem fila, se o motor cai a transação se perde. E a lentidão
do motor viraria problema de quem chama.

### Para guardar a memória de cada cliente: Kafka Streams x Redis

Essa foi a decisão mais disputada do projeto, então ela não ficou no argumento. **A alternativa foi construída e
medida.**

Fiz uma cópia do motor trocando apenas onde a memória mora: consumidor Kafka comum no lugar do Kafka Streams, e Redis no
lugar da memória local. Mesmas regras, mesma avaliação, mesmos alertas — conferi que os dois produziam resultado
idêntico para as mesmas transações.

A cópia foi removida do repositório depois de medida. **Os números abaixo são o que ela deixou.**

#### A diferença de fundo, em uma frase

|                   | Onde a memória fica       | O que custa ler           |
|-------------------|---------------------------|---------------------------|
| **Kafka Streams** | dentro da própria máquina | uma leitura local         |
| **Redis**         | em outro servidor         | uma ida e volta pela rede |

Todo o resto decorre disso.

#### Duas formas de guardar no Redis

Testei as duas, porque elas têm desempenhos bem diferentes.

**Gravando o objeto inteiro.** A memória do cliente — a lista de compras recentes, o quanto ele costuma gastar, a última
cidade — vira um texto só e é gravada de uma vez.

```
lê    →  "{eventos:[...30 compras...], ticketMedio: 6800, ...}"   ~5 KB
grava →  "{eventos:[...31 compras...], ticketMedio: 6900, ...}"   ~5 KB
```

Chegou uma compra nova? Lê as 30, acrescenta a 31ª em memória, e **regrava as 31**. Simples de escrever, mas move muito
dado para mudar pouca coisa.

**Gravando só o que mudou.** A lista de compras vira uma estrutura própria do Redis, onde dá para acrescentar um item
sem tocar nos outros. O perfil vira um mapa, onde dá para mudar um campo só.

```
acrescenta  →  a compra nova                    ~80 bytes
atualiza    →  dois campos do perfil            ~40 bytes
```

As 30 compras anteriores não são lidas nem regravadas.

#### Os números medidos

Mesma carga, mesma máquina, cada motor rodando sozinho:

|                       | Transações por segundo | Tempo para decidir |
|-----------------------|------------------------|--------------------|
| **Kafka Streams**     | **~8.000**             | **0,20 ms**        |
| Redis, objeto inteiro | ~5.000                 | 1,17 ms            |
| Redis, só o que mudou | ~3.500                 | 3,79 ms            |

**O Kafka Streams decide seis vezes mais rápido** e entrega 60% mais transações por segundo.

#### A surpresa: gravar menos deixou mais lento

A expectativa era óbvia — mover 50 vezes menos dados deveria ser mais rápido. **Foi o contrário.**

A explicação está em contar as conversas com o Redis, não os bytes:

|                | Conversas por transação          | Dados   |
|----------------|----------------------------------|---------|
| Objeto inteiro | **2** (uma leitura, uma escrita) | ~10 KB  |
| Só o que mudou | **~11**                          | ~0,2 KB |

Gravar só o que mudou exige mais comandos separados: um para marcar a transação como vista, um para ler a lista, um para
ler o perfil, um para acrescentar a compra, um para limpar o que expirou, três para atualizar campos do perfil, e assim
por diante.

**Cada comando é uma ida e volta pela rede, e custa cerca de 0,2 milissegundo.** Onze idas dão 2,2 ms — que é quase
exatamente o tempo que medimos.

> Economizar dados não adiantou porque **o gargalo nunca foram os dados**. Era o número de vezes que
> a aplicação precisava parar e esperar uma resposta.

> **O custo do Redis não são os bytes. É o número de vezes que você fala com ele.**

E numa rede de verdade, entre máquinas diferentes, cada ida custa duas a cinco vezes mais.

O Kafka Streams não faz ida nenhuma. Não duas, não onze.

#### Três coisas que o Kafka Streams dá e o Redis não

**A memória é reconstruível.** Ela é derivada do histórico que o Kafka já guarda. Se a máquina morre, outra reconstrói
sozinha. Com Redis, se os dados somem, o histórico de comportamento de todos os clientes some junto — e não há de onde
tirar de volta.

**Não existe dependência a mais para cair.** Se o Redis parar, o motor com Redis para junto: não há degradação possível.
O motor com Kafka Streams só depende do Kafka, que já é obrigatório.

**Ler, atualizar e publicar acontecem juntos.** O Kafka Streams garante que os três passos são atômicos. Com Redis, uma
queda no meio pode gravar a memória e não publicar o alerta.

#### O que o Redis ganharia

É justo dizer, porque não é pouco:

|                                           | Kafka Streams                 | Redis               |
|-------------------------------------------|-------------------------------|---------------------|
| Linhas de código específicas da abordagem | 345                           | 210                 |
| Subir uma máquina nova                    | reconstrói a memória: minutos | entra e já trabalha |
| Deploy                                    | mesma reconstrução, toda vez  | reinicia e pronto   |
| Cópia de segurança em outra máquina       | necessária                    | desnecessária       |

O código com Redis é mais simples de ler. Um consumidor comum recebendo uma transação é óbvio; uma topologia com
serializadores e armazenamento de estado não é.

#### Por que Kafka Streams mesmo assim

O requisito é decidir em meio segundo. Medimos que **mais de 99% desse tempo é espera em fila** — o que sobra para
decidir é pouco, e cada milissegundo gasto conversando com outro servidor sai desse orçamento.

A complexidade a mais do Kafka Streams compra exatamente uma coisa: **não precisar sair da máquina para lembrar quem é o
cliente.** Para este requisito, é a coisa certa para comprar.

O Redis continua no sistema, mas para outra coisa: evitar avisar o mesmo cliente duas vezes na entrega da notificação.
Ali ele é ideal, porque a operação é pequena e não está no caminho da decisão.

### Para escrever as regras

**Escolhido: CEL**, a linguagem de expressões criada pelo Google e usada nas políticas do Kubernetes.

A propriedade que decide é contraintuitiva: **CEL não permite laço nem recursão**. Parece limitação e é a maior
virtude — significa que **toda expressão termina, sempre**. Uma regra mal escrita não pode travar a máquina.

| Perigo           | Como o CEL fecha a porta                              |
|------------------|-------------------------------------------------------|
| Laço infinito    | Não existe na linguagem                               |
| Conta muito cara | Sem laço, o custo é proporcional ao tamanho do texto  |
| Acesso indevido  | Sem rede, sem arquivo, sem acesso ao resto do sistema |

**O custo:** é menos expressiva que uma linguagem completa. Regras que exigissem encadeamentos complexos não caberiam.
Nenhuma das nossas precisa.

**Drools — descartado.** É o motor de regras completo do mundo Java, maduro e com interface para o time de negócio. Mas
é pesado, o tempo de resposta é menos previsível, e a estrutura interna dele precisa ser reconstruída a cada mudança de
regra — o que briga com recarregar a cada 30 segundos.

**Groovy ou JavaScript — descartados.** Expressividade total, e é justamente o problema: permitem laço infinito, e
isolar um interpretador dentro da aplicação é notoriamente difícil de acertar.

**SpEL (do próprio Spring) — descartado, e era o mais tentador**, porque já vem junto e não adiciona dependência.
Descartado por segurança: o SpEL consegue chamar qualquer método e acessar o resto da aplicação. Para texto escrito por
outra pessoa, é buraco difícil de fechar.

**Regras em código Java — descartado.** Testável e rápido, mas mudar uma regra exigiria novo deploy, que é exatamente o
que o enunciado pede para evitar.

### Para onde as regras ficam guardadas

**Escolhido: MongoDB, alimentado por arquivos no Git.**

O arquivo `regras/regras.yml` é a fonte da verdade. Um script lê o arquivo e grava no Mongo. O motor percebe sozinho em
até 30 segundos.

**Por que o Git no meio:** ele já resolve, de graça, tudo que uma tela de administração precisaria construir.

| O que se precisa              | Quem resolve                |
|-------------------------------|-----------------------------|
| Quem pode mudar               | Permissão do repositório    |
| Aprovação de duas pessoas     | Regra de proteção do branch |
| Histórico de quem mudou o quê | `git log`                   |
| **E por quê**                 | A descrição do pull request |
| Validação e testes            | A esteira de integração     |
| Voltar atrás                  | `git revert`                |

**Uma tela de administração — descartada por custo.** Precisaria de endpoints, autorização, aprovação, versionamento,
histórico, validação e interface. Três a cinco dias, num prazo de sete.

**O contra-argumento honesto:** às 3 da manhã, abrir um pull request é lento demais. Por isso existe um atalho:

| Ação                    | Caminho              | Tempo           |
|-------------------------|----------------------|-----------------|
| Mudar o que a regra faz | Git, com revisão     | minutos a horas |
| **Desligar** uma regra  | Endpoint operacional | **segundos**    |

---

## 5. As regras de detecção

Todas ficam em `regras/regras.yml`, viram documentos no Mongo e são recarregadas a cada 30 segundos.

Em todos os exemplos abaixo, o cliente costuma gastar **R\$ 100** por compra.

### O que uma regra enxerga

|             | Contém                                                      |
|-------------|-------------------------------------------------------------|
| `transacao` | valor, cidade, país, tipo de estabelecimento, canal         |
| `janela5m`  | quantas transações e quanto somou nos últimos 5 minutos     |
| `janela60m` | o mesmo, na última hora                                     |
| `perfil`    | quanto o cliente costuma gastar e quantas transações já fez |
| `ultimo`    | a cidade da transação **anterior**                          |
| `regras`    | o resultado de outra regra, para regras combinadas          |

Nada além disso. Sem rede, sem arquivo, sem banco.

### Regra 1 — `velocidade-alta` (severidade alta)

**Dispara quando as três coisas acontecem juntas:**

- o cliente já tem pelo menos 5 transações no histórico
- fez mais de 3 transações nos últimos 5 minutos
- **esta** transação passa de R\$ 200 (o dobro do que ele costuma gastar)

Como o cliente gasta R\$ 100 por compra, o limite dele é R\$ 200.

| Exemplo                            | Dispara?                                      |
|------------------------------------|-----------------------------------------------|
| 4 compras de R\$ 800 em 2 minutos  | **sim**, na quarta — R\$ 800 passa de R\$ 200 |
| 4 compras de R\$ 150 em 2 minutos  | não — R\$ 150 **não chega** aos R\$ 200       |
| 3 compras de R\$ 800               | não — falta a quarta transação                |
| Cliente novo, 4 compras de R\$ 800 | não — precisa de 5 compras anteriores         |

Repare que o limite é **de cada cliente**. Para quem gasta R\$ 2.000 por compra, o limite seria R\$ 4.000, e as mesmas 4
compras de R\$ 800 não disparariam nada.

**Por que as duas condições juntas:** quatro compras pequenas é gente fazendo compras. Uma compra grande sozinha pode
ser legítima. **Muitas compras e todas grandes** é o padrão de cartão clonado — o fraudador com pressa, tirando o máximo
antes do bloqueio.

Cada metade sozinha geraria alarme falso. É a combinação que aponta fraude.

### Regra 2 — `valor-absoluto` (severidade média)

**Dispara quando:** uma única transação passa de **R\$ 5.000**.

Não olha histórico nem janela.

| Exemplo                   | Dispara?                    |
|---------------------------|-----------------------------|
| Uma compra de R\$ 6.000   | **sim**                     |
| Uma compra de R\$ 4.000   | não                         |
| Três compras de R\$ 6.000 | **sim, três vezes**         |
| Dez compras de R\$ 900    | não — nenhuma passa sozinha |

**Por que ela existe, se o limiar fixo é justamente o que queremos evitar?** Porque responde outra pergunta.

A regra 1 pergunta *"isso é estranho **para essa pessoa**?"*. Esta pergunta *"isso é grande o bastante para o erro sair
caro, **seja quem for**?"*.

A primeira detecta anomalia. A segunda limita prejuízo. E ela cobre dois buracos da primeira:
funciona para **cliente novo**, que não tem histórico, e **não pode ser enganada** por alguém que manipulou o histórico
devagar.

Severidade média, não alta: pergunta ao cliente sem acionar o time antifraude, porque não há indício de fraude — só
valor alto.

### Regra 3 — `soma-na-hora` (severidade alta)

**Dispara quando:** a soma dos últimos 60 minutos passa de **R\$ 10.000**.

| Exemplo                                    | Dispara?               |
|--------------------------------------------|------------------------|
| 3 compras de R\$ 4.000                     | **sim**, na terceira   |
| 2 compras de R\$ 4.000                     | não                    |
| Compras de R\$ 4.000 espalhadas em 3 horas | não — a janela esvazia |

**Ela existe para pegar o fraudador paciente.** Quem conhece a regra 1 simplesmente vai mais devagar:
três transações a cada cinco minutos, nunca mais. A regra 1 nunca dispara.

Só que em uma hora isso são 36 transações. **Invisível uma a uma, escandaloso no total.**

E como o limite é fixo, não olha o histórico do cliente — então manipular o histórico não ajuda o fraudador.

### Regra 4 — `cidade-diferente-no-ecommerce` (severidade média)

**Dispara quando as quatro coisas acontecem juntas:**

- é compra online
- existe uma cidade anterior registrada
- a cidade é diferente da anterior
- o valor passa de R\$ 300 (o triplo do normal do cliente)

Como o cliente gasta R\$ 100 por compra, **o limite dele aqui é R\$ 300**.

| Exemplo                                                       | Dispara?                                |
|---------------------------------------------------------------|-----------------------------------------|
| Compra online de R\$ 500, última em São Paulo, esta no Recife | **sim** — R\$ 500 passa de R\$ 300      |
| Compra online de R\$ 500, as duas em São Paulo                | não — mesma cidade                      |
| Compra na maquininha, R\$ 500, cidade diferente               | não — não é online                      |
| Compra online de R\$ 150, cidade diferente                    | não — R\$ 150 **não chega** aos R\$ 300 |

O valor alto entra na condição de propósito: mudar de cidade sozinho é comum demais — viagem, trabalho, mudança. Junto
com valor fora do padrão, aí sim vira sinal.

### Regra 5 — `combinacao-critica` (severidade alta)

**Dispara quando:** as regras 1 e 4 dispararam na **mesma transação**.

Velocidade alta sozinha pode ser compras de Natal. Cidade diferente sozinha pode ser viagem. **Juntas, é quase
certamente cartão clonado.**

Ela mostra uma capacidade importante: **regras podem se apoiar em outras**, sem duplicar lógica. O sistema descobre
sozinho a ordem de avaliação lendo o texto da regra, e se você desligar a regra 1, a regra 5 **cai junto
automaticamente** — porque uma regra combinada sem seus ingredientes não significa nada.

### Como o sistema decide o que é normal para cada cliente

O número que representa "quanto essa pessoa costuma gastar" é atualizado a cada transação. Cada nova compra empurra o
número um pouco na direção dela:

| Compra     | Conta                      | O normal fica |
|------------|----------------------------|---------------|
| R\$ 120    | 100 × 0,95 + 120 × 0,05    | R\$ 101       |
| R\$ 120    | 101 × 0,95 + 120 × 0,05    | R\$ 102       |
| R\$ 10.000 | 102 × 0,95 + 10.000 × 0,05 | R\$ 597       |

Repare na última linha: uma compra de R\$ 10.000 *não* transforma o normal em R\$ 10.000. Um pico não vira o novo
padrão.

O peso também cresce com o tempo: comportamento de meses atrás vai desaparecendo sozinho. A meia-vida é de 30 dias —
depois desse tempo, uma compra antiga vale metade.

---

## 6. Os requisitos funcionais: decisões e o que custaram

### 1. Receber eventos em tempo real

**Decisão: a origem publica direto no Kafka. Não existe API REST de entrada.**

Uma API na frente adicionaria uma aplicação inteira para manter e um salto de rede no orçamento de tempo, sem resolver
nada que o Kafka já não resolva.

**O custo:** quem publica precisa falar Kafka. Se algum sistema legado só souber HTTP, aí sim seria preciso um
adaptador — mas construir para um caso que não existe é desperdício.

### 2. Aplicar a lógica de detecção

**Decisão: as regras ficam fora do código, em arquivos publicados no banco.**

O motivo é uma corrida perdida por construção: fraudador muda de tática em dias, e um ciclo de release leva semanas.
Regra dentro do código deixa o banco estruturalmente atrasado em relação ao crime.

**O custo:** dar a alguém o poder de mudar o comportamento do sistema sem passar por deploy abre portas perigosas. Elas
foram fechadas assim:

| Perigo                                    | Defesa                                    |
|-------------------------------------------|-------------------------------------------|
| Regra travar a máquina                    | Linguagem sem laço                        |
| Regra com campo que não existe            | Testada contra um exemplo antes de entrar |
| Regra que quebra ao rodar                 | Isolada; as outras seguem funcionando     |
| Regra disparando demais                   | Endpoint que desliga em segundos          |
| Regra apontando para outra que não existe | Recusada ao carregar                      |

### 3. Gerar alertas para dentro e para fora

**Decisão: o motor publica no tópico `alertas`, e três destinos consomem dele.**

O time antifraude lê o tópico direto. Não criei um serviço no meio só para repassar — o tópico é tratado como contrato,
e a proteção contra quebra vem de validação de formato, não de uma camada extra.

**A decisão de quem avisar viaja dentro do alerta.** O motor grava dois campos: `notificarCliente` e a severidade. O
serviço de notificação obedece **sem conhecer regra nenhuma**.

Isso é importante: quando você criar a décima regra, o serviço de notificação não precisa saber que ela existe.

**O custo:** três consumidores presos ao formato do alerta. Mudar um campo exige coordenação. Por isso o formato só pode
crescer, nunca encolher.

### 4. Continuar funcionando mesmo com um serviço fora do ar

**Decisão: nenhuma chamada síncrona no caminho crítico.**

O motor não chama ninguém. Ele calcula o perfil do cliente sozinho, a partir das próprias transações.

Sobra **exatamente uma** chamada síncrona no sistema inteiro: o envio do push e do e-mail. É lá, e só lá, que mora o
disjuntor.

| Se cair                 | O que acontece                                               |
|-------------------------|--------------------------------------------------------------|
| Mongo (regras)          | O motor segue com as regras que já estão na memória          |
| Provedor de push/e-mail | Disjuntor abre, fila morta acumula, reprocessa quando voltar |
| Postgres (auditoria)    | Alertas ficam no Kafka e são gravados depois                 |
| Redis                   | Envia assim mesmo — melhor duplicar que silenciar            |
| Time antifraude fora    | Não é problema nosso; o tópico guarda                        |
| **Kafka**               | **Aí tudo para**                                             |

O Kafka é o único ponto sem degradação suave. A resposta é redundância, não plano B.

**Isso foi demonstrado**, não só desenhado: derrubei o provedor e as transações continuaram sendo avaliadas e gravadas
na auditoria.

---

## 7. Os seis tópicos não funcionais

### 7.1 Vazão e latência

São duas exigências diferentes, e é fácil confundir uma com a outra.

|              | O que pede                             | O número                          |
|--------------|----------------------------------------|-----------------------------------|
| **Vazão**    | dar conta de quantas transações chegam | 8 mil por segundo, 25 mil no pico |
| **Latência** | responder rápido em cada uma           | meio segundo                      |

**Dar conta do volume não garante responder rápido.** Essa foi a lição mais importante da medição, e vale explicar com
calma.

#### O problema de dividir o trabalho

Uma máquina não aguenta 25 mil por segundo. Então é preciso dividir entre várias.

Só que fraude não se detecta olhando a transação sozinha — é preciso comparar com o histórico do cliente. Se as
transações de uma pessoa forem parar em máquinas diferentes, cada uma vê um pedaço e nenhuma decide direito.

**A solução é a divisão por cliente**, explicada no tópico 2: cada máquina fica dona de uma fatia de clientes, e tem a
memória deles ali dentro.

#### Onde o tempo é gasto

Medimos separando duas coisas que antes estavam misturadas:

|                            | O que é                             | Quanto deu  |
|----------------------------|-------------------------------------|-------------|
| **Tempo de processamento** | quanto o motor leva para decidir    | **0,20 ms** |
| **Tempo total**            | do evento nascer até a decisão sair | **453 ms**  |

O motor decide em **dois décimos de milissegundo**. Mas a transação leva quase meio segundo para receber a resposta.

**A diferença é fila.** A transação chega, entra na fila de espera, e fica lá até chegar a vez dela.

```
transação nasce  →  [ espera na fila: 452 ms ]  →  motor decide: 0,2 ms  →  alerta
```

Mais de 99% do tempo é espera.

#### A consequência para o dimensionamento

Imagine uma fila de banco. Se chegam 10 pessoas por minuto e o caixa atende 10 por minuto, a fila nunca some — ela fica
parada num tamanho, e todo mundo espera.

Para a fila esvaziar, o caixa precisa atender **mais rápido do que chegam pessoas**.

É exatamente isso no sistema:

| Se o motor...                   | A fila                  | O prazo de meio segundo |
|---------------------------------|-------------------------|-------------------------|
| processa menos do que chega     | cresce sem parar        | estoura cada vez mais   |
| processa exatamente o que chega | fica parada num tamanho | estoura por causa dela  |
| **processa mais do que chega**  | **esvazia**             | **cumprido**            |

> **Vazão se resolve acompanhando o fluxo. Latência se resolve tendo folga.**

Por isso o dimensionamento é de 5 máquinas para um pico de 25 mil, e não de 3 — que dariam conta do volume, mas
manteriam fila permanente.

#### Como isso aparece na prática

Medido em três taxas diferentes:

| Chegando          | Fila                  | Tempo total |
|-------------------|-----------------------|-------------|
| 300 por segundo   | vazia                 | **11 ms**   |
| 3.000 por segundo | 30 mil esperando      | 704 ms      |
| 8.000 por segundo | 20 a 30 mil esperando | 1.955 ms    |

Repare que a 8 mil por segundo o motor **estava acompanhando** — processava 8 mil por segundo. Mesmo assim o prazo
estourava, porque a fila nunca esvaziava.

#### O que decorre disso na operação

**O sinal para crescer é o tamanho da fila, nunca o uso de processador.** Fila crescendo significa que o prazo vai
estourar, mesmo que o processador esteja tranquilo — e foi exatamente o que observamos.

**Mas antes de crescer é preciso saber por que a fila cresceu.** Se o gargalo não estiver na máquina que você
multiplicar, adicionar máquinas não resolve nada. No nosso teste o gargalo era gravação; ter cinco motores gravando no
mesmo lugar deixaria a fila igual e quatro máquinas ociosas.

**Provisionamos para o pico** em vez de crescer sob demanda, porque subir uma máquina nova dispara redistribuição das
partições e reconstrução de memória — o que piora as coisas justamente na hora do aperto.

### 7.2 Segurança e conformidade

**A dor tem três faces.** Vazamento externo é a óbvia. **Abuso interno é a maior** em banco. E a LGPD tem multa de até
2% do faturamento.

**A decisão que mais protege é não ter o dado.** O evento carrega um **token** no lugar do número do cartão, mais o
começo e o fim do número. A chave que divide os clientes é um identificador interno, nunca o CPF.

Isso é feito na origem, antes de a transação chegar. **Se o dado nunca entra, ele não pode vazar.**

**Duas camadas de identificação entre serviços:**

| Camada             | Prova                                     |
|--------------------|-------------------------------------------|
| Certificado (mTLS) | **qual máquina** está falando             |
| Token OAuth2       | **qual aplicação** e o que ela pode fazer |

**Permissões mínimas por tópico:** a origem só escreve em `transacoes`. O motor lê `transacoes` e só escreve em
`alertas`. Ninguém tem mais do que precisa.

**Sobre a LGPD, quatro pontos:**

O dado com token continua sendo dado pessoal e continua sob a lei — trocar o número por um código não anonimiza nada.

A base legal **não é consentimento**. Seria revogável, e um cliente não pode desligar a prevenção a fraude do banco. As
bases corretas são obrigação legal e legítimo interesse.

O sistema precisa registrar **qual versão de qual regra** disparou e com quais números. Está implementado: cada alerta
carrega isso.

Retenção tem duas forças opostas: as transações ficam 7 dias no Kafka; os alertas ficam anos no banco de auditoria, pelo
prazo regulatório.

**O que guardar na auditoria** foi decisão de custo:

|                         | Por dia               | Em 5 anos           |
|-------------------------|-----------------------|---------------------|
| Toda transação avaliada | 691 milhões de linhas | mais de um petabyte |
| **Só os alertas**       | 3,5 milhões           | ~9 TB               |

Guardamos só os alertas. Para responder *"por que não pegamos aquela fraude?"*, a resposta é reprocessar o histórico que
o Kafka guarda.

**Trade-off aceito:** certificado entre serviços e criptografia com cofre de chaves ficaram desenhados, não
implementados. Num prazo de sete dias, seriam infraestrutura pura consumindo tempo que rendeu mais no núcleo.

### 7.3 Consistência e não repetir

São dois problemas diferentes que costumam ser confundidos:

|                           | Pergunta                      | Causa           |
|---------------------------|-------------------------------|-----------------|
| **Repetição de mensagem** | "isso chegou duas vezes?"     | a rede reenviou |
| **Consistência**          | "isso chegou na ordem certa?" | a rede atrasou  |

**Por que mensagem repetida é inevitável:** quando um sistema envia algo e não recebe resposta, ele não consegue
distinguir "não chegou" de "chegou e a resposta se perdeu". Os dois cenários são idênticos de fora e pedem ações
opostas.

Para fraude, perder evento é inaceitável. Então escolhemos sempre reenviar e assumir a responsabilidade de tratar
repetição. **É decisão consciente.**

#### O estrago que uma mensagem repetida causa

Acompanhe o caso concreto. A regra dispara quando o cliente faz mais de 3 compras em 5 minutos.

**O que realmente aconteceu:** o cliente fez 3 compras. Comportamento normal, nada a fazer.

**O que o sistema vê:** a rede reenviou a segunda compra. O motor conta 4.

**O resultado:** a regra dispara. O cliente recebe um aviso de fraude que não existiu.

E o pior vem depois. Esse alarme falso é **indistinguível de fraude real** — no painel os dois aparecem igual. O time de
fraude vê a taxa de alarme falso subindo, conclui que a regra está apertada demais, e afrouxa o limite de 3 para 5
compras.

> A regra afrouxada agora deixa passar a fraude de verdade. **Uma mensagem repetida virou uma regra
> pior.**

#### Como o motor se defende

Toda transação chega com um identificador único, criado na origem. O motor guarda esse identificador numa lista e
consulta antes de processar:

```
chegou a transação abc-123
   já vi esse identificador?
      sim  →  descarta, não conta em lugar nenhum
      não  →  processa e anota o identificador
```

Identificadores com mais de uma hora somem sozinhos — depois desse tempo, um reenvio deixa de ser reconhecido, e é um
risco aceito conscientemente: guardar 24 horas custaria 4 GB por máquina para cobrir um caso raro que o reprocessamento
resolve melhor.

A lista fica **dentro da memória do cliente**, que já existe. Nenhum banco novo, nenhuma consulta a outro serviço.

**Aqui apareceu um problema que só a operação revelou.** Existe um segundo tipo de repetição, que não é a mesma mensagem
chegando duas vezes:

> Transações **diferentes**, descrevendo **o mesmo acontecimento**.

Uma regra baseada em janela continua verdadeira enquanto a janela não esvazia, e é reavaliada a cada transação. Num
ataque de 30 transações, uma regra chegou a publicar **16 alertas**.

O caso que expôs o problema: a regra da soma da hora não olha o valor da transação atual. Depois que a soma passa do
limite, **um café de R\$ 5 gera alerta de severidade alta**. Isso foi medido.

**Por que importa mais do que parece:** um alarme falso que repete 16 vezes é 16 vezes pior. E o gatilho pode ser
inteiramente legítimo — quem compra notebook, monitor e teclado em cinco minutos aciona a regra em cada compra. **A
repetição multiplica o dano de todo erro do sistema.**

**A solução:**

| Tipo de regra    | Comportamento        | Por quê                                |
|------------------|----------------------|----------------------------------------|
| Com janela       | um alerta por janela | o acontecimento é um só                |
| Sem janela       | alerta toda vez      | cada compra grande é um evento próprio |
| Severidade subiu | publica mesmo assim  | escalada é informação nova             |

**A segunda correção: fraude não define o que é normal.**

A transação fraudulenta **alimentava o número que define o normal do cliente** — o mesmo número usado para julgá-la.
Cada fraude empurrava o limite para cima.

Simulei um cliente que gasta R\$ 68 por compra, com um fraudador repetindo R\$ 800:

| Fraude nº | O "normal" vira | Limite  | Dispara? |
|-----------|-----------------|---------|----------|
| 1         | R\$ 104         | R\$ 209 | sim      |
| 11        | R\$ 383         | R\$ 767 | sim      |
| **12**    | R\$ 404         | R\$ 808 | **não**  |

**Depois da décima segunda fraude, a regra emudece.** O ataque estava ensinando o sistema a aceitá-lo.

A correção: **transação que gerou alerta não entra na conta do normal.** Uma transação sob suspeita não pode servir para
definir o que não é suspeito. Ela continua contando nas janelas — sem isso a regra de velocidade ficaria cega — mas não
mexe no perfil.

**Uma terceira correção que a primeira exigiu.** Nas primeiras transações de um cliente novo, o
"normal" nasce igual à primeira compra. Se ela vier baixa, o limite fica abaixo do gasto normal dele, compras legítimas
passam a alertar, e — com a correção acima — **nunca mais corrigem o número**. Ele trava errado para sempre.

Medido num cliente real: normal congelado em R\$ 496 para quem gastava R\$ 1.076.

A solução: **nas 5 primeiras transações tudo entra na conta**, com alerta ou sem. Depois disso a exclusão vale.

**Trade-off que fica em aberto:** um cliente que legitimamente mudou de padrão depois disso continua alertando, porque o
número não acompanha. O destravamento correto é a resposta dele — *"fui eu"* — que está desenhada e não implementada.

### 7.4 Integração com sistemas internos

**A dor principal:** a lentidão do outro vira a sua. Se o motor chamar um serviço que normalmente responde rápido mas
hoje demora dois segundos, as threads ficam paradas esperando e o motor trava.

**Uma dependência lenta é pior que uma morta.** Morta, a chamada falha rápido e você segue. Lenta, ela consome seus
recursos sem entregar nada.

**A decisão: o motor calcula o perfil do cliente sozinho.**

Das oito regras que fariam sentido num sistema assim, **cinco não precisam de nada externo** — e são justamente as mais
eficazes contra fraude de cartão. Só três dependeriam de dado cadastral: limite do cartão, aviso de viagem e data de
abertura da conta.

Calcular internamente ganha duas vezes: **está sempre atualizado** e **não depende de ninguém**.

**Trade-off:** as três regras que dependem de cadastro não foram implementadas, só descritas. E a memória de 30 dias
custa cerca de metade da memória de cada máquina.

**Sobre as janelas de tempo, uma armadilha:** janela longa **não pode ser criada sob demanda**. Uma regra publicada hoje
pedindo "média dos últimos 30 dias" encontraria uma janela vazia.

Por isso o cardápio é fixo: 5 minutos, 60 minutos, 30 dias, e o último valor. Regra que usa janela existente entra em
segundos. **Regra que precisa de janela nova é planejamento de mês.**

### 7.5 Regras configuráveis sem novo deploy

Esse é o requisito mais visível do enunciado, e está atendido.

#### Por que "sem deploy" importa

Deploy é trocar o programa que está rodando: compilar, empacotar, parar a aplicação, subir a nova. Leva de minutos a
horas, passa por aprovação, e tem janela marcada.

Fraudador muda de tática em dias. **Se a regra estiver dentro do programa, o banco fica estruturalmente atrasado em
relação ao crime** — não por incompetência, mas porque o ciclo de release é mais lento que o ciclo do ataque.

#### Como funciona, passo a passo

A ideia é simples: **a regra deixa de ser código e vira dado.** O programa não muda; o que ele lê é que muda.

```
1. você edita      regras/regras.yml            um arquivo de texto no repositório
2. roda o script   ./infra/carregar-regras.sh   valida e grava no MongoDB
3. o motor percebe sozinho                      ele consulta o banco a cada 30 segundos
```

**Nenhum passo desse caminho reinicia o motor.** Ele continua processando transações o tempo todo.

O que o motor faz a cada 30 segundos:

|   |                                                                                 |
|---|---------------------------------------------------------------------------------|
| 1 | Pergunta ao MongoDB quais regras estão ligadas                                  |
| 2 | Transforma o texto de cada condição em algo executável                          |
| 3 | Testa cada uma contra um exemplo — regra com erro é recusada e as outras seguem |
| 4 | Descobre a ordem de avaliação, caso alguma dependa de outra                     |
| 5 | **Troca a lista inteira de uma vez**                                            |

O passo 5 é o que torna a troca segura sob 25 mil transações por segundo: a lista é substituída numa operação só. Quem
está avaliando naquele instante vê **o conjunto antigo inteiro ou o novo inteiro**, nunca metade de cada.

#### Demonstrado com o sistema no ar

| O que fiz                                                      | Quanto levou                                        |
|----------------------------------------------------------------|-----------------------------------------------------|
| Baixei o limite da regra de valor de R\$ 30.000 para R\$ 5.000 | **20 segundos** até uma compra de R\$ 8.000 alertar |
| Adicionei uma regra que não existia                            | **19 segundos** até ela disparar                    |
| Desliguei uma regra pelo endpoint                              | imediato                                            |

O processo do motor era o mesmo em todos os casos — não foi reiniciado nenhuma vez.

#### Por que 30 segundos, e não 5 minutos

Para o interruptor de emergência ser útil. Às 3 da manhã, com uma regra gerando milhares de alarmes falsos, esperar 5
minutos é inaceitável.

E o endpoint de desligar não espera nem os 30 segundos: ele força a recarga na hora.

#### O que protege contra regra ruim

Dar a alguém o poder de mudar o comportamento do sistema sem passar por deploy é perigoso. Três defesas:

**A linguagem não permite laço nem repetição.** Uma regra não consegue travar a máquina, porque não existe forma de
escrever "faça isso para sempre".

**Toda regra é executada contra um exemplo antes de entrar em uso.** Isso recusa três tipos de erro:
campo que não existe, condição que não devolve sim ou não, e referência a uma regra inexistente. Regra recusada fica de
fora e as outras continuam rodando.

**O interruptor.** Se algo passar pelas duas defesas e começar a disparar demais, um comando desliga a regra em
segundos.

#### O que é modo sombra, e por que ele não está aqui

**Modo sombra** é rodar uma regra nova contra o tráfego real **sem gerar alerta nenhum** — só contando quantas vezes ela
teria disparado.

Serve para responder, antes de qualquer cliente ser incomodado: *"essa regra nova vai disparar 50 vezes por dia como eu
esperava, ou 50 mil?"*

Uma regra mal calibrada é detectada em algumas horas de observação, em vez de ser detectada pelo call center lotado.

**Não foi implementado.** E é justo perguntar por que isso aparece aqui, já que não é sobre deploy.

A razão é que as duas coisas resolvem o **mesmo risco por caminhos opostos**:

|                 | Como age                                 | Estado           |
|-----------------|------------------------------------------|------------------|
| **Modo sombra** | evita que a regra ruim chegue a produção | não implementado |
| **Interruptor** | tira a regra ruim depois que ela chegou  | funcionando      |

O enunciado pede regra alterável sem deploy, e isso está feito. O modo sombra é prática de qualidade **acima** do que
foi pedido — e, faltando ele, a defesa contra regra ruim é remediar em vez de prevenir.

### 7.6 Monitoração

**A falha que ninguém vê:** se a aplicação cai, todo mundo sabe em segundos. A perigosa é outra — o sistema processa 25
mil por segundo, sem erro no log, e **uma regra está quebrada há três dias deixando fraude passar**. Todo indicador
técnico está verde.

| Falha                                 | Aparece nos indicadores técnicos? |
|---------------------------------------|-----------------------------------|
| Aplicação cai                         | imediato                          |
| Fila cresce                           | sim                               |
| **Regra parou de disparar**           | **não**                           |
| **Contagem corrompida por repetição** | **não**                           |

> Monitorar o sistema não basta. É preciso monitorar **a qualidade da decisão**.

**O indicador mais importante é a taxa de disparo por regra.** Um salto significa regra quebrada **ou** ataque real — as
duas exigem ação imediata, mas opostas.

**O alerta que quase ninguém escreve:** ausência de alertas é sintoma. Se há tráfego entrando e nada saindo por 10
minutos, isso é incidente, não sucesso. **Testado de propósito:** desliguei todas as regras com a carga ligada, e a
condição ficou verdadeira com 188 transações por segundo entrando e zero alertas saindo.

A condição é relativa ao tráfego, senão dispararia toda madrugada quando o volume cai naturalmente.

#### As métricas que existem

**No motor:**

| Métrica                  | Responde                                                  |
|--------------------------|-----------------------------------------------------------|
| `transacoes_avaliadas`   | quantas transações passaram                               |
| `transacoes_duplicadas`  | quantos reenvios a origem mandou                          |
| `transacoes_com_alerta`  | quantas geraram pelo menos um alerta                      |
| `alertas_gerados`        | total de alertas                                          |
| **`alertas_por_regra`**  | **qual regra está disparando quanto — a mais importante** |
| `alertas_suprimidos`     | quanto ruído o controle de repetição conteve              |
| `latencia_processamento` | quanto o motor leva para decidir                          |
| `latencia_ponta_a_ponta` | o prazo do enunciado, incluindo a fila                    |
| `regras_ativas`          | cai a zero se a recarga quebrar                           |
| `memoria_no_limite`      | cliente concentrando carga demais numa partição           |

**Na notificação:**

| Métrica                | Responde                                                 |
|------------------------|----------------------------------------------------------|
| `recebidos`            | alertas consumidos do tópico                             |
| `entregues`            | avisos que chegaram ao provedor                          |
| `sem_notificacao`      | alertas que não pedem aviso ao cliente                   |
| `ja_entregues`         | reentregas descartadas — o Redis fazendo o trabalho dele |
| `fila_morta`           | alertas que não puderam ser entregues                    |
| **`fila_morta_idade`** | **há quanto tempo a mais antiga está parada**            |
| `redis_indisponivel`   | quantas vezes seguimos sem proteção contra repetição     |
| `latencia_entrega`     | da decisão até o provedor                                |

**Na auditoria:**

| Métrica       | Responde                                           |
|---------------|----------------------------------------------------|
| `gravados`    | alertas na trilha                                  |
| `ja_gravados` | reentregas que o banco recusou por chave duplicada |

A da fila morta merece destaque: **profundidade sozinha não basta.** Uma fila com 5 mensagens que drena é normal; uma
fila com 5 mensagens paradas há duas horas é um segundo incidente escondido dentro do primeiro.

#### Dois painéis, não um

Porque as perguntas são diferentes: o plantão pergunta *"está de pé?"*; o time de fraude pergunta *"está acertando?"*.
Misturar os dois faz ninguém olhar nenhum.

**Uma armadilha que derruba a monitoração inteira:** nenhum indicador pode usar identificação de cliente como rótulo. O
Prometheus tentaria criar milhões de séries e morreria. Rótulo é para coisas de pouca variedade — regra, canal,
severidade.

**Para cada incidente, um procedimento.** O principal:

*Taxa de disparo disparou* → **desligar a regra primeiro, investigar depois**. Cada minuto com a regra ligada custa
milhares de clientes incomodados. Investigar antes de desligar inverte a prioridade, e é o erro mais comum de plantão.

---

## 8. O simulador

Nada podia ser testado sem transações chegando. Por isso a primeira coisa construída foi um gerador.

Ele não é parte da solução — é ferramenta. Mas acabou sendo o que permitiu descobrir quase todos os problemas reais
deste projeto.

**Ele cria 200 mil clientes fictícios**, cada um com um padrão próprio: quanto costuma gastar, em que cidade compra,
qual cartão usa. Depois gera transações coerentes com esse padrão — ou incoerentes de propósito, quando você pede
fraude.

**O que ele descobriu:**

| Problema                                       | Como apareceu                                   |
|------------------------------------------------|-------------------------------------------------|
| Memória do cliente estourava o limite do Kafka | Erro em produção após meio milhão de transações |
| A regra emudecia após 12 fraudes               | Ataque sustentado contra o mesmo cliente        |
| Um café de R\$ 5 gerava alerta grave           | Comprar algo barato depois de um alerta         |
| Uma regra nunca disparava                      | Nenhum alerta dela em 500 mil transações        |

Nenhum desses apareceria em teste unitário. **Todos apareceram rodando o sistema de verdade.**

---

## 9. O teste de estresse

### A pergunta que ele precisa responder

O enunciado exige três números:

|                   | Exigido                      |
|-------------------|------------------------------|
| Volume médio      | 8 mil transações por segundo |
| Volume de pico    | 25 mil por segundo           |
| Prazo de resposta | meio segundo                 |

**A resposta curta: o volume, sim. O prazo, não neste ambiente.**

### Antes dos números, o aviso que muda tudo

Isso foi medido num **notebook**, com **uma máquina só**, e com **tudo dentro do Docker** — o Kafka, os bancos e o motor
dividindo o mesmo disco por baixo de uma camada de virtualização.

A arquitetura foi desenhada para **5 máquinas**. Aqui rodou **1** — e com **4 threads numa máquina de 10 núcleos**,
porque o número de threads é fixo na configuração e foi dimensionado para uma máquina de produção menor.

Testei se isso limitava alguma coisa: subindo para 12 threads, a vazão ficou igual (9.830 contra 9.778 por segundo). Não
era o limite.

Então os números não dizem "o sistema aguenta X em produção". Eles dizem duas outras coisas, que são as que importam:

1. **O que uma máquina faz** — e daí dá para projetar cinco
2. **Onde está o gargalo** — que é propriedade do código, não do ambiente

### O que uma máquina entregou

| Situação                      | Resultado                                  |
|-------------------------------|--------------------------------------------|
| Pico momentâneo               | **28 mil por segundo**                     |
| Sustentado, memória limpa     | **8 mil por segundo** por 2 minutos e meio |
| Sustentado, memória acumulada | caiu para 3 mil                            |

**Uma máquina sozinha cobriu a média exigida de 8 mil por segundo**, e chegou a tocar o pico de 25 mil por instantes.

Projetando para as 5 máquinas do desenho: 40 mil por segundo, contra 25 mil de pico. Cabe.

### Mas o prazo de meio segundo não foi cumprido

Aqui está a parte honesta.

| Chegando          | Fila                  | Tempo total                 |
|-------------------|-----------------------|-----------------------------|
| 300 por segundo   | vazia                 | **11 ms** — dentro do prazo |
| 3.000 por segundo | 30 mil esperando      | 704 ms — estourou           |
| 8.000 por segundo | 20 a 30 mil esperando | 1.955 ms — estourou muito   |

Repare que a 8 mil o motor **estava acompanhando** — processava 8 mil por segundo. Mesmo assim o prazo estourou, porque
**a fila nunca esvaziava**.

E o motor não é o culpado: ele decide em **0,20 milissegundo**. O que estourou o prazo foi a espera, não a decisão.

> Numa máquina só, o prazo é cumprido até cerca de mil transações por segundo. Acima disso a fila
> começa a se formar, e a fila é o prazo.

Com 5 máquinas, cada uma receberia um quinto da carga — 1.600 por segundo no volume médio. Perto do limite observado,
mas com uma diferença grande: em produção o Kafka roda em máquinas próprias, com disco próprio, e não disputa recursos
com o motor.

### Onde está o gargalo

Descobri por eliminação.

**Não é o processador.** Durante a queda, **90% dos núcleos estavam ociosos** e havia 460 mil transações esperando. As
threads estavam paradas.

**Não é a garantia de atomicidade.** A documentação estimava que ela custasse de 10% a 30% do desempenho. Rodei a mesma
carga sem ela e a queda aconteceu igual.

**É a gravação.** E ela tem duas partes:

*A parte do ambiente:* o Kafka não tinha volume de dados configurado, gravando na camada mais lenta do Docker.
Corrigindo isso, a capacidade de escrita foi de 19 mil para **175 mil mensagens por segundo** — nove vezes mais. Metade
do problema era o ambiente.

*A parte do código:* cada transação de 192 bytes faz o sistema escrever cerca de **1.400 bytes**, porque a memória do
cliente é regravada por inteiro em vez de só o que mudou. São 7,5 vezes mais escrita do que o necessário.

E isso piora com o tempo: quanto mais histórico o cliente acumula, maior fica cada gravação. Foi por isso que a mesma
máquina fez 8 mil por segundo com memória limpa e 3 mil com memória acumulada.

### O que eu concluo

**Sobre o volume:** atende. Uma máquina cobre a média, cinco cobrem o pico com folga.

**Sobre o prazo:** não posso afirmar que atende, e não posso afirmar que não atende. Neste ambiente não cumpre acima de
mil por segundo. A causa identificada — gravação amplificada num disco compartilhado com tudo — não existiria da mesma
forma em produção.

**O que falta para responder com certeza:** rodar em Linux, com o Kafka em máquina própria e disco separado, e com mais
de uma instância. Fora do alcance de um teste de sete dias.

**O que vale corrigir de qualquer forma:** a gravação amplificada é do código, não do ambiente. Em produção ela também
está lá, só que escondida por hardware melhor.

## 10. O que ficou de fora e o que faria depois

### Coisas que o sistema pediria em produção

**Terraform.** Hoje o ambiente sobe com Docker Compose, que serve para desenvolver e não serve para produção. O ambiente
real precisaria de infraestrutura descrita em código — cluster, tópicos, permissões, bancos, alertas — para ser
reproduzível e revisável.

**Tela para o time de fraude.** As regras hoje são um arquivo no Git. Funciona, e resolve versionamento e aprovação de
graça, mas analista de fraude tipicamente não usa Git. Uma tela que gerasse o arquivo e abrisse o pull request
automaticamente juntaria as duas coisas.

**Certificado entre serviços e cofre de chaves.** Estão desenhados no tópico de segurança e não implementados. Não mudam
o comportamento do sistema, então rendem menos que o núcleo num prazo curto.

**Reprocessamento automático da fila morta.** Hoje o que não foi entregue fica parado, e o alerta avisa quando passa de
30 minutos — mas a ação é manual.

**Testes dentro da própria regra.** Cada regra deveria carregar seus casos de teste no mesmo arquivo, rodando na
esteira. É isso que torna defensável dar a alguém o poder de escrever regra sem saber programar. Hoje as cinco regras
não têm teste próprio — foi assim que uma regra que nunca disparava passou despercebida.

**Modo sombra.** Rodar uma regra nova contra o tráfego real sem gerar alerta, só medindo. Uma regra que deveria disparar
50 vezes por dia e está disparando 50 mil seria detectada **antes** de qualquer cliente ser incomodado.

### Dívidas técnicas conhecidas

São coisas que sei que estão erradas ou incompletas, com o custo de cada uma.

#### 1. A gravação amplificada

**O que é:** cada transação de 192 bytes faz o sistema escrever cerca de 1.400 bytes, porque a memória do cliente é
regravada por inteiro em vez de só o que mudou.

**O que custa hoje:** é o gargalo medido no teste de carga. E piora com o tempo — quanto mais histórico o cliente
acumula, maior fica cada gravação.

**O suspeito principal:** a lista de identificadores usada para reconhecer reenvio. São até 500 itens, regravados
inteiros **para acrescentar um**. Separá-la em um armazenamento próprio faria cada gravação custar um item em vez de
500.

**Esforço:** algumas horas. É a dívida de maior retorno.

#### 2. A mediana — **não implementada**

Aqui preciso ser explícito, porque a documentação de arquitetura chegou a decidir mediana e o código faz outra coisa.

**O que está implementado:** uma **média ponderada**. Cada compra empurra o "normal do cliente" um pouco na direção
dela, com peso maior para o que é recente.

**O que a mediana daria:** resistência a valor extremo. A mediana é o valor do meio — se alguém gastou 100, 110, 120,
130 e 9.000 reais, a mediana é 120 e a média é 1.892. Um valor absurdo não desloca a mediana.

**Por que ficou de fora:** mediana exata exige guardar a distribuição de cada cliente. Com milhões de clientes, guardar
200 valores por pessoa daria dezenas de gigabytes. A saída seria um estimador que mantém cinco marcadores por cliente em
cerca de 40 bytes — mas é código não trivial.

**E por que ela perdeu importância:** a correção da seção 7.3 já resolve boa parte do que a mediana resolveria. Valor
extremo isolado hoje **gera alerta**, e transação que gera alerta não entra na conta do normal. O que a mediana ainda
cobriria é a subida lenta abaixo do limite de alerta — e mesmo aí ela cede, porque a mediana resiste a poucos valores
extremos, não a um deslocamento gradual da distribuição inteira.

**Conclusão honesta:** deixou de ser dívida a pagar e virou melhoria marginal. A defesa real contra a subida lenta é a
regra da soma da hora, cujo limite é fixo e não olha o histórico.

#### 3. As regras não têm teste próprio

**O que falta:** cada regra deveria carregar seus casos de teste no mesmo arquivo, rodando automaticamente antes de
entrar em produção.

**O que isso já custou:** uma regra chamada `teste-de-cartao` ficou dias em produção sem **nunca**
disparar. Ninguém percebeu, porque nada verificava se ela funcionava. Foi removida.

> Uma regra que nunca dispara é indistinguível de uma regra quebrada.

**Por que importa mais do que parece:** é isso que torna defensável dar a alguém que não programa o poder de escrever
regra.

#### 4. A resposta do cliente

**O que falta:** capturar o *"fui eu"* ou *"não fui eu"* que o cliente responde ao aviso.

**O que isso resolveria, e é muito:**

Hoje, quando a taxa de alerta de uma regra dispara, não dá para saber se é **regra quebrada** ou **ataque real em
curso** — e as duas exigem ações opostas. A resposta do cliente separa as duas imediatamente.

E resolve outro problema: um cliente que legitimamente mudou de padrão de gasto fica alertando para sempre, porque
transação alertada não atualiza o normal dele. O *"fui eu"* quebraria esse ciclo.

#### 5. A fila morta não drena sozinha

**O que falta:** reprocessar automaticamente os alertas que não puderam ser entregues.

**Como está hoje:** eles ficam parados, e o alerta de plantão avisa quando a mais antiga passa de 30 minutos. A ação é
manual.

#### 6. Métricas que só nascem quando são usadas

**O problema:** o contador de uma regra só passa a existir depois do primeiro disparo dela.

**A consequência:** uma regra que **para** de disparar não vai a zero no gráfico — ela **some**. E linha que some parece
"nada acontecendo", quando significa "algo parou".

Pior: existe no código um contador de falha de regra que, como nenhuma regra quebrou ainda, **não existe em lugar
nenhum** — e um alerta sobre ele nunca dispararia.

**A correção:** registrar os contadores com valor zero quando o motor sobe, para todas as regras ativas. É pouco código
e destrava o alerta mais importante da monitoração.

## 11. Onde ver cada coisa

| O quê               | Onde                                    |
|---------------------|-----------------------------------------|
| Como rodar e testar | [README](../README.md)                  |
| As regras           | `regras/regras.yml`                     |
| Publicar regras     | `infra/carregar-regras.sh`              |
| Painéis             | `infra/grafana/dashboards/`             |
| Alertas de plantão  | `infra/prometheus/regras-de-alerta.yml` |
| Diário de execução  | [diario.md](execucao/diario.md)         |
