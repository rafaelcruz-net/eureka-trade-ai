**Collaborator:** aidlc-design-agent

## Contribution

Revisão de UX e fidelidade às personas do rascunho de `stories.md` e `personas.md`, comparado às telas S1 a S7 e aos fluxos F1 a F6 de `ideation/rough-mockups/` (wireframes.md, user-flow.md), às decisões dos mockups (Q1 a Q7: painel primeiro, assistente em 4 etapas, pt-BR com termos em inglês explicados, tabela densa com painel lateral, só computador, só tema escuro, acessibilidade básica) e a FR/NFR de `requirements.md`. Tudo abaixo está escrito para ser integrado direto. Os IDs novos continuam a numeração de cada história e respeitam o teto de 6 critérios por história (Q3).

### 1. Lacuna principal: estados de tela fora das histórias

Os wireframes definem quatro estados (vazio, carregando, erro, parcial) para cada tela S1 a S7, mas o rascunho só cobre de forma sistemática o vazio do scanner (AC2.2.3) e o vazio do painel (AC5.3.1). Carregando e erro não aparecem em quase nenhuma história. Para não inflar cada história, proponho dois mecanismos complementares:

- **(a) Critérios transversais de interface** (seção nova, antes do Grupo 1, valendo como definição de pronto de toda história com tela). Cada história de interface cita "atende CT-UX-1 a CT-UX-9".
- **(b) ACs específicos** só onde o estado tem comportamento próprio da tela (itens 3 a 9 abaixo).

#### Seção proposta: "Critérios transversais de interface (definição de pronto)"

| ID | Critério | Origem |
|---|---|---|
| CT-UX-1 | Todo texto de interface em pt-BR. Termos de mercado ficam em inglês (market cap, holders, slippage, drawdown, mint, P&L) e mostram uma explicação curta ao passar o mouse **e** ao receber foco do teclado. | NFR14; mockups Q3; wireframes S3 |
| CT-UX-2 | Carregando: a área afetada mostra marcadores de conteúdo no lugar do conteúdo, nunca uma página inteira em branco. O restante da tela continua utilizável. | Wireframes S2 a S7 (estado Carregando) |
| CT-UX-3 | Erro: a mensagem diz o que aconteceu, por quê e o que fazer, oferece "Tentar de novo" quando a ação pode ser repetida e nunca mostra código técnico, stack trace ou SQL. Uma falha num quadro não esconde os demais. | Wireframes, diretrizes transversais (Erros); team.md (Erros da API) |
| CT-UX-4 | Teclado: todas as ações do fluxo principal funcionam só com teclado, em ordem lógica, com foco sempre visível e sem armadilha de foco. Ao abrir cada tela, o foco entra no elemento indicado no wireframe (S1 campo de convite, S2 primeiro quadro, S3 filtros, S4 primeiro campo da etapa, S5 "Pausar", S6 filtro de período, S7 primeiro botão). | NFR11; project.md (Corrections, acessibilidade básica); wireframes |
| CT-UX-5 | Diálogos de confirmação (encerrar bot, desconectar carteira, kill switch, feedback): ao abrir, o foco entra no diálogo; Esc ou "Cancelar" fecha sem efeito; ao fechar, o foco volta ao botão que o abriu. | Wireframes S5, S7; NFR11 |
| CT-UX-6 | Formulários: todo campo tem rótulo visível (não só placeholder) e unidade quando houver (SOL, %, min). Erro de validação aparece em texto junto do campo, ligado a ele, e não só como borda colorida. | NFR11; wireframes S4 |
| CT-UX-7 | Cor nunca é o único indicador: ganho e perda usam sinal (+/-) e texto; risco usa número e nível escrito; estado do bot (ATIVO, PAUSADO, ENCERRADO, ENCERRAMENTO PENDENTE) aparece em texto; dado desatualizado e divergência usam texto ou ícone. Contraste de texto ≥ 4,5:1 no tema escuro. | NFR11; wireframes (Tema); project.md (Corrections) |
| CT-UX-8 | Atualizações automáticas (painel, scanner, operações do bot) são anunciadas a leitores de tela de forma não intrusiva, não movem o foco e mostram "Atualizado às HH:MM". | FR12.2; wireframes S2 e S5 (anúncio a leitores de tela) |
| CT-UX-9 | Cada tela tem um título h1 igual ao nome do item de menu (ou ao nome do bot em S5) e regiões nomeadas (navegação, conteúdo principal, avisos). Horários aparecem no fuso do usuário. | Wireframes (Acessibilidade por tela); project.md (UTC) |

