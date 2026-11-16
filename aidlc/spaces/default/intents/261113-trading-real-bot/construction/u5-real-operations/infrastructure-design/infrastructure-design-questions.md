# Perguntas de Infrastructure Design — U5 `u5-real-operations`

Conversa: português (Brasil). Base:
- `construction/u5-real-operations/nfr-design/`: componentes, CI com o SHA da imagem, redação da trilha, migração 0021;
- `construction/u3-real-risk/infrastructure-design/` e `construction/u4-real-execution/infrastructure-design/`;
- o código atual: o `DepositScanner` da U1 registra toda entrada de SOL, inclusive de origem desconhecida;
- `team.md` (Deployment, Determinismo).

## Q1. [Nuvem] O que muda?

A. Nenhum serviço novo:
- **Token do GitHub:** fica num segredo próprio do Secrets Manager, lido só pelo papel do worker.
- **Rede:** o worker sai para `api.github.com` em HTTPS (443) pela NAT que já existe.
- **Imagens:** recebem o SHA do commit no build.
- **Pools:** o worker vai a 25 tarefas agendadas e 33 conexões (Compose e definição da tarefa quando o worker tiver tarefa própria); a API vai a 22 tarefas.
B. Um serviço novo para a reconciliação
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Local] Como provar a divergência de ponta a ponta sem rede real?

A. Um spec novo, `apps/web/e2e/real-ops/divergence.spec.ts`, no validador local:
1. Cria um mint de teste e envia tokens dele para a carteira do bot, por uma ferramenta só de teste. O `DepositScanner` só registra entradas de SOL, então o token não entra no ledger. É uma divergência real de um mint, como alguém mandando tokens para a carteira.
2. Espera a reconciliação marcar a divergência e pausar o usuário.
3. Pelo administrador, compensa a quantidade medida e confere a nova reconciliação limpa e a despausa.
4. Liga e desliga o kill switch e confere a recusa e a volta.

No `verify.sh`, a CI fica verde pela configuração (`ConfiguredCiStatus`), e o intervalo da reconciliação é curto, só no perfil de teste. O texto da mudança no `verify.sh` vai pronto para você.
B. Provar só com testes de integração
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [CI] O que muda?

A. O `ci.yml` é seu. Vai pronto, no code-summary, o texto de três mudanças:
- `--build-arg BUILD_SHA=${{ github.sha }}` nos `docker build` da API e do TxSigner (jobs `smoke` e `e2e`);
- `real-ops-alerts` na lista do passo do `promtool`;
- o spec `e2e/real-ops` no `verify.sh`.

Nenhum token do GitHub entra na CI.
B. Mudar o `ci.yml` nesta Unit
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Implantação e rollback] Em que ordem?

A. A ordem:
1. migração 0021, só de expansão;
2. worker;
3. API.

O TxSigner não muda nesta Unit. Depois da implantação, os portões só liberam com a CI do commit implantado verde.

Rollback:
1. desligar o REAL ou ligar o kill switch;
2. voltar as imagens.

O bloco de rollback da 0021 devolve o gatilho original da trilha (0005). As tabelas ficam. O saque continua disponível o tempo todo.
B. Rollback removendo as tabelas
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Nenhum serviço novo: token do GitHub num segredo só do worker, saída para `api.github.com`, SHA na imagem e pools novos [Q1].
- Divergência provada de ponta a ponta: tokens de teste mandados à carteira do bot, depois pausa, compensação, despausa e kill switch [Q2].
- CI e `verify.sh` com texto pronto para você aplicar (SHA no build, `promtool` e o spec novo) e sem token na CI [Q3].
- Ordem: migração, worker e API. O rollback devolve o gatilho original da trilha e o saque fica sempre livre [Q4].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
