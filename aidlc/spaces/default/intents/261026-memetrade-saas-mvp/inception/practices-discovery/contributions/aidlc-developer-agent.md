**Collaborator:** aidlc-developer-agent

## Contribution

Revisão de nomes, fronteiras de camadas e módulos, tratamento de erros, organização de arquivos e estilo de código para a stack candidata do PRD (Next.js/React/TypeScript no frontend e Java 21 Spring Boot como monólito modular no backend, desenvolvidos no Windows). Como o projeto é greenfield, tudo abaixo é **sugestão** ou **ponto aberto**. Nada foi observado em código. Os itens estão escritos para que o líder os integre direto em `## Code Style` (e, quando couber, em `## Way of Working`) e na lista de perguntas da entrevista.

### 1. Lacuna principal: o rascunho não fala de fronteiras entre módulos e camadas

O `## Code Style` do rascunho cobre formatador, linter, nomes e fim de linha, mas não diz nada sobre como o monólito modular se mantém modular. O PRD §12 lista 13 módulos (Identity, Tenancy, Wallets, Scanner, MarketData, Signals, Strategies, Risk, Execution, Ledger, Portfolio, Billing, Notifications) e "workers assíncronos isolados". Sem uma regra de fronteira, um monólito modular feito por uma pessoa com IA tende a virar acoplamento direto entre módulos em poucas sprints. Sugestões para o líder incorporar como **sugestão a confirmar**:

- **Pacote por módulo, não por camada técnica**: `<pacote-raiz>.<modulo>` (por exemplo `...ledger`, `...risk`), cada um com uma API pública pequena e o restante interno. Evitar pacotes globais `controllers/`, `services/`, `repositories/` que atravessam módulos.
- **Camadas dentro de cada módulo**: entrada web (controllers/DTOs) → aplicação (casos de uso) → domínio (regras puras, sem Spring) → infraestrutura (JPA/JDBC, adaptadores externos). O domínio não depende de infraestrutura nem de classes web.
- **Comunicação entre módulos**: só pela API pública do outro módulo ou por eventos de domínio. Um módulo nunca acessa o repositório ou as tabelas de outro módulo.
- **Verificação automática da fronteira (ponto aberto)**: escolher entre Spring Modulith (`ApplicationModules.verify()` em um teste) e ArchUnit, ou não automatizar por enquanto. Sem verificação automática, a regra vale só no papel. A escolha da ferramenta pode ficar para o design de aplicação. A pergunta da entrevista é se a fronteira **deve ser verificada na CI**.
- **Adaptadores externos atrás de portas**: descoberta, preços, Solana RPC/WebSocket, roteador de swap e pagamentos ficam atrás de uma interface do módulo dono, com implementação mock para o Docker Compose local. Isso já é exigido pela fatia candidata do esqueleto (adaptador de dados simulado) e por C-06.
- **Execução nunca no navegador** (já candidata em `discovered-rules.md`): no código, significa que o frontend não importa nenhum SDK de assinatura de swap depois da ativação do bot. Só o fluxo de autorização inicial usa Wallet Standard no cliente.

### 2. Nomes (complementos ao rascunho)

O rascunho diz "convenções idiomáticas de cada linguagem", o que está certo como padrão. Faltam decisões que o linter não toma e que, se ficarem abertas, geram inconsistência já na Sprint 1:

- **Idioma dos identificadores (ponto aberto)**: código, nomes de tabelas, endpoints e mensagens de log em inglês (o PRD já usa `LedgerEntry`, `TradingAuthorization`, `/api/v1/...`) e textos de interface em português do Brasil via arquivos de mensagens. Confirmar.
- **Banco de dados**: `snake_case` para tabelas e colunas (o PRD §13 já usa `tenant_id`, `observed_at`). Ponto aberto: tabelas no singular ou no plural.
- **JSON da API (ponto aberto)**: `camelCase` (padrão do Jackson e do TypeScript) ou `snake_case`. Recomendo `camelCase`, para não precisar de conversão no frontend.
- **Rotas REST**: substantivos no plural em `kebab-case`, sob `/api/v1/`, como o PRD §14 já faz.
- **Enums de domínio**: valores em maiúsculas (`REAL`, `PAPER`), iguais no Java, no JSON e no banco.
- **Arquivos TypeScript em `kebab-case`**: o Windows não diferencia maiúsculas de minúsculas no nome de arquivo, mas a CI em Linux diferencia. Um import `./TokenCard` que aponta para `tokenCard.tsx` funciona local e quebra na CI. Componentes React continuam em `PascalCase` no código; só o nome do arquivo segue `kebab-case`. Alternativa: manter `PascalCase.tsx` e ativar a regra de ESLint que exige o mesmo case no import. Ponto aberto.
- **Migrações de banco (ponto aberto)**: escolher Flyway ou Liquibase e uma convenção de nome, por exemplo `V<AAAAMMDDHHmm>__<descricao>.sql`. Timestamp evita colisão entre Bolts paralelos melhor que um número sequencial.

### 3. Tratamento de erros (ausente do rascunho)

