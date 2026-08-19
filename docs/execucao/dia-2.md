# Dia 2 — O motor

> Diário de execução. O raciocínio arquitetural está em [../architecture.md](../architecture.md);
> aqui fica o que aconteceu na prática.

**Objetivo do dia, conforme o plano:** topologia do Kafka Streams com memória local, janelas, registro de transações já
vistas e uma regra fixa em código — ainda sem CEL.

**O risco que o plano marcava:** a serialização. O cronograma previa um ponto de decisão no dia 3:
se consumisse mais de meio dia, avaliar o pivô para Redis.

---

## O que foi entregue

**O motor funcionando de ponta a ponta.** O simulador dispara uma sequência suspeita e o alerta sai no tópico `alertas`,
com o raciocínio completo embutido.

**Memória por cliente local**, com backup automático no changelog: janelas de 5 e 60 minutos, linha de base que esquece
comportamento antigo, última cidade e horário, e o registro de transações já vistas — tudo com teto de tamanho.

**Duas regras**, com lógicas deliberadamente diferentes para provar a abstração:

| Regra                 | Como decide                                               | Severidade |
|-----------------------|-----------------------------------------------------------|------------|
| `RegraVelocidadeAlta` | Contagem na janela **e** valor acima do padrão do cliente | ALTA       |
| `RegraValorAbsoluto`  | Valor acima de um limiar fixo, sem consultar nada         | MÉDIA      |

**Trinta e três testes automatizados.**

**Estrutura de código organizada por funcionalidade**, com a separação garantida por teste.

---

## Verificações

| Verificação                               | Resultado                                                                                      |
|-------------------------------------------|------------------------------------------------------------------------------------------------|
| Sequência suspeita gera alerta            | 5 alertas para o `cli-001234`, após 10 transações de histórico                                 |
| O alerta carrega o raciocínio             | `contagemJanela5m`, `ticketMedioCentavos`, `limiarCentavos` e `historicoConsiderado` presentes |
| Threshold dinâmico funciona               | Limiar calculado subiu de R$ 2.942 para R$ 3.705 conforme o histórico crescia                  |
| Memória sobrevive a reinício              | Restaurada do changelog, com offset registrado no log                                          |
| **Reprocessamento de 520 mil transações** | **Zero exceções**, ~90 segundos                                                                |
| Negócio sem framework                     | Teste automatizado varre imports e falha se encontrar Spring, Kafka, Jackson ou Micrometer     |
| Testes                                    | 33 passando                                                                                    |

**O conteúdo de um alerta real:**

```json
{
  "clienteId": "cli-000777",
  "regraId": "velocidade-alta",
  "regraVersao": 1,
  "severidade": "ALTA",
  "notificarCliente": true,
  "valoresEntrada": {
    "contagemJanela5m": 11,
    "limiteContagem": 3,
    "valorCentavos": 587416,
    "ticketMedioCentavos": 128627,
    "limiarCentavos": 257254,
    "historicoConsiderado": 11
  }
}
```

Nenhum número fixo aparece na decisão da regra relativa. O limiar de R$ 2.572 foi calculado a partir do padrão daquele
cliente — para outro, seria outro.

---

## O risco previsto não se materializou. Outro apareceu.

**A serialização passou de primeira.** O `SerdeJson` genérico funcionou para os três tipos, incluindo a memória local
com listas e mapas aninhados. O ponto de decisão do dia 3 não precisou ser acionado.

**Em compensação, sob carga apareceu isto:**

```
RecordTooLargeException: The message is 1048712 bytes when serialized
which is larger than 1048576
```

E ele revelou um custo que o documento de arquitetura não tinha contabilizado.

### O que estava acontecendo

O `KeyValueStore` do Kafka Streams grava **o valor inteiro** no changelog a cada `put()`. Como fazemos um `put` por
transação, e a memória guarda todos os eventos e identificadores da última hora, o tamanho da gravação cresce junto com
o volume do cliente:

