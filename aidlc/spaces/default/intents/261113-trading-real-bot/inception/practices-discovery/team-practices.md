# Práticas da equipe — Trading REAL (re-run)

Conversa: português (Brasil). Base: `aidlc/spaces/default/memory/team.md` (práticas já afirmadas), mantida sem
mudança, exceto nos pontos do ADR-008 (`inception/domain-design/decisions.md`) e da regra "Termos" do
`inception/contract-design/contract-summary.md` e nas decisões da entrevista
(`practices-discovery-questions.md`, Q1 a Q4). Evidências e mudanças em [evidence.md](evidence.md).

## Way of Working

- **Estratégia de branches**: desenvolvimento baseado em tronco (trunk-based).
  O tronco é `main`: base `main`, destino `main`. As mudanças entram direto em
  `main`, em um commit que o responsável do projeto pede, com a CI verde. Não
  criamos uma worktree nem uma branch por Bolt. **Squash-merge** em `main` só
  acontece quando existe uma branch, ou seja, quando o responsável pediu uma. O
  histórico de `main` fica linear.
- **Nome do tronco**: renomeamos `master` para `main` antes do primeiro commit.
  Não existe ramo `master` depois disso.
- **Commits fora de Bolts**: um commit pedido fora de um Bolt vai para a branch
  atual. Só criamos branch quando pedimos.
- **Cobertura da CI**: a CI já roda lint, formatação, verificação de tipos, Jest
  com o piso de cobertura e `check:api` do app web (`apps/web`) e do app
  administrativo (`apps/admin`), além do backend e do `scripts/verify.sh`. As
  lacunas do intent `261109-termos-lgpd` foram corrigidas. Só o responsável do
  projeto altera o `ci.yml`.
- **Revisão**: como trabalhamos sozinhos, não há revisão por outra pessoa no dia
  a dia. Cada mudança é revisada pelo responsável do projeto nos gates de
  aprovação do AI-DLC e pelos portões automáticos da CI. Nenhum merge acontece
  com a CI vermelha.
- **Hospedagem**: repositório privado no GitHub, com CI no GitHub Actions. A
  cota gratuita de minutos do plano é confirmada na Sprint 0 (a definir: valor
  exato da cota e se ela cobre a frequência de testes abaixo).
- **Repositório**: um único repositório (monorepo) com o frontend Next.js/
  TypeScript e o backend Spring Boot Java 21. Layout: `apps/web/` (Next.js),
  `apps/api/` (Spring Boot, módulos por pacote), `infra/` (Docker Compose local;
  IaC futura), `docs/` (ADRs e anexos técnicos), com `.editorconfig`,
  `.gitattributes` e `docker-compose.yml` na raiz.
- **Ferramentas de build**: Maven no backend; pnpm no frontend, com a versão do
  Node LTS fixada no projeto (por exemplo `.nvmrc` ou o campo `packageManager`/
  `engines`). Lockfiles são versionados e a CI instala a partir deles.
- **Proteção de segredos antes do primeiro commit**: nenhum segredo entra no
  repositório. Variáveis ficam em `.env` local, ignorado pelo git; o repositório
  guarda só um `.env.example` sem valores. Um hook de pré-commit com varredura de
  segredos (por exemplo, Gitleaks) bloqueia o commit que contenha segredo. Antes
  do primeiro commit, fazemos uma varredura única da árvore de trabalho atual
  (inclusive arquivos ainda não rastreados) e confirmamos que
  `.claude/settings.local.json` está ignorado. Se um segredo vazar, primeiro
  revogamos e trocamos o segredo, depois limpamos o histórico.
- **Segurança na CI**: a CI roda varredura de segredos, de dependências e análise
  estática (SAST) com ferramentas gratuitas. Achado Crítico ou Alto bloqueia o
  merge; achado Médio gera aviso. Exceções ficam em um arquivo versionado, cada
  uma com justificativa, validade de no máximo 90 dias e aprovação registrada do
  responsável do projeto; exceção vencida volta a bloquear.
