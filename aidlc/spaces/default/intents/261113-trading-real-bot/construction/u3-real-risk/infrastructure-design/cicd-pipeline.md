# Pipeline — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/nfr-requirements/tech-stack-decisions.md`, `construction/u2-real-market-data/infrastructure-design/cicd-pipeline.md`, `construction/u2-real-market-data/code-generation/code-summary.md` (texto da etapa do `promtool` para o responsável) e `infrastructure-design-questions.md` (Q3).

## CI

O `ci.yml` não muda nesta Unit [Q3].

- **Job `backend`:** os testes da U3 rodam no Maven `verify`, com piso de 80% e ArchUnit (regras novas e as que mudam). Isso inclui os testes de concorrência, de repetição e de privilégios do banco com Testcontainers, além do `RealRiskAlarmParityTest`.
- **`promtool`:** hoje o `ci.yml` não tem a etapa. O texto pronto está no code-summary da U2 e só o responsável do projeto altera o `ci.yml`. Até ele aplicar:
  - o arquivo `infra/observability/real-risk-alerts.yml` não é validado na CI;
  - a Code Generation da U3 valida o arquivo localmente, com a imagem `prom/prometheus` no Docker, como na U2, e registra o resultado no code-summary;
  - quando a etapa entrar, ela confere todos os arquivos da pasta, inclusive o da U3.
- **`verify.sh`:** não muda.

## Implantação

A ordem e o rollback estão em `infrastructure-specification.md`:
- **Ordem:** migração, worker, API.
- **Rollback:** primeiro desligar o REAL, depois voltar a imagem; as tabelas ficam.
