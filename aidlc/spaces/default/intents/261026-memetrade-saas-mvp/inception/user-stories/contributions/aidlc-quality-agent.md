**Collaborator:** aidlc-quality-agent

## Contribution

Foco: testabilidade dos critérios de aceite (AC) de [stories.md](../stories.md) contra [requirements.md](../../requirements-analysis/requirements.md) e a Testing Posture de `team.md` (TDD no núcleo financeiro, testes obrigatórios por risco, relógio injetável, Test Strategy `Comprehensive`). Os IDs novos continuam a numeração de cada história; o lead pode renumerar ao integrar. Onde escrevo "limite" sem outra indicação, uso a convenção proposta em C0 (sujeita à decisão da Position 3).

### C0. Convenções de teste para incluir no cabeçalho de stories.md

Proponho acrescentar às "Convenções" do topo, porque vários AC dependem delas para virar asserção de passa/falha:

1. **Fronteira dos limites:** um limite "máximo" é inclusivo (valor igual ao limite é permitido; o menor incremento acima é recusado). Um limite "mínimo" também é inclusivo (valor igual é aceito; abaixo é recusado). Exceção explícita: perda diária, em que **atingir** o limite já pausa (AC4.7.1). "Menor incremento" é a menor unidade da grandeza (unidade base do token, ou a escala decimal definida para o saldo virtual).
2. **Tempo:** todo AC com prazo (expiração, frescor, meia-noite UTC, intervalo entre operações) é verificado com relógio injetável, no instante exato da fronteira e um instante depois. "Mais de 5 minutos" significa estritamente maior que 5:00.
3. **Servidor primeiro:** toda validação descrita como "o campo indica o erro" ou "a etapa não avança" vale também na API (requisição direta recusada com 4xx e `ProblemDetail`), não só na tela.
4. **Núcleo financeiro (TDD):** os AC marcados com `[TDD]` são escritos como teste antes do código e precisam de exemplos numéricos. O lead ou o design funcional fornece a tabela de exemplos; o AC aponta para ela.
5. **Testes obrigatórios por risco:** cada AC que cumpre um dos itens de `team.md` recebe a marca `[RISCO:<item>]`, para o Build and Test conferir que nenhum ficou de fora (mapa em C10).

### C1. AC que hoje não são asserções de passa/falha (reescrever)

| AC | Problema | Redação proposta |
|---|---|---|
| AC1.2.4 | "quando inspecionado" não tem resultado verificável. | Dado o contrato da API e o código de produção, quando a verificação automática roda, então (a) nenhum campo do contrato aceita frase secreta, mnemônica ou chave privada; (b) uma requisição com um campo extra desse tipo é recusada ou ignorada e nada é persistido; (c) o código de produção não usa APIs de importação ou geração de chave secreta ou mnemônica. `[RISCO:login]` |
| AC3.2.3 | "quando revisados" é revisão humana. | Dados os textos de interface em pt-BR, quando a verificação automática roda sobre o catálogo de textos, então nenhum contém termos da lista proibida versionada (por exemplo "lucro garantido", "sem risco", "100% seguro", "detecta golpes"). A lista proibida é revisada pelo responsável do projeto. |
| AC4.2.4 | "não existe ação" é uma negativa não verificável. | Dado qualquer bot PAPER, quando uma requisição tenta transferir saldo, mudar o modo para `REAL` ou criar ordem `REAL`, então é recusada com 4xx, nenhum registro muda e o evento é auditado. `[RISCO:REAL/PAPER]` |
| AC4.5.5 | "quando inspecionada" é revisão. | Dada a configuração do beta, quando o teste do motor de decisão roda, então nenhum componente de modelo de IA é carregado nem chamado (espião registra zero chamadas) e o módulo de decisão não depende do módulo de IA. |
| AC4.3.4 | Mistura duas verificações e não cobre "rota e job" de FR15.2. | Dividir: **AC4.3.4** Dado um bot PAPER operando com a configuração padrão, quando ele cria operações, então o componente de assinatura ou envio de transação registra zero chamadas. **AC4.3.6 (novo)** Dada a configuração padrão, quando a suíte enumera as rotas, jobs e telas registradas, então nenhuma aceita operação `REAL`, e uma chamada a qualquer rota de modo `REAL` devolve 403 ou 404. `[RISCO:REAL desligado]` |
| AC2.3.4 | Medição de 1 hora com fonte real não roda na CI e depende do G0. | Manter o AC como verificação de NFR1 na validação de NFRs, e acrescentar **AC2.3.5 (novo, roda na CI)**: Dada a fonte simulada atualizando a cada N segundos e relógio injetável, quando o scanner é consultado, então a idade exibida de cada valor é igual a agora menos o horário de observação, calculada pelo horário de observação e não pelo horário de busca. |

