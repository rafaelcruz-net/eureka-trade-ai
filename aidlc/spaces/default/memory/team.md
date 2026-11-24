# Team-Level Rules

> This team's affirmed practices and corrections. Loaded after `org.md` as
> strict-additive guidance; contradictions with broader policy are rejected.
> Populated by the practices-discovery affirmation gate. Edit at the gate,
> not directly.

## Way of Working

- **Estratégia de branches**: desenvolvimento baseado em tronco (trunk-based).
  O tronco é `main`: base `main`, destino `main`. As mudanças entram direto em
  `main`, em um commit que o responsável do projeto pede. Não criamos uma
  worktree nem uma branch por Bolt. **Squash-merge** em `main` só acontece
  quando existe uma branch, ou seja, quando o responsável pediu uma. O histórico
  de `main` fica linear. `main` não tem proteção de branch.
- **CI vermelha em `main`**: como os commits entram direto, a CI roda depois do
  push e `main` pode ficar vermelha por pouco tempo. A correção vem no commit
  seguinte, antes de qualquer outra mudança. Os pontos de controle do AI-DLC e as
  implantações só usam um commit com a CI verde [Q2].
- **Nome do tronco**: renomeamos `master` para `main` antes do primeiro commit.
  Não existe ramo `master` depois disso.
- **Commits fora de Bolts**: um commit pedido fora de um Bolt vai para a branch
  atual. Só criamos branch quando pedimos.
- **Mensagens de commit**: em inglês, com prefixo do tipo (`feat:`, `fix:`,
  `test:`, `chore:`, `ci:`, `docs:`), citando a Unit ou o intent quando houver
  [Q6].
- **Cobertura da CI**: o workflow `ci` roda em todo PR, em todo push para `main`
  e à mão (`workflow_dispatch`) [Q5]:
  - `backend`: `docker compose config` das composições, `mvnw verify` da API
    (Spotless, testes de unidade e de integração, JaCoCo com piso de 80%,
    enforcer, ArchUnit) e `promtool check rules` nas regras de alerta de
    `infra/observability/`;
  - `frontend` e `admin`: lint, formatação, verificação de tipos e Jest com o
    piso de 80% de `apps/web` e `apps/admin`; o `admin` roda também `check:api`;
  - `contract`: testes de contrato do OpenAPI e `check:api` do `apps/web`;
  - `smoke`: `bash scripts/verify.sh`;
  - `txsigner`: `mvnw verify` do `apps/txsigner` (testes, JaCoCo 80%, Spotless);
  - `e2e`: só no push para `main`, depois dos seis jobs acima, roda o
    `scripts/verify.sh` de novo.
  O workflow `load-test` é só manual. Só o responsável do projeto altera os
  arquivos de `.github/workflows/`.
- **Pendências da CI, com o responsável** [Q5]: melhorias decididas e ainda não
  aplicadas: as ações em Node 24 e o CodeQL v4 (texto pronto no `ci-config.md`
  do intent `261113-trading-real-bot`), a proteção do download do Maven,
  `terraform fmt`, `validate` e `test` e o Checkov na CI. O `WorkflowsTest`
  precisa aceitar `id-token: write` só no `deploy.yml` antes que esse workflow
  entre.
- **Revisão**: como trabalhamos sozinhos, não há revisão por outra pessoa no dia
  a dia. Cada mudança é revisada pelo responsável do projeto nos gates de
  aprovação do AI-DLC e pelos portões automáticos da CI.
- **Hospedagem**: repositório **privado** no GitHub
  (`rafaelcruz-net/eureka-trade-ai`) desde 2026-11-21, com CI no GitHub Actions.
  Os minutos do Actions contam na cota do plano; se a cota apertar, a frequência
  dos jobs é revista com dados de consumo [Q1].
- **Repositório**: um único repositório (monorepo). Layout: `apps/web/`
  (Next.js do usuário), `apps/admin/` (Next.js da administração), `apps/api/`
  (Spring Boot, módulos por pacote), `apps/txsigner/` (serviço de assinatura
  isolado), `infra/` (composições Docker de teste e do REAL,
  `infra/observability/` com as regras de alerta, `infra/terraform/` com a IaC
  da AWS), `docs/` (ADRs, anexos de contrato, privacidade, `docs/runbooks/`,
  desempenho), `scripts/` (`verify.sh`, `verify-checkpoint.sh`, carga),
  `security/exceptions.yml`, com `.editorconfig`, `.gitattributes` e
  `docker-compose.yml` na raiz [Q6].
