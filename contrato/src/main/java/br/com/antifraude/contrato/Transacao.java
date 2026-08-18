package br.com.antifraude.contrato;

import java.time.Instant;

/**
 * Evento de transacao publicado pelos sistemas de origem no topico {@code transacoes}.
 *
 * <p>Decisoes de contrato registradas no documento de arquitetura:
 *
 * <ul>
 *   <li><b>Sem numero de cartao.</b> O motor recebe {@code cartaoToken}, o BIN e os quatro
 *       ultimos digitos. A tokenizacao acontece na origem — o motor de fraude nao deve ter
 *       acesso ao numero real em nenhuma hipotese (topico 2, minimizacao).
 *   <li><b>{@code clienteId} e identificador interno, nunca CPF.</b> E a chave de particionamento,
 *       e o Kafka precisa dela em claro para rotear — por isso nao pode ser cifrada.
 *   <li><b>Valor em centavos, como inteiro.</b> Ponto flutuante nao representa dinheiro
 *       exatamente, e as janelas somam e comparam valores o tempo todo.
 *   <li><b>{@code horarioEvento} e quando a transacao aconteceu</b>, nao quando o motor a
 *       processou. As janelas usam este campo (topico 3, consistencia).
 * </ul>
 */
public record Transacao(
        String transacaoId,
        String clienteId,
        String cartaoToken,
        String bin,
        String ultimosQuatro,
        long valorCentavos,
        String estabelecimentoId,
        String categoriaEstabelecimento,
        String cidade,
        String pais,
        Canal canal,
        Instant horarioEvento) {

    public double valorEmReais() {
        return valorCentavos / 100.0;
    }
}
