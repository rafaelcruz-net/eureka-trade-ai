# Perguntas — Infrastructure Design da U1 (esqueleto de ponta a ponta)

Objetivo: definir a infraestrutura local, a CI e o caminho de entrega da U1, a partir do desenho não funcional ([logical-components.md](../nfr-design/logical-components.md), [security-design.md](../nfr-design/security-design.md), [reliability-design.md](../nfr-design/reliability-design.md), [observability-design.md](../nfr-design/observability-design.md), [performance-design.md](../nfr-design/performance-design.md), [scalability-design.md](../nfr-design/scalability-design.md)), do [functional-spec.md](../functional-design/functional-spec.md) e dos contratos de [contract-summary.md](../../../inception/contract-design/contract-summary.md).

Já decidido e que não volta a ser perguntado: não há conta de nuvem, região nem orçamento (C-10, C-12); o único ambiente é o local, no Windows, com Docker Compose; CI no GitHub Actions com ações fixadas por SHA e `permissions` mínimas; Gitleaks, CodeQL e OWASP Dependency-Check; métricas numa porta de gestão sem coletor no Compose; `scripts/verify.sh` como comando de verificação; AWS só no futuro (Sprint 7).

## Q1. [Plataforma] Como as imagens da API e do app do convidado são construídas?

A. Dockerfiles próprios em vários estágios: a API numa imagem com só o runtime Java 21 (JRE) e o app no modo `standalone` do Next.js sobre Node LTS enxuto; ambos com usuário sem privilégios e imagem base fixada por digest.
B. Ferramentas do próprio ecossistema: `spring-boot:build-image` (buildpacks) para a API e um Dockerfile simples para o app.
X. Other (please specify)

[Answer]: A

## Q2. [Plataforma] Como o Docker Compose atende ao desenvolvimento e à verificação?

A. Um único arquivo com as imagens de produção, usado pelo script de verificação e pelo smoke; no dia a dia, um perfil sobe só o PostgreSQL e a API e o app rodam fora do contêiner, com recarga rápida.
B. Dois arquivos: um de desenvolvimento com o código montado e recarga dentro do contêiner, e outro com as imagens de produção para a verificação.
X. Other (please specify)

[Answer]: A

## Q3. [CI] O que roda em cada pedido de merge e no merge para `main`?

O `team.md` pede o smoke em toda mudança e a suíte completa no merge para `main`, com nenhum merge com a CI vermelha.

A. Em todo pedido de merge, jobs em paralelo: backend (formatação, testes unitários e de integração, cobertura de 80%), frontend (lint, tipos, testes, cobertura de 80%), contrato (OpenAPI gerado contra o versionado e tipos do frontend), segurança (Gitleaks, CodeQL, Dependency-Check) e smoke com `scripts/verify.sh`. No merge para `main`, tudo isso mais o ponta a ponta completo no Playwright.
B. Em todo pedido de merge, só formatação, lint, tipos, segurança e o smoke; testes unitários, de integração e cobertura só no merge para `main`.
X. Other (please specify)

[Answer]: A

## Q4. [Desempenho] Onde roda o teste de carga leve de NFR3.1?

O teste sobe o ambiente com o perfil de carga e mede o p95 por 5 minutos.

A. Num workflow manual, disparado antes de fechar a U1 e quando uma mudança tocar login ou lista; o resultado fica anexado à execução.
B. A cada merge para `main`, como parte da suíte completa.
C. Só na máquina local, com instruções no repositório.
X. Other (please specify)

[Answer]: A

## Q5. [Segurança] Quais segredos a CI usa e de onde?

O OWASP Dependency-Check baixa a base de vulnerabilidades com uma chave de API gratuita da NVD; sem chave, a atualização é lenta e falha com frequência.

A. Só a chave da NVD, num segredo do repositório no GitHub, disponível só nos workflows de `main` e de pedidos de merge do próprio repositório (nunca de fork); a base fica em cache entre execuções. Os testes geram segredos efêmeros (chave de assinatura do token, senha do banco) a cada execução.
B. Nenhum segredo: o Dependency-Check roda sem chave, aceitando a lentidão.
X. Other (please specify)

[Answer]: A

## Q6. [Entrega] O que a CI faz com as imagens enquanto não existe ambiente para implantar?

A. Constrói as imagens, marca com o SHA do commit, usa no smoke e descarta; nada é publicado até existir um ambiente.
B. Publica as imagens no registro de contêineres do GitHub a cada merge para `main`, marcadas com o SHA.
X. Other (please specify)

[Answer]: A

## Q7. [Entrega] Qual ferramenta abre os pedidos de atualização de dependências?

O `team.md` pede atualizações automáticas e alguns dias de espera para versões novas do frontend, salvo correções de segurança.

A. Dependabot (nativo do GitHub) para Maven, pnpm, Docker e GitHub Actions, com espera de 5 dias para versões novas do frontend e atualizações de segurança imediatas.
B. Renovate com idade mínima de versão de 5 dias no frontend.
X. Other (please specify)

[Answer]: A

## Q8. [Dados] Qual versão do PostgreSQL e como ele roda no Compose?

A. PostgreSQL 17 na imagem oficial fixada por digest, volume nomeado, porta publicada só em `127.0.0.1`; a mesma versão nos testes de integração (Testcontainers).
B. PostgreSQL 16, mesma configuração.
X. Other (please specify)

[Answer]: A

## Q9. [Segurança] Qual ferramenta de SAST substitui o CodeQL num repositório privado sem licença paga?

Seguimento da revisão desta etapa. O CodeQL só é gratuito para código aberto: num repositório privado, ele exige a licença paga do GitHub (Code Security). O `team.md` pede SAST com ferramentas gratuitas, e a Q3 do NFR Requirements escolheu o CodeQL.

A. Semgrep Community Edition com as regras gratuitas para Java e TypeScript, rodando na CI; o resultado fica como artefato SARIF da execução, e o job falha em achado de severidade alta (equivalente a Crítico ou Alto), com o mesmo arquivo de exceções.
B. Manter o CodeQL e contratar o GitHub Code Security para o repositório privado.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Imagens por Dockerfiles próprios em vários estágios: API só com JRE 21 e app no modo `standalone` do Next.js sobre Node LTS enxuto, com usuário sem privilégios e base fixada por digest (Q1).
- Um único Docker Compose com as imagens de produção para a verificação e o smoke; no dia a dia, um perfil sobe só o PostgreSQL e a API e o app rodam fora do contêiner (Q2).
- CI em todo pedido de merge: backend, frontend (com cobertura de 80%), contrato, segurança e smoke em paralelo; no merge para `main`, tudo isso mais o ponta a ponta completo no Playwright (Q3).
- Teste de carga de NFR3.1 num workflow manual, disparado antes de fechar a U1 e quando uma mudança tocar login ou lista (Q4).
- Único segredo da CI: a chave da NVD, nunca exposta a forks, com a base em cache; os testes geram segredos efêmeros a cada execução (Q5).
- Imagens construídas, marcadas com o SHA, usadas no smoke e descartadas; nada é publicado até existir um ambiente (Q6).
- Dependabot para Maven, pnpm, Docker e GitHub Actions, com espera de 5 dias no frontend e atualizações de segurança imediatas (Q7).
- PostgreSQL 17 na imagem oficial fixada por digest, volume nomeado, porta só em `127.0.0.1`, mesma versão no Testcontainers (Q8).
- SAST com Semgrep Community Edition e regras gratuitas para Java e TypeScript, no lugar do CodeQL, que exige licença paga em repositório privado; SARIF como artefato da execução e falha em severidade alta, com o mesmo arquivo de exceções (Q9).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