Fica explícito que só computador e só tema escuro **não** geram ACs de responsividade nem de tema claro (Out of Scope em requirements.md). Para uma recomendação sem requisito, veja a nota no item 10.

### 2. Personas (`personas.md`)

- **Linha nova "Fluxos principais"** em cada persona, para amarrar personas às telas: P1 → F2 (descobrir e avaliar), F5 (P&L) e F4 (acompanhar); P2 → F3 (criar bot), F4 (controlar) e US3.1/US3.2 (entender o risco); P3 → convites e kill switch. P3 **não tem tela nos wireframes**: registrar isso como lacuna para o design funcional (ver item 9).
- **Linha nova "Ambiente de uso"**: "Computador, navegador com extensão Phantom ou Solflare, interface em pt-BR, tema escuro". Isso confirma na persona as decisões Q3, Q5 e Q6 dos mockups. Uso em celular continua fora do beta e precisa ser validado nas entrevistas (FR14.2) como hipótese.
- **P2, dores → critérios**: as três dores de Camila têm de aparecer nos critérios. "Termos técnicos sem explicação" → CT-UX-1, AC4.2.5. "Medo de configurar algo errado" → valores padrão (AC4.2.1), explicação por limite (AC4.2.5), revisão (AC4.3.1). "Entender o motivo de uma decisão automática" → AC4.5.6 (resumo em linguagem simples). Hoje só a segunda dor tem cobertura.
- **Sugestão menor**: a persona P1 se chama "Rafael", o mesmo primeiro nome do responsável do projeto, que é a P3. Trocar o nome de P1 (por exemplo "Bruno") evita confusão ao ler a trilha de auditoria e as entrevistas. Decisão do lead, não bloqueia.

### 3. Grupo 1 — Acesso e conta (S1, S7, F1, F6)

**US1.1** — reescrever e acrescentar:
- **AC1.1.1 (reescrito)** Dado um convite válido, quando abro o link, então vejo os termos e o aviso de risco, e "Conectar carteira" fica indisponível com o motivo escrito ao lado até eu marcar os dois aceites.
- **AC1.1.4** Dado que abro a entrada sem código de convite, quando a tela carrega, então vejo um campo para colar o código e a explicação de que o beta é só por convite. *(S1 Vazio)*

**US1.2** — acrescentar (fica com 6):
- **AC1.2.5** Dado que nenhuma carteira Phantom ou Solflare está instalada, quando escolho conectar, então vejo como instalar uma delas e consigo voltar à entrada sem perder os aceites marcados. *(F1, caminho de erro)*
- **AC1.2.6** Dado que a carteira está aberta pedindo a assinatura, quando espero ou recuso, então a tela mostra "Confirme a assinatura na sua carteira" com a opção de cancelar e, se eu recusar, uma mensagem diz que nada foi assinado e oferece tentar de novo. A tela lembra que a assinatura serve só para entrar e não autoriza operações. *(S1 Carregando e Erro; FR1.4)*

**US1.3** — acrescentar:
- **AC1.3.5** Dada uma sessão ativa, quando escolho "Sair", então a sessão termina, volto à entrada e meus bots PAPER ativos continuam rodando no servidor. *(S7; FR1.6, FR11.3. Hoje o título promete "sair", mas nenhum critério cobre.)*
- **AC1.3.6** Dada uma sessão que expira enquanto uso qualquer tela, quando faço a próxima ação, então vejo um aviso de sessão expirada e volto à entrada. Depois de entrar, retorno à tela onde estava. *(S7 Erro; F6)*