### C2. Fronteiras ausentes (no limite e logo depois)

Cada linha é um AC novo da história indicada, no formato Dado/Quando/Então.

| Novo AC | História | Dado | Quando | Então |
|---|---|---|---|---|
| AC1.1.4 | US1.1 | um convite com validade até T | usado em T e em T + 1 s | em T é aceito; em T + 1 s é negado com o motivo "expirado". |
| AC1.1.5 | US1.1 | um convite válido de uso único | duas requisições de uso chegam ao mesmo tempo | existe exatamente uma conta criada; a outra recebe o motivo "já usado". |
| AC1.1.6 | US1.1 | um convite válido e termos não aceitos | a API de conexão de carteira é chamada diretamente | a chamada é recusada e nenhuma carteira é vinculada (FR1.2). |
| AC1.2.5 | US1.2 | um nonce emitido em T com expiração de 5 min | a assinatura chega em T + 4:59 e em T + 5:01 | a primeira cria a sessão; a segunda é recusada e auditada (NFR6). `[RISCO:login]` |
| AC1.2.6 | US1.2 | uma mensagem assinada com domínio, chain ou nonce diferente do emitido, ou assinada por outra carteira, ou alterada depois da assinatura | é enviada | o login é recusado, nenhuma sessão é criada e o evento é auditado. `[RISCO:login]` |
| AC1.2.7 | US1.2 | um mesmo nonce válido | duas verificações chegam ao mesmo tempo | só uma cria sessão. `[RISCO:login]` |
| AC1.2.8 | US1.2 | o vínculo concluído | o registro da carteira é lido no banco | só existem endereço público e provedor (FR1.5). |
| AC2.2.4 | US2.2 | liquidez mínima L e idade máxima A | um token tem liquidez exatamente L e idade exatamente A; outro tem L menos o menor incremento | o primeiro aparece; o segundo fica fora. |
| AC2.3.6 | US2.3 | um valor observado em T | exibido em T + 5:00 e em T + 5:01 | em T + 5:00 não está desatualizado; em T + 5:01 está marcado com texto ou ícone, nunca só por cor (NFR11). |
| AC2.3.7 | US2.3 | uma tolerância de divergência D | as fontes diferem exatamente D e D mais o menor incremento | com D não há aviso; acima de D aparece o aviso. |
| AC3.1.4 | US3.1 | um mínimo de cobertura C | um token tem cobertura exatamente C | o indicador é exibido (abaixo de C vale AC3.2.1). |
| AC3.1.5 | US3.1 | os mesmos dados de entrada e a mesma versão dos pesos | o indicador é recalculado | o valor é idêntico e fica entre 0 e 100 inclusive. |
| AC4.1.5 | US4.1 | um parâmetro com faixa [mín, máx] | são informados mín, máx, mín menos um passo, máx mais um passo e um valor não numérico | mín e máx são aceitos; os demais são recusados no campo e pela API. |
| AC4.2.5 | US4.2 | exposição total T | exposição por token igual a T e T mais o menor incremento | igual a T é aceito; acima é recusado. |
| AC4.2.6 | US4.2 | os limites do bot | tamanho por ordem maior que a exposição por token, valor zero ou negativo, ou slippage acima de 100% | a etapa não avança e cada campo indica o erro. |
| AC4.2.7 | US4.2 | um teto de saldo virtual S | são informados S, S mais o menor incremento, zero e um valor negativo | só S é aceito. |
| AC4.3.7 | US4.3 | 2 bots ativos | inicio o terceiro | a ativação é aceita (lado permitido da fronteira de AC4.3.2). |
| AC4.6.4 | US4.6 | intervalo mínimo I entre operações e a última operação em T | a próxima ordem chega em T + I menos 1 s e em T + I | a primeira é recusada por intervalo; a segunda segue para as demais verificações. `[RISCO:limites]` |
| AC4.6.5 | US4.6 | limite de frescor F para ordens | o dado tem idade exatamente F e F + 1 s | com F a ordem segue; acima de F é recusada por frescor (completa AC4.6.3). `[RISCO:limites]` |
| AC4.7.5 | US4.7 | limite de perda diária P | a perda acumulada do dia é P menos o menor incremento | a próxima decisão pode gerar operação (lado permitido de AC4.7.1). `[TDD]` `[RISCO:limites]` |
| AC4.7.6 | US4.7 | um bot pausado pelo limite | o relógio passa de 23:59:59 UTC para 00:00:00 UTC | às 23:59:59 continua pausado; a partir de 00:00:00 a próxima decisão pode operar. O teste usa um usuário em UTC−3 para mostrar que vale a meia-noite UTC, não a local. `[TDD]` |
| AC4.7.7 | US4.7 | um bot pausado pelo limite com perda acumulada X | elevo o limite para um valor menor ou igual a X | a retomada é recusada com o motivo (completa AC4.7.4). `[TDD]` |

