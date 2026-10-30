# Requisitos — MemeTrade Analytics (beta PAPER por convite)

**Data:** 26/10/2026  
**Fontes:** pedido inicial (`PromptInicial.txt`), PRD v1.3, [Intent Statement](../../ideation/intent-capture/intent-statement.md), [Scope Document](../../ideation/scope-definition/scope-document.md), [Intent Backlog](../../ideation/scope-definition/intent-backlog.md), [Feasibility Assessment](../../ideation/feasibility/feasibility-assessment.md), [Constraint Register](../../ideation/feasibility/constraint-register.md), [Wireframes](../../ideation/rough-mockups/wireframes.md), [Team Practices](../practices-discovery/team-practices.md) e as respostas em [requirements-analysis-questions.md](requirements-analysis-questions.md) (Q1 a Q9).

## Análise da intenção

O pedido inicial e o PRD descrevem um SaaS completo: scanner multi-DEX, IA, bots REAL e PAPER, risco, P&L e assinaturas. As decisões da Ideação recortaram o primeiro passo. A prioridade é validar com traders convidados no Brasil se descoberta e avaliação de tokens de Solana com dados transparentes, somadas a uma simulação PAPER com P&L reconciliável, resolvem uma dificuldade real ([Intent Statement](../../ideation/intent-capture/intent-statement.md), D-01, D-05). O sucesso inicial é medido por integridade: dados com origem e horário, operações sem duplicidade, limites respeitados e P&L reconciliável. Metas de uso só depois do discovery.

O que isto significa para os requisitos:

- **Classificação do pedido:** produto novo, multicomponente, complexidade alta (domínio financeiro, multiusuário, integrações externas e regulação). Profundidade abrangente.
- **Beta (compromisso):** acesso por convite, scanner e avaliação rastreáveis, bot PAPER com regras determinísticas, ledger e P&L PAPER, isolamento, permissões, auditoria e área administrativa mínima (Must do escopo, PB-00 a PB-04).
- **Depois de validar o núcleo:** filtros salvos, alertas, métricas avançadas, planos (Should, PB-05 a PB-07).
- **Bloqueado por gate:** REAL e delegação. Ficam documentados para rastreabilidade, com uma prova de conceito da delegação na Sprint 0 só em devnet (Q1).
- **Fora do produto:** alavancagem, derivativos, outras chains, copy trading, custódia, saques administrados e manipulação.

Prioridade: **Must** = primeiro beta; **Should** = após validar o núcleo; **Gated** = bloqueado até G2/G3 e aprovação explícita; **Discovery** = entrega da Sprint 0.

## Requisitos funcionais

### FR1 — Acesso por convite e identidade (PB-01, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR1.1 | O sistema deve aceitar o cadastro apenas com um convite válido, de uso único e com validade definida. | Dado um convite expirado, revogado ou já usado, quando a pessoa tenta entrar, então o acesso é negado com mensagem que diz o motivo e nenhuma conta é criada. | PRD §3, US01; escopo Must |
| FR1.2 | O sistema deve exigir o aceite dos termos de uso e do aviso de risco antes do primeiro acesso e registrar a versão aceita com data e hora. | Dado um convite válido, quando os termos não foram aceitos, então a conexão de carteira fica indisponível; após o aceite, a versão e o horário ficam registrados na trilha de auditoria. | PRD §3; wireframes S1; C-16 |
| FR1.3 | O sistema deve autenticar por assinatura de uma mensagem que contenha domínio, nonce de uso único, expiração e chain. | Dado um nonce já usado ou uma assinatura expirada, quando é enviada para verificação, então é rejeitada e o evento é auditado. | PRD §7, §16 Wallet |
| FR1.4 | A assinatura de login não deve conceder nenhuma autorização de operação. | Dada uma sessão criada por login, quando qualquer rota de autorização de trading é chamada só com ela, então a chamada é recusada. | C-02; regra rígida do projeto |
| FR1.5 | O sistema deve vincular carteiras públicas Phantom e Solflare com prova de posse, sem nunca solicitar seed phrase ou chave privada. | Dado o fluxo de vínculo, quando concluído, então só o endereço público e o provedor ficam armazenados; nenhuma tela, campo ou API aceita frase secreta ou chave privada. | PRD §7, US02; C-02 |
| FR1.6 | O sistema deve manter sessões com expiração e permitir sair e desconectar a carteira. | Dada uma sessão expirada no meio de um fluxo, quando a pessoa entra de novo, então volta ao ponto e o rascunho de bot é preservado. Ao desconectar a carteira, os bots PAPER ativos dela são pausados após confirmação. | Wireframes S7, F6 |

