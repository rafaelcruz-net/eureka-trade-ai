# Entrevista da Practices Discovery — paper-real-unico

Conversa: português (Brasil). As perguntas tratam só do que a evidência mostrou diferente das práticas já registradas (`evidence.md`, DIV-xx). As que não são críticas seguem a instrução "Responda todos com os recomendados". As críticas são marcadas e ficam com o responsável.

## Q1. O repositório é público ou privado? (crítico)

As práticas dizem "repositório privado no GitHub", mas hoje ele é **público**, com fork permitido. A varredura de segredos do GitHub, a proteção no push e os alertas do Dependabot estão desligados (DIV-01 e DIV-16).

A. Tornar privado agora e ligar a varredura de segredos, a proteção no push e os alertas do Dependabot. Atenção: num repositório privado, os minutos do Actions passam a contar na cota do plano. (recomendado)
B. Manter público e ligar a varredura de segredos, a proteção no push e os alertas do Dependabot, que são gratuitos em repositório público
C. Manter como está
X. Other (please specify)

[Answer]: A

## Q2. Proteção da `main` (crítico)

As práticas dizem "nenhum merge com a CI vermelha". Hoje, porém, os commits entram direto na `main`, sem proteção de branch, e 18 das últimas 30 execuções da CI na `main` ficaram vermelhas e foram corrigidas depois (DIV-05).

A. Manter os commits direto na `main` e escrever a prática como ela é: "a `main` pode ficar vermelha por pouco tempo, e a correção vem no commit seguinte, antes de qualquer outra mudança". Os pontos de controle e as implantações só usam um commit com a CI verde. (recomendado: mantém o ritmo de quem trabalha sozinho e é honesto com o histórico)
B. Proteger a `main` com uma regra que exige a CI verde, trabalhando em branches com pull request
X. Other (please specify)

[Answer]: A

## Q3. Implantação

A Operation do intent 261113 desenhou duas contas AWS separadas (devnet e mainnet), o Terraform, um `deploy.yml` manual com GitHub Environments e OIDC, e o passo do TxSigner feito pelo fundador com MFA. Nada disso foi aplicado ainda (DIV-02).

A. Registrar esse desenho como a prática de implantação, com a observação "ainda não aplicado". A devnet recebe implantação manual, e não "staging a cada merge". (recomendado)
X. Other (please specify)

[Answer]: A

## Q4. Ambiente único dos usuários

A. Os usuários usam só a mainnet. A devnet serve aos testes do fundador e de pré-produção. A "produção do beta PAPER" passa a ser o ambiente mainnet (DIV-03). (recomendado)
X. Other (please specify)

[Answer]: A

## Q5. CI

A. Registrar a CI como ela é hoje: os jobs `txsigner` e `e2e`, o `promtool` e o `load-test.yml` manual. As melhorias propostas e ainda não aplicadas ficam registradas como pendências do responsável: Node 24, CodeQL v4, proteção do download do Maven, `terraform fmt/validate/test` e Checkov. O conflito entre o `id-token: write` do `deploy.yml` e o `WorkflowsTest` também entra como pendência: o teste precisa aceitar essa permissão só no `deploy.yml` (DIV-04 e DIV-06). (recomendado)
X. Other (please specify)

[Answer]: A

## Q6. Organização do repositório, tags e commits

A. Acrescentar ao registro:
   - as pastas `apps/admin`, `apps/txsigner`, `infra/terraform`, `infra/observability` e `docs/runbooks`;
   - as tags `vAAAA.MM.DD-N`, que marcam versões locais;
   - os commits em inglês com os prefixos `feat`, `fix`, `test`, `chore`, `ci` e `docs`.

   (DIV-08, DIV-09 e DIV-10) (recomendado)
X. Other (please specify)

[Answer]: A

## Q7. Verificação dos pontos de controle

A. Registrar a regra: o `scripts/verify-checkpoint.sh` roda primeiro no local; se a máquina não aguentar, a prova é a execução da CI do mesmo commit (DIV-11). (recomendado)
X. Other (please specify)

[Answer]: A

## Q8. Volume de testes e fatia fina deste intent

A. O volume de testes do projeto continua `Comprehensive`, e o deste intent segue o que ele registra (DIV-13). A fatia fina deste intent é a visão única com um bot PAPER e um bot REAL no validador local, com um spec novo no `verify.sh` que cobre o teste de risco "nunca somar os modos" (DIV-14). O responsável acrescenta o spec ao `verify.sh`. (recomendado)
X. Other (please specify)

[Answer]: A