### C3. AC4.6.1: desdobrar "qualquer limite" em uma verificação por limite `[TDD]` `[RISCO:limites]`

AC4.6.1 cobre nove verificações de FR8.3 num só critério. Para TDD, cada uma precisa do seu par de fronteira. Proponho manter AC4.6.1 como regra geral e acrescentar **AC4.6.6**, com uma tabela de exemplos que o design funcional preenche com números:

| Verificação (FR8.3) | Caso permitido | Caso recusado | Motivo exibido |
|---|---|---|---|
| Tamanho por ordem | ordem = máximo | máximo + menor incremento | "tamanho por ordem" |
| Exposição por token | exposição após a ordem = máximo | máximo + menor incremento | "exposição por token" |
| Exposição total | total após a ordem = máximo | máximo + menor incremento | "exposição total" |
| Perda diária | perda < limite | perda ≥ limite | "perda diária" |
| Drawdown | drawdown = máximo | máximo + menor incremento | "drawdown" |
| Slippage estimado | estimado = máximo | máximo + menor incremento | "slippage" |
| Liquidez mínima | liquidez = mínimo | mínimo − menor incremento ou `unknown` | "liquidez mínima" |
| Frescor | idade = F | idade > F | "frescor" |
| Intervalo | T + I | T + I − 1 s | "intervalo" |
| Duplicidade | primeira com a chave K | segunda com a chave K | "duplicada" |

Regra de asserção comum: no caso recusado, nenhuma operação e nenhum lançamento no ledger são criados, a recusa aparece com o motivo e existe exatamente um evento de auditoria "ordem recusada" (AC7.3.1). Quando mais de um limite falha, o teste exige que a recusa liste todos ou o primeiro segundo uma ordem definida (decisão do design funcional; o teste precisa saber qual).

Também falta um AC próprio para **drawdown** (só aparece dentro de "qualquer"): **AC4.7.8** Dado um drawdown máximo D medido sobre o patrimônio virtual do bot, quando uma operação levaria o drawdown acima de D, então ela é recusada; ao atingir D, o bot é pausado pelo limite de drawdown. (A definição de drawdown está na Position 4.)

### C4. Idempotência e concorrência `[TDD]` `[RISCO:deduplicação]`

