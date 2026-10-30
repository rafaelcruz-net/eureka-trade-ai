# Perguntas do design de domínio — MemeTrade Analytics

## Contexto

- Requisitos FR1 a FR15 e NFR1 a NFR14 em `../requirements-analysis/requirements.md`; 33 histórias e o anexo de transições do bot em `../user-stories/stories.md`.
- Práticas: monólito modular organizado por módulo, fronteiras por convenção, adaptadores externos atrás de interfaces, `unknown` como tipo explícito, tenant vindo da autenticação (`../practices-discovery/team-practices.md`).
- Este passo decide os **blocos lógicos** (componentes) e quem é dono de cada dado. Topologia de implantação, tecnologia e padrões não funcionais ficam para etapas seguintes.
- Componentes candidatos a partir do PRD (§12), recortados para o beta: Identidade e acesso, Auditoria, Dados de mercado, Scanner, Avaliação de risco, Bots e estratégias, Motor de decisão, Controle de risco pré-ordem, Execução PAPER, Ledger, Portfólio e P&L, Administração, Feedback. REAL, cobrança e IA ficam fora.

## Q1. [Arquitetura] Como fica o tenant no beta?
O PRD prevê que cada usuário pertence a um tenant lógico, com membros.

A. Cada convidado é um tenant próprio no beta; o modelo já guarda o vínculo usuário–tenant para permitir mais membros no futuro (recomendado)
B. Tenants compartilhados por vários usuários desde o beta
X. Other (please specify)

[Answer]: B. Tenants compartilhados

## Q2. [Arquitetura] A avaliação de risco do token e o controle de risco antes da ordem são o mesmo bloco?
Um calcula o indicador de 0 a 100 para a tela; o outro barra ordens por limites do bot.

A. Dois componentes: "Avaliação de risco" (indicador do token) e "Controle de risco" (barreira de limites antes de cada ordem) — mudam por motivos diferentes (recomendado)
B. Um único componente "Risco" com as duas responsabilidades
X. Other (please specify)

[Answer]: A. Dois componentes

## Q3. [Arquitetura] Quem grava operação, lançamentos e posição, que precisam ser gravados juntos ou nada (AC4.11.2)?

A. Um componente "Execução PAPER e ledger" dono de ordem, preenchimento, lançamentos e posição, numa única gravação; "Portfólio e P&L" separado, só de leitura sobre o ledger (recomendado: evita transação entre componentes)
B. Execução PAPER e Ledger separados, sincronizados por eventos com reconciliação
X. Other (please specify)

[Answer]: A. Execução PAPER e ledger juntos

## Q4. [Arquitetura] Como o ciclo do bot encadeia decisão, controle de risco e execução?

A. Fluxo síncrono por ciclo, coordenado pelo componente de bots: decisão → controle de risco → execução PAPER; eventos só para avisar painel e auditoria (recomendado: mais simples de testar e de garantir "nenhuma ordem sem barreira")
B. Tudo por eventos assíncronos entre os componentes
X. Other (please specify)

[Answer]: A. Fluxo síncrono por ciclo

## Q5. [Arquitetura] Como a trilha de auditoria é organizada?

A. Componente próprio de auditoria, que recebe eventos de todos os outros e é o único dono dos registros de auditoria (recomendado)
B. Cada componente grava sua própria trilha
X. Other (please specify)

[Answer]: A. Componente de auditoria

## Q6. [Arquitetura] Onde ficam convites, kill switch e estado do sistema?

A. Convites em "Identidade e acesso"; um componente fino "Administração" é dono do estado do kill switch e monta o estado do sistema a partir de contagens dos outros (recomendado)
B. Um componente "Administração" dono de convites, kill switch e estado
X. Other (please specify)

[Answer]: A. Administração fina

## Q7. [Arquitetura] Quanto histórico de dados de mercado o sistema guarda?
As estratégias precisam de uma janela recente de preço e volume (AC4.5.3).

A. Dados de mercado guardam observações pela janela necessária às estratégias e ao P&L (proposta inicial: 24 horas) e descartam o restante; o prazo final sai do design funcional e do inventário de dados (recomendado)
B. Guardar todo o histórico observado
X. Other (please specify)

[Answer]: A. Janela de 24 h

## Q8. [Arquitetura] Dentro de um tenant compartilhado, o que cada membro vê dos outros?
Você escolheu tenants compartilhados (Q1). As histórias atuais dizem que nenhum outro convidado vê ou altera os bots, operações e resultados de alguém (US1.4), e cada convidado entra com o próprio convite (US1.1). Precisamos saber se o isolamento é por pessoa ou por tenant.

A. Isolamento continua por pessoa: membros do mesmo tenant não veem bots, ledger nem P&L uns dos outros; o tenant agrupa pessoas só para administração e cobrança futura; no beta, o administrador escolhe o tenant ao emitir o convite (recomendado: mantém US1.4 como está)
B. Membros do mesmo tenant compartilham tudo (bots, ledger e P&L), como uma equipe; o isolamento passa a ser entre tenants, e US1.4 muda para "outros tenants"
C. Membros veem os resultados uns dos outros, mas só o dono altera seus bots
X. Other (please specify)

[Answer]: A. Isolamento por pessoa

## Consolidated Summary Confirmation

- Tenants compartilhados por vários usuários desde o beta; o administrador escolhe o tenant ao emitir o convite.
- Isolamento continua por pessoa: membros do mesmo tenant não veem bots, ledger nem P&L uns dos outros; o tenant agrupa pessoas para administração e cobrança futura.
- Dois componentes de risco: "Avaliação de risco" (indicador do token) e "Controle de risco" (barreira de limites antes de cada ordem).
- Um componente "Execução PAPER e ledger" grava ordem, preenchimento, lançamentos e posição juntos; "Portfólio e P&L" é separado e só lê o ledger.
- Ciclo do bot síncrono, coordenado pelo componente de bots: decisão → controle de risco → execução PAPER; eventos só para painel e auditoria.
- Componente próprio de auditoria, único dono dos registros, alimentado por eventos dos demais.
- Convites ficam em "Identidade e acesso"; "Administração" fina é dona do kill switch e monta o estado do sistema a partir de contagens.
- Dados de mercado guardam observações pela janela de 24 horas (proposta inicial), com prazo final no design funcional e no inventário de dados.

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