### FR2 — Contas, permissões e auditoria (PB-01, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR2.1 | Toda leitura e escrita de dados de cliente deve ser filtrada pelo tenant obtido da autenticação, nunca do corpo da requisição. | Dado um usuário do tenant A, quando ele tenta ler ou alterar por ID um recurso do tenant B (inclusive por tarefa em segundo plano), então recebe 403 ou 404 e nenhum dado de B. | PRD §3, §11, §16 SaaS; regra rígida |
| FR2.2 | O sistema deve aplicar papéis: usuário beta (opera só suas carteiras e bots) e administrador (convites, kill switch e estado do sistema). | Dado um administrador, quando ele tenta ver as operações ou ledger de um usuário além de metadados mínimos, então o acesso é negado. Administradores nunca assinam ou operam por usuários. | PRD §3 RBAC |
| FR2.3 | O sistema deve registrar eventos de auditoria para ações sensíveis com ator, ação, tenant, horário UTC e identificador de correlação. | Dado login, vínculo de carteira, aceite de termos, criação, início, pausa ou encerramento de bot, ação administrativa ou ordem recusada, quando ocorre, então existe exatamente um evento de auditoria correspondente. | PRD §11, US15; escopo Must |

### FR3 — Área administrativa mínima (Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR3.1 | O administrador deve poder emitir, listar e revogar convites. | Dado um convite revogado, quando usado, então o acesso é negado (FR1.1). | Q5; PRD US14 |
| FR3.2 | O administrador deve poder acionar e desligar um kill switch global que pausa todos os bots PAPER. | Dado o kill switch acionado, quando qualquer bot tentaria uma nova operação simulada, então nenhuma operação é criada e os bots aparecem como pausados pelo sistema. | Q5; PRD §9, US08 |
| FR3.3 | Toda ação administrativa deve ser auditada. | Dada qualquer ação de FR3.1 ou FR3.2, quando executada, então existe um evento de auditoria com o administrador como ator. | Q5; FR2.3 |

### FR4 — Dados de mercado (PB-02, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR4.1 | Cada fonte de dados deve ficar atrás de um adaptador com contrato comum, com uma implementação simulada para o ambiente local. | Dado o ambiente local sem fornecedor contratado, quando o scanner roda, então funciona com dados simulados e mostra a fonte como simulada. | C-06; team-practices (Walking Skeleton) |
| FR4.2 | Tokens devem ser identificados por chain + mint, e pools por identificador próprio. | Dados dois tokens com o mesmo símbolo e mints diferentes, quando listados, então aparecem como itens distintos. | PRD §4, §13 |
| FR4.3 | Todo campo de mercado exibido deve trazer fonte, horário de observação (UTC), frescor e cobertura. | Dado qualquer valor no scanner ou no detalhe, quando inspecionado, então a fonte e o horário estão visíveis. | PRD §4; C-05; regra rígida |
| FR4.4 | Dado ausente deve ser representado como `unknown`, nunca como zero. | Dada uma fonte que não informa holders, quando o token é exibido, então o campo mostra `unknown` e os filtros não o tratam como zero. | PRD §4; C-05; regra rígida |
| FR4.5 | Dados com mais de 5 minutos devem aparecer como desatualizados. | Dado um valor observado há 6 minutos, quando exibido, então aparece marcado como desatualizado, com o horário da última atualização. | Q8 |
| FR4.6 | A falha de uma fonte deve degradar só o que depende dela. | Dada a fonte B indisponível, quando o scanner carrega, então os tokens da fonte A continuam visíveis e um aviso nomeia a fonte B e o horário da última atualização. | Corner checklist; wireframes S2, S3 |
| FR4.7 | Divergências entre fontes para o mesmo campo devem ser sinalizadas. | Dados dois valores de liquidez de fontes diferentes acima de uma tolerância configurada, quando exibidos, então o campo mostra as duas origens e o aviso de divergência. | Feasibility (dados); RAID R-05 |

