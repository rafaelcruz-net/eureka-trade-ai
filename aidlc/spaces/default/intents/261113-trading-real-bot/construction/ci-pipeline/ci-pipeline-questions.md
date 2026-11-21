# Perguntas da CI Pipeline — intent 261113-trading-real-bot

Conversa: português (Brasil). As respostas seguem a instrução do responsável "siga sem me perguntar": em cada pergunta foi escolhida a opção recomendada.

Fontes:
- `construction/build-and-test/build-and-test-summary.md` e `test-results.md`;
- os `code-summary.md` das 7 Units;
- `.github/workflows/ci.yml`, `security.yml` e `load-test.yml`;
- `team.md` (Way of Working, Testing Posture, Deployment).

## Pergunta 1 — Ferramenta de CI

A CI do projeto já roda no GitHub Actions. Ela tem três workflows:
- `ci` com sete jobs: backend, frontend, admin, contrato, smoke, txsigner e e2e;
- `security`;
- `load-test`, só manual.

Qual ferramenta a CI do REAL usa?

A. Continuar com o GitHub Actions, estendendo os workflows atuais (recomendado: já cobre todos os testes do REAL e é o que o `team.md` fixa)
B. Migrar para CodePipeline/CodeBuild na AWS
C. Outra ferramenta
X. Other (please specify)

[Answer]: A

## Pergunta 2 — Estratégia de branches

O `team.md` fixa o desenvolvimento baseado em tronco: commits direto em `main`, pedidos pelo responsável, e merge só com a CI verde. Qual estratégia a CI considera?

A. Manter a estratégia do `team.md`: a CI roda em todo push e pull request, e o e2e completo roda no push para `main` (recomendado)
B. Exigir pull request para todo commit em `main`
C. Criar branches de release
X. Other (please specify)

[Answer]: A

## Pergunta 3 — Portões de qualidade antes do merge

Os portões atuais já bloqueiam:
- lint, formatação e tipos;
- os testes de unidade e de integração, com piso de 80% em cada parte;
- o contrato OpenAPI;
- ArchUnit;
- `promtool`;
- os 42 specs de ponta a ponta;
- segredos, SAST e dependências (Alto e Crítico bloqueiam).

No Build and Test apareceram três pontos frágeis:
- downloads do Maven que falham por rede;
- ações que ainda usam Node 20;
- nenhum cenário de carga do REAL.

O que fazer com os portões?

A. Manter todos os portões e acrescentar proteção contra a falha de rede no download do Maven, com cache da distribuição do wrapper e nova tentativa. Atualizar as ações para versões em Node 24, fixadas pelo SHA. Deixar o cenário de carga do REAL para a Performance Validation, como manda a matriz. (recomendado)
B. Manter os portões como estão
C. Acrescentar também o cenário de carga do REAL agora
X. Other (please specify)

[Answer]: A

## Pergunta 4 — Repositório de artefatos

Hoje as imagens são montadas na própria CI, com a etiqueta do SHA do commit, e não são publicadas. Ainda não existe conta de nuvem; a conta AWS nova entra no Environment Provisioning. Onde ficam os artefatos?

A. Nenhum repositório agora. As imagens continuam com a etiqueta do SHA. A publicação no ECR fica para o Environment Provisioning e a Deployment Pipeline. (recomendado)
B. Publicar já no GitHub Container Registry
C. Publicar já no ECR
X. Other (please specify)

[Answer]: A

## Pergunta 5 — Exceção EX-014

O aviso GHSA-fc5q-39xc-vjpf (`picomatch`) foi retirado pelo GitHub em 2026-11-18. A EX-014 e o `ignoreGhsas` dos dois apps ficaram sem efeito, e o `ignoreGhsas` não tem validade. O que fazer?

A. Remover a EX-014 e o `ignoreGhsas` de `apps/web` e `apps/admin`, e conferir que o `pnpm audit` e o workflow `security` continuam verdes (recomendado pela revisão da U2)
B. Manter até a validade de 2027-02-16
X. Other (please specify)

[Answer]: A

## Pergunta 6 — Quem altera os workflows

O `team.md` diz que só o responsável altera o `ci.yml`. Como as mudanças dos workflows chegam?

A. Como texto pronto em `ci-config.md`, para o responsável aplicar. Os arquivos que não são workflow, como a EX-014 e os `package.json`, são alterados aqui. (recomendado)
B. O assistente edita os workflows direto
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- A CI continua no GitHub Actions, estendendo os workflows `ci`, `security` e `load-test` [Q1].
- A estratégia é a baseada em tronco do `team.md`: CI em todo push e pull request, e o e2e completo no push para `main` [Q2].
- Todos os portões atuais ficam. Entram duas melhorias:
  - proteção contra a falha de rede no download do Maven, com cache da distribuição do wrapper e nova tentativa;
  - ações atualizadas para Node 24, fixadas pelo SHA.
- O cenário de carga do REAL fica para a Performance Validation [Q3].
- Nenhum repositório de artefatos por enquanto: as imagens seguem com a etiqueta do SHA, e o ECR fica para o Environment Provisioning e a Deployment Pipeline [Q4].
- A EX-014 e o `ignoreGhsas` saem dos dois apps, porque o aviso foi retirado. A conferência é o `pnpm audit` e o workflow `security` verdes [Q5].
- As mudanças dos workflows chegam como texto pronto em `ci-config.md` para o responsável aplicar. Os demais arquivos são alterados aqui [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