**US1.5 (nova) — Ver os dados da minha conta**
**Como** P1, **quero** ver a carteira associada, a validade da sessão e os termos aceitos, **para que** eu saiba com que identidade estou e o que aceitei.
**Prioridade:** Should · **Requisitos:** FR1.2, FR1.5, FR1.6, FR15.2 · **Origem:** wireframes S7, F6 · **Depende de:** US1.2
- **AC1.5.1** Dada a tela Conta, quando abro, então vejo o endereço da carteira abreviado e só para leitura, o provedor e quando a sessão expira.
- **AC1.5.2** Dados os termos aceitos, quando abro "ver", então vejo a versão e a data do aceite no meu fuso.
- **AC1.5.3** Dada a tela Conta, quando exibida, então mostra "Modo REAL: indisponível no beta" em texto e nenhum controle para ativá-lo.

### 4. Grupo 2 — Descoberta (S3, F2)

**US2.1** — acrescentar (fica com 6):
- **AC2.1.6** Dados novos dados chegando enquanto navego a lista, quando a lista se atualiza, então a linha selecionada e o foco continuam no mesmo token, o painel lateral não fecha e o horário "Atualizado às" muda. *(Com frescor ≤ 60 s (NFR1), sem esse critério a linha muda de lugar sob o foco do teclado e o painel perde o contexto. CT-UX-8.)*

**US2.2** — acrescentar:
- **AC2.2.4** Dada uma lista com mais de uma página, quando avanço ou volto de página, então os filtros e a ordenação continuam aplicados e a tela mostra "Exibindo X–Y de N". *(S3; FR5.1)*

**US2.3** — acrescentar:
- **AC2.3.5** Dadas todas as fontes indisponíveis, quando o scanner abre, então um aviso no topo explica a falha, a lista aparece vazia com explicação (nunca com valores zerados ou antigos sem marca) e há "Tentar de novo". *(S3 Erro; F2. AC2.3.2 cobre só uma fonte fora.)*

### 5. Grupo 3 — Avaliação (S3, painel lateral)

**US3.1** — reescrever e acrescentar:
- **AC3.1.1 (reescrito)** Dada uma linha da lista, quando a seleciono com clique ou Enter, então o painel lateral abre sem que a lista perca posição e filtros. Quando fecho com Esc ou pelo botão de fechar, o foco volta à mesma linha. *(Wireframe S3 define Enter/Esc; o texto atual não diz como abrir pelo teclado.)*
- **AC3.1.4** Dado o painel lateral carregando, quando a avaliação ainda não chegou, então ele mostra "Carregando avaliação" e nunca um indicador provisório. *(S3 Carregando)*
- **AC3.1.5** Dado o indicador de risco, quando exibido, então mostra o número, o nível por escrito e a explicação curta de que maior significa mais risco. *(CT-UX-7; P2)*

**US3.2** — reescrever:
- **AC3.2.1 (acréscimo ao texto)** ... e, na lista, a coluna Risco mostra `--` com o motivo acessível por foco ou mouse. *(S3, notas de interação)*

### 6. Grupo 4 — Bot PAPER (S4, S5, F3, F4)

**Inconsistência a corrigir (US4.1 x FR7.2/S4):** AC4.1.1 coloca a escolha da estratégia na "etapa 1", mas FR7.2, o wireframe S4 e o fluxo F3 chamam a etapa 1 de "Seleção de tokens" (regras de quais tokens o bot considera), e nenhuma história cobre essas regras. Proposta, mantendo as 4 etapas aprovadas: renomear a etapa 1 para **"Estratégia e tokens"**, com a estratégia modelo primeiro e as regras de seleção de tokens em seguida. Atualizar AC4.1.1, AC4.1.2 e AC4.1.4 com esse nome. O lead deve registrar o ajuste em FR7.2 na rastreabilidade.

**US4.1** — acrescentar (fica com 6):
- **AC4.1.5** Dada a etapa 1 sem regra de seleção de tokens, quando a tela aparece, então mostra uma orientação curta com exemplo, e "Continuar" fica indisponível com o motivo escrito. *(S4 Vazio)*
- **AC4.1.6** Dado o assistente, quando mudo de etapa, então o indicador mostra "Etapa N de 4" com o nome das quatro etapas, a etapa atual tem título próprio e o foco vai para o primeiro campo dela. *(S4; CT-UX-4)*

