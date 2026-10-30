# Evidências — Descoberta de práticas

## Contexto

- **Tipo de projeto**: greenfield (`aidlc-state.md`, Project Type Source: workspace scan).
- **Escopo**: `enterprise`, Test Strategy `Comprehensive`, Depth `Comprehensive`.
- **Re-execução**: não. Todas as seções de `aidlc/spaces/default/memory/team.md`
  estavam vazias (só comentários de modelo).
- **Equipe**: um desenvolvedor solo com apoio de IA, sem orçamento (C-12).
- **Fonte das sugestões iniciais**: `aidlc/spaces/default/memory/org.md`
  (Way of Working, Walking Skeleton, Testing Posture, Deployment, Code Style),
  tratadas como padrões sugeridos; as decisões finais vêm da entrevista.

## O que cada participante inspecionou ou inferiu

### Líder (pipeline e implantação)

| Fonte | O que mostrou | Uso |
|---|---|---|
| Repositório git | Branch `master`, nenhum commit, nenhum remoto | Pergunta sobre o nome do tronco (Q1) e a hospedagem (Q2) |
| Arquivos de configuração | Nenhum `.github/`, `package.json`, `pom.xml`, `build.gradle`, `docker-compose*.yml`, `.prettierrc` ou `.editorconfig` | Nenhuma convenção de CI, build, estilo ou deploy pôde ser observada |
| `.claude/scopes/aidlc-enterprise.md` | `skeleton: on` | Pergunta sobre a fatia fina (Q6) |
| `org.md` | Trunk-based com squash para `main`; test-after; 80% de cobertura e CI antes do merge; staging a cada merge e aprovação manual para produção; seguir linter/formatador | Sugestões das cinco seções |
| `project.md` | Correções da Ideation, inclusive acessibilidade básica do beta | Referência na seção de acessibilidade |
| PRD v1.3 §4, §5, §7, §9–§17, §19 | Stack candidata, Docker Compose no Windows, monorepo, AWS futura, gates G0–G4, regras de domínio | Fatia do esqueleto, testes por risco, regras candidatas |
| `ideation/feasibility/constraint-register.md` | C-01 a C-16 | Situação de deploy e origem das regras |
| `ideation/approval-handoff/initiative-brief.md` | Projeto solo; REAL, cobrança pública e on-chain bloqueados | Voz da equipe e bloqueios de deploy |

Inferências do líder (não observadas): revisão feita pelos gates do AI-DLC e
pela CI por falta de segundo desenvolvedor; aprovação de produção recaindo sobre
a mesma pessoa; staging inexistente até haver infraestrutura.

### Engenheiro de qualidade (`contributions/aidlc-quality-agent.md`)

Inspecionou o rascunho, `org.md`, `project.md`, o PRD e o documento de escopo.
Propôs: piso de cobertura verificável (por parte, exclusões na configuração,
falha pela ferramenta); `custom` como opção concreta de metodologia; lista de
testes por risco completa (pausa, ledger sem edição, P&L determinístico, REAL
desligado); determinismo dos testes; contrato OpenAPI; portão de acessibilidade;
divergência Windows/Linux na CI e economia de cota; formato do comando de
verificação do esqueleto; nenhum portão de desempenho sem meta numérica.

### Desenvolvedor (`contributions/aidlc-developer-agent.md`)

Inspecionou o rascunho e o PRD §10–§14. Propôs: fronteiras de módulos e camadas
do monólito modular; idioma e convenções de nomes; tratamento de erros RFC 7807
e ausência de falha silenciosa; `unknown` como tipo explícito; tenant vindo do
contexto de autenticação; layout do monorepo; regras de precisão numérica e UTC;
Checkstyle restrito ao que o formatador não cobre; TypeScript `strict`.

### Engenheiro de segurança (`contributions/aidlc-devsecops-agent.md`)

Inspecionou o rascunho, o PRD §7/§12/§15/§16, o registro de restrições e a árvore
de trabalho (arquivos não rastreados como `PromptInicial.txt` e `.claude/`).
Propôs controles em camadas por marco: proteção de segredos antes do primeiro
commit; segurança e integridade da CI; espera na adoção de pacotes do frontend
(citando ataques à cadeia de suprimentos do ecossistema Solana, fato a confirmar
com fonte no estágio de segurança); testes de IDOR, tarefas assíncronas e
antirreplay do login; revisão independente para G2/G3; seis regras candidatas.

## Decisões da entrevista

