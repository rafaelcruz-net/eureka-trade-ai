# Perguntas — Units Generation

Objetivo: dividir os 14 componentes aprovados em [components.md](../domain-design/components.md) em Units de trabalho, cada uma implementável e testável por conta própria, e montar o grafo de dependências entre elas. A ordem de entrega (quem vem primeiro e por quê) fica para o planejamento de entrega; aqui decidimos só o que pode depender de quê.

Contexto já decidido e que não volta a ser perguntado: monólito modular em Spring Boot com módulos por pacote e frontend Next.js no mesmo monorepo ([team-practices.md](../practices-discovery/team-practices.md)); primeira Unit é a fatia fina de ponta a ponta do `team.md` (navegador → tela web → API → PostgreSQL, com fonte de mercado simulada); tipos do frontend gerados do contrato da API; duas interfaces web em endereços separados (ADR-009).

## Q1. [Arquitetura] Como recortar as Units?

Os componentes do backend se chamam por interfaces públicas dentro do mesmo processo, e as telas dependem deles. O recorte define o que cada Unit entrega e como as histórias se distribuem.

A. Por componente de domínio: cada Unit agrupa um ou dois componentes do backend; as interfaces web são Units à parte.
B. Por fatia vertical: cada Unit entrega backend e telas de uma área (descoberta, bot, resultados, acesso, administração).
C. Por camada: uma Unit para todo o backend e outra para todo o frontend.
D. Híbrido: o backend agrupado por área de domínio, e cada área entrega também as telas que dependem só dela; telas que juntam várias áreas (painel) ficam numa Unit própria.
X. Other (please specify)

[Answer]: A

## Q2. [Arquitetura] Qual o tamanho das Units?

Units maiores têm menos fronteiras para contratar; Units menores dão pontos de controle mais frequentes para um desenvolvedor solo.

A. Grossas: 4 a 6 Units.
B. Médias: 7 a 10 Units.
C. Finas: uma Unit por componente (cerca de 14).
X. Other (please specify)

[Answer]: A

## Q3. [Arquitetura] Como agrupar o núcleo financeiro?

O núcleo tem cinco componentes: gestão de bots (BotManagement), motor de decisão (DecisionEngine), barreira de limites (RiskControl), execução simulada e ledger (PaperTradingLedger) e P&L (PortfolioPnL). Hoje a barreira lê o P&L, o P&L lê o ledger, e a gestão de bots coordena os três. É a parte com TDD obrigatório.

A. Uma Unit por componente (5 Units).
B. Três Units: gestão de bots com motor de decisão; barreira de limites; ledger com P&L.
C. Três Units: motor de decisão; barreira de limites com ledger e P&L (o "livro-caixa com proteção"); gestão de bots coordenando as outras duas.
D. Uma única Unit com os cinco componentes.
X. Other (please specify)

[Answer]: B

## Q4. [Arquitetura] Identidade e auditoria ficam juntas ou separadas?

Quase toda Unit grava eventos na trilha de auditoria (AuditTrail) e lê o contexto de autenticação (IdentityAccess). A trilha não depende de nada; a identidade depende só da trilha.

A. Uma Unit só com identidade, convites, sessões e auditoria.
B. Duas Units: auditoria primeiro (sem dependências) e identidade depois.
C. Auditoria como biblioteca compartilhada (`library`) e identidade como Unit de serviço.
X. Other (please specify)

[Answer]: B

## Q5. [Arquitetura] Como dividir as interfaces web?

A interface do convidado tem nove telas (S1 a S9) e a do administrador tem duas (A1 e A2), em endereços separados.

A. Duas Units de interface: uma para o convidado (todas as telas) e outra para o administrador.
B. As telas acompanham as Units de backend de cada área (fatias verticais); o painel e a navegação geral numa Unit de interface própria; o administrador numa Unit própria.
C. Interface do convidado dividida por área (descoberta, bots, resultados e conta) e uma Unit para o administrador.
X. Other (please specify)

[Answer]: B

## Q6. [Arquitetura] Como cada Unit é implantada?

