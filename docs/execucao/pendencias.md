# O que falta e como será feito

> Estado em: fim do dia 2. Ordem de prioridade, com o **como** de cada item.
>
> O raciocínio das decisões está em [../architecture.md](../architecture.md).

---

## Decisões abertas, antes de codar

Três coisas pequenas que travam ou sujam o que vem depois.

### 1. `FonteDeRegras` — agora ou no dia 4

Hoje `TopologiaConfig` recebe `List<Regra>` injetada pelo Spring **uma vez, na subida**. Para a recarga a cada 30
segundos funcionar, a lista não pode ser um retrato fixo.

**Como resolver:** trocar a `List<Regra>` por uma interface.

```java
public interface FonteDeRegras {
    List<Regra> regrasAtivas();
}
```

Hoje, uma implementação devolve a lista fixa. No dia 4, outra devolve o que veio do Mongo — **e a fiação da topologia
não muda**.

**Ganho colateral:** resolve a atomicidade da troca, que está registrada como pendência de validação. Se a fonte devolve
uma lista imutável e a recarga apenas troca a referência, quem estiver avaliando vê o conjunto antigo inteiro ou o novo
inteiro, nunca metade de cada.

**Esforço:** ~30 min. Fazer agora deixa o dia 4 puramente aditivo.

### 2. Padrão de nome das classes de configuração — **resolvido**

Adotado o sufixo `Config`: `TopologiaConfig` e `RegrasConfig`. Vale para as outras aplicações.

### 3. Comentários nos arquivos `.yml`

Os `.java` estão sem comentários. Os `.yml` ainda têm, e alguns carregam decisão de arquitetura —
`processing.guarantee: exactly_once_v2` vem com a explicação de que é ela que torna o registro de já vistos confiável.
Remover deixa a justificativa só no documento.

---

## Já concluído depois do dia 2

Itens que estavam nesta lista e saíram.

| Item                                | Como ficou                                                                                                                                                |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Testes de `MemoriaDoCliente`        | 11 testes cobrindo descarte, janelas, deduplicação, última cidade e ticket médio                                                                          |
| Linha de base que esquece           | Média móvel exponencial com meia-vida de 30 dias, mais peso mínimo de 5% por transação                                                                    |
| Teto de memória por cliente         | 200 eventos e 500 identificadores, com métrica sinalizando cliente quente                                                                                 |
| Segunda regra                       | `RegraValorAbsoluto`, que provou a abstração ao entrar sem tocar no motor                                                                                 |
| Envenenamento pelo ataque agressivo | Transação que gerou alerta não atualiza mais o ticket médio. A regra emudecia na 12ª transação; agora não emudece                                         |
| Guarda de partida a frio            | Nas 5 primeiras transações tudo é absorvido, senão a base trava no valor da primeira                                                                      |
| Janela de 60 minutos sem uso        | `RegraSomaNaHora`, limite absoluto de R$ 10.000, pega o fraudador de ritmo controlado                                                                     |
| Nomes vagos no motor                | Auditoria completa de nomes: `anterior` → `memoriaAntesDaTransacao`, `CURTA`/`MEDIA` → `CINCO_MINUTOS`/`UMA_HORA`, vocabulário unificado em `ticketMedio` |

---

## Dívidas ainda abertas

### Mediana no lugar de média

**O que a média móvel resolveu:** o esquecimento. Comportamento antigo agora desaparece sozinho.

**O que ela não resolveu:** resistência a envenenamento. Fraude executada devagar, com muitas transações pequenas, ainda
desloca o valor e treina o sistema a aceitar valores cada vez maiores.

**O que foi resolvido depois:** o ataque **agressivo** — aquele em que a fraude dispara alerta e mesmo assim alimentava
a base. Excluir da base a transação alertada eliminou esse caso (medido: a regra emudecia na 12ª transação fraudulenta).
Restam o ataque **paciente**, que se mantém abaixo do limiar de alerta e por isso nunca é excluído, e é contra ele que a
mediana continua sendo necessária. A `RegraSomaNaHora` cobre boa parte dele por acumulação, mas não o caso de rampa
lenta ao longo de dias.

**Por que não é só trocar a fórmula:** mediana exata exige guardar a distribuição de cada cliente. Com milhões de
clientes ativos, guardar uma amostra de 200 valores por pessoa daria dezenas de gigabytes.

**Como resolver:** um estimador de quantil de memória constante — o algoritmo P-quadrado mantém cinco marcadores por
cliente e estima a mediana em ~40 bytes.

**Mitigação parcial que já existe:** a `RegraValorAbsoluto` não pode ser envenenada, porque não olha para a linha de
base. Ela cobre o caso extremo mesmo com a média deslocada.

**Esforço:** ~3 h. É o item mais fácil de cortar. A alternativa barata é declarar a limitação.

### Amplificação de escrita no changelog

**O que foi resolvido:** o teto impede o estouro de 1 MB e limita a escrita a ~52 KB por transação de cliente quente.

**O que continua:** a escrita ainda é proporcional ao tamanho da memória, não ao tamanho da mudança.

**Solução estrutural:** trocar a lista de eventos por **contadores por minuto** — 60 baldes de contagem e soma dão
tamanho fixo independente da taxa, algo em torno de 1,5 KB.