| Transações do cliente na última hora | Memória | Escrita **por transação** |
|--------------------------------------|---------|---------------------------|
| 10                                   | 1,5 KB  | 1,5 KB                    |
| 100                                  | 15 KB   | 15 KB                     |
| 1.000                                | 155 KB  | 155 KB                    |
| 6.700                                | 1 MB    | **recusado pelo Kafka**   |

Cada transação acrescenta ~155 bytes à memória (85 do evento, 70 do identificador). Dividindo o limite de 1 MB por isso,
**o ponto de ruptura é ~2 transações por segundo no mesmo cliente**, sustentadas por uma hora.

### Isso qualifica uma decisão registrada na arquitetura

Optamos por **um registro único por cliente** em vez de agregações de janela, e registrei que o preço era a poda manual.
O preço real inclui **amplificação de escrita**: o custo de gravação é proporcional ao tamanho da memória, não ao
tamanho da mudança.

É exatamente onde as agregações de janela do Kafka Streams levariam vantagem — elas gravam só o agregado. A escolha
continua defensável pelos motivos originais, mas o trade-off era maior do que eu tinha escrito.

### E dá número a uma limitação que era só uma frase

O documento lista **partição quente** entre as limitações conhecidas. Até aqui era uma afirmação. Agora sabemos **onde
quebra** (~2 transações por segundo no mesmo cliente) e **como quebra**: não degrada devagar, estoura com exceção e para
de processar a partição.

### A correção

Teto de tamanho além do corte por idade: **200 eventos e 500 identificadores** por cliente. Ao atingir o teto, os mais
antigos saem.

O raciocínio: se um cliente já tem 200 eventos na janela, **toda regra de contagem já disparou**. Guardar o 201º não
muda decisão nenhuma — só custa memória e banda. O cliente quente passa a **degradar** em vez de derrubar a partição.

**A contagem histórica continua exata.** Truncar a lista não pode falsear quantas transações o cliente já fez, senão a
linha de base seria corrompida em silêncio. Há teste cobrindo isso.

**E a truncagem virou sinal.** `MemoriaDoCliente.atingiuLimiteDeEventos()` sobe até o processador e vira a métrica
`antifraude.memoria.no.limite` — que é, na prática, **o detector de partição quente**. Se ela sair de zero em produção,
há um cliente concentrando volume.

### A verificação

Apagado o changelog, o Kafka Streams reprocessou o tópico inteiro para reconstruir a memória — 520 mil transações, boa
parte concentrada em poucos clientes por causa das rodadas repetidas de teste. **Zero exceções, ~90 segundos.**

O reprocessamento foi acidental, mas demonstrou a propriedade que a arquitetura usa como argumento a favor da memória
local: **reconstruir é possível porque o histórico está no Kafka.** E deu um número concreto de custo de reconstrução,
útil para o dia 6.

---

## Decisões tomadas durante a implementação

### Um registro único por cliente, em vez de agregações de janela

O caminho idiomático seria `groupByKey().windowedBy(...).aggregate(...)`. Não encaixa: uma regra precisa consultar
**várias janelas ao mesmo tempo**, mais o último valor, mais o registro de deduplicação. Com agregações separadas isso
viraria uma junção entre tabelas de janelas de tamanhos diferentes. Com um registro só, é **uma leitura por transação**.

O preço é a poda manual **e a amplificação de escrita** descrita acima.

**Isso também qualifica outra afirmação do documento:** listei "janelas de tempo prontas" entre as vantagens do Kafka
Streams. A vantagem existe para agregação de janela única — não para o nosso caso. Ficamos com a memória gerenciada e o
changelog automático, que continuam valendo.

### Linha de base com média móvel exponencial

O código acumulava soma e contagem **desde a primeira transação, para sempre**. Isso não era uma janela de 30 dias — era
uma média eterna. Um cliente que mudou de padrão há seis meses carregava a média antiga indefinidamente.

Trocado por média móvel com decaimento por tempo:

```
media = media * (1 - peso) + valor * peso
```

Com meia-vida de 30 dias, o comportamento antigo desaparece sozinho, **sem guardar evento nenhum** — um `long` no lugar
de dois.

Há um **peso mínimo de 5% por transação**, e ele resolve um caso que o decaimento puramente temporal erraria: dez
transações em um minuto representam um minuto de tempo, mas dez amostras de comportamento. Sem o piso, uma rajada quase
não moveria a média.

### Regra de valor absoluto

Um limiar fixo (R$ 30.000) que dispara independentemente do histórico.

**Não contradiz o argumento contra limiares absolutos** — responde outra pergunta:

| Regra    | Pergunta                                                            |
|----------|---------------------------------------------------------------------|
| Relativa | "Isso é incomum **para essa pessoa**?"                              |
| Absoluta | "Isso é grande o bastante para errar sair caro, **seja quem for**?" |

A primeira detecta anomalia; a segunda **limita perda**. Por isso o número é alto — R$ 5.000 dispararia em toda compra
de cliente de ticket alto, concentrando incômodo em quem gasta mais, que é o problema que a regra relativa existe para
evitar.

**Onde ela ganha:** cobre a partida a frio, que a regra relativa não alcança (exige cinco transações de histórico), e
**não pode ser envenenada** — se um fraudador subiu a média devagar, a relativa para de funcionar e a absoluta continua
valendo.

**Severidade média**, não alta: pergunta ao cliente sem acionar o antifraude, porque não há indício de fraude, só valor
alto.

**E ela provou a abstração:** lógica completamente diferente da primeira — não consulta janela, não consulta linha de
base — e entrou **sem tocar no motor, na topologia ou no avaliador**. Só um `@Bean`
novo.

### Serde próprio em vez do `JsonSerde` do Spring

Custou ~60 linhas e evitou três coisas: o cabeçalho `__TypeId__` acoplando consumidores ao nosso pacote Java, a
configuração de pacotes confiáveis, e o tratamento implícito de carga nula — que o Kafka Streams usa como marcador de
remoção e quebraria no meio da topologia.

Escolha defensável, não a única.

### Regra fixa em código antes do CEL

Deliberado: prova que as janelas e a memória funcionam **antes** de somar o motor de expressões.

### Valor monetário em centavos

O argumento de desempenho que eu tinha dado é fraco — `BigDecimal` a 25 mil por segundo não é gargalo. Os argumentos
reais são **exatidão** (ponto flutuante não representa decimais, e as janelas somam valores continuamente) e
**memória**, onde um `long` ocupa 8 bytes contra ~45 de um
`BigDecimal`.

### "Memória" no lugar de "estado"

"Estado" é o termo técnico do Kafka Streams, mas exige tradução mental. Duas coisas continuam com
"state" porque são da API: `state.dir` e `addStateStore`.

### Organização por funcionalidade, com a separação garantida por teste

**A estrutura foi refeita duas vezes.**

A primeira tentativa foi hexagonal por pastas — `dominio` e `infraestrutura`. Ficou ruim: `dominio`
virou um saco com oito arquivos sem organização interna; a distinção entre porta e adaptador não aparecia; os nomes
descreviam a arquitetura em vez do problema; e não encolhia para as outras aplicações, que terão quatro ou cinco classes
cada.

A segunda organiza por funcionalidade:

```
motor/
├── memoria/     MemoriaDoCliente, EventoRecente, JanelasDeTempo, LimitesDaMemoria,
│                RepositorioDeMemoria, MemoriaNoKafkaStreams
├── regra/       Regra, RegraVelocidadeAlta, RegraValorAbsoluto, RegrasConfig
├── deteccao/    AvaliadorDeTransacao, ResultadoDaAvaliacao
└── kafka/       TopologiaConfig, ProcessadorDeTransacoes, SerdeJson
```

