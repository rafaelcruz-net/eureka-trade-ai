# Perguntas da Deployment Pipeline — intent 261113-trading-real-bot

Conversa: português (Brasil). As respostas seguem a instrução do responsável "siga sem me perguntar": em cada pergunta foi escolhida a opção recomendada.

Fontes:
- `construction/ci-pipeline/ci-config.md` e `quality-gates.md`;
- `construction/u*/infrastructure-design/cicd-pipeline.md` e `infrastructure-specification.md` das 7 Units;
- `construction/build-and-test/build-and-test-summary.md`;
- `verification/phase-check-construction.md`, que pede a passagem condicional;
- `team.md`, seção Deployment;
- `project.md`.

## Pergunta 1 — Estratégia de implantação

O Infrastructure Design fixou o piloto assim:
- ECS Fargate numa zona;
- a API e o worker na mesma tarefa;
- o TxSigner numa instância só;
- dois RDS `db.t4g.micro`.

O REAL tem idempotência e um processo único de assinatura. Qual estratégia de implantação usar?

A. Rolling no ECS, com `minimumHealthyPercent=100`/`maximumPercent=200` na API e no worker, ou seja, a tarefa nova sobe saudável antes de a antiga sair. O TxSigner é substituído só depois de o novo ficar pronto: readiness do banco e `ListKeys`. A ordem é TxSigner, depois worker e API. O deployment circuit breaker do ECS com rollback automático fica ligado. (recomendado: cabe no custo do piloto e respeita uma única instância de assinatura)
B. Blue/green com CodeDeploy e load balancer duplo
C. Canary por porcentagem de tráfego
X. Other (please specify)

[Answer]: A

## Pergunta 2 — Promoção entre ambientes

Hoje existe só o ambiente local, mais o validador Solana local na CI. O `team.md` define o rollout do REAL assim: local e devnet desde o primeiro Bolt; depois a carteira do fundador na mainnet; depois até 20 convidados. Quais são os portões de promoção?

A. Quatro portões, nesta ordem (recomendado: é o rollout do `team.md` e mantém as condições da passagem para a Operação):
   - **CI verde:** todos os portões de `quality-gates.md`.
   - **Devnet:** o mesmo SHA, com smoke test e o spec da carteira na devnet.
   - **Mainnet do fundador:** exige os pré-requisitos de mainnet:
     - conta AWS e KMS reais;
     - dois provedores de RPC;
     - as metas de desempenho e observabilidade verificadas;
     - as saídas condicionais desligadas até a gravação real da Jupiter.
   - **Convidados:** exige três condições:
     - quarentena de 24 h;
     - 7 dias da carteira do fundador sem divergência;
     - parecer de custódia.
B. Pular a devnet e ir direto do local para a mainnet
C. Criar um staging separado antes da devnet
X. Other (please specify)

[Answer]: A

## Pergunta 3 — Aprovação para produção

O `team.md` diz que a produção exige aprovação manual separada do responsável, e que o fundador é o revisor único dos gates do REAL. Como é a aprovação?

A. Pelo GitHub Environments: os ambientes `devnet` e `mainnet`, com o responsável como revisor obrigatório no `mainnet`. O workflow de implantação, `deploy.yml`, é disparado à mão (`workflow_dispatch`) com o SHA aprovado. A credencial é AWS OIDC, com um papel só de implantação e sem chave longa. (recomendado)
B. Implantar da máquina do fundador, sem workflow
C. Implantar automaticamente a cada merge
X. Other (please specify)

[Answer]: A

## Pergunta 4 — Rollback

O `team.md` pede um rollback documentado e testado, artefatos marcados com o SHA, migrações expand-contract e smoke test ao fim. No REAL, o rollback começa desligando o flag do REAL ou acionando o kill switch, e o saque continua disponível. Qual procedimento de rollback usar?

A. Os passos são (recomendado: é o que o `team.md` exige):
   1. acionar o kill switch REAL, ou desligar `memetrade.real.enabled` e `txsigner.real.enabled`;
   2. voltar a definição de tarefa do ECS para o SHA anterior, na ordem inversa: API e worker, depois TxSigner;
   3. nunca reverter migração;
   4. rodar o smoke test;
   5. conferir o saque e a reconciliação;
   6. religar o REAL só com a aprovação do fundador.

   O procedimento é ensaiado na devnet antes da primeira mainnet.
B. Restaurar o banco por PITR em todo rollback
X. Other (please specify)

[Answer]: A

## Pergunta 5 — Flags de funcionalidade

O código já usa estes flags de configuração:
- `memetrade.real.enabled` e `txsigner.real.enabled`, desligados por padrão;
- `memetrade.real.exits.enabled`, desligado até a gravação real da Jupiter;
- a liberação por usuário, com quarentena;
- o kill switch na administração.

Que estratégia de flags usar?

A. Manter os flags como variáveis de ambiente da definição de tarefa, versionadas no Terraform. O kill switch e a liberação por usuário seguem no banco, pela administração. Não entra um serviço novo como AppConfig ou Evidently no piloto. (recomendado: menos peças e mesmo efeito, e mudar um flag é uma implantação auditada)
B. Usar AWS AppConfig para os flags
C. Usar CloudWatch Evidently
X. Other (please specify)

[Answer]: A

## Pergunta 6 — Quem altera os workflows de implantação

O `team.md` diz que só o responsável altera os arquivos de `.github/workflows/`. Como o `deploy.yml` chega ao repositório?

A. Como texto pronto em `cd-config.md`, para o responsável criar o arquivo e configurar os ambientes e o papel OIDC. O assistente não cria o workflow nem toca na conta AWS. (recomendado)
B. O assistente cria o `deploy.yml` direto
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Rolling no ECS.** A tarefa nova sobe saudável antes de a antiga sair. O TxSigner é substituído primeiro e só depois de ficar pronto. A ordem é TxSigner, worker e API, com o circuit breaker do ECS e rollback automático [Q1].
- **Promoção com quatro portões:** CI verde, devnet, mainnet do fundador e convidados.
  - A mainnet exige conta e KMS reais, dois RPC, as metas de desempenho e observabilidade verificadas e as saídas condicionais desligadas até a gravação real.
  - Os convidados exigem quarentena de 24 h, 7 dias sem divergência e parecer de custódia [Q2].
- **Aprovação pelo GitHub Environments.** O responsável é o revisor obrigatório da `mainnet`. A implantação é manual, com o SHA aprovado e credencial AWS por OIDC, sem chave longa [Q3].
- **Rollback.** Primeiro o kill switch ou o REAL desligado. Depois a definição de tarefa anterior, na ordem inversa, sem reverter migração. Em seguida o smoke test e a conferência do saque e da reconciliação. O REAL só volta com a aprovação do fundador. O ensaio é feito na devnet [Q4].
- **Flags.** Ficam como variáveis de ambiente versionadas no Terraform. O kill switch e a liberação continuam no banco. Nenhum serviço novo de flags no piloto [Q5].
- **`deploy.yml`.** Chega como texto pronto em `cd-config.md`, para o responsável criar, junto com os ambientes e o papel OIDC. O assistente não toca na conta AWS [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