### FR5 — Scanner (PB-02, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR5.1 | O scanner deve listar tokens paginados com ordem determinística (critério escolhido e desempate estável). | Dados dois tokens com o mesmo valor no critério de ordenação, quando a página é recarregada, então a ordem entre eles não muda. | PRD §14 (paginação cursor); corner checklist |
| FR5.2 | O scanner deve oferecer filtros combináveis por idade, liquidez, volume, holders, indicador de risco e origem. | Dados filtros de liquidez mínima e idade máxima, quando aplicados juntos, então só aparecem tokens que atendem os dois; tokens com o campo `unknown` ficam fora do filtro e um contador informa quantos foram excluídos por dado ausente. | PRD §4, US03; wireframes S3 |
| FR5.3 | O scanner deve distinguir os estados vazio, carregando, erro e parcial. | Dados filtros sem resultado, quando aplicados, então a tela explica e oferece "Limpar filtros". | Wireframes S3 |
| FR5.4 | Selecionar um token deve abrir o detalhe e a avaliação sem perder a posição e os filtros da lista. | Dada uma linha selecionada, quando o detalhe é fechado, então a lista mantém posição, filtros e foco. | Q4 dos mockups; wireframes S3 |

### FR6 — Avaliação e indicador de risco (PB-02, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR6.1 | O sistema deve calcular um indicador de risco de 0 a 100 (maior = mais risco) a partir de fatores ponderados configuráveis, rotulado na tela como indicador em validação. | Dado um token com cobertura suficiente, quando avaliado, então o indicador, os fatores, seus pesos e a versão dos pesos ficam visíveis. | PRD §9; escopo (pesos são hipótese); revisão dos mockups R-04 |
| FR6.2 | O indicador deve ficar indisponível quando a cobertura for insuficiente, e `unknown` nunca deve ser tratado como seguro. | Dado um token com cobertura abaixo do mínimo configurado, quando avaliado, então o indicador aparece como indisponível com o motivo, e a avaliação é NO_TRADE. | PRD §9; C-16; regra rígida |
| FR6.3 | Cada avaliação deve listar os dados usados, com fonte e horário. | Dada uma avaliação, quando aberta, então cada fator mostra o valor de entrada, a fonte e o horário. | PRD §5, US07 |
| FR6.4 | Nenhuma tela ou texto deve prometer lucro ou garantir detecção de fraude. | Dados os textos de interface, quando revisados, então não contêm promessa de retorno nem garantia de fraude. | C-16; regra rígida |

### FR7 — Estratégias e bot PAPER (PB-03, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR7.1 | O sistema deve oferecer 1 ou 2 estratégias modelo com parâmetros ajustáveis. | Dado o assistente, quando a pessoa escolhe uma estratégia modelo, então vê os parâmetros com valores padrão e limites aceitos. | Q3 |
| FR7.2 | O bot deve ser criado por um assistente em 4 etapas (seleção de tokens, limites, revisão e início), sem perder dados ao voltar entre etapas. | Dada a etapa 3, quando a pessoa volta à etapa 1, então os dados das etapas 1 e 2 continuam preenchidos. | Wireframes S4; mockups Q2 |
| FR7.3 | O bot deve ter limites obrigatórios: tamanho máximo por ordem, exposição por token, exposição total, perda diária máxima, drawdown máximo, slippage máximo, liquidez mínima e intervalo entre operações. | Dados limites incoerentes (exposição por token maior que a total), quando a pessoa tenta continuar, então o campo indica o erro e a etapa não avança. | PRD §6, §9; US05 |
| FR7.4 | O saldo virtual de cada bot é definido na criação, dentro de um teto configurado pela plataforma, e nunca migra para REAL. | Dado um saldo acima do teto, quando informado, então é recusado com o teto exibido. Nenhuma operação permite transferir saldo PAPER para fora do modo. | Q4; PRD §6; regra rígida |
| FR7.5 | O bot segue os estados DRAFT → VALIDATED → ACTIVE → PAUSED/STOPPED, com rascunho salvo. | Dado um assistente abandonado, quando a pessoa volta, então o rascunho aparece em Bots PAPER. Dada uma transição inválida (STOPPED → ACTIVE), quando solicitada, então é recusada. | PRD §6; wireframes F3 |
| FR7.6 | Cada convidado pode ter até 3 bots PAPER ativos ao mesmo tempo. | Dados 3 bots ativos, quando a pessoa tenta ativar o quarto, então a ativação é recusada com o limite exibido. | Q7 |
| FR7.7 | A lista de bots deve mostrar todos os bots do usuário por estado, com acesso a rascunhos, pausados e encerrados. | Dado um rascunho salvo, quando a pessoa abre Bots PAPER, então o rascunho aparece com a ação de retomar. | Revisão dos mockups R-02; aprovação-handoff D-34 |
| FR7.8 | Editar limites de um bot ativo exige revisão dos limites e vale só para operações futuras, com o histórico de versões registrado. | Dado um bot ativo, quando os limites são editados e confirmados, então novas decisões usam a nova versão e o ledger mostra que versão vigorava em cada operação. | Revisão dos mockups R-03 |

