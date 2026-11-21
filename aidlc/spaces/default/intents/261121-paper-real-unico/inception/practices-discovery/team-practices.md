# Práticas da equipe — rascunho do líder (intent `261121-paper-real-unico`)

> **Rascunho do Step 2 (líder: `aidlc-pipeline-deploy-agent`), 2026-11-21.** Ponto de partida: as cinco seções
> afirmadas de `aidlc/spaces/default/memory/team.md`. O texto abaixo já traz os fatos que a evidência confirma
> (por exemplo, jobs da CI e pastas que existem hoje). Cada trecho marcado com **[a confirmar: DIV-xx]** muda ou
> pode mudar uma prática afirmada e depende da entrevista; o detalhe de cada divergência está em `evidence.md`.
> Nada aqui foi promovido para a memória.

## Way of Working

- **Estratégia de branches**: desenvolvimento baseado em tronco (trunk-based).
  O tronco é `main`: base `main`, destino `main`. As mudanças entram direto em
  `main`, em um commit que o responsável do projeto pede. Não criamos uma
  worktree nem uma branch por Bolt. **Squash-merge** em `main` só acontece
  quando existe uma branch, ou seja, quando o responsável pediu uma. O histórico
  de `main` fica linear.
- **CI verde em `main`** **[a confirmar: DIV-05]**: o `team.md` diz que as
  mudanças entram "com a CI verde" e que nenhum merge acontece com a CI
  vermelha. Na prática, o commit vai direto para `main` e a CI roda depois do
  push; `main` não tem proteção de branch, e 18 dos últimos 30 runs do `ci` em
  `main` falharam e foram corrigidos com um commit seguinte. A entrevista decide
  entre: (a) manter o push direto e registrar a correção para a frente
  (fix-forward) como prática, com a CI local (`scripts/verify-checkpoint.sh`)
  antes do push; ou (b) passar a exigir a CI verde antes de `main` (branch curta
  com PR e proteção de branch).
- **Nome do tronco**: renomeamos `master` para `main` antes do primeiro commit.
  Não existe ramo `master` depois disso.
- **Commits fora de Bolts**: um commit pedido fora de um Bolt vai para a branch
  atual. Só criamos branch quando pedimos.
- **Mensagens de commit** **[a confirmar: DIV-10]**: em inglês, com prefixo do
  tipo (`feat:`, `fix:`, `test:`, `chore:`, `ci:`, `docs:`), como o histórico
  mostra. Prática observada, ainda não afirmada.
- **Cobertura da CI** **[a confirmar: DIV-04]**: o workflow `ci` roda, em todo
  PR, em todo push para `main` e à mão (`workflow_dispatch`):
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
- **Ações da CI em Node 24** **[a confirmar: DIV-04]**: a troca das ações para
  as versões em Node 24 (e do CodeQL para a v4) e a proteção do download do
  Maven foram decididas no estágio CI Pipeline do intent
  `261113-trading-real-bot`, mas ainda não foram aplicadas: todas as ações
  continuam nas versões em Node 20.
- **Revisão**: como trabalhamos sozinhos, não há revisão por outra pessoa no dia
  a dia. Cada mudança é revisada pelo responsável do projeto nos gates de
  aprovação do AI-DLC e pelos portões automáticos da CI.
- **Hospedagem** **[a confirmar: DIV-01]**: repositório no GitHub
  (`rafaelcruz-net/eureka-trade-ai`), com CI no GitHub Actions. O `team.md` diz
  "privado", mas o GitHub informa o repositório como **público**. A entrevista
  decide se ele volta a ser privado ou se continua público, com os controles que
  isso pede (ver `evidence.md`). Se continuar público, a pergunta da cota de
  minutos deixa de existir (os runners padrão são gratuitos em repositório
  público).
