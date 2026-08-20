package br.com.antifraude.auditoria.registro;

import br.com.antifraude.contrato.Alerta;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class RepositorioDeAuditoria {

    private static final String GRAVAR = """
            INSERT INTO alerta (
                alerta_id, transacao_id, cliente_id, cartao_token, ultimos_quatro,
                valor_centavos, regra_id, regra_versao, janela, severidade,
                notificar_cliente, valores_entrada, horario_evento_transacao, horario_avaliacao)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (alerta_id, horario_avaliacao) DO NOTHING""";

    private final JdbcTemplate banco;
    private final ObjectMapper conversorJson;

    public RepositorioDeAuditoria(JdbcTemplate banco, ObjectMapper conversorJson) {
        this.banco = banco;
        this.conversorJson = conversorJson;
    }

    public boolean gravar(Alerta alerta) {
        int linhasAfetadas = banco.update(
                GRAVAR,
                UUID.fromString(alerta.alertaId()),
                UUID.fromString(alerta.transacaoId()),
                alerta.clienteId(),
                alerta.cartaoToken(),
                alerta.ultimosQuatro(),
                alerta.valorCentavos(),
                alerta.regraId(),
                alerta.regraVersao(),
                alerta.janela(),
                alerta.severidade().name(),
                alerta.notificarCliente(),
                comoJson(alerta),
                Timestamp.from(alerta.horarioEventoTransacao()),
                Timestamp.from(alerta.horarioAvaliacao()));

        return linhasAfetadas > 0;
    }

    private String comoJson(Alerta alerta) {
        try {
            return conversorJson.writeValueAsString(alerta.valoresEntrada());
        } catch (JsonProcessingException problema) {
            throw new IllegalStateException(
                    "nao foi possivel serializar os valores de entrada do alerta " + alerta.alertaId(),
                    problema);
        }
    }
}
