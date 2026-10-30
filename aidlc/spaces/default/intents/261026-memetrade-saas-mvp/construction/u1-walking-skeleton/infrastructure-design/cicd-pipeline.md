# Pipeline de CI/CD — U1 Esqueleto de ponta a ponta

Pipeline de entrega que a Unit `u1-walking-skeleton` cria para o projeto, a partir de [security-design.md](../nfr-design/security-design.md), [reliability-design.md](../nfr-design/reliability-design.md), [performance-design.md](../nfr-design/performance-design.md), da [infrastructure-specification.md](infrastructure-specification.md), das regras do `team.md` e do `project.md` e dos contratos de [contract-summary.md](../../../inception/contract-design/contract-summary.md). Decisões em [infrastructure-design-questions.md](infrastructure-design-questions.md) (Q3 a Q7 e Q9).

Plataforma: GitHub Actions num repositório privado. Desenvolvimento trunk-based: cada mudança chega a `main` por pedido de merge com squash, e nenhum merge acontece com a CI vermelha.

## Regras gerais dos workflows

- Ações de terceiros fixadas pelo SHA do commit, nunca por tag.
- Cada workflow declara `permissions` mínimas: `contents: read` em todos os jobs. Nenhum job precisa de escrita, porque o resultado do SAST fica como artefato da execução, e não no code scanning do GitHub (Q9).
- Gatilhos `pull_request` (nunca `pull_request_target`) e `push` em `main`; nenhum segredo disponível para pedidos vindos de fork.
- Dependências instaladas a partir dos lockfiles (`mvn` com versões fixadas, `pnpm install --frozen-lockfile`); caches do Maven, do pnpm e da base da NVD entre execuções.
- Versão do Java (21) e do Node LTS fixadas no repositório (por exemplo, `.nvmrc` e `packageManager`).
- Runners Linux; o fim de linha LF vem de `.gitattributes` (NFR13.2).

## Hook de pré-commit (local)

- O hook fica versionado em `.githooks/pre-commit` e roda `gitleaks git --pre-commit --staged`, bloqueando o commit que contenha segredo.
- O `scripts/setup.sh` liga o hook com `git config core.hooksPath .githooks`. Ele funciona no Git Bash do Windows e no Linux, e confere se a versão do Gitleaks instalada é a fixada no repositório (`.gitleaks-version`).
- No Windows, o Gitleaks é instalado por `winget` ou `scoop`; o `README` traz o comando com a versão fixada.
- O job `security` da CI roda o Gitleaks de novo, então um hook pulado localmente não deixa segredo chegar a `main`.

## Workflow `ci`: todo pedido de merge e todo push em `main`

Jobs em paralelo (Q3):

1. **backend**:
   - Spotless com palantir-java-format em modo de verificação.
   - Testes unitários e de integração: JUnit 5 e Testcontainers com PostgreSQL 17.
   - JaCoCo com regra `check` de 80% de linhas.
   - Regra de dependências que barra o SDK Solana fora do escopo de teste, e teste de arquitetura contra geração ou importação de chave (NFR6.4).
   - Teste que confere o limite de tentativas padrão igual a 10.
   - Teste que lê o `docker-compose.yml` e falha se ele ativar perfil, conta semeada ou limite elevado.
2. **frontend**:
   - ESLint e Prettier em modo de verificação.
   - `tsc` em modo `strict`.
   - Jest com limite de 80% de linhas.
3. **contract**:
   - Gera o OpenAPI a partir do backend e compara com o versionado.
   - Gera os tipos do frontend e compara.
   - Qualquer diferença falha o job.
4. **security**:
   - Gitleaks na árvore e nos commits do pedido.
   - Semgrep Community Edition com as regras gratuitas para Java e TypeScript, com saída SARIF guardada como artefato da execução por 30 dias (Q9).
   - OWASP Dependency-Check com a base em cache.
   - Bloqueio:
     - Bloqueia: Crítico ou Alto (no Semgrep, severidade `ERROR`; no Dependency-Check, CVSS 7 ou mais).
     - Avisa: Médio.
     - Exceções ficam num arquivo versionado com validade de até 90 dias; exceção vencida volta a bloquear.
5. **smoke**:
   - Constrói as imagens `api` e `web` marcadas com o SHA.
   - Gera um `.env` efêmero, com senha do banco e chave de assinatura aleatórias.
   - Roda `bash scripts/verify.sh`, que sobe o Compose com `docker-compose.test.yml` e o perfil `test`.
   - Faz a varredura dos logs (NFR7.3) e guarda os logs como artefato por 7 dias.
   - Descarta as imagens no fim (Q6).

Só no push em `main`, depois dos cinco jobs:

6. **e2e**: Playwright completo sobre o mesmo ambiente do smoke. Cobre login com a carteira de teste, lista com `unknown`, uso só por teclado, contraste, largura abaixo de 1280 px e CSP ligada.

## Workflow `load-test`: manual (Q4)