- **AC4.6.7 (novo)** Dada a mesma decisão processada por dois workers ao mesmo tempo, quando ambos tentam registrar, então existe uma única operação e um único conjunto de lançamentos no ledger.
- **AC4.6.8 (novo)** Dada uma chave de idempotência já usada com conteúdo diferente (outro token, quantidade ou preço), quando chega, então é recusada como conflito e nada é registrado.
- **AC4.6.9 (novo, NFR4)** Dada qualquer operação persistida, quando lida, então tem chave de idempotência não nula e única dentro do tenant e do modo; o banco recusa uma segunda linha com a mesma chave.
- **AC4.3.3 (ajuste)** Explicitar que o duplo clique envia a mesma chave de idempotência e que a segunda requisição devolve o mesmo resultado da primeira, sem segunda ativação.
- **AC7.3.1 (ajuste)** Acrescentar: uma repetição idempotente da mesma ação não gera segundo evento de auditoria.

### C5. Pausa, kill switch e precedência dos motivos `[RISCO:pausa]`

- **AC4.8.1 (ajuste)** Trocar "a partir daquele momento" por: Dado um ciclo de decisão em andamento, quando a pausa é confirmada antes do registro da ordem, então a ordem é recusada por bot pausado. A verificação do estado do bot acontece na mesma transação que registra a operação.
- **AC7.2.4 (novo)** Dado o kill switch acionado, quando um convidado tenta iniciar ou retomar um bot, então a ação é recusada com o motivo "pausado pelo sistema".
- **AC7.2.5 (novo)** Dado um usuário sem papel de administrador, quando chama a rota do kill switch ou de convites, então recebe 403 e o evento é auditado (FR2.2).
- **AC4.7.9 (novo)** Dado um bot pausado pelo limite e, em seguida, pausado pela pessoa ou pelo kill switch, quando chega a meia-noite UTC, então o bot continua pausado; só o motivo "limite diário" é removido.
- **AC1.3.5 (novo)** Dada a desconexão confirmada, quando um bot pausado por ela recebe uma nova decisão, então nenhuma operação é criada.

### C6. Ledger e P&L `[TDD]`

- **AC5.2.2 (ajuste)** `[RISCO:ledger]` Explicitar duas camadas: (a) pela aplicação, a tentativa de alterar ou apagar é recusada; (b) no banco, `UPDATE` ou `DELETE` em lançamento com o papel da aplicação falha. Acrescentar: o lançamento compensatório referencia o original, e o saldo após a compensação é igual ao esperado no exemplo.
- **AC5.1.2 (ajuste)** `[RISCO:P&L]` "Um conjunto de cenários" é aberto demais para dirigir os testes. Proponho que o AC nomeie o catálogo mínimo, com valores calculados no design funcional:
  1. compra e venda total com ganho;
  2. compra e venda total com perda;
  3. duas compras a preços diferentes (custo médio ponderado) e venda parcial;
  4. taxas aplicadas conforme a política explícita de FR10.2 (custo ou despesa);
  5. tokens com escalas diferentes (por exemplo 6 e 9 casas) e quantidade de uma unidade base;
  6. arredondamento na fronteira (valor que cai exatamente no meio), com modo e escala definidos;
  7. posição zerada: o custo médio reinicia na compra seguinte;
  8. não realizado com marcação `unknown`: aparece `unknown`, nunca zero.
- **AC5.1.1 (ajuste)** "a soma por token é igual ao total" deve ser igualdade exata em decimal, sem tolerância.
- **AC5.1.3 (ajuste)** "sinalizado" precisa de forma verificável: o valor aparece com o texto "sem liquidez" (não só cor), a fonte e o horário da última marcação.
- **AC5.2.4 (novo)** Dado o ledger reconciliado com as operações, quando a tela abre, então aparece "reconciliado" com o horário da verificação (caminho feliz de AC5.2.3, que hoje só tem o caminho de erro).
- **AC4.5.6 (novo)** `[TDD]` Dado um conjunto fixo de liquidez, spread e taxas (fixture gravada), quando uma ordem simulada é preenchida, então o preço de preenchimento, o slippage e as taxas são exatamente os do exemplo. Sugiro tratar a política de simulação como parte do núcleo financeiro, porque o P&L depende dela.
- **AC4.8.3 (ajuste)** Acrescentar: depois do fechamento a quantidade da posição é zero, o resultado vai para o realizado e o bot fica `STOPPED`.
- **AC4.8.4 (ajuste)** Acrescentar: enquanto a posição está pendente, o não realizado aparece `unknown`; quando surge preço confiável, o fechamento usa esse preço, gera os lançamentos e um evento de auditoria.

