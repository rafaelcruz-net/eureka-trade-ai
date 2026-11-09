# Runbook de rollback

Conversa: português (Brasil). Resposta 5A de `deployment-pipeline-questions.md`. Vale para o ambiente local de hoje e para a AWS no futuro.

## Quando fazer rollback

| Sinal | Ação |
|---|---|
| `scripts/verify.sh` (ou o smoke do ambiente) termina com saída diferente de zero depois da subida | rollback imediato |
| Saúde de algum serviço `DOWN` por mais de 5 minutos depois da subida | rollback |
| Erro 5xx ou falha de ciclo do worker acima do normal logo depois da subida | pausar os bots com o kill switch da U8; se não normalizar, rollback |
| Defeito de dinheiro, tenant ou ledger visto depois da subida | kill switch da U8 primeiro, depois rollback |

## Princípio

Rollback só de imagens: volta-se para as imagens do SHA anterior e **não** se desfaz migração. As migrações são só de expand, então a versão anterior roda sobre o banco migrado (`deployment-strategy.md`). O ledger é append-only e nunca é editado; qualquer correção de dados é feita com lançamento compensatório (`project.md`).

## Rollback local (hoje)

1. Pausar os bots, se estiverem rodando: kill switch no app do administrador.
2. Identificar a release anterior: `git tag --sort=-creatordate | head -2`.
3. `git checkout <tag anterior>`.
4. `IMAGE_TAG=$(git rev-parse --short HEAD) docker compose up -d --build --remove-orphans`. O `--remove-orphans` remove serviços que a versão anterior não tem (por exemplo, o `worker`, se a anterior for de antes da U5).
5. `bash scripts/verify.sh` precisa sair com código 0.
6. Desligar o kill switch, se a versão anterior tiver bots.
7. Registrar: SHA de origem, SHA de destino, motivo, horário e resultado do smoke.

Estados esperados por Unit depois do rollback estão nos `cicd-pipeline.md` de cada Unit (por exemplo, U5: bots ACTIVE ficam sem ciclo até a volta da versão com worker, sem repetir fatias).

## Rollback na AWS (futuro)

1. O workflow `deploy` não troca o tráfego se o smoke do verde falhar: nada a fazer além de investigar.
2. Depois da troca, se um sinal acima aparecer: trocar o tráfego de volta para o azul (que ainda roda o SHA anterior).
3. Se o azul já tiver sido desligado: implantar as imagens do SHA anterior a partir do registro, com o mesmo workflow, sem rodar migração nova.
4. Smoke do ambiente verde.
5. Registro igual ao do rollback local.

## Ensaio (antes de cada release)

1. Subir a release candidata localmente e rodar `bash scripts/verify.sh` (saída 0).
2. Fazer o rollback local para a release anterior (passos 2 a 5 acima): `verify.sh` com saída 0 na versão anterior, sobre o banco já migrado pela candidata.
3. Voltar para a candidata e rodar `verify.sh` de novo.
4. A release só é anunciada se os três `verify.sh` passarem. O resultado fica no registro da tag.

O ensaio prova o que o expand-contract promete: a versão anterior funciona sobre o banco migrado.

## Contatos e escalonamento

- Responsável pela decisão de rollback e pela produção do beta: o responsável do projeto.
- Funções REAL: não existem; quando existirem, qualquer incidente envolve também o revisor independente (C-14).
- Incidente P1/P2 exige revisão pós-incidente com linha do tempo, causa raiz e prevenção (`phases/operation.md`).