- **Ferramentas de build**: Maven (wrapper) no backend e no TxSigner; pnpm nos
  dois apps web, com o Node 24 LTS fixado em `.nvmrc` e em `engines`. Lockfiles
  são versionados e a CI instala a partir deles.
- **Proteção de segredos**: nenhum segredo entra no repositório. Variáveis ficam
  em `.env` local, ignorado pelo git; o repositório guarda só um `.env.example`
  sem valores. Estado e planos do Terraform e arquivos `*.tfvars` nunca entram no
  repositório. A proteção de segredos é o Gitleaks, no hook
  `.githooks/pre-commit` e no workflow `security`; a varredura de segredos do
  GitHub não existe para um repositório privado pessoal sem GitHub Advanced
  Security [Q1]. Se um segredo vazar, primeiro revogamos e trocamos o segredo,
  depois limpamos o histórico.
- **Segurança na CI**: o workflow `security` roda Gitleaks (histórico e árvore),
  CodeQL (Java e TypeScript), OWASP Dependency-Check e `pnpm audit` nos dois
  apps. Achado Crítico ou Alto bloqueia; achado Médio gera aviso. Exceções ficam
  em `security/exceptions.yml`, cada uma com justificativa, validade de no máximo
  90 dias e aprovação registrada do responsável; exceção vencida volta a
  bloquear.
- **Integridade da CI**: ações de terceiros são fixadas pelo SHA do commit, não
  por tag (o `WorkflowsTest` confere); cada workflow declara `permissions`
  mínimas (por padrão, só leitura do conteúdo); a única exceção prevista é
  `id-token: write` no job de implantação do `deploy.yml`, que roda num GitHub
  Environment. Nenhum segredo de produção fica disponível em workflows
  disparados por branch ou fork não confiável.
- **Atualização de dependências**: o Dependabot abre PRs semanais (Maven, npm dos
  dois apps, Docker, Docker Compose e GitHub Actions). Versões novas dos pacotes
  npm esperam 5 dias. Os alertas do Dependabot e as correções automáticas de
  segurança estão ligados, e as correções de segurança entram sem a espera [Q1].
- **Versões e releases**: um único tronco, sem branches de release de longa
  duração. As tags anotadas `vAAAA.MM.DD-N` em `main` marcam versões locais
  (por exemplo, `v2026.11.12-1`) [Q6].

## Walking Skeleton

- **Postura**: construímos primeiro uma fatia fina de ponta a ponta. A primeira
  Unit é a menor fatia integrada que funciona de verdade e passa por todos os
  estágios por Unit, inclusive geração de código, antes das demais.
- **Fatia do produto**: navegador → tela web (Next.js) → API (Spring Boot) → banco
  de dados (PostgreSQL), tudo subindo localmente com Docker Compose. Um adaptador
  de dados de mercado simulado alimenta uma lista mínima do scanner que exibe
  fonte, horário e `unknown` para campos ausentes. A fatia não chama fornecedor
  real (C-06) nem usa carteira real.
- **Verificação**: um único script versionado no repositório sobe o ambiente,
  espera os serviços ficarem saudáveis, roda os specs de ponta a ponta que
  atravessam a fatia inteira e devolve código de saída diferente de zero em
  qualquer falha. Esse comando é `bash scripts/verify.sh`, autorizado pelo
  responsável do projeto, e ele roda na CI (jobs `smoke` e `e2e`).
- **Verificação dos pontos de controle**: `scripts/verify-checkpoint.sh` roda
  primeiro o `verify.sh` local. Se a máquina não aguentar (a execução local
  morre por falta de memória), a prova é o run verde do `ci` do mesmo commit,
  desde que a árvore não tenha mudança fora dos registros do AI-DLC e o commit
  esteja no remoto [Q7].
- **Fatia do intent `261109-termos-lgpd`**: a página `/privacidade` servida pela
  API a partir do catálogo, com a versão vigente, e mostrada no app sem login,
  coberta por um spec de ponta a ponta. A verificação continua sendo
  `bash scripts/verify.sh`.
- **Fatia do intent `261113-trading-real-bot`**: a carteira do bot no validador
  Solana local (Unit U1 `u1-bot-wallet`, Bolt B1): navegador (tela mínima) → API →
  BotWallet → TxSigner (processo próprio, KMS simulado no ambiente local) →
  ChainWatcher → validador local → ledger REAL. O spec `e2e/real-bot-wallet`
  ativa a carteira do bot, deposita 1 SOL a partir da carteira principal de
  teste, confere o saldo, saca de volta e confirma que um saque para outro
  endereço é recusado. A carteira principal de teste é gerada só no código de
  teste. O spec roda em toda mudança e no push para `main`.