**US4.2** — acrescentar:
- **AC4.2.5** Dado cada limite da etapa 2, quando exibido, então tem rótulo visível, unidade, valor padrão e uma frase em linguagem simples sobre o que ele impede (por exemplo, "Perda diária máxima: o bot pausa sozinho ao perder este valor no dia"). *(Dores de P2; CT-UX-1, CT-UX-6)*

**US4.3** — ajustar e acrescentar:
- **AC4.3.2 (acréscimo)** ... e, se eu já tiver 3 bots ativos ao abrir o assistente, um aviso informa "3 de 3 bots ativos" e diz que é preciso pausar ou encerrar um bot para iniciar este. O rascunho pode ser salvo. *(Prevenir o erro em vez de só recusá-lo no fim.)*
- **AC4.3.6** Dada uma falha ao iniciar, quando ocorre, então o bot continua em rascunho, a mensagem diz como tentar de novo e, enquanto o pedido está em andamento, a tela mostra "Iniciando bot PAPER" sem aceitar novo envio. *(S4 Carregando e Erro; F3)*

**US4.5** — acrescentar (fica com 6):
- **AC4.5.6** Dada uma decisão aberta, quando exibida, então começa com uma frase em linguagem simples que resume a ação e o motivo principal (por exemplo, "Não comprou XYZ: liquidez desatualizada há 7 min") antes dos fatores e dos dados técnicos. *(P2: "explicação de cada decisão em linguagem simples". Texto gerado por regra, não por IA (FR8.6).)*

**US4.7** — acrescentar:
- **AC4.7.5** Dado um bot pausado pelo limite, quando exibido, então mostra o motivo em texto e o horário de reinício no meu fuso (por exemplo, "volta a operar às 21:00, horário de Brasília"). *(A meia-noite UTC não é óbvia para convidados no Brasil.)*

**US4.8** — reescrever e acrescentar (fica com 6):
- **AC4.8.1 (reescrito)** Dado um bot ativo, quando pauso pela tela do bot ou pelo quadro do painel, com um único comando e sem confirmação, então nenhuma nova operação simulada é criada a partir daquele momento e o estado PAUSADO aparece em texto. *(S2 tem "Pausar" no quadro; persona P2: "pausa sempre à mão". Pausar é reversível e não deve ter atrito.)*
- **AC4.8.5** Dada a confirmação de encerramento aberta, quando cancelo, então o bot e as posições não mudam e o foco volta a "Encerrar". *(CT-UX-5)*
- **AC4.8.6** Dado um bot que pausei manualmente, quando escolho retomar, então ele volta a ATIVO com os limites vigentes. Se eu já tiver 3 bots ativos, a retomada é recusada com o limite exibido. *(Nenhuma história cobre retomar uma pausa manual. FR7.5 e FR7.6.)*

**US4.10 (nova) — Acompanhar meu bot PAPER**
**Como** P1, **quero** ver numa tela o estado do bot, quanto dos limites já usei hoje, as operações simuladas e as ordens recusadas, **para que** eu saiba o que o bot está fazendo e possa agir rápido.
**Prioridade:** Must · **Requisitos:** FR8.3, FR9.3, FR11.1, FR4.5, FR7.3 · **Origem:** wireframes S5, fluxo F4 · **Depende de:** US4.3
- **AC4.10.1** Dado um bot ativo, quando abro a tela dele, então vejo nome, "MODO PAPER (simulado)", estado em texto, os limites e o uso do dia (exposição atual de exposição total, perda do dia de perda diária máxima).
- **AC4.10.2** Dado um bot ativo sem operações, quando abro a tela, então vejo "Nenhuma operação simulada até agora" e as regras em uso. *(S5 Vazio)*
- **AC4.10.3** Dadas operações simuladas, quando listadas, então cada uma aparece uma única vez, com horário, token, lado, quantidade, preço simulado, taxas, slippage e identificador.
- **AC4.10.4** Dadas ordens recusadas por limite, quando abro "ver motivos", então cada recusa mostra o limite que a barrou e o horário.
- **AC4.10.5** Dados dados de mercado desatualizados, quando a tela está aberta, então um aviso diz que o bot pode ficar sem operar até os dados voltarem a ficar frescos. *(S5 Parcial; FR8.2)*
- **AC4.10.6** Dada uma falha ao pausar ou encerrar, quando ocorre, então a mensagem permite tentar de novo e o estado exibido é sempre o último confirmado pelo servidor. A mudança de estado é anunciada a leitores de tela. *(S5 Erro; CT-UX-8)*