- **Repositório** **[a confirmar: DIV-08]**: um único repositório (monorepo).
  Layout: `apps/web/` (Next.js do usuário), `apps/admin/` (Next.js da
  administração), `apps/api/` (Spring Boot, módulos por pacote),
  `apps/txsigner/` (serviço de assinatura isolado, Spring Boot), `infra/`
  (composições Docker de teste e do REAL, `infra/observability/` com as regras de
  alerta, `infra/terraform/` com a IaC da AWS), `docs/` (ADRs, anexos de
  contrato, privacidade, runbooks, desempenho), `scripts/` (`verify.sh`,
  `verify-checkpoint.sh`, carga), `security/exceptions.yml`, com
  `.editorconfig`, `.gitattributes` e `docker-compose.yml` na raiz.
- **Ferramentas de build**: Maven (wrapper) no backend e no TxSigner; pnpm
  10.18.3 nos dois apps web, com o Node 24 LTS fixado em `.nvmrc` e em
  `engines`. Lockfiles são versionados e a CI instala a partir deles.
- **Proteção de segredos**: nenhum segredo entra no repositório. Variáveis ficam
  em `.env` local, ignorado pelo git; o repositório guarda só um `.env.example`
  sem valores. O hook `.githooks/pre-commit` roda o Gitleaks na versão de
  `.gitleaks-version` e bloqueia o commit que contenha segredo. Estado e planos do
  Terraform e arquivos `*.tfvars` nunca entram no repositório. Se um segredo
  vazar, primeiro revogamos e trocamos o segredo, depois limpamos o histórico.
- **Segurança na CI**: o workflow `security` roda Gitleaks (histórico e árvore),
  CodeQL (Java e TypeScript), OWASP Dependency-Check e `pnpm audit` nos dois
  apps. Achado Crítico ou Alto bloqueia; achado Médio gera aviso. Exceções ficam
  em `security/exceptions.yml`, cada uma com justificativa, validade de no máximo
  90 dias e aprovação registrada do responsável; exceção vencida volta a
  bloquear. **[a confirmar: DIV-01]** a varredura de segredos nativa do GitHub e
  a proteção no push estão desligadas.
- **Integridade da CI**: ações de terceiros são fixadas pelo SHA do commit, não
  por tag (o teste `WorkflowsTest` confere); cada workflow declara `permissions`
  mínimas (por padrão, só leitura do conteúdo); nenhum segredo de produção fica
  disponível em workflows disparados por branch ou fork não confiável.
  **[a confirmar: DIV-06]** o `deploy.yml` desenhado precisa de
  `id-token: write`, e o `WorkflowsTest` hoje recusa qualquer permissão de
  escrita.
- **Atualização de dependências**: o Dependabot abre PRs semanais (Maven, npm dos
  dois apps, Docker, Docker Compose e GitHub Actions). Versões novas dos pacotes
  npm esperam 5 dias, salvo correções de segurança. **[a confirmar: DIV-16]** os
  alertas de segurança do Dependabot estão desligados no repositório.
- **Versões e releases** **[a confirmar: DIV-09]**: um único tronco, sem branches
  de release de longa duração. O histórico já tem tags anotadas de "release
  local" no formato `vAAAA.MM.DD-N` (`v2026.11.08-1`, `v2026.11.12-1`), mesmo sem
  ambiente implantado.
- **Trabalho pendente fora de `main`** **[a confirmar: DIV-07]**: os resultados
  dos estágios de operação do intent `261113-trading-real-bot` (Terraform,
  observabilidade, runbooks, testes e `security/exceptions.yml`) estão na árvore
  de trabalho sem commit desde 2026-11-20.

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
- **Ponto de controle com pouca memória local** **[a confirmar: DIV-11]**: nos
  pontos de controle da Construction, `scripts/verify-checkpoint.sh` roda primeiro
  o `verify.sh` local; se a execução local morrer (por falta de memória), a
  segunda tentativa do mesmo commit aceita o run verde do `ci` no GitHub Actions,
  desde que a árvore não tenha mudança fora dos registros do AI-DLC e o commit
  esteja no remoto.
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
  teste. O spec roda em toda mudança e no merge para `main`.
