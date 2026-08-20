#!/usr/bin/env bash
# Carrega regras/regras.yml na colecao "regras" do Mongo.
# O motor recarrega sozinho em ate 30 segundos — nenhum redeploy e necessario.
set -euo pipefail

ARQUIVO="${1:-regras/regras.yml}"
URI="${MONGO_URI:-mongodb://localhost:27017/antifraude}"

if [ ! -f "$ARQUIVO" ]; then
  echo "arquivo nao encontrado: $ARQUIVO" >&2
  exit 1
fi

JSON=$(python3 - "$ARQUIVO" <<'PY'
import json, sys, re

texto = open(sys.argv[1], encoding="utf-8").read()
regras, atual, chave_dobrada = [], None, None

for linha in texto.splitlines():
    sem_comentario = linha.split("#", 1)[0] if linha.lstrip().startswith("#") else linha
    if not sem_comentario.strip():
        continue

    item = re.match(r"^  - (\w+): (.*)$", sem_comentario)
    campo = re.match(r"^    (\w+): (.*)$", sem_comentario)

    if item:
        if atual:
            regras.append(atual)
        atual, chave_dobrada = {}, None
        nome, valor = item.group(1), item.group(2).strip()
    elif campo:
        chave_dobrada = None
        nome, valor = campo.group(1), campo.group(2).strip()
    elif chave_dobrada and sem_comentario.startswith("      "):
        atual[chave_dobrada] = (atual[chave_dobrada] + " " + sem_comentario.strip()).strip()
        continue
    else:
        continue

    if valor == ">":
        atual[nome], chave_dobrada = "", nome
        continue
    if valor in ("true", "false"):
        atual[nome] = valor == "true"
    elif valor.isdigit():
        atual[nome] = int(valor)
    else:
        atual[nome] = valor.strip("'\"")

if atual:
    regras.append(atual)

obrigatorios = {"id", "versao", "descricao", "habilitada", "severidade", "janela",
                "notificarCliente", "condicao"}
for regra in regras:
    faltando = obrigatorios - regra.keys()
    if faltando:
        sys.exit(f"regra {regra.get('id', '?')} sem os campos: {sorted(faltando)}")

identificadores = [r["id"] for r in regras]
if len(identificadores) != len(set(identificadores)):
    sys.exit("ha regras com id repetido")

print(json.dumps(regras, ensure_ascii=False))
PY
)

QUANTIDADE=$(printf '%s' "$JSON" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))")

docker compose exec -T mongo mongosh "$URI" --quiet --eval "
const regras = $JSON;
regras.forEach(r => db.regras.replaceOne({ _id: r.id }, Object.assign({ _id: r.id }, r), { upsert: true }));
print('regras na colecao: ' + db.regras.countDocuments());
db.regras.find({}, { _id: 1, versao: 1, habilitada: 1 }).forEach(r =>
  print('  ' + r._id + '  v' + r.versao + (r.habilitada ? '  ativa' : '  DESLIGADA')));
"

echo "$QUANTIDADE regra(s) enviadas. O motor recarrega em ate 30 segundos."
