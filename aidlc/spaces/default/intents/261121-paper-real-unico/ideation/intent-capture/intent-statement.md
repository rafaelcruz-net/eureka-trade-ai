# Declaração da intenção — paper-real-unico

## Problem Statement

O usuário precisa de um único lugar onde cria bots simulados e reais lado a lado e acompanha o resultado de cada um, sem trocar de ambiente nem de conta [Q1].

O fundador também quer evitar o custo de manter dois ambientes, devnet e mainnet, para os usuários [Q1].

O pedido inicial é ter "somente um ambiente aonde o usuário possa criar bots com trade real e trade simulado", com "P&L categorizado por bot e geral entre devnet e mainnet" [desc].

## Target Customer

Os clientes são os convidados do beta e o próprio fundador [Q3].

Hoje, o bot nasce PAPER ou REAL e não muda, e o resultado do PAPER e o do REAL ficam em telas separadas. Não existe uma visão por bot nem um total [Q3].

## Success Metrics

- O usuário cria um bot simulado e um bot real no mesmo ambiente [Q4].
- O usuário vê o P&L de cada bot e o total, separado entre simulado e real [Q4].
- O P&L de cada modo fecha com o ledger do próprio modo [Q4].

## Initiative Trigger

O REAL está ficando pronto, e o fundador quer que o usuário teste no simulado e passe para o real dentro do mesmo produto [Q5].

## Initial Scope Signal

**Escopo do fluxo (workflow-selected):** `feature` [scope].

**O que é "simulado" nesta intenção:** o simulado é o PAPER que já existe, com saldo virtual, preços reais da mainnet e nenhuma ordem enviada à rede. No pedido, "devnet" quer dizer simulado, e o ambiente único dos usuários fica na mainnet [Q2].

**Limites desta versão, confirmados pelo responsável.** Esta versão não faz nada do que segue [Q8]:
- misturar saldos, ordens ou P&L entre simulado e real [Q8];
- converter um bot existente de um modo para o outro [Q8];
- criar uma nova rede de execução além da que já existe [Q8];
- incluir a exportação fiscal do simulado [Q8].

A regra do projeto continua valendo: REAL e PAPER ficam estritamente separados em fundos, ordens, ledger, posições e P&L [memory:M1].

## Assumptions & Open Questions

- [assumption] O ambiente de devnet continua existindo só para os testes do produto pelo fundador, e não para os usuários.