A stack prescrita é um monólito modular Spring Boot com workers, PostgreSQL e Next.js, tudo no Docker Compose local. A pergunta é como as Units viram processos.

A. Um único serviço de backend (todas as Units de backend são módulos dele, incluindo o agendador do ciclo dos bots) e um app web Next.js com as duas superfícies em rotas separadas.
B. Um serviço de backend e dois apps Next.js separados (convidado e administrador).
C. O backend em dois processos do mesmo código (API e worker do ciclo dos bots) e dois apps Next.js separados.
D. O backend em dois processos do mesmo código (API e worker) e um app Next.js com as duas superfícies.
X. Other (please specify)

[Answer]: C

## Q7. [Arquitetura] Como as Units se integram?

O design de domínio usa chamadas síncronas para o ciclo do bot e eventos para auditoria e painel. A especificação formal dos contratos vem na próxima etapa (Contract Design); aqui escolhemos o mecanismo.

A. Dentro do backend, chamadas síncronas pela interface pública de cada módulo e eventos em processo para auditoria e painel; entre frontend e backend, REST documentado por contrato com tipos gerados.
B. Como A, mas com eventos gravados na mesma transação da ação (outbox) e entregues depois, para não perder auditoria.
C. Tudo por eventos entre módulos, inclusive o ciclo do bot.
X. Other (please specify)

[Answer]: A

## Q8. [Entrega] O que entra na primeira Unit (esqueleto)?

O `team.md` define a fatia fina: navegador → tela web → API → PostgreSQL, com adaptador de mercado simulado alimentando uma lista mínima do scanner que mostra fonte, horário e `unknown`, verificada por um script de ponta a ponta. A história US2.1 é a fatia, sem login.

A. Exatamente a fatia do `team.md`: dados de mercado simulados, lista mínima do scanner e a tela correspondente, sem login.
B. A fatia do `team.md` mais o login por carteira, para o esqueleto já passar pela autenticação.
C. A fatia do `team.md` mais a gravação de um evento de auditoria, para provar o caminho de auditoria.
X. Other (please specify)

[Answer]: B

## Q9. [Entrega] Onde fica a fundação do repositório?

Antes do primeiro commit é preciso estruturar o monorepo (`apps/web`, `apps/api`, `infra/`), Docker Compose, `.editorconfig`, `.gitattributes`, varredura de segredos, CI com portões de segurança e cobertura e o script de verificação.

A. Dentro da primeira Unit (esqueleto): a fundação é parte de fazer a fatia rodar.
B. Numa Unit própria de empacotamento (`packaging`), da qual a primeira Unit de código depende.
C. Fundação mínima no esqueleto (Compose, script de verificação, segredos) e CI completa numa Unit de empacotamento separada.
X. Other (please specify)

[Answer]: A

## Q10. [Entrega] O grafo deve permitir Units em paralelo?

Você trabalha sozinho, mas o AI-DLC pode construir Units independentes ao mesmo tempo em cópias separadas do repositório, quando você escolher.

A. Sim: declarar só as dependências reais, para que Units independentes possam correr em paralelo.
B. Não: encadear todas as Units numa sequência única.
X. Other (please specify)

[Answer]: A

## Q11. [Entrega] Onde ficam as histórias Should?

Três histórias são Should: ver os dados da conta (US1.5), editar os limites de um bot ativo (US4.9) e enviar feedback (US6.1, com o componente Feedback).

A. Nas Units da área de cada uma (conta na identidade, edição de limites no núcleo de bots, feedback numa Unit pequena própria).
B. Numa Unit separada de "itens Should", construída depois do núcleo.
C. Fora das Units deste beta, marcadas como adiadas no mapa de histórias.
X. Other (please specify)

[Answer]: A

## Q12. [Entrega] As investigações da Sprint 0 viram Units?

Os itens SP-1 a SP-5 (comparar fontes de dados, entrevistas, inventário de dados pessoais, capacidade e prova de conceito da delegação em devnet) não são código do produto. A divergência entre fontes (US2.4) depende do resultado de SP-1.