- **Integridade da CI**: ações de terceiros são fixadas pelo SHA do commit, não
  por tag; cada workflow declara `permissions` mínimas (por padrão, só leitura do
  conteúdo); nenhum segredo de produção fica disponível em workflows disparados
  por branch ou fork não confiável.
- **Atualização de dependências**: atualizações automáticas abrem PRs (a
  ferramenta, Dependabot ou Renovate, é um exemplo, não uma escolha). Versões
  recém-publicadas de pacotes do frontend só são adotadas depois de alguns dias
  de espera, salvo correções de segurança.
- **Versões e releases**: um único tronco, sem branches de release de longa
  duração. Releases são marcadas por tags em `main` quando houver um ambiente
  para implantar.

## Walking Skeleton

- **Postura**: construímos primeiro uma fatia fina de ponta a ponta. A primeira
  Unit é a menor fatia integrada que funciona de verdade e passa por todos os
  estágios por Unit, inclusive geração de código, antes das demais.
- **Fatia do produto**: navegador → tela web (Next.js) → API (Spring Boot) → banco de dados
  (PostgreSQL), tudo subindo localmente com Docker Compose. Um adaptador de dados
  de mercado simulado alimenta uma lista mínima do scanner que exibe fonte,
  horário e `unknown` para campos ausentes. A fatia não chama fornecedor real
  (C-06) nem usa carteira real.
- **Verificação**: um único script versionado no repositório sobe o ambiente,
  espera os serviços ficarem saudáveis, roda um teste de ponta a ponta (smoke)
  que atravessa a fatia inteira e verifica fonte, horário e `unknown`, e devolve
  código de saída diferente de zero em qualquer falha. Esse comando é
  `bash scripts/verify.sh`, autorizado pelo responsável do projeto, e ele já
  roda na CI.
- **Fatia do intent `261109-termos-lgpd`**: a página `/privacidade` servida pela
  API a partir do catálogo, com a versão vigente, e mostrada no app sem login,
  coberta por um spec de ponta a ponta novo. A verificação continua sendo
  `bash scripts/verify.sh`, com o spec novo que o responsável do projeto
  acrescenta ao script.
- **Fatia do intent `261113-trading-real-bot`**: a carteira do bot no validador
  Solana local (Unit U1 `u1-bot-wallet`, Bolt B1): navegador (tela mínima) → API →
  BotWallet → TxSigner (processo próprio, KMS simulado no ambiente local) →
  ChainWatcher → validador local → ledger REAL. O spec de ponta a ponta novo
  ativa a carteira do bot, deposita 1 SOL a partir da carteira principal de
  teste, confere o saldo, saca de volta para a carteira principal e confirma que
  um saque para outro endereço é recusado. A carteira principal de teste é
  gerada só no código de teste. A verificação continua sendo sempre
  `bash scripts/verify.sh`, com o spec novo e o validador local que o
  responsável do projeto acrescenta ao script. O spec da carteira, com o
  validador local, roda em toda mudança e no merge para `main`; se a cota do
  GitHub Actions apertar, a frequência é revista com dados de consumo.
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
  intent `261109-termos-lgpd` (Q5, opção A), o responsável do projeto estendeu a
  ordem de testes antes do código à lógica de dados pessoais (anonimização,
  exclusão, novo aceite dos termos e expurgos), porque ela toca tabelas
  append-only como a trilha de auditoria e as ligadas ao ledger. No intent
  `261113-trading-real-bot`, o NFR8 dos requisitos estende a ordem de testes
  antes do código à lógica de carteira e saque, e o plano de Bolts pede os testes
  de ataque da política do TxSigner escritos antes do código (B1) e os testes de
  estados e idempotência da execução REAL antes do código (B4).