### FR8 — Decisão determinística e controles de risco no PAPER (PB-03, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR8.1 | O bot decide por regras determinísticas e produz uma decisão estruturada: token, ação (BUY, SELL, HOLD ou NO_TRADE), versão da estratégia, fatores, dados de entrada com horário e validade. | Dados os mesmos dados de entrada e a mesma versão da estratégia, quando a decisão é recalculada, então o resultado é idêntico. | Q2; PRD §5 |
| FR8.2 | NO_TRADE é o padrão quando faltam dados, os dados estão desatualizados ou a avaliação está indisponível. | Dado um token com dado desatualizado, quando o bot avalia, então decide NO_TRADE e registra o motivo. | PRD §5; FR4.5; FR6.2 |
| FR8.3 | Antes de cada ordem simulada, o sistema verifica limite por ordem, exposição, perda diária, drawdown, slippage, liquidez mínima, frescor do dado, intervalo entre operações e duplicidade. | Dada uma ordem que excederia qualquer limite, quando avaliada, então é recusada, nenhuma operação é criada e a recusa aparece com o limite que a barrou. | PRD §9; C-04; regra rígida |
| FR8.4 | Ordens simuladas são deduplicadas por chave de idempotência. | Dada a mesma decisão processada duas vezes, quando registrada, então existe uma única operação. | C-04; PRD §6; regra rígida |
| FR8.5 | Ao atingir a perda diária máxima, o bot é pausado automaticamente até o dia seguinte (UTC) ou até nova ação da pessoa. | Dada uma perda acumulada igual ao limite, quando a próxima decisão ocorre, então nenhuma operação é criada e o bot aparece pausado pelo limite. | PRD §6 (circuit breakers) |
| FR8.6 | O modelo de sinal com IA fica fora do beta até existir backtest point-in-time e avaliação fora da amostra. | Dada a configuração do beta, quando inspecionada, então nenhuma decisão de bot depende de modelo de IA. | Q2; PRD §5, §16 IA |

### FR9 — Simulação PAPER (PB-03, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR9.1 | Cada preenchimento simulado deve considerar spread, impacto de preço, taxas, slippage e liquidez, e registrar as hipóteses usadas e a versão da política de simulação. | Dada uma operação simulada, quando aberta, então mostra o preço de referência, a cotação usada, o slippage aplicado, as taxas e a versão da política. | PRD §8; RAID R-04; feasibility condição 5 |
| FR9.2 | O modo PAPER nunca assina nem envia transações. | Dada a configuração padrão, quando qualquer bot PAPER opera, então nenhuma transação é construída para assinatura ou envio. | Escopo; regra rígida |
| FR9.3 | Toda tela de bot e de P&L PAPER exibe o rótulo persistente de modo simulado. | Dada qualquer tela S4 a S6, quando renderizada, então o rótulo "MODO PAPER (simulado)" aparece em texto no título. | PRD §10; wireframes |

### FR10 — Ledger, posições e P&L PAPER (PB-04, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR10.1 | O ledger PAPER é append-only, e correções acontecem só por lançamento compensatório. | Dada uma tentativa de alterar ou apagar um lançamento, quando executada, então falha. | PRD §13; regra rígida |
| FR10.2 | Posições usam custo médio ponderado com política explícita para taxas, em aritmética decimal e unidades de token. | Dado um conjunto de cenários determinísticos de resultado conhecido, quando calculados, então os resultados coincidem exatamente com os esperados. | PRD §10; regra rígida (sem ponto flutuante) |
| FR10.3 | O P&L separa realizado, não realizado e taxas, por token, bot e período. | Dado um período, quando a pessoa filtra por bot, então os três valores aparecem separados e a soma por token é igual ao total. | PRD §10, US06; wireframes S6 |
| FR10.4 | A marcação a mercado mostra fonte e horário, e preços sem liquidez são sinalizados. | Dado um token sem liquidez, quando marcado a mercado, então o valor aparece sinalizado. | PRD §10 |
| FR10.5 | A reconciliação entre ordens simuladas e ledger fica visível e divergências nunca são escondidas. | Dada uma divergência, quando a tela de P&L abre, então aparece o aviso com a quantidade e o detalhe das operações divergentes. | PRD §16 Paper; US11 |
| FR10.6 | Dados PAPER ficam isolados de qualquer dado REAL em fundos, ordens, ledger, posições e P&L. | Dada qualquer consulta PAPER, quando executada, então não retorna nem considera registros REAL. | PRD §8; C-03; regra rígida |