Abrir o projeto mostra **memória, regra, detecção** — os conceitos do problema. A quarta pasta se chama `kafka` de
propósito: as três classes de lá são inteiramente Kafka, e nomeá-la "fluxo"
esconderia onde mora o acoplamento.

**A separação que importa não é a pasta — é o teste.** `ArquiteturaTest` varre os imports e falha se encontrar framework
onde não deve. Uma pasta chamada `dominio` não impede ninguém de escrever
`@Component` lá dentro; o teste impede. Verificado: injetando um import do Spring em
`MemoriaDoCliente`, o build quebra.

### A orquestração saiu do processador

A sequência deduplica → registra → avalia estava dentro de uma classe do Kafka Streams. Extraída para
`AvaliadorDeTransacao`, sem dependência de infraestrutura:

| Suíte                                               | Tempo   |
|-----------------------------------------------------|---------|
| `TopologiaConfigTest` (6 testes, com Kafka Streams) | ~250 ms |
| Testes de domínio (25 testes, sem infraestrutura)   | ~15 ms  |

**Uma tensão declarada:** isso introduz uma porta sobre o acesso à memória (`RepositorioDeMemoria`), depois de abstração
parecida ter sido recusada. A recusa anterior era sobre abstrair para trocar Kafka Streams por Redis — modelos
incompatíveis, menor denominador comum. Esta porta expõe apenas
`buscar` e `salvar`, e não vaza porque **já havíamos escolhido o armazenamento chave-valor simples**.

### Sem comentários e sem `var`

Efeito colateral bom: ver `Serde<MemoriaDoCliente>` escrito deixa evidente que a memória local também precisa do próprio
serializador — o que `var` escondia.

---

## Armadilhas encontradas

**Métricas em endpoint não exposto.** Perdi tempo caçando um bug inexistente porque consultei
`/actuator/metrics`, que não está exposto — só `health`, `info` e `prometheus`. **Confirmar o endpoint antes de concluir
que a métrica não existe.**

**Volume montado antes de o diretório existir.** O Grafana subiu apontando para uma pasta de provisionamento que ainda
não existia, e o Docker montou um diretório vazio. `restart` não refaz montagem — só `--force-recreate`.

**Colisão entre método de fábrica e acessor de record.** `ResultadoDaAvaliacao` tinha o componente
`duplicada` e um método estático `duplicada()`. O componente virou `ehDuplicada`.

**Escrita sobrescrevendo renomeação.** Escrevi num arquivo pelo nome antigo depois de ele ter sido renomeado, criando
duplicata com dois beans iguais. **O `ArquiteturaTest` foi quem pegou** — a lista de exceções apontava para um arquivo
que não existia mais.

---

## Pendências e riscos

**As regras estão fixas em código.** `RegrasConfig` registra as duas como beans, e o Spring injeta a lista **uma vez, na
subida**. Mudar uma regra hoje exige redeploy. É proposital para o dia 2, e o dia 4 resolve — mas exige trocar
`List<Regra>` por uma fonte consultável.

**A linha de base é média, e a arquitetura decidiu mediana.** A média móvel resolveu o esquecimento, não a resistência a
envenenamento. Fraude executada devagar ainda desloca o valor. Mediana com memória constante exige um estimador de
quantil.

**A amplificação de escrita continua**, só está limitada. Com o teto, cada transação de um cliente quente escreve ~52 KB
no changelog. A solução estrutural seria trocar a lista de eventos por contadores por minuto, ao custo de perder os
valores individuais que a regra de teste de cartão vai precisar.

**Formato de serialização em aberto.** Está JSON, sem registro de esquemas.

---

## Estado ao fim do dia

O núcleo funciona: transação entra, memória é atualizada com teto, duas regras de naturezas diferentes avaliam, e o
alerta sai com a trilha de auditoria. O risco previsto passou sem custo, um risco não previsto foi encontrado e medido,
e a estrutura de código está definida para as duas aplicações que faltam.

O que falta está em [pendencias.md](pendencias.md).
