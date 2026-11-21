# Registro RAID — paper-real-unico

Riscos, premissas, problemas e dependências. A probabilidade e o impacto vão de Baixo a Alto.

## Riscos

| ID | Risco | Prob. | Impacto | Mitigação | Fontes |
|---|---|---|---|---|---|
| R1 | Até o ciclo mudar, um bot real vende tokens comprados por outro bot da mesma pessoa. Nesse período, o P&L por bot depende da regra de leitura para o excedente. | Alta | Médio | O excedente é separado na leitura e marcado no relatório. O ciclo passa a vender só a quantidade do bot depois da validação do intent do REAL. | `bots/worker/RealCycle.java`; `realexecution/infra/DecisionConsumer.java`; [Q10] |
| R2 | A mudança no ciclo do bot real atrasa, porque a Performance Validation do intent do REAL ainda não terminou. | Média | Médio | O cálculo na leitura entrega o P&L por bot antes. O ciclo vem numa etapa separada. | [Q6]; [Q10]; `261113-trading-real-bot/aidlc-state.md` |
| R5 | O P&L real atual não lê as vendas das condicionais (indício no código). O total não fecharia com o ledger depois de uma condicional executada, e a separação por bot herdaria o erro. | Média | Alto | Confirmar com um teste no intent `261113-trading-real-bot` e corrigir lá antes, porque esta feature parte desse total. Ver o problema I4 e a dependência D1. | `pnl/application/RealPnlService.java`; `realexecution/infra/JdbcRealExecutionStore.java` (`settled`); `realexits/application/FillPosting.java` |
| R6 | O simulado ao lado do real é lido como promessa de lucro. | Média | Alto | Rótulo de simulado em texto e aviso de que o simulado não garante o real. Nenhum ranking que misture os modos. | [memory] `project.md` Forbidden (C-16); [Q3] |
| R7 | O rateio [Q9] e o custo médio por bot [Q8], com arredondamento, fazem a soma das linhas diferir do total em alguns lamports. | Média | Médio | Regra fixa para o resto do arredondamento. O total é definido como a soma das linhas. Testes de resultado conhecido. | [memory] `project.md` Forbidden (ponto flutuante); `team.md` Testing Posture |
| R8 | A ordem das vendas importa para o custo médio e para o rateio. Hoje as ordens são lidas pela data da última atualização, não pela confirmação na rede. | Baixa | Médio | Ordenar pela confirmação na rede no cálculo por bot. | `JdbcRealExecutionStore.java` (`settled`, `order by updated_at`) |
| R9 | O recálculo na leitura, com todas as ordens da pessoa, fica lento com muito histórico. | Baixa no beta | Baixo | Medir na carga. Se preciso, guardar um resumo por bot depois. | [assumption]; [Q5]; [Q10] |
| R10 | Mexer nos arquivos comuns (`pnl` e painel) em paralelo com o intent do REAL gera conflito ou regressão. | Média | Médio | Rota ou campo novo, sem alterar a rota em validação. A CI verde e o `verify.sh` completo seguram a regressão. | [Q6]; `team.md` Way of Working |

## Premissas