- **Fatia do intent `261121-paper-real-unico`**: a visão única com um bot PAPER e
  um bot REAL no validador Solana local: navegador (telas de Bots e de P&L) → API
  (P&L por bot nos dois modos) → ledger PAPER e ledger REAL → blocos separados
  Simulado (US$) e Real (SOL), sem total geral, com o aviso fixo no simulado. Um
  spec de ponta a ponta novo, que o responsável acrescenta ao `verify.sh`, cria
  os dois bots, faz uma compra em cada modo e confere que cada bloco mostra só o
  próprio modo e que os modos nunca são somados [Q8]. A verificação continua
  sendo `bash scripts/verify.sh`.
- **Depois do esqueleto**: com o esqueleto verificado e aprovado, o framework
  oferece seguir automaticamente ou revisar cada ponto de controle. Plan
  Approval, confirmação de resumo, escolha do comando de verificação e falhas
  continuam exigindo a pessoa.

## Testing Posture

- **Methodology**: custom
- **Ordering**: No núcleo financeiro (limites de risco, ledger, P&L e deduplicação), na lógica de dados pessoais (anonimização, exclusão, novo aceite dos termos e expurgos) e na lógica da carteira do bot, do saque e da política de assinatura do TxSigner (inclusive os estados e a idempotência das ordens REAL) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.
- **Methodology evidence**: decisão do responsável do projeto na entrevista
  (Q10, opção A). TDD no núcleo financeiro porque código gerado com IA tende a
  passar em testes escritos depois sobre o próprio código; com o teste escrito
  antes, ele valida o requisito e não a implementação. Test-after no restante
  (UI, adaptadores, configuração) mantém o ritmo de um desenvolvedor solo. No
  intent `261109-termos-lgpd` (Q5, opção A), a ordem de testes antes do código
  foi estendida à lógica de dados pessoais. No intent `261113-trading-real-bot`,
  o NFR8 estendeu a ordem à lógica de carteira e saque, e o plano de Bolts pediu
  os testes de ataque da política do TxSigner (B1) e os de estados e
  idempotência da execução REAL (B4) antes do código. No intent
  `261121-paper-real-unico`, o P&L REAL por bot (custo médio por bot e rateio
  das vendas sem bot) já está dentro de "P&L" no núcleo financeiro, e os
  cenários são escritos antes do código (CT-15 do registro de restrições).
- **Volume de testes**: o volume do projeto continua `Comprehensive`; cada intent
  segue a Test Strategy que o próprio estado registra (o intent
  `261121-paper-real-unico` registra `Standard`) [Q8]. O piso de cobertura
  abaixo é somado à estratégia e nunca a substitui.
- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no
  backend (JaCoCo, unitários e integração mesclados), no TxSigner (JaCoCo) e em
  cada app web (Jest, `coverageThreshold.global.lines = 80`). As exclusões ficam
  listadas na configuração da ferramenta, nunca decididas na hora. A ferramenta
  falha o build abaixo do piso. O piso não pode ser reduzido para fazer uma etapa
  passar.