**O que quebraria:** a regra de teste de cartão precisa dos valores individuais em sequência. Ficaria uma lista pequena
e limitada (últimos 10) ao lado dos baldes.

**Esforço:** ~4 h. Só vale se o teste de carga do dia 6 mostrar que a escrita é gargalo.

### Deduplicação do alerta dentro do motor

**O sintoma medido:** a `RegraSomaNaHora` produziu **29 alertas** num ataque que deveria render 1. Passado o limite da
hora, ela dispara em toda transação seguinte enquanto a janela não esvazia.

**Por que não é só dessa regra:** qualquer regra baseada em janela acumulada tem o mesmo comportamento. É estrutural,
não um defeito da regra nova.

**A solução já está desenhada no tópico 3:** chave `cliente + regra + janela + severidade` para decidir se publica, e
`cliente + regra + janela` para decidir o campo `notificarCliente`. Cabe na memória local, porque a chave inteira vive
dentro de uma partição.

**Consequência de não fazer:** o cliente recebe dezenas de avisos do mesmo evento — exatamente a fadiga de alerta que o
tópico 6 mede e que queima o canal de notificação.

**Esforço:** ~3 h. Deve entrar antes do dia 5, porque o `notificacao` herda o problema se a duplicata sair do motor.

### Exceção em regra derruba a thread

Uma regra que lance exceção dentro de `process()` mata a `StreamThread` e para a partição inteira. Hoje não há proteção.
Com regras vindas do Mongo no dia 4, o risco deixa de ser teórico.

**Solução:** envolver a avaliação de cada regra em `try/catch`, contar a falha numa métrica por regra e seguir com as
demais. Uma regra quebrada deve desativar a si mesma, não derrubar o motor.

**Esforço:** ~1 h. É a proteção mais barata da lista.

---

## Dia 4 — Regras sem redeploy

**O dia mais importante do case.** É aqui que o requisito mais visível do enunciado é atendido.

### 4.1 Carga das regras do Mongo

**Como:**

1. Documento de regra no Mongo, com `id`, `versao`, `habilitada`, `severidade`, `janela`, `condicao`
   e `acoes`
2. `RegrasNoMongo implements FonteDeRegras`, consultando a cada 30 segundos por regras alteradas desde a última checagem
3. A consulta devolve zero documentos na maioria das vezes — o custo é ruído estatístico
4. Ao encontrar mudança, compila as expressões e **troca a referência da lista de uma vez**

**Checkpoint:** editar o YAML, rodar o script de carga, e a regra nova valer em 30 segundos **com o sistema no ar**.

### 4.2 Integração do CEL

**Como:** `RegraCel implements Regra`, recebendo a expressão compilada e o contexto fixo.

O contexto que a expressão enxerga:

| Nome                    | Conteúdo                                      |
|-------------------------|-----------------------------------------------|
| `transacao`             | O evento atual                                |
| `janela5m`, `janela60m` | Contagens e somas do período                  |
| `perfil`                | Linha de base de 30 dias                      |
| `ultimo`                | Última cidade e horário                       |
| `regras`                | Resultado de outras regras, para as compostas |

Nada além disso é acessível — sem rede, sem arquivo, sem reflexão. **O contexto é a fronteira de segurança.**

A `RegraVelocidadeAlta` fixa em código permanece durante a transição, como referência para comparar o comportamento, e
sai quando as regras em YAML cobrirem o mesmo caso.

### 4.3 As cinco regras em YAML

| Regra                  | Do que precisa                                   |
|------------------------|--------------------------------------------------|
| Velocidade alta        | Janela de 5 min + linha de base                  |
| Valor atípico          | Linha de base                                    |
| Geografia impossível   | Último valor (cidade e horário)                  |
| Teste de cartão        | Janela de 5 min, sequência de valores crescentes |
| Horário fora do padrão | Linha de base de horários                        |

Cada uma com casos de teste no mesmo arquivo, rodando na esteira.

**Nota sobre o que a memória ainda não oferece:** as janelas de 5 e 60 minutos existem, e a linha de base agora é média
móvel com meia-vida de 30 dias. Mas a **regra de horário fora do padrão exige guardar distribuição de horários** por
cliente, o que é estrutura nova — provavelmente 24 contadores por faixa de hora. É a regra mais cara das cinco, e a
primeira candidata a corte.

### 4.4 Grafo de regras compostas

**Como:**

1. Uma regra composta referencia outras por `id` na condição
2. No carregamento, montar o grafo de dependências e resolver a **ordem topológica**
3. **Recusar ciclo** na esteira, antes do merge — validação estática, barata
4. Avaliar na ordem, guardando resultados para as compostas consultarem

**Decisão já tomada:** desligar uma regra **desliga em cascata** as compostas que dependem dela, e o motor informa quais
caíram junto.

### 4.5 Endpoint de desligamento

**Como:** um `POST /regras/{id}/desligar` que vira o campo `habilitada` e propaga a cascata, devolvendo a lista do que
foi desligado.

É o interruptor de emergência: a definição da regra muda por Git com revisão, mas **desligar é operação**, e precisa
levar segundos.

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
