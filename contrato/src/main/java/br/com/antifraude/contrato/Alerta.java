package br.com.antifraude.contrato;

import java.time.Instant;
import java.util.Map;

/**
 * Alerta publicado pelo motor no topico {@code alertas}.
 *
 * <p>Este topico e <b>contrato versionado</b>, nao detalhe interno: a equipe antifraude, o
 * servico de notificacao e o consumidor de auditoria leem dele. Campos podem ser adicionados;
 * remover ou renomear quebra consumidor.
 *
 * <p>Decisoes registradas no documento de arquitetura:
 *
 * <ul>
 *   <li><b>{@code regraId} e {@code regraVersao} viajam juntos.</b> Responder "por que este
 *       cliente foi sinalizado?" meses depois exige saber qual versao de qual regra decidiu
 *       (topico 2, rastreabilidade).
 *   <li><b>{@code valoresEntrada} carrega o que levou a decisao.</b> Sem isso a trilha de
 *       auditoria diz que houve alerta, mas nao por que.
 *   <li><b>A decisao de roteamento viaja no alerta.</b> O motor grava {@code notificarCliente}
 *       porque e ele que tem o estado para decidir; quem consome apenas obedece, sem precisar
 *       entender regra nenhuma (topico 3).
 *   <li><b>Sem dado sensivel desnecessario.</b> Vao o token e os quatro ultimos digitos, nunca
 *       o numero completo. Este topico tem retencao longa por causa da auditoria.
 * </ul>
 */
public record Alerta(
        String alertaId,
        String transacaoId,
        String clienteId,
        String cartaoToken,
        String ultimosQuatro,
        long valorCentavos,
        String regraId,
        int regraVersao,
        String janela,
        Severidade severidade,
        boolean notificarCliente,
        Map<String, Object> valoresEntrada,
        Instant horarioEventoTransacao,
        Instant horarioAvaliacao) {
}