### C7. `unknown` nunca é zero `[RISCO:unknown]`

- **AC2.1.6 (novo)** Dados um campo que a fonte informou como 0 e outro que a fonte não informou, quando exibidos e quando lidos pela API, então o primeiro aparece como 0 e o segundo como `unknown`; no contrato da API os dois são distinguíveis sem depender de `null` ou de zero.
- **AC4.5.3 (ajuste)** Incluir o caso "dado `unknown`" (FR8.2 fala em dado ausente), além de desatualizado e avaliação indisponível.
- **AC3.2.2 (ajuste)** "nunca classificado como seguro" precisa de asserção: Dado um token com um fator `unknown`, quando avaliado, então esse fator não contribui como risco zero e nenhum rótulo de baixo risco é exibido. A regra exata de cálculo é da Position 4.

### C8. Isolamento e permissões `[RISCO:tenant]`

- **AC1.4.5 (novo, FR2.1)** Dada uma requisição autenticada do tenant A com o identificador do tenant B no corpo ou na query, quando processada, então o tenant B é ignorado ou a requisição é recusada, e nada de B é lido ou gravado.
- **AC1.4.2 (ajuste)** Dados registros do tenant A e do tenant B e uma tarefa em segundo plano executada para A, quando termina, então os registros de B estão inalterados; uma tarefa cujo tenant não corresponde ao do bot é recusada e auditada.
- **AC1.4.6 (novo, NFR5)** Dada a lista de rotas e jobs que tocam dados de cliente, quando a suíte roda, então cada um tem um teste negativo entre tenants; uma rota nova sem esse teste falha a verificação.
- **AC1.4.7 (novo, FR2.2)** Dado o administrador, quando tenta iniciar, pausar ou encerrar o bot de um usuário individual, ou ler o ledger dele, então recebe 403 e o evento é auditado. O kill switch global é a única ação dele sobre bots.

### C9. Requisitos sem AC e ajustes de rastreabilidade

| Requisito | Situação | Proposta |
|---|---|---|
| FR5.3 | Só o estado "vazio" tem AC (AC2.2.3). | **AC2.1.7 (novo)** Dados os estados carregando, erro e resultado parcial (uma fonte falhou), quando ocorrem, então cada um aparece de forma distinta, em texto, e diferente de `unknown`. |
| FR4.3 | Frescor e cobertura não têm AC (só fonte e horário). | **AC2.1.8 (novo)** Dado um token, quando exibido, então mostra o frescor de cada valor e a cobertura dos campos (quantos foram informados). |
| FR7.5 | O estado `VALIDATED` e o conjunto de transições não têm AC. | **AC4.4.4 (novo)** Dada a tabela de transições permitidas (`DRAFT → VALIDATED → ACTIVE ⇄ PAUSED`, qualquer estado → `STOPPED`, conforme o design funcional), quando qualquer transição fora dela é pedida (por exemplo `DRAFT → ACTIVE` ou `STOPPED → PAUSED`), então é recusada. Teste dirigido por tabela, todas as combinações. |
| FR9.3 | O rótulo de modo simulado só está em US4.3; FR9.3 inclui as telas de P&L (S6). | **AC5.1.4 (novo)** Dadas as telas de P&L e ledger, quando renderizadas, então o título mostra "MODO PAPER (simulado)" em texto. |
| FR12.2 | AC5.3.2 não tem prazo. | Acrescentar "em até 60 s (NFR1)". |
| NFR14 | US2.1 cita NFR14 sem AC. | **AC2.1.9 (novo)** Dados os textos de interface, quando a verificação roda, então todos estão em pt-BR e cada termo de mercado em inglês tem explicação na tela. |
| NFR11 | Só há AC de foco em US3.1. | Acrescentar um AC de teclado ao fluxo principal: **AC4.1.6** Dado o assistente, quando uso só o teclado, então completo as quatro etapas com foco visível e rótulos visíveis. Os estados (desatualizado, divergente, sem liquidez, pausado) sempre têm texto ou ícone além da cor. |
| FR14.1 a FR14.4, FR15.1 | Viraram spikes (SP-1 a SP-5), que não são `USx.y`. | No `traceability.json`, marcar `Deferred` com destino `delivery-planning`; um alvo `OK` precisa nomear uma história existente. |
| FR15.3 | Won't neste beta. | `N/A` com a justificativa "bloqueado até G2/G3 e aprovação explícita". |
| NFR2, NFR3, NFR9, NFR10 | Medições de capacidade, tempo de resposta, observabilidade e disponibilidade. | `Deferred` para `nfr-requirements`, não `GAP`. NFR10 depende da hospedagem. |
| NFR7 (retenção), NFR12, NFR13 | Processo e qualidade, não comportamento de história. | NFR7: mascaramento coberto por AC7.3.2; política de retenção `Deferred` (SP-3). NFR12 e NFR13: `Deferred` para `build-and-test`. |