O PRD §14 já fixa RFC 7807 para erros da API. A regra de construção "erros devem ser expostos ao chamador ou registrados, nunca silenciosos" vale para todo o projeto. Sugestões para o `## Code Style`:

- **Backend**: um único `@RestControllerAdvice` que converte exceções em `ProblemDetail` (nativo no Spring 6), com `type` estável por categoria de erro, um código de erro legível por máquina e o `correlationId`. Nunca expor stack trace, SQL ou segredo na resposta nem no log (PRD §12: "logs sem segredos").
- **Erro de domínio x erro técnico**: violação de limite, autorização revogada, saldo PAPER insuficiente e tenant divergente são erros de domínio, com tipo próprio, retornados como 4xx e auditados. Falha de fornecedor, timeout e banco indisponível são erros técnicos, retornados como 5xx/503 e passíveis de nova tentativa só quando a operação for idempotente (`Idempotency-Key`).
- **Nada de `catch` vazio**: toda exceção capturada é relançada com contexto, convertida em resultado explícito ou registrada com nível adequado. Isso pode ser verificado por regra de linter (Checkstyle `EmptyCatchBlock`, ESLint `no-empty`).
- **`unknown` não é erro nem `null` nem zero**: dado de mercado ausente precisa de um tipo explícito no código, por exemplo um tipo selado `Known<T> | Unknown` no Java e uma união discriminada no TypeScript, em vez de `null`, `Optional` vazio sem motivo ou `0`. Isso dá forma de código à regra candidata "ALWAYS representar dado ausente como `unknown`".
- **Frontend**: o cliente da API devolve resultado tipado (sucesso ou `ProblemDetail`) e as telas tratam carregando, erro e `unknown` como estados visíveis distintos. Usar error boundaries do Next.js (`error.tsx`) por rota.

### 4. Precisão numérica e tempo: proposta de regra candidata nova

O PRD §10 exige "precisão decimal e unidades de token", e a métrica beta exige "precisão do P&L validada em cenários determinísticos". Isso não aparece em `discovered-rules.md`. Proponho ao líder adicionar como **candidata** (origem: PRD §10 e §2 métricas beta), para a pessoa confirmar ou descartar:

- `NEVER usar ponto flutuante (double/float no Java, number no TypeScript) para valores monetários, quantidades de token ou P&L; usar BigDecimal ou inteiros em unidades base (BigInteger/bigint ou string na API).`
- `ALWAYS gravar e trafegar instantes em UTC (Instant no Java, ISO-8601 com Z na API), convertendo para o fuso do usuário só na interface.`

A segunda é menos crítica e pode ficar como convenção de estilo em vez de regra rígida, se a pessoa preferir.

### 5. Isolamento por tenant no formato do código

A regra candidata "ALWAYS validar o tenant no servidor" fica mais verificável com uma convenção de código: todo método de repositório que lê ou grava dado de cliente recebe `tenantId` explicitamente (ou o tenant vem de um contexto obrigatório preenchido pelo filtro de autenticação), e nenhuma consulta de dados de cliente existe sem filtro de tenant. O tenant nunca vem do corpo da requisição. Sugiro que o líder registre isso como sugestão de estilo, ligada ao teste negativo de isolamento já listado em `## Testing Posture`.

### 6. Organização de arquivos do monorepo (ponto aberto)

O rascunho diz "monorepo", mas não define o layout. Proposta para a entrevista:

```
apps/web/        Next.js (App Router), TypeScript
apps/api/        Spring Boot, módulos por pacote
infra/           Docker Compose local; IaC futura
docs/            ADRs e anexos técnicos
.editorconfig  .gitattributes  docker-compose.yml
```

Decisões abertas ligadas ao layout:

- **Ferramenta de build Java**: Gradle (Kotlin DSL) ou Maven. Ambas funcionam no Windows. Gradle tem build incremental e toolchain Java 21 declarativa; Maven é mais simples de ler. A experiência do responsável com a stack ainda não foi mapeada (C-11), então essa pode ser a melhor pergunta para fazer agora.
- **Gerenciador de pacotes Node**: npm ou pnpm, com a versão do Node LTS fixada (`.nvmrc` ou campo `engines`/`packageManager`).
- **Workers**: o PRD pede workers isolados para ingestão, análise, execução e reconciliação. Pergunta: o mesmo código do monólito sobe em processos diferentes por perfil do Spring, ou cada worker é uma aplicação separada no monorepo? A primeira opção mantém um só build e é a mais simples para uma pessoa.
- **Contrato da API**: OpenAPI gerado a partir do código (springdoc) ou escrito primeiro e usado para gerar o cliente TypeScript (por exemplo `openapi-typescript`). Em qualquer caso, recomendo que os tipos do frontend venham do contrato, nunca escritos à mão.

### 7. Estilo e configuração (ajustes ao rascunho)