### FR11 — Controles do bot (PB-03, Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR11.1 | Pausar um bot impede novas operações simuladas imediatamente. | Dado um bot pausado, quando chega uma nova decisão, então nenhuma operação é criada. | PRD US08; escopo (critérios) |
| FR11.2 | Encerrar um bot exige confirmação que descreve o que acontece com as posições simuladas abertas; as posições são fechadas pela simulação ao preço de referência vigente e registradas no ledger. | Dado um bot com posições abertas, quando encerrado, então a confirmação lista as posições e, após confirmar, existem lançamentos de fechamento no ledger. | Revisão dos mockups R-03; wireframes S5 |
| FR11.3 | Bots PAPER continuam rodando no servidor independentemente da sessão da pessoa; só param por pausa, encerramento, limite, desconexão da carteira ou kill switch. | Dada uma sessão expirada, quando o bot está ativo, então continua operando e o estado é exibido ao entrar de novo. | Revisão dos mockups R-03; PRD §12 (execução fora do navegador) |

### FR12 — Painel (Must)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR12.1 | O painel é a primeira tela após o login e mostra o resumo dos bots PAPER, o P&L simulado e avisos de dados. | Dado um usuário sem bots, quando entra, então o painel mostra o estado vazio com atalhos para o scanner e para criar bot. | Mockups Q1; wireframes S2 |
| FR12.2 | Os valores do painel se atualizam sem recarregar a página e mostram o horário da última atualização. | Dado um bot que opera, quando a operação é registrada, então o painel reflete a mudança dentro do limite de frescor (NFR1). | PRD §10, §14 (eventos) |

### FR13 — Feedback do beta (Should)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR13.1 | O convidado deve ter um ponto de envio de feedback acessível no menu, que registre a tela de origem e o horário. | Dado um feedback enviado, quando salvo, então fica associado ao tenant, à tela e ao horário, visível só ao administrador. | Fluxo de valor do escopo; revisão dos mockups R-01 |

### FR14 — Discovery e Gate G0 (PB-00, Discovery)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR14.1 | Comparar duas fontes candidatas, só para leitura, quanto a campos, cobertura, frescor observado, divergência, quotas, custo projetado e termos de exibição e redistribuição. | Existe um relatório comparativo com medições observadas e, para cada fonte, a decisão aprovada ou não aprovada com motivo. | Feasibility condição 1; C-06; D-15 |
| FR14.2 | Realizar entrevistas e tarefas acompanhadas com convidados no ambiente local para validar a dificuldade, os critérios do scanner e a utilidade da avaliação. | Existem registros de tarefas com exemplo, ferramenta atual, fricção e resultado esperado, e uma conclusão sobre a hipótese. | Escopo Sprint 0; Q6; D-09 |
| FR14.3 | Inventariar dados pessoais e de carteira, finalidades e retenção mínima proposta para revisão jurídica. | Existe o inventário com categoria, finalidade, base legal proposta e prazo proposto para cada dado. | C-07, C-08; D-18 |
| FR14.4 | Mapear a capacidade na stack e produzir estimativas para a decisão de orçamento. | Existe uma estimativa revisada e um pedido de orçamento para a sua decisão. | C-11, C-12; D-33 |

### FR15 — REAL e delegação (Gated)