**AC7.3.2 (ajuste):** para a varredura automática de logs (NFR7), definir o formato de mascaramento (por exemplo, 4 primeiros e 4 últimos caracteres) e a regra da varredura: nenhuma sequência base58 de 32 a 44 caracteres que corresponda a um endereço completo aparece em logs ou métricas dos testes.

**AC7.3.3 (ajuste):** incluir cabeçalhos `Authorization` na lista do que nunca aparece (regra de `project.md`).

### C10. Mapa dos testes obrigatórios por risco (team.md → AC)

| Teste obrigatório | AC que o cumprem | Situação |
|---|---|---|
| Isolamento entre tenants, por ID e em segundo plano | AC1.4.1, AC1.4.2, AC1.4.5, AC1.4.6, AC6.1.3 | Coberto com os ajustes de C8 |
| Separação REAL e PAPER | AC1.4.4, AC4.2.4, AC4.3.6 | Coberto com as reescritas de C1 |
| Deduplicação por chave de idempotência | AC4.6.2, AC4.3.3, AC4.6.7 a AC4.6.9 | Faltava concorrência e conflito (C4) |
| Violação de limites e controles de risco | AC4.6.1, AC4.6.6, AC4.7.1, AC4.7.5 a AC4.7.8, AC4.2.5 a AC4.2.7 | Faltavam fronteiras e drawdown (C2, C3) |
| `unknown` nunca zero | AC2.1.2, AC2.1.6, AC2.2.2, AC3.2.2, AC4.5.3, AC5.1.2 (cenário 8) | Faltava 0 legítimo versus ausente (C7) |
| Pausa impede novas operações | AC4.8.1, AC4.7.1, AC7.2.1, AC7.2.4, AC1.3.5 | Faltava corrida com ciclo em andamento (C5) |
| Ledger sem edição | AC5.2.2 | Faltava a camada do banco (C6) |
| P&L determinístico em decimal e unidades de token | AC5.1.1, AC5.1.2, AC4.5.6 | Faltava o catálogo de cenários (C6) |
| REAL desligado por padrão | AC4.3.4, AC4.3.6 | Faltava rota e job (C1) |
| Login sem reuso de assinatura | AC1.2.2, AC1.2.3, AC1.2.5 a AC1.2.7 | Faltavam fronteira de 5 min e adulteração (C2) |

Observação sobre independência das histórias (regra da fase): US4.6, US4.7 e US5.1 dependem de US4.3 no fluxo, mas os AC de risco, ledger e P&L são testáveis isoladamente com fixtures do motor de risco e do ledger, sem passar pelo assistente. Sugiro registrar isso na nota INVEST dessas histórias, para não forçar testes de ponta a ponta onde um teste de unidade basta.