- **Fatia do intent `261121-paper-real-unico`** **[a confirmar: DIV-14]**:
  proposta: a lista única de bots com um bot PAPER e um bot REAL lado a lado,
  no validador Solana local: navegador (tela de Bots e de P&L) → API (P&L por
  bot nos dois modos) → ledger PAPER e ledger REAL → blocos separados Simulado
  (US$) e Real (SOL), sem total geral, com o aviso fixo no simulado. Um spec de
  ponta a ponta novo, acrescentado ao `verify.sh` pelo responsável (CO-04 do
  registro de restrições), cria os dois bots, faz uma compra em cada modo e
  confere que cada bloco mostra só o próprio modo. A verificação continua sendo
  `bash scripts/verify.sh`.
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
  idempotência da execução REAL (B4) antes do código. Neste intent, o P&L REAL
  por bot (custo médio por bot e rateio das vendas sem bot) já está dentro de
  "P&L" no núcleo financeiro; o registro de restrições (CT-15) pede os cenários
  escritos antes do código.
- **Volume de testes** **[a confirmar: DIV-13]**: nos intents anteriores a Test
  Strategy foi `Comprehensive`. O estado deste intent registra `Standard`, vindo
  do escopo `feature`. O piso de cobertura abaixo é somado à estratégia e nunca a
  substitui.
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
    próprio modo, e não existe total que some os dois **[a confirmar: DIV-14]**;
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
    `/api/v1/real/bot-wallet/withdrawals/{withdrawalId}`); o teste cobre as duas
    metades: as rotas da exceção respondem sem o novo aceite e todas as demais
    respondem `403 TERMS_REACCEPTANCE_REQUIRED`;
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
  dados pessoais reais. No REAL, a rede é o validador Solana local, o KMS é
  simulado no ambiente local e as respostas da Jupiter e do RPC usadas em testes
  de unidade são gravadas; nenhum teste da CI usa devnet, mainnet, KMS real ou
  chave real.
- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no
  Windows. O fim de linha é padronizado em LF por `.gitattributes` e
  `.editorconfig`.
- **Frequência**: em toda mudança e no push para `main` rodam os testes de
  unidade e de integração de todas as partes (backend, TxSigner, `apps/web` e
  `apps/admin`), com o piso de cobertura, e mais o `scripts/verify.sh`. O
  `verify.sh` com todos os specs de ponta a ponta é a suíte completa; no push
  para `main` o job `e2e` roda a mesma suíte uma segunda vez, depois dos demais
  jobs verdes.
- **Testes da IaC** **[a confirmar: DIV-04]**: os testes do Terraform
  (`infra/terraform/tests/*.tftest.hcl`, `terraform validate` e Checkov) existem,
  mas nenhum workflow os roda.
- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado
  em silêncio.
- **Ferramentas**: JUnit 5, Testcontainers, ArchUnit e JaCoCo no backend e no
  TxSigner; Jest com Testing Library nos dois apps; Playwright para ponta a ponta;
  `node:test` nos cenários de carga.
- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não
  funcionais definam metas numéricas; o workflow `load-test` é manual.

## Deployment

- **Situação atual** **[a confirmar: DIV-02]**: nenhum ambiente na nuvem está
  provisionado; o único ambiente que roda é o local, no Windows, com Docker
  Compose. Os estágios de operação do intent `261113-trading-real-bot` já
  desenharam a nuvem: Terraform em `infra/terraform/`, um AWS Organizations com a
  conta de gerenciamento e duas contas membro separadas (`devnet` e `mainnet`),
  sem conexão entre elas, e um orçamento de US$ 350 por conta. Nada foi aplicado.
