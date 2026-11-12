# Perguntas do CI Pipeline

Conversa: português (Brasil). As respostas seguem a opção recomendada, como o responsável do projeto pediu para todas as perguntas do AI-DLC (2026-11-10).

O repositório já tem CI no GitHub Actions, com as lacunas do intent anterior fechadas:

- `.github/workflows/ci.yml`: jobs `backend` (`mvnw verify` com JaCoCo), `frontend` (`apps/web` inteiro com piso de 80%), `admin` (`apps/admin` com lint, formatação, tipos, Jest e `check:api`), `contract`, `smoke` (`scripts/verify.sh`, agora com 19 specs) e `e2e` (push em `main`). Neste intent ganhou `workflow_dispatch` (commit `7e77c11`) e o valor fictício de `MEMETRADE_AUDIT_WALLET_HMAC_KEY` no passo "Compose config" (commit `4bc09d1`).
- `.github/workflows/security.yml`: Gitleaks, CodeQL, OWASP Dependency-Check e `pnpm audit`, com exceções em `security/exceptions.yml`. Neste intent o caminho do arquivo de exceções foi corrigido para `${GITHUB_WORKSPACE}/dependency-check-suppressions.xml` (patch do responsável).
- `.github/workflows/load-test.yml`: carga manual, agora com `reacceptance` e `data-requests` (commit `5d30541`).

Achados do Build and Test que tocam a CI: até 2026-11-11 a CI nunca tinha rodado num push para `main`; o `security.yml` nunca aplicava as exceções; a varredura de dependências do backend acusava CVSS ≥ 7 (corrigido com versões novas e as exceções EX-001 a EX-013).

## Pergunta 1
Qual ferramenta de CI usamos?

A) GitHub Actions, como já está no repositório (recomendado: é o que o `team.md` define)
B) Outra ferramenta
X) Other (please specify)

[Answer]: A

## Pergunta 2
Qual estratégia de branches a CI segue?

A) Desenvolvimento baseado em tronco: CI em todo pull request e em push para `main`; nenhum merge com a CI vermelha (recomendado: é o que o `team.md` define)
B) Outra estratégia
X) Other (please specify)

[Answer]: A

## Pergunta 3
Quais portões de qualidade bloqueiam o merge?

A) Os mesmos do intent anterior, agora todos ativos: backend com JaCoCo ≥ 80%; `apps/web` e `apps/admin` com lint, formatação, tipos e Jest ≥ 80%; contrato; `scripts/verify.sh` com os 19 specs (inclusive U3, U4 e U5 deste intent); segurança com segredos, SAST e dependências Crítico ou Alto, respeitando as exceções versionadas (recomendado)
B) A, e também a carga a cada merge
X) Other (please specify)

[Answer]: A

## Pergunta 4
O analisador OSS Index da Sonatype recusou a consulta sem login (`401`) na varredura local de dependências. Como tratar na CI?

A) Manter o analisador ligado; se a CI também der `401`, o responsável cadastra as credenciais gratuitas do OSS Index como segredo do repositório (recomendado: não reduz a cobertura da varredura)
B) Desligar o analisador OSS Index e ficar só com a base da NVD
X) Other (please specify)

[Answer]: A

## Pergunta 5
Onde ficam os artefatos gerados pela CI?

A) Só como artefatos do GitHub Actions, com retenção curta; imagens e registro ficam para os estágios de implantação, quando houver nuvem (recomendado: ainda não há conta de nuvem, C-10 e C-12)
B) Publicar imagens num registro a cada merge
X) Other (please specify)

[Answer]: A