- **Volume de testes**: a Test Strategy do workflow é `Comprehensive`, também no
  intent `261109-termos-lgpd` (Q4, opção A); o piso de cobertura abaixo é somado
  a ela e nunca a substitui.
- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no
  backend e no frontend. As exclusões (por exemplo, código gerado como o cliente
  TypeScript do contrato da API, classes de configuração, ponto de entrada) ficam
  listadas na configuração da ferramenta de cobertura, nunca decididas na hora. A
  ferramenta falha o build abaixo do piso (por exemplo, regra `check` do JaCoCo
  no Maven e limite de cobertura do executor de testes do frontend). O piso não
  pode ser reduzido para fazer uma etapa passar. O processo TxSigner também
  segue o piso de 80%.
- **Testes obrigatórios por risco**: toda mudança que toque essas áreas inclui ou
  mantém verdes os testes de:
  - isolamento entre contas (tenants), inclusive acesso a recurso de outra conta
    por ID (devolve 403 ou 404 e nunca o dado) e tarefas em segundo plano que
    também validam o tenant;
  - separação entre REAL e PAPER (saldo, ordens, ledger, posições e P&L);
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
  sobre PostgreSQL real em contêiner (por exemplo, Testcontainers) e não dependem
  de estado compartilhado nem da ordem de execução. Fixtures nunca usam carteira,
  mint ou dados pessoais reais. No REAL, a rede é o validador Solana local (com
  contas clonadas para os swaps), o KMS é simulado no ambiente local e as
  respostas da Jupiter e do RPC usadas em testes de unidade são gravadas; nenhum
  teste da CI usa devnet, mainnet, KMS real ou chave real.
- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no
  Windows. O fim de linha é padronizado em LF por `.gitattributes` e
  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.
- **Frequência**: em toda mudança e no merge para `main` rodam os testes de
  unidade e de integração de todas as partes (backend, `apps/web` e
  `apps/admin`), com o piso de cobertura, e mais o `scripts/verify.sh`. O
  `verify.sh` com todos os specs de ponta a ponta é a suíte completa; não há uma
  suíte maior reservada ao merge.
- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado
  em silêncio.
- **Ferramentas (exemplos, não escolhas)**: JUnit 5, Testcontainers e JaCoCo no
  backend; Vitest ou Jest no frontend; Playwright para ponta a ponta.
- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não
  funcionais definam metas numéricas.

## Deployment

- **Situação atual**: não há conta de nuvem, região nem orçamento (C-10, C-12).
  O único ambiente é o local, no Windows, com Docker Compose. Para o REAL, uma
  conta AWS nova é aberta para o KMS (TR-C20) antes da mainnet.
- **Staging**: quando existir um ambiente de staging, implantamos nele a cada
  merge em `main` (a definir: quando e onde o staging vai existir).
- **Produção do beta PAPER**: exige aprovação manual separada, dada pelo
  responsável do projeto.
- **Gates de REAL (G2/G3)**: nesta fase, o fundador (responsável do projeto) é o
  revisor único dos gates do REAL. O G2 é cumprido pelo modelo de ameaças da
  carteira do bot do ADR-005. O G3 (auditoria externa) fica dispensado nesta fase
  e será reavaliado com o crescimento do produto. Falha em G0, G1, G2 ou G4
  impede o avanço da função afetada; cobrança pública e implantação on-chain
  continuam bloqueadas até as aprovações específicas.
- **Rollout do REAL**: o REAL avança por etapas, cada uma aprovada pelo fundador:
  validador local e devnet desde o primeiro Bolt; depois a carteira do fundador
  na mainnet (Bolts B1 a B6 prontos, conta AWS e KMS reais, dois provedores de
  RPC); depois até 20 convidados. A abertura aos convidados exige as três
  condições: quarentena de 24 horas da liberação, pelo menos 7 dias da carteira
  do fundador na mainnet sem divergência e parecer de custódia decidido. Toda
  liberação de usuário para o REAL passa por uma quarentena de 24 horas entre o
  registro e o efeito.