| ID | Requisito | Critério de aceite | Origem |
|---|---|---|---|
| FR15.1 | Prova de conceito de delegação na Sprint 0, só em devnet, sem fundos reais e sem implantar nada em mainnet, avaliando limites agregados, expiração, revogação e compatibilidade com os swaps pretendidos. | Existe um relatório que diz, para cada critério, se foi demonstrado, com evidência em devnet. Nenhuma transação em mainnet foi feita. | Q1; pedido inicial; PRD §7, §17 Sprint 0 |
| FR15.2 | REAL fica desabilitado por padrão e sem caminho de assinatura na configuração padrão. | Dada a configuração padrão, quando inspecionada e testada, então nenhuma rota, job ou tela permite operação REAL. | C-01; regra rígida |
| FR15.3 | Autorização REAL limitada e revogável, execução automática dentro da autorização, reconciliação on-chain e comparação REAL x PAPER (PRD US09 a US12) ficam registradas como requisitos bloqueados até G2/G3, revisão independente, análise jurídica e sua aprovação explícita. | Nenhum desses requisitos é implementado antes da aprovação do gate correspondente. | Q1; PRD §7, §8, §16; team-practices (Deployment) |

## Requisitos não funcionais

| ID | Categoria | Requisito | Critério mensurável | Origem |
|---|---|---|---|---|
| NFR1 | Frescor de dados | Dados do scanner atualizados com idade máxima de 60 s; dado com mais de 5 min aparece como desatualizado. | Em medição de 1 hora com a fonte operante, 95% dos valores exibidos têm idade ≤ 60 s; 100% dos valores com idade > 5 min aparecem marcados. Meta final confirmada no G0. | Q8 |
| NFR2 | Capacidade | O sistema sustenta 20 convidados e 60 bots PAPER ativos (3 por convidado) sem atraso de decisão. | Em teste de carga com 60 bots ativos, cada ciclo de decisão termina antes do próximo dado ficar desatualizado (≤ 60 s). Hipótese revista após o discovery. | Q7 |
| NFR3 | Tempo de resposta | Telas principais respondem sem espera perceptível no ambiente de referência. | Premissa provisória: p95 ≤ 2 s para carregar scanner, painel e P&L com 20 usuários simultâneos; meta revista quando houver hospedagem. | Premissa (ver Assumptions) |
| NFR4 | Integridade | Operações e P&L são confiáveis. | 100% das operações com chave de idempotência; 0 operações acima dos limites; 100% dos cenários determinísticos de P&L com resultado exato. | Intent Statement (métricas); PRD §2 |
| NFR5 | Isolamento | Nenhum acesso entre tenants. | 0 falhas na suíte de testes negativos multi-tenant, que cobre todos os endpoints e tarefas de dados de cliente. | PRD §16 SaaS; regra rígida |
| NFR6 | Segurança | Nenhum segredo ou chave do usuário é coletado; login resiste a replay. | 0 campos que aceitem seed ou chave; nonce de uso único com expiração ≤ 5 min; varredura de segredos sem achados na CI; achados Críticos e Altos bloqueiam o merge. | C-02; team-practices; regras rígidas |
| NFR7 | Privacidade | Endereços de carteira mascarados em logs e métricas; completos só na trilha de auditoria com acesso restrito; retenção mínima após inventário. | 0 endereços completos em logs e métricas na varredura de logs de teste; política de retenção aprovada antes dos convites. | Q9; C-07, C-08 |
| NFR8 | Auditabilidade | Cada decisão de bot pode ser reproduzida. | Para qualquer decisão registrada, recalcular com os dados e a versão registrados produz o mesmo resultado (FR8.1). | PRD §5, US15 |
| NFR9 | Observabilidade | Cada componente tem métrica de saúde e de taxa de erro; logs sem segredos e com identificador de correlação. | Existe ao menos 1 métrica de saúde e 1 de erro por componente; 100% das requisições têm identificador de correlação nos logs. | Diretrizes da fase de operação; PRD §12 |
| NFR10 | Disponibilidade | Sem meta de disponibilidade enquanto o beta roda só localmente. | Meta numérica definida junto com a hospedagem, antes dos convites. | Q6; C-10 |
| NFR11 | Acessibilidade | Acessibilidade básica no beta; WCAG 2.1 AA completo antes de abrir ao público. | Todas as funções do fluxo principal operáveis por teclado, com foco visível e rótulos visíveis; contraste de texto ≥ 4,5:1 no tema escuro (premissa a confirmar no refinamento dos mockups); cor nunca é o único indicador. | project.md (Corrections); mockups Q7 |
| NFR12 | Qualidade | Testes como entrega de primeira classe. | Cobertura de linhas ≥ 80% no backend e no frontend separadamente; testes obrigatórios de risco presentes; TDD no núcleo financeiro. | team-practices (Testing Posture) |
| NFR13 | Portabilidade | Ambiente local no Windows e CI em Linux com o mesmo resultado. | O ambiente sobe com um comando no Windows com Docker Compose; a suíte passa no Linux da CI com fim de linha padronizado. | team-practices; PRD §12 |
| NFR14 | Usabilidade | Interface em português do Brasil, só para computador, tema escuro. | Todos os textos de interface em pt-BR; termos de mercado em inglês com explicação na tela. | Mockups Q3, Q5, Q6 |

