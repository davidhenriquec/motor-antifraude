CREATE TABLE alerta
(
    alerta_id                UUID        NOT NULL,
    transacao_id             UUID        NOT NULL,
    cliente_id               VARCHAR(32) NOT NULL,
    cartao_token             VARCHAR(64) NOT NULL,
    ultimos_quatro           CHAR(4)     NOT NULL,
    valor_centavos           BIGINT      NOT NULL,
    regra_id                 VARCHAR(64) NOT NULL,
    regra_versao             INTEGER     NOT NULL,
    janela                   VARCHAR(16) NOT NULL,
    severidade               VARCHAR(8)  NOT NULL,
    notificar_cliente        BOOLEAN     NOT NULL,
    valores_entrada          JSONB       NOT NULL,
    horario_evento_transacao TIMESTAMPTZ NOT NULL,
    horario_avaliacao        TIMESTAMPTZ NOT NULL,
    horario_gravacao         TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (alerta_id, horario_avaliacao)
) PARTITION BY RANGE (horario_avaliacao);

CREATE INDEX idx_alerta_cliente ON alerta (cliente_id, horario_evento_transacao DESC);
CREATE INDEX idx_alerta_regra ON alerta (regra_id, horario_avaliacao DESC);

CREATE TABLE alerta_2026_08 PARTITION OF alerta
    FOR VALUES FROM
(
    '2026-08-01'
) TO
(
    '2026-09-01'
);
CREATE TABLE alerta_2026_09 PARTITION OF alerta
    FOR VALUES FROM
(
    '2026-09-01'
) TO
(
    '2026-10-01'
);
CREATE TABLE alerta_2026_10 PARTITION OF alerta
    FOR VALUES FROM
(
    '2026-10-01'
) TO
(
    '2026-11-01'
);
CREATE TABLE alerta_fora_de_faixa PARTITION OF alerta DEFAULT;