- Disparado à mão (`workflow_dispatch`) antes de fechar a U1 e quando uma mudança tocar login ou lista.
- Sobe o ambiente com os perfis `test,loadtest`; o limite de tentativas é elevado só nesse perfil.
- Roda 20 usuários simulados, com uma ação a cada 2 s, por 5 minutos, e lê o p95 de `http_server_requests_seconds`.
- Falha quando o p95 de desafio, verificação ou lista passa de 300 ms; o relatório fica anexado à execução.

## Atualização de dependências (Q7)

- Dependabot para Maven, pnpm, Docker (imagens base por digest) e GitHub Actions (SHAs).
- Versões novas de pacotes do frontend esperam 5 dias; atualizações de segurança são abertas na hora.
- Cada pedido do Dependabot passa pelo workflow `ci` completo, como qualquer outro.
- Execuções disparadas pelo Dependabot não recebem os segredos de Actions, só os segredos do Dependabot. Por isso o `NVD_API_KEY` também é cadastrado como segredo do Dependabot, e o job `security` funciona igual nesses pedidos (ver "Segredos em CI/CD").

## Portões por estágio

| Estágio | Portão | Bloqueia o merge |
|---|---|---|
| Pré-commit (local) | Gitleaks pelo hook versionado | Bloqueia o commit |
| backend | Formatação, testes, cobertura ≥ 80%, regra de dependências, teste do limite padrão, teste do Compose padrão | Sim |
| frontend | Lint, formatação, tipos, testes, cobertura ≥ 80% | Sim |
| contract | OpenAPI e tipos iguais aos versionados | Sim |
| security | Segredo encontrado; achado Crítico ou Alto do Semgrep ou do Dependency-Check sem exceção válida; base da NVD com mais de 7 dias | Sim (Médio só avisa) |
| smoke | `scripts/verify.sh` com código zero; varredura de logs limpa | Sim |
| e2e (`main`) | Playwright completo | Falha em `main` vira defeito a corrigir antes do próximo merge |
| load-test (manual) | p95 ≤ 300 ms | Bloqueia o fechamento da U1 |

### Como "nenhum merge com a CI vermelha" é garantido

- Com a proteção de branch disponível no plano do repositório: `main` exige os jobs `backend`, `frontend`, `contract`, `security` e `smoke` verdes, e merge por squash.
- Num repositório privado, a proteção de branch e os rulesets exigem plano pago do GitHub (por exemplo, Pro). A escolha do plano é a confirmação da Sprint 0 que o `team.md` deixa "a definir".
- Enquanto o plano não for confirmado, a regra é de processo: o responsável do projeto só faz o merge com os cinco jobs verdes. O push em `main` roda o `ci` de novo, e uma falha ali é tratada como defeito a corrigir antes do próximo merge.

## Consumo estimado de minutos

| Execução | Jobs | Minutos estimados |
|---|---|---|
| Pedido de merge | backend ~8, frontend ~4, contract ~4, security ~8 (Dependency-Check com cache), smoke ~8 | ~32 |
| Push em `main` | Os cinco jobs mais e2e ~10 | ~42 |
| `load-test` manual | Subida mais 5 minutos de carga | ~12 |

Cada mudança custa cerca de 75 minutos: o pedido de merge mais o push em `main`. A cota gratuita de 2.000 minutos por mês de um repositório privado cobre umas 25 mudanças por mês, sem contar as reexecuções e os pedidos do Dependabot.

A confirmação da cota é a outra pendência da Sprint 0 no `team.md`. Se a cota não bastar, há duas saídas: reduzir o smoke nos pedidos que mudam só documentação (filtro por caminho) ou trocar de plano.

## Segredos em CI/CD (Q5)

| Segredo | Onde | Exposição |
|---|---|---|
| `NVD_API_KEY` | Segredo de Actions do repositório e segredo do Dependabot, com o mesmo valor | Só no job `security`, em pedidos do próprio repositório, nos do Dependabot e em `main`; nunca em fork |
| Senha do banco e chave de assinatura do token | Geradas a cada execução nos jobs `smoke`, `e2e` e `load-test` | Efêmeras; nunca gravadas em artefato nem em log |

Quando a chave falta ou a NVD não responde, o Dependency-Check usa a base do cache:

- Se a base tiver até 7 dias, o job roda com ela e registra um aviso.
- Se for mais antiga, o job falha, para não aprovar dependências contra uma base vencida.

Nenhum segredo de produção existe hoje. Quando existir, ficará fora de workflows disparados por branch ou fork não confiável.

## Implantação, promoção e rollback

- Hoje não há ambiente para implantar: a entrega termina no merge em `main` com a CI verde e as imagens descartadas.
- Quando houver staging, a implantação acontece a cada merge em `main`, com as imagens publicadas e marcadas com o SHA. A produção do beta PAPER exige aprovação manual separada do responsável do projeto, e as releases são marcadas por tags em `main`.
- Rollback local: voltar ao commit anterior e rodar `scripts/verify.sh` de novo.
- As migrações seguem expand-contract e nunca removem colunas na mesma implantação da mudança de aplicação. Assim a versão anterior continua funcionando sobre o banco migrado.
- Uma implantação só termina quando o smoke passa.
- Feature flags não são usadas na U1, e REAL não existe no código (FR15.2).