| ID | Premissa | Como confirmar | Fontes |
|---|---|---|---|
| A1 | O simulado é o PAPER que já existe. O ambiente dos usuários é a mainnet. A devnet fica só para os testes do fundador. | Já confirmada no intent-capture e na Q7. | `intent-statement.md`; [Q7] |
| A2 | Toda ordem real com bot já tem o bot gravado, inclusive as antigas, então não é preciso migrar dados. | Restrição do banco desde a migração 0019. | `0019-real-execution.sql` |
| A3 | As únicas vendas reais sem bot são "vender tudo e sacar" e as condicionais. Não existe "ordem manual". | Leitura das origens possíveis no banco; confirmada na Q8 e na Q9. | `0019-real-execution.sql`; `0022-real-exits.sql`; [Q8]; [Q9] |
| A4 | O cálculo por bot é feito na leitura, sem tabela nova. | Decidido para o período até a mudança do ciclo [Q10]. O desenho funcional confirma se a solução continua assim depois. | [Q10] |
| A5 | Não precisa de novo parecer jurídico nem de novo aceite dos termos. | Confirmar na leitura dos termos v3 durante os requisitos, porque o ciclo do bot muda [Q10]. | [Q3]; [Q10] |
| A6 | Nenhum serviço AWS novo e nenhum custo novo. | Já confirmada. | [Q5]; [Q7] |
| A7 | Não há limite de bots reais por pessoa, então a mistura entre bots (R1) acontece na prática. | Nenhuma regra de limite encontrada no código. | `bots/application/BotCommandService.java` |
| A8 | Até a mudança do ciclo, o excedente da venda de um bot é rateado entre os outros bots donos do token, como as vendas sem bot, e o relatório o marca como vendido por outro bot. | Definir a regra exata nos requisitos. | [Q9]; [Q10] |

## Problemas (já presentes hoje)

| ID | Problema | Efeito | Encaminhamento | Fontes |
|---|---|---|---|---|
| I3 | O ciclo do bot real vende a posição da carteira inteira, não só a do bot. | O P&L por bot precisa da separação na leitura até a mudança do ciclo. | Decidido: o ciclo muda depois da validação do intent do REAL [Q10]. | `bots/worker/RealCycle.java`; [Q10] |
| I4 | Há indício de que o P&L real atual não inclui as condicionais executadas. | Problema do intent do REAL, que esta feature herda (R5). | Levar ao intent `261113-trading-real-bot` com a recomendação de confirmar o problema por um teste lá e corrigir antes dos requisitos desta feature. | `pnl/application/RealPnlService.java`; `realexits/application/FillPosting.java` |

Os problemas I1 (Assinada e ordem manual na linha "Sem bot") e I2 (PEPS contra o total de hoje) foram resolvidos pelas respostas da Q8 e da Q9.

## Dependências

| ID | Dependência | Situação | Efeito | Fontes |
|---|---|---|---|---|
| D1 | Intent `261113-trading-real-bot`: P&L REAL, ciclo do bot real, liquidação e condicionais. | Em Performance Validation, esperando a carga. | Esta feature parte do P&L REAL dele e não pode mudar o que ele valida. A mudança no ciclo do bot entra depois da validação [Q10]. A confirmação e a correção das condicionais fora do P&L REAL (R5, I4) cabem a esse intent. | [Q6]; [Q10]; `261113-trading-real-bot/aidlc-state.md` |
| D2 | REAL na mainnet do fundador. | Ainda não aplicado. O Terraform está desenhado. | O trabalho entra depois disso [Q5]. Os testes usam o validador local. | [Q5]; [Q7]; `team.md` Deployment |
| D3 | Módulos `bots`, `pnl`, `realexecution`, `realexits` e `ledger`, e as telas do painel e de bots em `apps/web`. | Existem. | Integração só por interface pública. | [Q2]; `team.md` Code Style |
| D4 | Termos v3, que descrevem o PAPER e o REAL. | Em vigor desde 2026-11-20. | Base para não precisar de novo aceite (A5). | [Q3]; commit `f5327ca` |

## Sources

- [Q1] a [Q10]: `ideation/feasibility/feasibility-questions.md`.
- `ideation/intent-capture/intent-statement.md` e `stakeholder-map.md`.
- [memory] `aidlc/spaces/default/memory/project.md` e `team.md`.
- `aidlc/spaces/default/intents/261113-trading-real-bot/aidlc-state.md`, com o estágio atual `performance-validation`.
- Os caminhos de código citados nas tabelas, sob `apps/api/src/main/`.

## Assumptions & Open Questions

- [assumption] O R5 (condicionais fora do P&L real) vem da leitura do código e ainda não foi confirmado por teste. A confirmação cabe ao intent `261113-trading-real-bot`.
- [assumption] A regra de leitura para o excedente de um bot até a mudança do ciclo (A8) é definida nos requisitos.
