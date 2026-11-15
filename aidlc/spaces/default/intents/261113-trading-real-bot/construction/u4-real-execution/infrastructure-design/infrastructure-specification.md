# Especificação de infraestrutura — U4 `u4-real-execution`

Conversa: português (Brasil). Fontes:
- `construction/u4-real-execution/nfr-design/`: `logical-components.md` (pools, desvio de Q2) e `scalability-design.md` (privilégios);
- `construction/u1-bot-wallet/infrastructure-design/` e `construction/u3-real-risk/infrastructure-design/`;
- os arquivos atuais: `infra/docker-compose.real.yml`, `docker-compose.yml`, `ComposeFilesTest`, `RuntimeSettingsGuard`, `infra/kms-sim/KmsSim.java` e `infra/terraform/modules/data/main.tf`;
- `team.md`: Deployment e Determinismo, que permite contas clonadas para os swaps no validador local;
- `infrastructure-design-questions.md`, Q1 a Q5.

## Compute e configuração

| Item | Mudança |
|---|---|
| Worker no `docker-compose.yml` | `DB_POOL_MAX_SIZE` passa de 14 para 27; `SCHEDULER_POOL_SIZE` passa de 4 para 19; o comentário da conta de conexões é atualizado |
| `ComposeFilesTest` | passa a conferir 27 e 19 no worker e a conta de 56 de 100 conexões |
| `RuntimeSettingsGuard` | worker com scheduler de 19 a 26 e pool de 27 a 40, com o teste de contagem dos métodos `@Scheduled` |
| Worker na nuvem (Terraform) | hoje o worker divide a tarefa da API; as duas variáveis entram quando o worker ganhar tarefa própria, o que já está pendente desde a U2 |
| API e TxSigner | nenhuma mudança de compute |

## Banco

**`max_connections` não muda.** Isso corrige a resposta confirmada na Q1, que dizia 150; a revisão mostrou que não é preciso.
- **Nuvem:** o TxSigner tem banco próprio e não entra na conta.
- **Compose do REAL:** o TxSigner divide o mesmo PostgreSQL. A conta fica 43 − 14 + 27 = 56, mais as conexões do TxSigner (até 10), ou seja, **66 de 100**. Na nuvem, pelo máximo das faixas:

| Uso | Conexões |
|---|---|
| API | até 50 |
| worker | até 40 |
| retenções e trava | 4 |
| migração | 2 |
| **Total** | **96** |

O padrão do `db.t4g.micro` é cerca de 112, e por isso não é preciso criar grupo de parâmetros no módulo `data` do Terraform. Se um dia for preciso mais, a troca exige um grupo de parâmetros novo e reinício da instância; isso fica registrado no runbook.

**Migração Liquibase nova, só de expansão.** Cria o campo `market` em `bot`, com padrão `PAPER`, e as tabelas abaixo, com os índices de `scalability-design.md` e os privilégios desta matriz. TRUNCATE, REFERENCES e TRIGGER são revogados de todos, e ALL é revogado de PUBLIC:

| Tabela | Aplicação | `risk_retention` |
|---|---|---|
| `real_decision_request` | SELECT, INSERT, UPDATE (estado) | SELECT, DELETE |
| `real_order` | SELECT, INSERT, UPDATE (colunas da lista) | SELECT, DELETE |
| `real_order_attempt` | SELECT, INSERT, UPDATE (resultado) | SELECT, DELETE |
| `real_liquidation` | SELECT, INSERT, UPDATE (estado) | SELECT, DELETE |
| `real_upstream_health` | SELECT, INSERT, UPDATE | nenhum |
| `real_execution_purge_run` | nenhum | SELECT, INSERT, DELETE |

A migração também cria os gatilhos:
- das colunas mutáveis em `real_order` e `real_liquidation`;
- que recusam sair de estado final, salvo limpar a mensagem;
- de idade: 5 anos para ordens, envios e liquidações e 13 meses para decisões fechadas;
- que recusam UPDATE nas colunas de identidade.

Cada changeset tem bloco de rollback, e um teste confere os privilégios e os gatilhos.

## Rede e segredos