| Pergunta | Decisão do responsável do projeto |
|---|---|
| Q1 Nome do tronco | A. Renomear `master` para `main` antes do primeiro commit |
| Q2 Remoto e CI | A. GitHub privado com GitHub Actions; confirmar cota gratuita na Sprint 0 |
| Q3 Ambientes e aprovação | A. Só local por agora; staging a cada merge quando existir; o responsável aprova produção do beta PAPER; G2/G3 de REAL exigem revisor independente a nomear |
| Q4 Segredos | A. Proteção antes do primeiro commit (`.env` ignorado, `.env.example`, verificação no commit, varredura inicial) |
| Q5 Segurança na CI | A. Bloquear Crítico/Alto, avisar Médio; exceções versionadas de até 90 dias aprovadas pelo responsável; espera na adoção de versões novas do frontend, salvo correções |
| Q6 Fatia fina | A. Sim: navegador → tela → API → banco com dados simulados no scanner; verificação por script único de ponta a ponta |
| Q7 Idioma e contrato | A. Código, tabelas, endpoints e logs em inglês; erros RFC 7807; tipos do frontend gerados do contrato |
| Q8 Fronteiras de módulos | B. Só convenção por enquanto, sem verificação automática |
| Q9 Momento das ferramentas | B. Escolher agora (Q15 e Q16) |
| Q10 Ordem de testes | A. TDD no núcleo financeiro e test-after no restante (`custom`) |
| Q11 Cobertura | A. 80% separadamente no backend e no frontend, exclusões na configuração, build falha abaixo do piso |
| Q12 Testes por risco | B. Todos obrigatórios, sem portão automático de acessibilidade por enquanto |
| Q13 Onde e quando testar | A. CI em Linux, local no Windows, LF padronizado; smoke em toda mudança e suíte completa no merge; teste instável é defeito |
| Q14 Regras rígidas | A. Confirmar todas as candidatas |
| Q15 Build do backend | B. Maven |
| Q16 Pacotes do frontend | B. pnpm (Node LTS fixado) |

O resumo consolidado foi confirmado pelo responsável do projeto ("Looks correct").

## Objeções mantidas e como foram resolvidas

- **Qualidade — "sem regressão" atribuído ao escopo `enterprise`**: removido. O
  `org.md` não traz essa regra e a entrevista não a escolheu (Q11 A).
- **Qualidade — piso de cobertura sem escopo, exclusões nem aplicação**:
  resolvido por Q11 A.
- **Qualidade — acessibilidade automatizada**: o responsável decidiu não ter
  portão automático por enquanto (Q12 B); os critérios básicos de `project.md`
  continuam valendo.
- **Qualidade — lista de testes por risco incompleta**: resolvido por Q12, que
  inclui pausa, ledger sem edição, P&L determinístico e REAL desligado.
- **Qualidade — divergência Windows/Linux**: resolvido por Q13 A.
- **Qualidade — oferecer `custom`**: oferecido e escolhido (Q10 A).
- **Desenvolvedor — fronteiras de módulos e tratamento de erros**: resolvido por
  Q7 A e Q8 B (convenção, sem verificação automática).
- **Desenvolvedor — regra de precisão numérica**: confirmada como regra rígida
  (Q14 A).
- **Desenvolvedor — "CI só nos passos afetados"**: retirado; não foi escolhido.
- **Desenvolvedor — Spotless e Checkstyle sem papéis delimitados**: Checkstyle,
  se adotado, cobre só o que o formatador não cobre.
- **Segurança — segredos antes do primeiro commit**: resolvido por Q4 A.
- **Segurança — CI sem SAST, dependências e Actions por SHA**: resolvido por Q5 A
  e Q14 A.
- **Segurança — autoaprovação para REAL**: resolvido por Q3 A (G2/G3 exigem
  revisor independente).

## Incertezas restantes

- **Cota gratuita do GitHub Actions** para repositório privado: confirmar na
  Sprint 0 e verificar se cobre smoke em toda mudança e suíte completa no merge.
- **Staging**: quando e onde vai existir depende de conta de nuvem e orçamento
  (C-10, C-12).
- **Revisor independente de G2/G3**: ainda não nomeado (C-14).
- **Comando exato de verificação do esqueleto**: será autorizado na
  Construction.
- **Experiência com a stack** ainda não mapeada (C-11, Sprint 0); ferramentas
  além de Maven e pnpm são exemplos e podem mudar.
- **Não perguntado nesta etapa** (fica para o design ou estágios seguintes):
  convenção de nomes de arquivo no frontend, Conventional Commits, forma dos
  workers, `camelCase` ou `snake_case` no JSON, ferramenta de migração, máscara
  de endereços de carteira em logs (LGPD, C-07), ferramenta de atualização de
  dependências e momento do DAST, da varredura de imagens e do SBOM.
- **Acessibilidade e desempenho**: sem portão automático por enquanto; a
  verificação completa de WCAG 2.1 AA é exigida antes da abertura ao público e
  as metas de desempenho dependem dos requisitos não funcionais.
