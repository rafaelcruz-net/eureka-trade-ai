# Perguntas de Infrastructure Design — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Base:
- `construction/u7-exits-and-fiscal/nfr-design/`: componentes, interfaces de extensão, migração 0022, pools 28/36 e mínimo de 23 na API, `trigger-program.json`;
- `construction/u5-real-operations/infrastructure-design/` e `construction/u6-real-modes-ui/infrastructure-design/` (padrões);
- o ambiente local: `docker-compose.yml`, `infra/docker-compose.real.yml`, `scripts/verify.sh`, `infra/solana-fixtures/`;
- `team.md` (Deployment).

## Q1. [Nuvem] O que muda?

A. Nenhum serviço novo.
- **Segredo:** a chave de API da Jupiter (`jupiter-api-key`) vai para o Secrets Manager. Ela é lida pela API e pelo worker, só se a Jupiter exigir, e fica vazia até a gravação.
- **Rede:** a saída para `api.jup.ag` já existe para a U4, pela NAT.
- **Worker:** o scheduler vai a 28 e o pool a 36.
- **API:** o mínimo do scheduler vai a 23.
- **Compose e definição da tarefa:** mudam junto com os números acima.
- **TxSigner:** sem a gravação, ele sobe sem o `trigger-program.json` e recusa os pedidos de condicional.
B. Um serviço novo para as condicionais
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Local e ponta a ponta] Como provar a U7 sem a gravação real?

A. São dois specs novos no `verify.sh`, depois de `e2e/real-ui`.

**`e2e/real-exports`** exporta o histórico depois do `real-swap` e confere:
- o BOM e o separador;
- a linha da compra com os valores exatos;
- a soma de BR7.6;
- a recusa do período maior que 1 ano.

**`e2e/real-exits`** roda com o adaptador simulado da Jupiter, que só existe no perfil de teste local (`memetrade.real.exits.adapter=simulated` e o flag ligado só nesse perfil). Ele cobre:
- criar stop e alvo pela tela, com o diálogo de assinatura;
- um preenchimento simulado lançado no ledger;
- o cancelamento pelo kill switch (`try/finally`);
- os selos de estado.

O estado de entrada e de saída é o mesmo do `real-ui`. A linha do `verify.sh` vai pronta para você.

A gravação real fica para depois (BR0). O `record.sh` ganha o modo `--trigger`, que você roda uma vez, com rede, como na U4.
B. Esperar a gravação para ter e2e
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [CI] O que muda?

A. Duas mudanças, com o texto pronto para você:
- **`ci.yml`:** `real-exits-alerts` entra no laço do `promtool`.
- **`verify.sh`:** entram os dois specs novos.

O job do TxSigner já roda os testes da política. Sem a gravação, roda só os de `POLICY_NOT_RECORDED` e da carga do arquivo. Nenhum segredo da Jupiter entra na CI.
B. Mudar o `ci.yml` nesta Unit
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Implantação e rollback] Em que ordem?

A. A implantação segue esta ordem:
1. migração 0022 (só de expansão: tabela nova, `not_before` e `attempts` em `real_decision_request`);
2. worker e API;
3. TxSigner, que não muda sem a gravação;
4. web e admin.

O flag `memetrade.real.exits.enabled` continua desligado em toda rede fora do perfil de teste local.

**Rollback:** voltar as imagens na ordem inversa. O rollback da 0022 apaga a tabela nova só quando ela estiver vazia; com dados, ela fica, e só as imagens voltam. O saque continua disponível o tempo todo.
B. Rollback apagando a tabela sempre
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Nenhum serviço novo; segredo da Jupiter vazio até a gravação; saída para a Jupiter já existe; worker 28/36 e API 23 [Q1].
- Dois specs novos no `verify.sh`: exportação conferida e condicionais com o adaptador simulado só no perfil de teste; `record.sh --trigger` para a gravação real depois [Q2].
- `ci.yml` só com o `promtool` novo e `verify.sh` com os specs, com texto pronto para você; nenhum segredo na CI [Q3].
- Ordem: migração, worker e API, TxSigner, web e admin; flag desligado fora do teste local; rollback sem perder dados; saque sempre livre [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