- O worker sai por HTTPS (443) na subrede com NAT, como na U2, e passa a falar com a Jupiter (lista fixa na aplicação). A API não fala com a Jupiter.
- Nenhum segredo novo: o plano usado da Jupiter não pede chave.
- O TxSigner fica numa subrede sem internet. O caminho das métricas dele até o CloudWatch (endpoint de VPC) entra na mesma decisão pendente das métricas de U1 a U4.

## Prova do swap no local (corrige Q2)

Para os dados do swap gravado baterem com a execução, três coisas precisam ser fixas.

**Chave determinística no KMS simulado.**
- O `kms-sim` ganha a variável `KMS_SIM_SEED`, aceita só nos perfis `test` e `local`. A chave de cada carteira passa a ser derivada da semente e da ordem de criação.
- No Compose do REAL a semente é fixa, então a carteira do bot do usuário de teste tem sempre o mesmo endereço, e as contas de token dela também.
- Sem a variável, a chave continua aleatória, como hoje.
- O perfil de produção do TxSigner já recusa outro endereço de KMS; ele passa a recusar também a variável de semente.

**Valor fixo.** A compra do teste é sempre de 0,05 SOL, e a venda leva exatamente a quantidade recebida.

**Gravação na mesma janela.** O script `infra/solana-fixtures/record.sh` roda uma vez, fora dos testes, por quem desenvolve e com rede:
1. Pede à Jupiter a cotação e as instruções de swap de compra e de venda, para a carteira do bot determinística, numa rota de um salto só (`onlyDirectRoutes`) num pool CPMM.
2. Grava as respostas para o WireMock.
3. Grava no mesmo slot todas as contas que as instruções citam: o programa da Jupiter, o programa do DEX, o pool, os cofres, a configuração, o mint e as contas de oráculo ou de tick, se houver. Grava os programas com `solana program dump` e as contas com `solana account --output json`.
4. Confere que as duas pernas executam numa simulação contra essas contas.

As contas clonadas usam o mint real, o que o `team.md` permite só para os swaps no validador local. Os Token Programs (clássico e 2022) e os sysvars já vêm no validador.

**Validador.** O `solana-validator` do `infra/docker-compose.real.yml` sobe com:
- `--bpf-program` para cada programa gravado;
- `--account` para cada conta;
- a pasta montada como somente leitura.

**Jupiter simulada.** O `jupiter-stub` (WireMock fixado por digest) devolve as respostas gravadas. O worker usa `MEMETRADE_JUPITER_BASE_URL=http://jupiter-stub:8080` só nesse Compose.

**Decisão do bot.**
- O `RealTestSeeder` da U1, que só existe no perfil de teste, cria o usuário liberado e o bot REAL em Automática total, com uma estratégia de teste que compra o mint gravado uma vez.
- O mint gravado entra nas fontes simuladas (`sim-a`) com preço, liquidez e volume frescos, e a avaliação do scanner fica disponível para ele.

**Ponta a ponta.** O spec `apps/web/e2e/real-swap/swap.spec.ts`:
1. deposita na carteira do bot;
2. espera a compra `CONFIRMED` e confere os lançamentos;
3. pede "vender tudo e sacar";
4. confere o saldo devolvido à carteira principal de teste.

**`verify.sh`.** A mudança vai pronta para o responsável do projeto aplicar.

## Implantação e rollback [Q5]

**Ordem:**
1. migração;
2. TxSigner (o R1 muda antes da API);
3. worker;
4. API.

Os bots REAL só podem ser criados para usuários liberados (U5).

**Rollback:**
1. Desligar o REAL (`memetrade.real.enabled` e `txsigner.real.enabled`) ou acionar o kill switch REAL. O condutor cancela as ordens não enviadas.
2. Esperar as ordens `SENT` e `AMBIGUOUS` chegarem a estado final, por no máximo 15 minutos. Com o REAL desligado, os trabalhos de confirmação, ambíguas e liquidação continuam rodando, porque só terminam o que já foi enviado.
3. Voltar as imagens. Se sobrar ordem `AMBIGUOUS` depois dos 15 minutos, a volta segue mesmo assim: as tabelas ficam, e a conferência retoma quando a imagem nova voltar. A ordem ambígua continua reservada e aparece no alerta.

O saque continua disponível o tempo todo. Durante o rollback, os alertas de trabalho parado ficam silenciados, como no runbook da U3. A implantação só termina com o smoke test verde (`team.md`).