- **Testes obrigatórios por risco**: toda mudança que toque essas áreas inclui ou
  mantém verdes os testes de:
  - isolamento entre contas (tenants), inclusive acesso a recurso de outra conta
    por ID (devolve 403 ou 404 e nunca o dado) e tarefas em segundo plano que
    também validam o tenant;
  - separação entre REAL e PAPER (saldo, ordens, ledger, posições e P&L),
    inclusive na visão única: cada bloco e cada total por modo mostram só o
    próprio modo, e os modos nunca são somados [Q8];
  - deduplicação por chave de idempotência;
  - violação de limites autorizados e controles de risco;
  - dado de mercado ausente vira `unknown`, nunca zero;
  - pausa do bot impede novas operações;
  - ledger sem edição: UPDATE ou DELETE em lançamento falha e a correção só
    acontece por lançamento compensatório;
  - P&L com cenários determinísticos de resultado conhecido, em aritmética
    decimal e unidades de token;
  - REAL desligado por padrão: com a configuração padrão (API, worker e
    TxSigner), nenhuma carteira do bot é criada, nenhum caminho assina ou envia
    transação e nenhuma assinatura é pedida ao KMS;
  - login sem reutilização de assinatura: nonce reutilizado ou assinatura
    expirada são rejeitados, e a assinatura de login não serve para autorizar
    trading;
  - a anonimização não deixa o endereço da carteira em nenhuma tabela nem log
    consultável;
  - a versão nova dos termos exige novo aceite antes de qualquer outra rota,
    **exceto** a leitura da carteira do bot (`GET /api/v1/real/bot-wallet`) e as
    rotas de saque (`/api/v1/real/bot-wallet/withdrawals` e
    `/api/v1/real/bot-wallet/withdrawals/{withdrawalId}`), que continuam
    disponíveis sem o novo aceite para o usuário sempre poder tirar o próprio
    dinheiro; o teste cobre as duas metades: as rotas da exceção respondem sem o
    novo aceite e todas as demais respondem `403 TERMS_REACCEPTANCE_REQUIRED`;
  - saque só para a carteira principal registrada: qualquer outro destino é
    recusado pela API e pelo TxSigner, e o saque continua disponível com o kill
    switch acionado, com o REAL desligado e sem a liberação vigente;
  - a política de conteúdo do TxSigner (ADR-004) recusa toda transação fora da
    lista: programa fora da lista, assinante ou pagador diferente da carteira do
    bot, destino fora da carteira do bot ou da carteira principal, saída mínima
    abaixo da cotação menos a derrapagem, troca de autoridade, fechamento de
    conta para terceiros e valor acima do teto autorizado; cada recusa registra o
    motivo;
  - uma única transação por ordem REAL: repetir a ordem com a mesma
    `Idempotency-Key`, reprocessar a decisão da fila ou repetir depois de uma
    ordem ambígua nunca gera segunda transação.
- **Defeitos**: todo defeito corrigido ganha um teste de regressão que o
  reproduz.
- **Acessibilidade**: sem portão automático de acessibilidade na CI por enquanto.
  Os critérios básicos de acessibilidade do beta definidos em `project.md`
  continuam valendo.
- **Determinismo**: relógio injetável (sem `now()` direto no domínio), seeds
  fixas e nenhum acesso de rede real nos testes; adaptadores de mercado usam
  fixtures gravadas. Testes de integração usam as mesmas migrações da aplicação
  sobre PostgreSQL real em contêiner (Testcontainers) e não dependem de estado
  compartilhado nem da ordem de execução. Fixtures nunca usam carteira, mint ou
  dados pessoais reais. No REAL, a rede é o validador Solana local (com contas
  clonadas para os swaps), o KMS é simulado no ambiente local e as respostas da
  Jupiter e do RPC usadas em testes de unidade são gravadas; nenhum teste da CI
  usa devnet, mainnet, KMS real ou chave real.
- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no
  Windows. O fim de linha é padronizado em LF por `.gitattributes` e
  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.
- **Frequência**: em toda mudança e no push para `main` rodam os testes de
  unidade e de integração de todas as partes (backend, TxSigner, `apps/web` e
  `apps/admin`), com o piso de cobertura, e mais o `scripts/verify.sh`. O
  `verify.sh` com todos os specs de ponta a ponta é a suíte completa; no push
  para `main` o job `e2e` roda a mesma suíte uma segunda vez, depois dos demais
  jobs verdes.
- **Testes da IaC**: os testes do Terraform (`infra/terraform/tests/`), o
  `terraform validate` e o Checkov existem e rodam à mão; levá-los para a CI é
  pendência do responsável [Q5].
- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado
  em silêncio.
- **Ferramentas**: JUnit 5, Testcontainers, ArchUnit e JaCoCo no backend e no
  TxSigner; Jest com Testing Library nos dois apps; Playwright para ponta a ponta;
  `node:test` nos cenários de carga.
- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não
  funcionais definam metas numéricas; o workflow `load-test` é manual.

## Guard Policy

<!-- Affirmed by the team. Mode: strict, relaxed, or off. Strict here holds for every intent, whatever one person asks in chat; relaxed or off here applies to every intent whose policy came from its scope, unless project.md or the person sets another; changing this line changes it. A section under the retired Change Control heading, written by an earlier release, is still read. -->

## Deployment

- **Situação atual**: nenhum ambiente na nuvem está provisionado; o único
  ambiente que roda é o local, no Windows, com Docker Compose. O desenho abaixo
  vem dos estágios de operação do intent `261113-trading-real-bot` e é a nossa
  prática de implantação, **ainda não aplicada** [Q3].
