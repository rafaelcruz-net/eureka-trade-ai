# Perguntas do CI Pipeline — intent `261121-paper-real-unico`

Conversa: português (Brasil). As respostas seguem a instrução do responsável "Responda todos com os recomendados"; os pontos críticos continuam com ele.

Fontes:
- `construction/build-and-test/` (resumo, `test-results.md` seções 9 e 10, `cross-unit-traceability.md`).
- Os `code-summary.md` da U1 à U4: textos prontos do `load-test.yml` (U3, 9 leituras e 7 portões, substitui o da U2) e o passo `node --test "scripts/load/*.test.mjs"` do `ci.yml`.
- `team.md`, seção Way of Working: cobertura da CI e pendências da CI com o responsável.
- `261113-trading-real-bot/construction/ci-pipeline/` (precedente).

## Q1. Ferramenta e branch

Que ferramenta de CI e que estratégia de branch o intent usa?

A. Continuam o GitHub Actions (`ci`, `security`, `load-test` manual) e o tronco `main` com commits diretos. A CI roda depois do push, e uma `main` vermelha se corrige no commit seguinte. Nenhuma ferramenta nova. (recomendado)
B. Outra ferramenta
X. Other (please specify)

[Answer]: A

## Q2. Portões de qualidade

Que portões de qualidade valem para o intent?

A. Os portões que já existem continuam sem mudança:
   - `backend`, com Spotless, unidade, integração, JaCoCo 80%, ArchUnit, enforcer e `promtool check rules`; o teste das regras de alerta da U4 roda dentro dele;
   - `frontend` e `admin`, com lint, formatação, tipos e Jest 80%;
   - `contract`;
   - `smoke` e `txsigner`;
   - `e2e` no push para `main`;
   - `security`, com Gitleaks, CodeQL, OWASP DC e `pnpm audit`.

   O intent não acrescenta job novo nem muda os existentes. (recomendado)
B. Acrescentar jobs
X. Other (please specify)

[Answer]: A

## Q3. Mudanças nos workflows (só o responsável altera)

O que muda nos workflows, sabendo que só o responsável os altera?

A. Os textos prontos vão no `ci-config.md` para o responsável aplicar:
   1. no `load-test.yml`, o cenário `pnl-by-mode` da U3, que substitui o da U2 e fecha a meta U2-NFR1.9;
   2. no `ci.yml`, o passo `node --test "scripts/load/*.test.mjs"`.

   As pendências já registradas no `team.md` não mudam e continuam citadas: Node 24, CodeQL v4, Maven, Terraform e Checkov. Nenhum arquivo de `.github/workflows/` é editado por esta etapa. (recomendado)
B. Editar os workflows nesta etapa
X. Other (please specify)

[Answer]: A

## Q4. Repositório de artefatos

Que repositório de artefatos o intent usa?

A. Nenhum novo nesta etapa. As imagens com o SHA do commit no ECR fazem parte do `deploy.yml`, que ainda não existe e é pendência do 261113 com o responsável. (recomendado)
B. Definir agora
X. Other (please specify)

[Answer]: A

## Q5. Verificação da fronteira Construction → Operation

A rastreabilidade entre as Units está reprovada só por defeitos de registro. O que fazer com ela?

A. Corrigir os registros antes do veredito da fronteira:
   - **F-T1:** preencher o alvo das 14 linhas `target: null` do `traceability.json` da U3, com um arquivo existente ou com `N/A` e o motivo;
   - **F-T2:** trocar, no da U1, o caminho removido `RealBotPnlQueryV1.java` pelo arquivo que o substituiu;
   - **F-T3:** marcar como `OK` as linhas `Deferred` da U1 e da U3 que o Build and Test verificou, citando a evidência.

   Depois disso, rodar de novo o sensor e gravar `verification/phase-check-construction.md`. O que ainda ficar descoberto de verdade (AC3.11.1 e AC3.11.2, que dependem da carga e da devnet) fica registrado com o estágio dono. (recomendado)
B. Gravar a fronteira como reprovada e voltar às Units
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Ferramenta e branch:** o GitHub Actions e o tronco `main` continuam como estão [Q1].
- **Portões:** os jobs e o `security` continuam sem mudança, e o intent não acrescenta job [Q2].
- **Workflows:** os textos prontos do `load-test.yml` (`pnl-by-mode`) e do passo `node --test` do `ci.yml` vão para você aplicar; esta etapa não edita nenhum workflow [Q3].
- **Artefatos:** nada novo; o ECR fica para o `deploy.yml` [Q4].
- **Fronteira:** corrigir os três defeitos de registro da rastreabilidade (U3 sem alvo, caminho removido da U1, linhas pendentes já verificadas), rodar o sensor de novo e gravar o veredito da fronteira [Q5].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
