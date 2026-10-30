**Collaborator:** aidlc-quality-agent

## Contribution

Revisão independente do rascunho do líder com foco em postura de testes, ferramentas de cobertura, portões de qualidade na CI, padrões de teste e lacunas para a entrevista. Projeto greenfield: nada abaixo foi observado em código. Cada item é **sugestão** ou **ponto aberto**, com a origem indicada.

### 1. Piso de cobertura: deixar a regra verificável

O rascunho registra "80% de cobertura de linhas" (correto, padrão `enterprise` do `org.md`). Do jeito que está, porém, ainda não dá para dizer se uma Bolt passou ou falhou. Faltam definições que a entrevista precisa fechar:

- **Onde o piso vale**: por módulo (backend e frontend separados) ou só no agregado. Sugestão: por módulo. Assim um backend bem coberto não esconde um frontend sem testes, ou o contrário.
- **Exclusões**: listar de forma explícita e versionada o que fica fora da conta (código gerado, como um cliente TypeScript gerado de OpenAPI; classes de configuração; `main`). Só vale exclusão que esteja na configuração da ferramenta, nunca uma decidida na hora para fazer a etapa passar (`org.md`: o piso não pode ser enfraquecido).
- **Aplicação pela ferramenta**: o piso precisa falhar o build sozinho, e não só aparecer num relatório. Candidatos: regra `check` do JaCoCo no Maven/Gradle e `coverage.thresholds` no Vitest, ou `coverageThreshold` no Jest.
- **"Sem regressão"**: o rascunho põe "sem regressão" dentro do bullet "padrão do escopo `enterprise`", mas o `org.md` não traz esse requisito. É uma regra mais rígida que o padrão (uma catraca: a cobertura nunca cai em relação ao tronco). É legítima, mas precisa aparecer como **adição a confirmar**, e não atribuída ao escopo.
- **Módulos críticos com piso mais alto (sugestão)**: motor de risco/limites, ledger e cálculo de P&L, deduplicação por idempotência, filtro de tenant e o tratamento de `unknown`. Proposta: 90% de linhas mais cobertura de ramos (branch), ou teste de mutação seletivo (PIT no Java) só nesses pacotes. Cobertura de linha sozinha não prova que um limite de risco foi testado nas bordas.

### 2. Metodologia: oferecer `custom` como opção concreta

Como sugestão padrão, test-after está correto (é o default do `org.md`). Mesmo assim, recomendo que a entrevista apresente três opções concretas, porque o domínio tem invariantes financeiros determinísticos e o código é escrito com apoio de IA:

- **A. test-after** (padrão): implementar a camada, depois escrever e rodar os testes dela.
- **B. tdd no núcleo de domínio**: teste primeiro para risco/limites, ledger, P&L e idempotência; test-after no restante (UI, adaptadores, configuração). No campo estruturado, isso vira `custom`.
- **C. bdd + test-after**: cenários Given/When/Then das histórias da Inception automatizados como testes de aceitação **antes** da implementação; testes unitários depois. Também vira `custom`.

Se a resposta for B ou C, o campo `Methodology` precisa ser `custom` e o `Ordering` precisa dizer, numa frase, o que vem antes e o que vem depois (regra do estágio). Justificativa para B: código gerado com IA tende a "passar" em testes escritos depois sobre o próprio código. Com o teste escrito primeiro a partir da regra de negócio, o teste valida o requisito e não a implementação.

Independentemente da metodologia, sugiro uma regra fixa: **todo defeito corrigido ganha um teste que o reproduz antes da correção**.

### 3. Testes obrigatórios por risco: completar a lista

A lista do rascunho é boa. Faltam itens que já estão no escopo aprovado e no PRD:

- **Pausa e encerramento do bot**: depois de pausar, nenhuma nova operação simulada é criada (scope-document, critérios; PRD US08).
- **Ledger append-only**: uma tentativa de UPDATE ou DELETE em lançamento falha, e a correção só acontece por lançamento compensatório (PRD §13).
- **Precisão do P&L em cenários determinísticos** (métrica beta do PRD): um conjunto fixo de cenários com resultado esperado calculado à mão (golden files), usando aritmética decimal e unidades de token, nunca ponto flutuante.
- **REAL desligado por padrão**: um teste falha se algum caminho de código assinar ou enviar transação com a configuração padrão (C-01, C-02).
- **Nada de dados futuros**: só a partir do backtesting (PRD §5, Sprint 3). Registrar agora como obrigatório quando o módulo existir.