- **TypeScript**: `strict: true` no `tsconfig`, sem `any` implícito, verificação de tipos (`tsc --noEmit`) como passo da CI, não só o ESLint.
- **Java**: no rascunho, Spotless com formatador e Checkstyle aparecem juntos. Se o formatador já fixa o layout, o Checkstyle deve ficar só com regras que o formatador não cobre (nomes, `catch` vazio, imports com curinga). Caso contrário, os dois brigam. Recomendo palantir-java-format, que deixa linhas longas mais legíveis que o google-java-format, mas a escolha é da pessoa.
- **Windows e fim de linha**: concordo com `.editorconfig` e `.gitattributes` desde o primeiro commit. Detalhes concretos: `* text=auto eol=lf`, exceção `*.cmd`/`*.bat` com `eol=crlf`, `gradlew` com LF e bit executável, e `git config core.longpaths true` por causa de `node_modules` profundos.
- **Mensagens de commit (ponto aberto)**: o squash do Bolt usa o slug como nome do commit. Fora dos Bolts, adotar ou não Conventional Commits (`feat:`, `fix:`)? Não é obrigatório para uma pessoa, mas facilita gerar changelog para as tags de release.

### 8. Perguntas concretas para a entrevista

Formuladas na linguagem da pessoa, para o líder encaixar em `## Code Style` e `## Way of Working`:

1. O código, as tabelas e os endpoints ficam em inglês e só os textos da tela em português? (A. Sim; B. Tudo em português; X. Other (please specify))
2. Cada módulo do backend (Ledger, Risk, Execution etc.) só pode falar com outro pela "porta da frente" dele (API pública ou evento), nunca lendo as tabelas do outro? E isso deve ser checado por um teste automático na CI? (A. Sim, com teste na CI; B. Sim, só como convenção por enquanto; C. Não agora; X. Other (please specify))
3. Para dinheiro, quantidades de token e P&L, fica proibido usar número com ponto flutuante, usando sempre decimal exato ou inteiros em unidades base? (A. Sim, como regra rígida; B. Sim, como convenção; X. Other (please specify))
4. Os erros da API seguem o formato RFC 7807 (`ProblemDetail`) do PRD, com um código de erro estável e sem stack trace na resposta? (A. Sim; X. Other (please specify))
5. Gradle ou Maven para o backend? (A. Gradle; B. Maven; C. Decidir no design de aplicação; X. Other (please specify))
6. npm ou pnpm para o frontend? (A. npm; B. pnpm; C. Decidir depois; X. Other (please specify))
7. Os workers (ingestão, análise, execução, reconciliação) rodam o mesmo código do backend em processos separados, ou são aplicações separadas? (A. Mesmo código, processos separados; B. Aplicações separadas; C. Decidir no design de aplicação; X. Other (please specify))
8. Os tipos do frontend são gerados a partir do contrato OpenAPI do backend? (A. Sim, a partir do código do backend; B. Sim, com o contrato escrito primeiro; C. Não; X. Other (please specify))
9. Nomes de arquivo no frontend em `kebab-case` para evitar erro de maiúscula/minúscula entre Windows e CI? (A. Sim; B. PascalCase com regra de lint; X. Other (please specify))
10. Usar o padrão Conventional Commits (`feat:`, `fix:`) nos commits fora dos Bolts? (A. Sim; B. Não; X. Other (please specify))

### 9. Observação de escopo

Ferramentas como Spring Modulith/ArchUnit, Flyway/Liquibase e Gradle/Maven tocam decisões de arquitetura. Pela regra da Inception, essas decisões exigem análise de alternativas em ADR. Sugiro que a entrevista afirme apenas a **prática** (por exemplo, "fronteira de módulo verificada na CI", "migrações versionadas no repositório") e deixe a escolha da ferramenta para o design de aplicação quando a pessoa responder C nas perguntas 5 a 8. Isso também respeita C-11 (experiência com a stack ainda não mapeada).

## Positions

- AGREE: Trunk-based com squash-merge por Bolt é adequado a um desenvolvedor solo e mantém o histórico ligado à sequência de Bolts.
- AGREE: Decidir `master` x `main` antes do primeiro commit, já que hoje o custo de renomear é praticamente zero.
- AGREE: Seguir a configuração do linter/formatador do repositório como princípio, com Prettier e ESLint no frontend.
- AGREE: `.editorconfig` e `.gitattributes` com LF e UTF-8 desde o primeiro commit no Windows; detalhes concretos na seção 7.
- AGREE: Monorepo com frontend e backend juntos, conforme o PRD Sprint 1.
- OBJECT: `## Code Style` não trata de fronteiras entre módulos e camadas do monólito modular nem de tratamento de erros; sem isso, a convenção mais importante do backend fica fora da entrevista (seções 1 e 3).
- OBJECT: `discovered-rules.md` não traz candidata sobre precisão numérica de dinheiro e quantidades de token, embora o PRD §10 exija precisão decimal e P&L determinístico (seção 4).
- OBJECT: "CI deve rodar só os passos afetados por cada mudança" é otimização prematura para um repositório vazio de uma pessoa; sugiro rodar tudo até o tempo de CI justificar o contrário.
- OBJECT: Spotless com formatador e Checkstyle listados juntos sem delimitar o papel de cada um tende a gerar regras conflitantes; o Checkstyle deve cobrir só o que o formatador não cobre (seção 7).
