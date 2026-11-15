# Perguntas de Definição de Escopo — Trading REAL

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/feasibility/feasibility-assessment.md` e `ideation/feasibility/constraint-register.md`.

Capacidades candidatas (proto-Units), usadas nas perguntas:
- **PB-01 Dados de mercado reais:** a CoinGecko paga atrás da interface de fonte de mercado existente.
- **PB-02 Carteira do bot:** criação com a chave no KMS, depósito até o teto configurável no perfil, saque só para a carteira principal.
- **PB-03 Executor:** cotação e swap pela Jupiter, assinatura pelo KMS, envio, confirmação e repetição sem duplicar.
- **PB-04 Ledger REAL e reconciliação:** lançamentos REAL separados do PAPER e conferência periódica com a blockchain.
- **PB-05 Barreira de risco REAL:** limites por ordem e por dia, teto, pausa e kill switch valendo para o REAL.
- **PB-06 Modos e consentimento:** o seletor de modo, o consentimento assinado para subir de modo e os termos do REAL com novo aceite.
- **PB-07 Modo Assinada:** o usuário assina cada ordem real na própria carteira.
- **PB-08 Automática nas saídas:** o stop e o alvo viram ordens condicionais da Jupiter.
- **PB-09 Liberação controlada:** REAL ligado por usuário pelo administrador, quarentena de 24 horas e a sequência fundador, convidados.
- **PB-10 Exportação fiscal:** o histórico REAL exportável pelo usuário.

## Q1. [Produto] O que é obrigatório na primeira versão REAL? (select all that apply)

A. Obrigatório (Must): PB-01, PB-02, PB-03, PB-04, PB-05, PB-06, PB-07 e PB-09; o modo Assinada entra porque é o padrão, e a Automática total é o foco
B. Importante, logo depois (Should): PB-08 (Automática nas saídas) e PB-10 (exportação fiscal)
C. Tudo, de PB-01 a PB-10, como obrigatório
D. Not yet defined
X. Other (please specify)

[Answer]: A, B

## Q2. [Produto] Qual ordem de construção?

A. Risco primeiro: provar as premissas técnicas (assinatura Ed25519 no KMS e um swap da Jupiter no validador clonado) e a fonte real de dados, depois carteira, executor, ledger e barreira, e só então telas e liberação
B. Valor primeiro: telas e modos antes, com o executor simulado
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Produto] Quais dependências valem entre as capacidades?

A. Dados reais (PB-01) e carteira do bot (PB-02) antes do executor (PB-03); executor antes do ledger REAL e da reconciliação (PB-04); barreira (PB-05) antes de qualquer ordem real; modos e termos (PB-06, PB-07) antes da liberação (PB-09); PB-08 e PB-10 dependem do executor e do ledger
B. Todas independentes
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Produto] Há prazo fixo para alguma capacidade?

A. None: sem data fixa, como na viabilidade
B. A carteira do bot até uma data definida
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Produto] Quais tokens o bot pode operar em REAL?

A. Só tokens que passaram pela avaliação do scanner com cobertura suficiente (dado `unknown` nunca conta como seguro), mais uma lista de bloqueio do administrador
B. Qualquer token negociável na Jupiter
C. Uma lista fechada mantida pelo administrador
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Produto] Em que moeda a carteira do bot opera?

A. SOL: o depósito, o teto e o saque são em SOL, e o bot compra e vende tokens contra SOL
B. USDC
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Produto] O que o kill switch faz no REAL?

A. Bloqueia novas ordens REAL de todos os usuários, cancela as ordens condicionais abertas feitas pelas carteiras do bot e mantém o saque liberado; as posições abertas ficam como estão
B. Também vende todas as posições abertas das carteiras do bot para SOL
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q8. [Produto] O que fica fora desta intent? (select all that apply)

A. Vault ou programa on-chain próprio e auditoria externa (G3)
B. Público aberto e cobrança
C. Outras redes e outros roteadores além da Solana e da Jupiter
D. Alavancagem, derivativos e empréstimo
E. None
X. Other (please specify)

[Answer]: A, B, C, D

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