Sugestão de forma: transformar o isolamento entre tenants numa **suíte reutilizável e parametrizada** que percorre todos os endpoints e repositórios de dados de cliente (um usuário do tenant A tenta ler e escrever dados do tenant B e recebe negação), mais uma regra de arquitetura automatizada (por exemplo, ArchUnit) que exige `tenant_id` nas consultas de dados de cliente. Um teste negativo escrito à mão por endpoint esquece endpoints novos.

Técnicas adequadas aos invariantes (sugestão): testes baseados em propriedades (por exemplo, jqwik) para "repetir uma operação com a mesma chave de idempotência não muda o estado", "nenhuma sequência de ordens ultrapassa o limite" e "a soma do ledger reconcilia com as posições".

### 4. Determinismo e dados de teste

Ponto ausente no rascunho e decisivo para uma CI confiável:

- Relógio injetável, sem `now()` direto no domínio; seeds fixas; nenhum acesso de rede real nos testes. Os adaptadores de mercado usam fixtures gravadas (respeita C-06: nenhum fornecedor escolhido).
- Uma **suíte de conformidade do adaptador de dados de mercado** que roda contra o mock hoje e contra o fornecedor real depois do G0. Ela garante que campo ausente vira `unknown` e que fonte e timestamp estão sempre presentes.
- Dados de teste construídos a partir dos tipos e esquemas reais (builders/factories). Testes de integração usam as mesmas migrações de banco da aplicação, sobre PostgreSQL real via Testcontainers, e nunca dependem de estado compartilhado ou da ordem de execução.
- Nunca usar carteira, mint ou dados pessoais reais em fixtures.
- **Política de teste instável (flaky)**: teste instável é tratado como defeito. Ou é corrigido, ou fica em quarentena com prazo e registro; nunca é ignorado em silêncio.

### 5. Contrato entre frontend e backend

O monorepo Next.js + Spring Boot pede um contrato explícito. Sugestão: OpenAPI como fonte da verdade, com um teste no backend que valida as respostas contra o esquema e um cliente TypeScript gerado no frontend. Assim, uma quebra de contrato falha a CI antes do e2e. Pact é opcional e provavelmente pesado demais para uma pessoa só.

### 6. Acessibilidade básica como portão (lacuna)

O `project.md` (Corrections, 2026-10-26) exige no beta navegação por teclado, foco visível, rótulos visíveis, contraste legível no tema escuro e cor sempre acompanhada de texto ou ícone. O rascunho não menciona isso em Testing Posture. Sugestão: checagem automática com axe (via Playwright ou testing-library) nas telas do fluxo principal, como portão da CI, e um teste de navegação por teclado no fluxo principal. A verificação completa de WCAG 2.1 AA fica para antes da abertura ao público, como diz a correção.

### 7. Portões de CI: lista concreta e ordem sugerida

Para cada mudança, em ordem: (1) build; (2) formatação e lint (ver devsecops/developer); (3) testes unitários com piso de cobertura aplicado; (4) testes de integração com Testcontainers; (5) contrato OpenAPI; (6) a11y automatizado; (7) smoke e2e com Playwright sobre o Docker Compose, só nos fluxos críticos: convite → descoberta → avaliação → PAPER → P&L. Qualquer falha bloqueia o merge.

Pirâmide sugerida: cerca de 70% unitários, 20% integração e 10% e2e, com no máximo 20 a 30 cenários e2e. Mais do que isso indica falta de testes de integração.

**Divergência Windows/CI (ponto aberto técnico)**: o rascunho diz que os testes rodam "no Windows local com Docker e também na CI". Os runners Linux de CI hospedada (por exemplo, GitHub Actions `ubuntu-latest`) rodam containers Linux sem esforço. Já os runners Windows hospedados não oferecem bem containers Linux, então Testcontainers e Compose ficam na prática restritos a runners Linux. No Windows local, Testcontainers depende do Docker Desktop com WSL2. Proposta: a CI roda em Linux, o local roda em Windows, e o fim de linha LF via `.gitattributes` (já levantado pelo líder) passa a ser requisito para os testes darem o mesmo resultado nos dois ambientes.

**Orçamento (C-12)**: a CI hospedada gratuita tem cota limitada de minutos para repositório privado. Confirmar a cota vigente do provedor escolhido. Rodar e2e e Testcontainers em todo push pode consumir essa cota. Alternativa: e2e completo só no merge para o tronco ou numa execução noturna, mantendo o smoke em toda mudança.