- **Contas e IaC**: Terraform em `infra/terraform/`; um AWS Organizations com a
  conta de gerenciamento e duas contas membro separadas, `devnet` e `mainnet`,
  sem conexão entre elas; orçamento de US$ 350 por conta. A região ainda está a
  definir.
- **Ambiente dos usuários**: os usuários usam só a **mainnet**. Nela o usuário
  cria bots PAPER e REAL lado a lado, com P&L por bot e por modo sempre
  separados. A **devnet** serve aos testes do fundador e à pré-produção do mesmo
  SHA; nenhum usuário a usa [Q4].
- **Fluxo de implantação**: implantação manual de um commit de `main` com `ci` e
  `security` verdes, pelo `deploy.yml` (`workflow_dispatch`) nos GitHub
  Environments `devnet` e `mainnet`, com credencial AWS por OIDC e sem chave
  longa. A devnet recebe implantação manual, não automática a cada merge; não
  existe staging separado. O mesmo SHA passa primeiro pela devnet e só depois vai
  para a mainnet [Q3].
- **Passo do fundador com MFA**: antes de cada implantação, o fundador, da
  máquina dele e com MFA, publica a imagem do TxSigner, roda a migração do
  TxSigner, troca a revisão em serviço e confere a prontidão. O papel de
  implantação do GitHub nunca implanta o TxSigner, nunca toca no KMS e nunca liga
  o REAL.
- **Produção**: a mainnet é a produção do PAPER e do REAL. A implantação nela
  exige a aprovação do fundador como revisor obrigatório do Environment
  `mainnet` [Q4].
- **Estratégia**: atualização rolling no ECS (tarefa nova saudável antes de a
  antiga sair), circuit breaker com volta automática, TxSigner primeiro, smoke e
  janela de observação de 10 minutos.
- **Gates de REAL (G2/G3)**: nesta fase, o fundador (responsável do projeto) é o
  revisor único dos gates do REAL. O G2 é cumprido pelo modelo de ameaças da
  carteira do bot do ADR-005. O G3 (auditoria externa) fica dispensado nesta fase
  e será reavaliado com o crescimento do produto. Falha em G0, G1, G2 ou G4
  impede o avanço da função afetada; cobrança pública e implantação on-chain
  continuam bloqueadas até as aprovações específicas.
- **Rollout do REAL**: o REAL avança por etapas, cada uma aprovada pelo fundador:
  validador local e devnet (testes do fundador) desde o primeiro Bolt; depois a
  carteira do fundador na mainnet (Bolts B1 a B6 prontos, conta AWS e KMS reais,
  dois provedores de RPC); depois até 20 convidados. A abertura aos convidados
  exige as três condições: quarentena de 24 horas da liberação, pelo menos 7 dias
  da carteira do fundador na mainnet sem divergência e parecer de custódia
  decidido. Toda liberação de usuário para o REAL passa por uma quarentena de 24
  horas entre o registro e o efeito.
- **Rollback**: toda implantação tem caminho de rollback documentado e testado
  (`rollback-runbook.md` do intent `261113-trading-real-bot`); artefatos são
  marcados com o SHA do commit; migrações de banco seguem o padrão
  expand-contract e nunca removem colunas na mesma implantação da mudança de
  aplicação; em produção nenhuma migração é revertida, só as imagens voltam. Uma
  implantação só termina quando o smoke test passa. No REAL, o rollback desliga
  primeiro o flag do REAL (`memetrade.real.enabled` e `txsigner.real.enabled`) ou
  aciona o kill switch REAL, e o saque continua disponível durante todo o
  rollback.
- **Flags**: as flags do REAL são variáveis de ambiente das definições de tarefa,
  versionadas no Terraform; mudar uma flag é uma implantação auditada separada,
  nunca junto com uma mudança de código. Kill switch e liberação ficam no banco.
- **Controles antes do beta multiusuário (G1)**: varredura dinâmica (DAST),
  varredura de imagens e SBOM por release continuam candidatos (a definir).
- **Quando implantar**: o fundador é o único on-call; uma implantação na mainnet
  só começa quando ele pode acompanhar a janela de observação e os 30 minutos
  seguintes.

## Code Style

- **Princípio**: seguimos a configuração do linter e do formatador do
  repositório. A sugestão de um agente só vale quando o linter ainda não cobre o
  assunto. Lint, formatação e verificação de tipos rodam na CI; falha deixa o
  commit vermelho e é corrigida no commit seguinte.