- **Ambiente dos usuários** **[a confirmar: DIV-03]**: existe um único ambiente
  para os usuários, na **mainnet**. Nele o usuário cria bots PAPER e REAL lado a
  lado, com P&L por bot e por modo sempre separados. A **devnet** serve só aos
  testes do fundador e é o ambiente de pré-produção do mesmo SHA; nenhum usuário
  a usa. Nenhum serviço AWS novo nem custo novo entra por causa disso.
- **Fluxo de implantação** **[a confirmar: DIV-02]**: implantação manual de um
  commit de `main` com `ci` e `security` verdes, pelo `deploy.yml`
  (`workflow_dispatch`) em GitHub Environments `devnet` e `mainnet`, com
  credencial AWS por OIDC e sem chave longa. O mesmo SHA passa primeiro pela
  devnet e só depois vai para a mainnet. O `deploy.yml` e os Environments ainda
  não existem no repositório (existe só o texto pronto em `cd-config.md`).
- **Passo do fundador com MFA**: antes de cada implantação, o fundador, da
  máquina dele e com MFA, publica a imagem do TxSigner, roda a migração do
  TxSigner, troca a revisão em serviço e confere a prontidão. O papel de
  implantação do GitHub nunca implanta o TxSigner, nunca toca no KMS e nunca liga
  o REAL.
- **Staging** **[a confirmar: DIV-02]**: o `team.md` dizia "implantamos no
  staging a cada merge em `main`". O desenho atual não tem staging separado e não
  implanta na devnet a cada merge: a devnet recebe a implantação manual do SHA
  escolhido.
- **Produção** **[a confirmar: DIV-03]**: a implantação na mainnet, que agora
  serve ao PAPER e ao REAL dos usuários, exige a aprovação do fundador como
  revisor obrigatório do Environment `mainnet`.
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
  assunto. Lint, formatação e verificação de tipos rodam na CI antes do merge;
  falha bloqueia o merge.
- **Ferramentas**: Prettier e ESLint (`eslint-config-next`) nos apps web;
  Spotless com palantir-java-format no backend e no TxSigner. Não há Checkstyle;
  se um dia entrar, ele cobre só o que o formatador não cobre.
- **Idioma**: código, nomes de tabelas, endpoints e mensagens de log em inglês;
  textos de interface em português do Brasil.
- **Nomes**: convenções idiomáticas de cada linguagem (camelCase em TypeScript e
  Java, PascalCase para tipos); `snake_case` em tabelas e colunas; rotas REST sob
  `/api/v1/`; valores de enum de domínio em maiúsculas (`REAL`, `PAPER`), iguais
  no Java, no JSON e no banco.
- **Contrato entre frontend e backend**: os tipos do frontend são gerados a
  partir do contrato da API (`openapi-guest.yaml`, `openapi-admin.yaml`), nunca
  escritos à mão. Uma quebra de contrato falha a CI.
- **Erros da API**: formato RFC 7807 (`ProblemDetail`), com tipo estável por
  categoria, código de erro legível por máquina e `correlationId`. Nunca expomos
  stack trace, SQL ou segredo na resposta nem no log. Erros de domínio voltam
  como 4xx e são auditados; erros técnicos voltam como 5xx/503 e só são repetidos
  quando a operação é idempotente.
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
  módulo, não por camada técnica global. Um módulo fala com outro só pela
  interface pública ou por evento, nunca lendo as tabelas ou repositórios do
  outro; dentro do módulo, o domínio não depende de infraestrutura nem de classes
  web. PAPER nunca compartilha dados com REAL. Adaptadores externos ficam atrás
  de uma interface do módulo dono, com implementação simulada para o ambiente
  local.
- **TypeScript**: modo `strict` ligado.
- **Fim de linha e codificação**: `.editorconfig` e `.gitattributes` fixando LF e
  UTF-8 (com exceção CRLF para `*.cmd`/`*.bat`).