## Constraints

- Stack prescrita: Next.js/React/TypeScript, Java 21 Spring Boot (monólito modular com workers), PostgreSQL, Redis, Docker Compose no Windows, Maven e pnpm (pedido inicial; team-practices).
- Sem conta de nuvem, sem orçamento e sem contratações; nenhum fornecedor de dados aprovado antes do G0 (C-06, C-10, C-12).
- Regras rígidas do projeto em `project.md` (10 obrigações e 11 proibições), incluindo: nenhuma seed ou chave privada; REAL e PAPER separados; REAL desligado até G2/G3; `unknown` nunca zero; sem ponto flutuante para dinheiro; instantes em UTC.
- Nenhuma operação financeira real em desenvolvimento ou testes automatizados; nenhuma transação, transferência ou implantação on-chain sem sua aprovação explícita (pedido inicial).
- Enquadramento regulatório (CVM, BCB, LGPD, PLD, tributação) pendente de parecer antes de SaaS e de REAL (C-09).

## Assumptions

- **A1:** os bots PAPER rodam no servidor e não dependem da sessão aberta (FR11.3), porque o PRD proíbe execução no navegador.
- **A2:** a meta de tempo de resposta (NFR3) é provisória; não foi perguntada e depende da hospedagem.
- **A3:** o contraste de texto de 4,5:1 (NFR11) torna mensurável a "acessibilidade básica" e deve ser confirmado no refinamento dos mockups.
- **A4:** a tolerância de divergência entre fontes (FR4.7), o mínimo de cobertura (FR6.2) e o teto do saldo virtual (FR7.4) são parâmetros configuráveis, com valores definidos no design funcional.
- **A5:** endereços públicos de carteira são tratados como dado pessoal até parecer em contrário (Q9, C-07).
- **A6:** Phantom e Solflare são as carteiras do beta, conforme o pedido inicial e o PRD.

## Out of Scope

- REAL em produção, autorização on-chain, executor e reconciliação on-chain (bloqueados por FR15.3).
- Modelo de sinal com IA e explicação por linguagem natural no beta (FR8.6).
- Cobrança, planos pagos, quotas por plano e checkout (PRD §11, US13; Should após o beta).
- Filtros salvos, watchlist, alertas internos e métricas avançadas (Should, PB-05 e PB-06).
- Uso em celular e tema claro.
- Alavancagem, derivativos, outras blockchains, copy trading, custódia centralizada, saques administrados e manipulação de mercado.

## Open Questions

- Canal exato de feedback (FR13.1): formulário no produto ou canal externo. Decidir no refinamento dos mockups.
- Frescor final por fonte e tolerância de divergência: confirmar no G0.
- Hospedagem, região e residência de dados para os convites: decidir após a Sprint 0 e a revisão jurídica.
- Política de retenção por categoria de dado: após o inventário (FR14.3).
- Valores padrão das estratégias modelo, do teto de saldo virtual e do mínimo de cobertura: no design funcional.
- Responsáveis humanos por operações, segurança, jurídico e auditoria (C-14).

## Rastreabilidade com a Ideação

| Grupo | Proto-Unit / fonte da Ideação |
|---|---|
| FR1, FR2, FR3 | PB-01 Acesso seguro; Scope Must (isolamento, permissões, auditoria) |
| FR4, FR5, FR6 | PB-02 Scanner e avaliação; C-05, C-06, C-16 |
| FR7, FR8, FR9, FR11 | PB-03 Bot PAPER e controles; C-03, C-04 |
| FR10 | PB-04 Ledger PAPER e P&L; C-03 |
| FR12 | Wireframes S2; fluxo de valor do escopo |
| FR13 | Fluxo de valor do escopo (feedback); revisão dos mockups R-01 |
| FR14 | PB-00 Discovery e G0; Feasibility (condições 1 a 4) |
| FR15 | Intent Statement (evolução REAL); Feasibility (REAL bloqueado); pedido inicial (PoC de delegação) |
| NFR1 a NFR14 | Intent Statement (métricas de integridade), Constraint Register, Team Practices, respostas Q7 a Q9 |