- **Ferramentas**: Prettier e ESLint (`eslint-config-next`) nos apps web;
  Spotless com palantir-java-format no backend e no TxSigner. Não há Checkstyle;
  se um dia entrar, ele cobre só o que o formatador não cobre (nomes, `catch`
  vazio, imports com curinga).
- **Idioma**: código, nomes de tabelas, endpoints, mensagens de log e mensagens de
  commit em inglês; textos de interface em português do Brasil.
- **Nomes**: convenções idiomáticas de cada linguagem (camelCase em TypeScript e
  Java, PascalCase para tipos); `snake_case` em tabelas e colunas; rotas REST sob
  `/api/v1/`; valores de enum de domínio em maiúsculas (`REAL`, `PAPER`), iguais
  no Java, no JSON e no banco.
- **Contrato entre frontend e backend**: os tipos do frontend são gerados a
  partir do contrato da API (`openapi-guest.yaml`, `openapi-admin.yaml`), nunca
  escritos à mão. Uma quebra de contrato falha a CI.
- **Erros da API**: formato RFC 7807 (`ProblemDetail`), com tipo estável por
  categoria, código de erro legível por máquina e `correlationId`. Nunca expomos
  stack trace, SQL ou segredo na resposta nem no log. Erros de domínio (limite
  violado, autorização revogada, saldo PAPER insuficiente, tenant divergente)
  voltam como 4xx e são auditados; erros técnicos (fornecedor, timeout, banco)
  voltam como 5xx/503 e só são repetidos quando a operação é idempotente.
- **Sem falha silenciosa**: nenhum `catch` vazio; toda exceção capturada é
  relançada com contexto, convertida em resultado explícito ou registrada no
  nível adequado.
- **`unknown` como tipo explícito**: dado de mercado ausente tem um tipo próprio
  no código (tipo selado no Java, união discriminada no TypeScript), nunca
  `null`, `Optional` vazio sem motivo ou zero. No frontend, carregando, erro e
  `unknown` são estados visíveis distintos.
- **Valores do REAL em inteiros**: lamports e unidades base em `long` com
  operações exatas, frações em `BigInteger` e preços em `BigDecimal`; no app web,
  `bigint` só dentro de `features/real/money.ts`. Cada modo mostra os valores na
  própria unidade (PAPER em US$, REAL em SOL).
- **Tenant no código**: o tenant vem do contexto de autenticação, nunca do corpo
  da requisição; toda consulta de dados de cliente filtra pelo tenant.
- **Fronteiras de módulos (verificadas por ArchUnit)**: as regras ArchUnit do
  backend (`apps/api/src/test/java/com/memetrade/architecture/`) verificam as
  fronteiras, a pureza do domínio e as proibições de rede, chave, ponto
  flutuante e SDK; módulo novo ganha a sua regra. O backend é organizado por
  módulo (Ledger, Risk, Scanner etc.), não por camada técnica global. Um módulo
  fala com outro só pela interface pública ou por evento, nunca lendo as tabelas
  ou repositórios do outro; dentro do módulo, o domínio não depende de
  infraestrutura nem de classes web. PAPER nunca compartilha dados com REAL.
  Adaptadores externos (descoberta, preços, Solana, roteador de swap,
  pagamentos) ficam atrás de uma interface do módulo dono, com implementação
  simulada para o ambiente local.
- **TypeScript**: modo `strict` ligado.
- **Fim de linha e codificação**: `.editorconfig` e `.gitattributes` fixando LF e
  UTF-8 (com exceção CRLF para `*.cmd`/`*.bat`).
- Exceção única à regra de que toda consulta de dados de cliente filtra pelo tenant: `BotDirectory.ownedByAnotherTenant` consulta fora do tenant para auditar a tentativa de acesso a bot de outra conta; devolve só sim/não, nunca dados do bot, e é restrita por ArchUnit ao caminho do 404 (decisão do responsável, intent 261121-paper-real-unico). (learned 2026-11-24) <!-- cid:261121-paper-real-unico:functional-design:4b1fbf9e0fec15e62daa6053cd036697dd990e782f717c1d40152ee3bbbbfe2c -->

## Forbidden

<!-- Team-specific forbidden patterns -->

## Mandated

<!-- Team-specific mandates -->

## Corrections

<!-- Self-learning loop appends here. -->