- **Rollback**: toda implantação tem caminho de rollback documentado e testado;
  artefatos são marcados com o SHA do commit; migrações de banco seguem o padrão
  expand-contract e nunca removem colunas na mesma implantação da mudança de
  aplicação. Uma implantação só termina quando o smoke test passa. No REAL, o
  rollback desliga primeiro o flag do REAL (`memetrade.real.enabled` e
  `txsigner.real.enabled`) ou aciona o kill switch REAL, e o saque continua
  disponível durante todo o rollback.
- **Controles antes do beta multiusuário (G1)**: varredura dinâmica (DAST),
  varredura de imagens e SBOM por release são candidatos a definir nos estágios
  de segurança e infraestrutura (a definir).
- **Destino futuro**: AWS com serviços gerenciados, IaC e CI/CD, previsto no PRD
  para a Sprint 7. A ferramenta de IaC e a plataforma de CD ficam para os
  estágios de infraestrutura. A topologia do TxSigner (contêiner ou função, com
  credencial AWS própria) é decidida no Infrastructure Design.

## Code Style

- **Princípio**: seguimos a configuração do linter e do formatador do
  repositório. A sugestão de um agente só vale quando o linter ainda não cobre o
  assunto. Lint, formatação e verificação de tipos rodam na CI antes do merge;
  falha bloqueia o merge.
- **Ferramentas (exemplos, não escolhas)**: Prettier e ESLint no frontend;
  Spotless com um formatador Java (google-java-format ou palantir-java-format) no
  backend. Se houver Checkstyle ou equivalente, ele cobre só o que o formatador
  não cobre (nomes, `catch` vazio, imports com curinga), para as ferramentas não
  brigarem.
- **Idioma**: código, nomes de tabelas, endpoints e mensagens de log em inglês;
  textos de interface em português do Brasil.
- **Nomes**: convenções idiomáticas de cada linguagem (camelCase em TypeScript e
  Java, PascalCase para tipos); `snake_case` em tabelas e colunas; rotas REST sob
  `/api/v1/`; valores de enum de domínio em maiúsculas (`REAL`, `PAPER`), iguais
  no Java, no JSON e no banco.
- **Contrato entre frontend e backend**: os tipos do frontend são gerados a
  partir do contrato da API, nunca escritos à mão. Uma quebra de contrato falha a
  CI.
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
  no código (por exemplo, um tipo selado no Java e uma união discriminada no
  TypeScript), nunca `null`, `Optional` vazio sem motivo ou zero. No frontend,
  carregando, erro e `unknown` são estados visíveis distintos.
- **Tenant no código**: o tenant vem do contexto de autenticação, nunca do corpo
  da requisição; toda consulta de dados de cliente filtra pelo tenant.
- **Fronteiras de módulos (verificadas por ArchUnit)**: as regras ArchUnit do
  backend (`apps/api/src/test/java/com/memetrade/architecture/`) já verificam as
  fronteiras, a pureza do domínio e as proibições de rede, chave, ponto
  flutuante e SDK; módulo novo ganha a sua regra.
  o backend é organizado por módulo (Ledger, Risk, Scanner etc.), não por camada
  técnica global. Um módulo fala com outro só pela interface pública ou por
  evento, nunca lendo as tabelas ou repositórios do outro; dentro do módulo, o
  domínio não depende de infraestrutura nem de classes web. PAPER nunca
  compartilha dados com REAL. Adaptadores externos (descoberta, preços, Solana,
  roteador de swap, pagamentos) ficam atrás de uma interface do módulo dono, com
  implementação simulada para o ambiente local.
- **TypeScript**: modo `strict` ligado.
- **Fim de linha e codificação**: `.editorconfig` e `.gitattributes` desde o
  primeiro commit, fixando LF e UTF-8 (com exceção CRLF para `*.cmd`/`*.bat`).
