# Práticas da equipe — MemeTrade Analytics

> Somos uma equipe de uma pessoa: um desenvolvedor solo que trabalha com apoio
> de IA, sem orçamento por enquanto (C-12). O projeto é greenfield. Estas
> práticas foram decididas na entrevista de descoberta de práticas
> (2026-10-26) e valem a partir do primeiro commit.

## Way of Working

- **Estratégia de branches**: desenvolvimento baseado em tronco (trunk-based).
  O tronco é `main`: base `main`, destino `main`. Cada Bolt de Construction roda
  em uma worktree própria criada a partir de `main` e é integrado por
  **squash-merge** em `main`, em um único commit nomeado pelo slug do Bolt. O
  histórico de `main` fica linear e acompanha a sequência de Bolts do
  planejamento de entrega.
- **Nome do tronco**: renomeamos `master` para `main` antes do primeiro commit.
  Não existe ramo `master` depois disso.
- **Commits fora de Bolts**: um commit pedido fora de um Bolt vai para a branch
  atual. Só criamos branch quando pedimos.
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
- **Fatia**: navegador → tela web (Next.js) → API (Spring Boot) → banco de dados
  (PostgreSQL), tudo subindo localmente com Docker Compose. Um adaptador de dados
  de mercado simulado alimenta uma lista mínima do scanner que exibe fonte,
  horário e `unknown` para campos ausentes. A fatia não chama fornecedor real
  (C-06) nem usa carteira real.
- **Verificação**: um único script versionado no repositório sobe o ambiente,
  espera os serviços ficarem saudáveis, roda um teste de ponta a ponta (smoke)
  que atravessa a fatia inteira e verifica fonte, horário e `unknown`, e devolve
  código de saída diferente de zero em qualquer falha. O comando exato é
  autorizado pelo responsável do projeto na Construction (a definir).
- **Depois do esqueleto**: com o esqueleto verificado e aprovado, o framework
  oferece seguir automaticamente ou revisar cada ponto de controle. Plan
  Approval, confirmação de resumo, escolha do comando de verificação e falhas
  continuam exigindo a pessoa.

## Testing Posture

- **Methodology**: custom
- **Ordering**: No núcleo financeiro (limites de risco, ledger, P&L e deduplicação) escrevemos os testes antes do código a partir da regra de negócio, e em todas as demais camadas implementamos cada camada testável e, em seguida, escrevemos e executamos os testes dessa camada.
- **Methodology evidence**: decisão do responsável do projeto na entrevista
  (Q10, opção A). TDD no núcleo financeiro porque código gerado com IA tende a
  passar em testes escritos depois sobre o próprio código; com o teste escrito
  antes, ele valida o requisito e não a implementação. Test-after no restante
  (UI, adaptadores, configuração) mantém o ritmo de um desenvolvedor solo.
- **Volume de testes**: a Test Strategy do workflow é `Comprehensive`; o piso de
  cobertura abaixo é somado a ela e nunca a substitui.
- **Piso de cobertura**: 80% de cobertura de linhas, medido separadamente no
  backend e no frontend. As exclusões (por exemplo, código gerado como o cliente
  TypeScript do contrato da API, classes de configuração, ponto de entrada) ficam
  listadas na configuração da ferramenta de cobertura, nunca decididas na hora. A
  ferramenta falha o build abaixo do piso (por exemplo, regra `check` do JaCoCo
  no Maven e limite de cobertura do executor de testes do frontend). O piso não
  pode ser reduzido para fazer uma etapa passar.
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
  - REAL desligado por padrão: nenhum caminho assina ou envia transação com a
    configuração padrão;
  - login sem reutilização de assinatura: nonce reutilizado ou assinatura
    expirada são rejeitados, e a assinatura de login não serve para autorizar
    trading.
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
  mint ou dados pessoais reais.
- **Ambientes de teste**: a CI roda em Linux; o desenvolvimento local é no
  Windows. O fim de linha é padronizado em LF por `.gitattributes` e
  `.editorconfig` para que os testes deem o mesmo resultado nos dois ambientes.
- **Frequência**: o smoke de ponta a ponta roda em toda mudança; a suíte
  completa roda no merge para `main`.
- **Testes instáveis**: teste instável é tratado como defeito e nunca é ignorado
  em silêncio.
- **Ferramentas (exemplos, não escolhas)**: JUnit 5, Testcontainers e JaCoCo no
  backend; Vitest ou Jest no frontend; Playwright para ponta a ponta.
- **Desempenho**: nenhum portão de desempenho ou carga até que os requisitos não
  funcionais definam metas numéricas.

## Deployment

- **Situação atual**: não há conta de nuvem, região nem orçamento (C-10, C-12).
  O único ambiente é o local, no Windows, com Docker Compose.
- **Staging**: quando existir um ambiente de staging, implantamos nele a cada
  merge em `main` (a definir: quando e onde o staging vai existir).
- **Produção do beta PAPER**: exige aprovação manual separada, dada pelo
  responsável do projeto.
- **Gates de REAL (G2/G3)**: a autoaprovação do responsável do projeto não
  satisfaz G2/G3. Esses gates exigem um revisor independente, ainda a nomear
  (C-14). Falha em G0 a G4 impede o avanço da função afetada; trading REAL,
  cobrança pública e implantação on-chain continuam bloqueados até as aprovações
  específicas.
- **Rollback**: toda implantação tem caminho de rollback documentado e testado;
  artefatos são marcados com o SHA do commit; migrações de banco seguem o padrão
  expand-contract e nunca removem colunas na mesma implantação da mudança de
  aplicação. Uma implantação só termina quando o smoke test passa.
- **Controles antes do beta multiusuário (G1)**: varredura dinâmica (DAST),
  varredura de imagens e SBOM por release são candidatos a definir nos estágios
  de segurança e infraestrutura (a definir).
- **Destino futuro**: AWS com serviços gerenciados, IaC e CI/CD, previsto no PRD
  para a Sprint 7. A ferramenta de IaC e a plataforma de CD ficam para os
  estágios de infraestrutura.

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
- **Fronteiras de módulos (convenção, sem verificação automática por enquanto)**:
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
