# Perguntas de Domain Design — Trading REAL

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md` e o mapa do código (`architecture.md`, `component-inventory.md`).

## Q1. [Arquitetura] O ledger, o P&L e a barreira REAL são módulos novos ou os existentes com modo REAL?

Hoje 13 tabelas têm a coluna `mode` com `CHECK (mode = 'PAPER')`, e a chave única da ordem já inclui o modo.

A. Reaproveitar `ledger`, `pnl` e `riskcontrol` com o modo REAL: uma migração relaxa o `CHECK` para aceitar REAL; só o componente de execução REAL grava linhas REAL; toda consulta filtra pelo modo, com testes provando que nada cruza os modos
B. Criar módulos REAL separados, com tabelas próprias
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Arquitetura] Quais componentes novos entram?

A. Três: carteira do bot (ativação, consentimento, teto, referência da chave no KMS, assinatura, saque); execução REAL (ordem com estados, cotação e swap pela Jupiter, envio); rede (leitura da Solana: depósitos, confirmações, saldos e reconciliação); a liberação controlada entra no módulo de administração existente e a fonte real entra no `marketdata`
B. Um único módulo "real" com tudo
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Arquitetura] Como a decisão do bot chega à execução REAL?

O ciclo do bot é uma transação curta; a execução REAL é lenta e assíncrona.

A. O ciclo grava a decisão REAL e um evento na saída transacional (outbox), na mesma transação; a execução REAL consome o evento fora do ciclo
B. O ciclo chama a execução REAL diretamente
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q4. [Segurança] Quem pode pedir uma assinatura ao KMS?

A. Só o componente da carteira do bot fala com o KMS; a execução REAL pede a assinatura de uma transação específica, e a carteira confere antes de assinar: programas permitidos (só os da Jupiter e o de transferência), valor dentro do teto e do limite, saque só para a carteira principal verificada
B. A execução REAL chama o KMS diretamente
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q5. [Segurança] Onde fica o modelo de ameaças da carteira do bot (G2 do PRD)?

A. Em `decisions.md`, como um ADR com a tabela STRIDE (falsificação, adulteração, repúdio, vazamento, negação de serviço, elevação de privilégio) e a mitigação de cada ameaça, revisado nesta etapa
B. Num documento separado, depois
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Arquitetura] Como o sistema acompanha a rede?

A. O componente de rede consulta o RPC em intervalos curtos (assinaturas pendentes a cada 2 s, depósitos e saldos a cada 30 s, reconciliação a cada 15 min), com dois provedores; sem conexão permanente por enquanto
B. Conexão permanente (websocket) com o RPC
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q7. [Arquitetura] Onde ficam o modo e as preferências REAL do usuário?

A. No componente da carteira do bot: modo, teto, derrapagem e estado da liberação lido do administrador; a barreira lê esses valores pela interface pública da carteira
B. Na conta do usuário (identity)
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