O "uso do dia" não tem FR próprio: a origem é o wireframe S5, um artefato da Ideação. Se o lead preferir não criar a história, os critérios AC4.10.1 a AC4.10.6 podem ser distribuídos entre US4.5, US4.6 e US4.8, mas S5 é a tela de controle do Must PB-03 e hoje não tem história própria.

### 7. Grupo 5 — Resultados (S2, S6, F5)

**US5.1** — acrescentar (fica com 6):
- **AC5.1.4** Dada a tela de P&L, quando exibida, então o título mostra "MODO PAPER (simulado)" em texto e o horário do cálculo ("Simulado às HH:MM"). *(FR9.3 exige o rótulo em S4 a S6; hoje só US4.3 o cobre.)*
- **AC5.1.5** Dado um período sem operações, quando filtro, então a tela explica que não houve operações e oferece atalho para o bot. *(S6 Vazio)*
- **AC5.1.6** Dada uma falha de cálculo, quando ocorre, então a tela mostra a mensagem e "Tentar de novo" e nenhum total parcial aparece como se estivesse completo. *(S6 Erro; F5)*

**US5.2** — acrescentar:
- **AC5.2.4** Dado o ledger, quando escolho "ver hipóteses", então vejo as premissas de spread, slippage, taxas e preenchimento e a versão da política de simulação. *(S6; F5 passo 4; FR9.1)*

**US5.3** — acrescentar (fica com 6):
- **AC5.3.4** Dada uma falha ao carregar um quadro, quando o painel abre, então só aquele quadro mostra a mensagem com "Tentar de novo" e os demais continuam visíveis. *(S2 Erro)*
- **AC5.3.5** Dado um bot ativo, quando uso "Pausar" no quadro de bots, então o efeito é o de AC4.8.1, sem sair do painel. *(S2)*
- **AC5.3.6** Dado um aviso novo de dados ou uma mudança de valor, quando chega com o painel aberto, então é anunciado a leitores de tela sem mover o foco. *(S2 Acessibilidade; CT-UX-8)*

### 8. Grupo 6 — Feedback

**US6.1** — acrescentar (supondo que o canal seja no produto, ver OBJECT abaixo):
- **AC6.1.4** Dada uma falha ao enviar, quando ocorre, então o texto escrito é preservado e posso tentar de novo.
- **AC6.1.5** Dado um envio concluído, quando confirmado, então vejo e ouço (leitor de tela) a confirmação, o diálogo fecha e o foco volta ao ponto de onde abri. *(CT-UX-5)*

### 9. Grupo 7 — Administração (sem tela nos wireframes)

As histórias US7.x não têm tela de referência: a Ideação não esboçou a área administrativa. Proponho registrar no rodapé de `stories.md` que o design funcional esboça uma tela administrativa mínima com os quatro estados, e acrescentar:

**US7.1**
- **AC7.1.4** Dada a lista de convites, quando aberta, então cada convite mostra o estado em texto (pendente, usado, expirado, revogado) e a validade. Revogar pede confirmação.

**US7.2** — acrescentar (fica com 5):
- **AC7.2.4** Dado o comando de acionar o kill switch, quando escolhido, então uma confirmação diz quantos bots ativos serão pausados e só age depois de confirmada. Enquanto ligado, o estado "Kill switch ACIONADO" fica visível em texto na área administrativa. *(Ação global, de efeito amplo; CT-UX-5)*
- **AC7.2.5** Dado um bot pausado pelo sistema, quando o dono abre o painel ou o bot, então vê em texto que a pausa foi feita pela administração do beta e, depois do desligamento, que precisa retomá-lo. *(Liga a ação de P3 à experiência de P1 e P2; complementa AC7.2.2.)*