## Positions

- AGREE: Formato Dado/Quando/Então e IDs `AC{grupo}.{seq}.{n}` estáveis; dá para mapear cada AC a um caso de teste e ao `traceability.json`.
- AGREE: Spikes (SP-1 a SP-5) fora do backlog de histórias, com critério de pronto; não são testáveis como comportamento de produto.
- AGREE: AC4.3.2 (ativações simultâneas), AC4.8.4 (encerramento pendente, sem preço inventado) e AC5.2.2 (só compensatório) já trazem o caso difícil, não só o caminho feliz.
- OBJECT: AC1.2.4, AC3.2.3, AC4.2.4 e AC4.5.5 dizem "quando inspecionado/revisados", o que não é passa/falha; precisam virar verificações automáticas (C1). Ponto de conhecimento: um especialista resolve com a reescrita proposta.
- OBJECT: AC4.6.1 junta nove verificações de FR8.3 em "qualquer limite", sem fronteira por limite, e o drawdown não tem AC próprio; assim não dá para escrever os testes antes do código (C3). Ponto de conhecimento.
- OBJECT: A semântica das fronteiras não está definida (máximo inclusivo ou exclusivo; "mais de 5 min"; cobertura igual ao mínimo; convite no instante da expiração). Proponho a convenção de C0, mas ela muda o que o usuário pode fazer no limite. Decisão de julgamento: precisa da pessoa (ou de aceite explícito da convenção proposta).
- OBJECT: "Perda acumulada" (AC4.7.1) não diz se conta só o realizado ou também o não realizado e as taxas, e "drawdown" não tem definição (sobre qual patrimônio, desde quando). Sem isso o teste de TDD não tem resultado esperado. Decisão de julgamento: regra de negócio para a pessoa decidir, ou adiar explicitamente para o design funcional com um ID de regra.
- OBJECT: AC4.6.3 recusa ordem com dado "mais velho que o permitido", mas nenhum requisito diz qual é esse limite de frescor para ordens (60 s do NFR1, os 5 min de "desatualizado" ou um parâmetro do bot). Decisão de julgamento: precisa da pessoa; sugiro que seja no máximo 60 s, porque 5 min permitiria operar com dado quase vencido.
- OBJECT: Não está definido se bots pausados contam no limite de 3 ativos (AC4.3.2) nem se retomar um pausado conta como ativação; nem a precedência entre motivos de pausa na meia-noite UTC (AC4.7.9). Decisão de julgamento: proponho que só `ACTIVE` conte, que retomar conte como ativação e que a meia-noite remova apenas o motivo "limite diário".
- OBJECT: AC5.1.2 não nomeia os cenários nem a política de arredondamento (modo e escala), a política de taxas ou a unidade do saldo virtual, e isso impede o TDD do P&L. Ponto de conhecimento: o design funcional fixa os valores; a história deve nomear o catálogo mínimo de C6 e apontar para a tabela de exemplos.
- OBJECT: Faltam AC para FR5.3 (carregando, erro, parcial), FR4.3 (frescor e cobertura), FR7.5 (`VALIDATED` e tabela de transições), FR9.3 nas telas de P&L, FR2.1 (tenant no corpo ignorado), FR2.2 (administrador não opera por usuário) e para o bloqueio de ativação durante o kill switch (C5, C8, C9). Ponto de conhecimento.
- OBJECT: NFR2, NFR3, NFR9, NFR10, NFR12 e NFR13 não têm história e não devem aparecer como `GAP`; devem ir como `Deferred` com estágio de destino no `traceability.json`, e os spikes como `Deferred` para `delivery-planning`, porque um alvo `OK` precisa ser um `USx.y` existente. AC2.3.4 (medição de 1 hora) pertence à validação de NFRs e precisa de um AC irmão que rode na CI (AC2.3.5). Ponto de conhecimento.