A. Não: ficam fora das Units, como trabalho de investigação acompanhado no planejamento de entrega; US2.4 fica numa Unit que depende do resultado.
B. Sim, só a prova de conceito da delegação (SP-5), como Unit de especificação (`spec`) isolada.
C. Sim, todas como Units de especificação.
X. Other (please specify)

[Answer]: A

## Q13. [Arquitetura] Quantidade de Units: 4 a 6 ou o que as outras respostas pedem?

Seguimento de Q2. As respostas Q1 (por componente), Q3 (núcleo em 3 Units), Q4 (auditoria e identidade separadas) e Q5 (telas junto de cada área) somam cerca de 9 a 10 Units: esqueleto, auditoria, identidade e convites, descoberta (scanner e avaliação), bots e decisão, barreira de limites, ledger e P&L, administração com feedback, e painel. Isso passa do teto de 4 a 6 escolhido em Q2.

A. Aceitar cerca de 9 a 10 Units e manter Q1, Q3, Q4 e Q5 como estão.
B. Manter 4 a 6 Units: juntar o núcleo numa Unit (muda Q3) e auditoria com identidade (muda Q4).
C. Meio-termo de 7 a 8 Units: manter o núcleo em 3 Units (Q3) e juntar auditoria com identidade (muda Q4) e administração com o painel.
X. Other (please specify)

[Answer]: A

## Q14. [Arquitetura] As telas acompanham as Units de backend?

Seguimento de Q1 e Q5. Q1 escolheu interfaces web como Units à parte; Q5 escolheu que as telas do convidado acompanham a Unit de backend de cada área, com painel e navegação numa Unit própria e o administrador numa Unit própria.

A. Vale Q5: cada Unit de área entrega também suas telas no app do convidado; painel e navegação numa Unit própria; telas administrativas na Unit de administração.
B. Vale Q1: telas em Units de interface separadas das Units de backend.
X. Other (please specify)

[Answer]: A

## Q15. [Entrega] Login no esqueleto e a auditoria

Seguimento de Q4 e Q8. O esqueleto precisa ser a primeira Unit do grafo e incluir o login por carteira (Q8). Mas o login exige auditar cada tentativa recusada (critério de nonce reutilizado e assinatura expirada), e Q4 colocou a auditoria numa Unit própria que viria antes da identidade. A primeira Unit não pode depender de outra.

A. O esqueleto inclui a gravação mínima da auditoria (só o necessário para os eventos de login); a Unit de auditoria depois completa consulta, mascaramento e os demais eventos.
B. O esqueleto entrega o login sem eventos de auditoria; a Unit de auditoria acrescenta os eventos de login depois.
C. Tirar o login do esqueleto (volta à fatia do `team.md` sem login, como US2.1); login fica na Unit de identidade.
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- Recorte por componente de domínio (Q1), com as telas do convidado acompanhando a Unit de cada área; painel e navegação numa Unit própria; telas administrativas na Unit de administração (Q5, Q14).
- Cerca de 9 Units, acima do teto inicial de 4 a 6 (Q2 substituída por Q13).
- Núcleo financeiro em 3 Units: bots com decisão, barreira de limites, ledger com P&L (Q3).
- Auditoria e identidade em Units separadas (Q4); o esqueleto já grava a auditoria mínima dos eventos de login e a Unit de auditoria completa o resto (Q15).
- Implantação: backend em dois processos do mesmo código (API e worker do ciclo dos bots) e dois apps Next.js, convidado e administrador (Q6).
- Integração: chamadas síncronas pela interface pública de cada módulo, eventos em processo para auditoria e painel, REST com tipos gerados entre frontend e backend (Q7).
- Primeira Unit (esqueleto): fundação do repositório (monorepo, Compose, segredos, CI e script de verificação), dados de mercado simulados, lista mínima do scanner com fonte, horário e `unknown`, e login por carteira (Q8, Q9).
- Grafo com só as dependências reais, permitindo Units em paralelo (Q10).
- Histórias Should na área de cada uma: conta na identidade, edição de limites nos bots, feedback na administração (Q11).
- Investigações SP-1 a SP-5 fora das Units; US2.4 (divergência) fica na Unit de descoberta e depende do resultado de SP-1 (Q12).

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