### 8. Comando de verificação do esqueleto

Sugestão concreta para a pessoa autorizar: um único comando, de preferência um script versionado no repositório que funcione em PowerShell e em Linux. Ele sobe o Compose, espera os healthchecks (`docker compose up -d --wait`), roda o smoke Playwright que atravessa navegador → Next.js → API → PostgreSQL com o adaptador mock e verifica a exibição de fonte, timestamp e `unknown`, e no fim desmonta o ambiente. O comando precisa retornar código diferente de zero em qualquer falha.

### 9. NFR e desempenho: deixar explícito que ainda não há portão

O PRD não fixa limites numéricos de latência nem de disponibilidade; diz apenas "medida por provedor". Sem um número não existe teste de carga com passa/falha. Sugestão de registro: "testes de desempenho e carga ficam para depois que os NFRs definirem metas numéricas; até lá, nenhum portão de desempenho". Por causa da correção do `project.md` sobre cobertura e frescor dos dados, o frescor do dado (idade máxima aceitável por fonte) é o primeiro NFR que merece meta e teste.

### 10. Perguntas concretas para a entrevista

1. A regra de 80% de cobertura vale para backend e frontend separadamente ou só para o total? Pode haver exclusões, desde que fiquem listadas na configuração?
2. A cobertura pode cair em relação ao tronco, desde que continue acima de 80%, ou nunca pode cair?
3. Os módulos de risco/limites, ledger/P&L, idempotência e isolamento de tenant devem ter uma exigência mais alta? Opções: 90% de linhas + ramos; teste de mutação só nesses módulos; nenhuma exigência extra.
4. Como você prefere escrever os testes? A. depois de cada camada; B. teste primeiro no núcleo financeiro e depois no resto; C. cenários Given/When/Then automatizados antes e testes unitários depois.
5. Confirma os testes obrigatórios por risco, incluindo os acrescentados aqui: pausa impede novas operações, ledger sem edição, P&L com cenários de resultado conhecido, REAL desligado por padrão?
6. Topa que a checagem automática de acessibilidade básica bloqueie o merge?
7. A CI roda em Linux e o desenvolvimento local em Windows: aceita esse arranjo? Os testes de ponta a ponta completos rodam em toda mudança ou só no merge/à noite, para economizar a cota gratuita?
8. Teste instável: corrigir na hora, ou permitir quarentena com prazo?
9. Autoriza como comando de verificação do esqueleto um script único que sobe o ambiente, roda o smoke de ponta a ponta e devolve erro em qualquer falha?
10. Ferramentas: JUnit 5 + Testcontainers + JaCoCo no backend; Vitest (sugerido pela velocidade e integração com TypeScript) ou Jest no frontend; Playwright no e2e. Confirma ou prefere outras?

## Positions

- AGREE: test-after como valor sugerido padrão do `Methodology`, com a evidência marcada como default do `org.md`.
- AGREE: piso de 80% de linhas com CI antes do merge, somado à Test Strategy `Comprehensive` e sem substituí-la.
- AGREE: lista inicial de testes por risco (tenant, REAL/PAPER, idempotência, limites, `unknown`, reconciliação).
- AGREE: fatia candidata do esqueleto com adaptador mock e nenhum fornecedor real, e comando de verificação deixado para a pessoa autorizar.
- AGREE: ferramentas candidatas citadas e marcadas como não escolhidas.
- OBJECT: "sem regressão" aparece atribuído ao padrão do escopo `enterprise`, mas o `org.md` não traz essa regra; deve ser apresentado como adição a confirmar.
- OBJECT: o piso de cobertura não define escopo (por módulo ou agregado), exclusões nem aplicação pela ferramenta, então não dá para dizer se uma Bolt passou ou falhou.
- OBJECT: falta a acessibilidade básica exigida pela correção do `project.md` como verificação automatizada em Testing Posture.
- OBJECT: a lista de testes por risco omite pausa/kill switch, ledger append-only, precisão determinística do P&L e REAL desligado por padrão, que já estão no escopo aprovado e no PRD.
- OBJECT: "rodar no Windows local e na CI" ignora que containers Linux na CI exigem runners Linux; a divergência e a dependência de LF via `.gitattributes` precisam virar ponto aberto explícito.
- OBJECT: a entrevista deve oferecer `custom` (TDD no núcleo financeiro ou BDD + test-after) como opção concreta, e não só citar TDD/BDD em aberto.