### 10. Notas que não exigem critério

- **Janela estreita**: só computador está decidido e telas pequenas estão fora. Recomendo, sem criar requisito, que o design funcional defina uma largura mínima suportada e o que aparece abaixo dela (por exemplo, um aviso de que o beta é só para computador). Não proponho AC agora para não introduzir requisito sem origem.
- **Contraste de componentes e foco** (3:1, WCAG 1.4.11) fica como recomendação até o WCAG 2.1 AA completo exigido antes de abrir ao público (NFR11). Não é critério do beta.
- **Tamanho das histórias**: com os critérios transversais CT-UX como definição de pronto, as histórias continuam com no máximo 6 critérios e dentro de 1 a 3 dias (Q3). A exceção é US4.10, nova.

## Positions

- AGREE: Três personas, com dores e objetivos rotulados como hipótese a validar em FR14.2, seguem Q1 e a regra de não apresentar especulação como fato.
- AGREE: A organização por jornada (Q2) mapeia bem as telas S1 a S6 e os fluxos F1 a F5, o que facilita rastrear cada história até uma tela.
- AGREE: AC2.1.1 (fonte e horário por mouse **ou** foco), AC3.1.1 (foco volta à linha) e AC4.3.5 (rótulo PAPER em texto) já expressam bem a acessibilidade básica e as decisões dos mockups.
- AGREE: AC4.7.1 a AC4.7.4 e AC4.8.4 refletem fielmente Q5 e Q6, inclusive o estado visível de "encerramento pendente".
- AGREE: Só computador e só tema escuro não precisam de ACs de responsividade ou tema claro: já estão fora do escopo em requirements.md.
- OBJECT: Os estados carregando e erro das telas S1 a S7 estão ausentes de quase todas as histórias, embora os wireframes aprovados os definam. Proponho CT-UX-1 a CT-UX-9 e os ACs dos itens 3 a 9. Ponto de conhecimento (decorre dos wireframes, um especialista resolve).
- OBJECT: A tela S5 (acompanhamento do bot, uso dos limites, ordens recusadas) não tem história própria; proponho US4.10. Ponto de conhecimento (cobertura de tela aprovada; a única escolha é história nova ou ACs distribuídos, e o lead pode decidir).
- OBJECT: AC4.1.1 põe a estratégia na "etapa 1", mas FR7.2, S4 e F3 dizem que a etapa 1 é "Seleção de tokens", e as regras de seleção não têm critério. Proponho renomear para "Estratégia e tokens" e acrescentar AC4.1.5. Ponto de conhecimento (alinhamento com artefatos aprovados, sem mudar as 4 etapas).
- OBJECT: Retomar um bot pausado manualmente não aparece em nenhum critério (só a retomada após limite e após kill switch); proponho AC4.8.6. Ponto de conhecimento (FR7.5 e FR7.6 já definem estados e limite de 3).
- OBJECT: US1.3 promete "sair", mas nenhum critério cobre a saída, e a tela S7 (Conta) não tem história; proponho AC1.3.5, AC1.3.6 e US1.5. Ponto de conhecimento.
- OBJECT: AC6.1.1 ("escrever e enviar sem sair da tela") decide que o feedback é um formulário no produto, enquanto a Open Question de requirements.md deixa o canal (formulário no produto ou canal externo) para decisão. Julgamento (precisa da pessoa): confirmar o canal antes de fixar AC6.1.1, AC6.1.4 e AC6.1.5.
- OBJECT: O kill switch (US7.2) age sem confirmação e não diz como o dono do bot fica sabendo da pausa feita pelo sistema; proponho AC7.2.4 e AC7.2.5 e registrar que a área administrativa não tem wireframe. Ponto de conhecimento (padrão de confirmação para ação destrutiva de efeito amplo).
