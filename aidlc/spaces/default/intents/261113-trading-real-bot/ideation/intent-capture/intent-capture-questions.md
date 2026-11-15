# Perguntas de Intent Capture — Trading REAL

Conversa: português (Brasil).

## Sources

- [desc] Initial description: "Trading REAL: modo automático total com carteira do bot com teto (uma carteira por usuário gerada pelo sistema, chave no KMS, depósito até um teto, saque só para a carteira principal do usuário). Modos: Assinada (padrão), Automática nas saídas (ordens condicionais da Jupiter) e Automática total (carteira do bot). Execução pela Jupiter, ledger REAL separado do PAPER e reconciliado com a blockchain, limites por ordem e por dia, kill switch. Fundador solo é o único revisor; sem G3 nesta fase; rollout: carteira do fundador primeiro, depois até 20 convidados, quarentena de 24 horas antes de cada liberação. Tudo em devnet antes da mainnet."
- [scope] Workflow-selected scope: `classic`.
- [memory:M1] `aidlc/spaces/default/memory/project.md#Mandated`: "ALWAYS manter o trading REAL automático desabilitado até que os gates G2 e G3 do PRD e as aprovações jurídicas sejam cumpridos; PAPER continua operacional. (origem: C-01, C-09, PRD §7 e §16) (affirmed 2026-10-26)"
- [memory:M2] `aidlc/spaces/default/memory/project.md#Forbidden`: "NEVER solicitar, receber ou armazenar seed phrase, chave privada ou aprovação genérica irrestrita do usuário. (origem: C-02, PRD §7 e §16) (affirmed 2026-10-26)"
- [memory:M3] `aidlc/spaces/default/memory/project.md#Forbidden`: "NEVER movimentar fundos reais ou implantar contratos on-chain sem aprovação explícita do responsável do projeto. (origem: PRD §19, initiative brief) (affirmed 2026-10-26)"
- [memory:M4] `aidlc/spaces/default/memory/project.md#Mandated`: "ALWAYS manter REAL e PAPER estritamente separados em fundos, ordens, ledger, posições e P&L. (origem: C-03, PRD §15 US12) (affirmed 2026-10-26)"

## Q1. [Produto] Qual problema de negócio esta iniciativa resolve?

Hoje o produto só opera em PAPER; o REAL automático está bloqueado pelas regras do projeto (M1).

A. Deixar o bot operar com dinheiro real, inclusive sem o usuário online (modo Automática total pela carteira do bot com teto), mantendo a ordem assinada como padrão e o risco limitado por teto, limites e kill switch
B. Só permitir ordens reais assinadas pelo usuário, uma a uma
C. Validar se os convidados querem operar com dinheiro real antes de construir
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q2. [Produto] Quem são os clientes desta primeira versão?

A. Primeiro o próprio fundador, com a carteira dele; depois até 20 convidados do beta
B. Os convidados do beta desde o início
C. O público aberto
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q3. [Produto] O que mostra que deu certo? (select all that apply)

A. Zero ordens acima dos limites autorizados (por ordem, por dia e teto da carteira do bot)
B. 100% das transações reais rastreáveis pela assinatura na blockchain
C. Divergência entre o ledger REAL e a blockchain sempre conciliada e sinalizada
D. Nenhum saque para endereço diferente da carteira principal do usuário, e nenhum incidente de chave
E. Not yet defined
X. Other (please specify)

[Answer]: A, B, C, D

## Q4. [Produto] Por que agora?

A. Decisão do fundador de lançar uso real já no beta, como startup no início; o G3 (auditoria externa) fica dispensado nesta fase e será reavaliado com o crescimento
B. Pressão de concorrentes que já operam real
C. Exigência regulatória
D. Not identified
X. Other (please specify)

[Answer]: A

## Q5. [Produto] Quem são as partes interessadas?

A. O fundador (dono do produto, desenvolvedor e único revisor); os convidados (até 20); o advogado do parecer jurídico (pergunta de custódia); e os fornecedores dos quais o REAL depende (Jupiter para execução, provedor de RPC, KMS)
B. Só o fundador e os convidados
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q6. [Produto] Quem decide escopo e prioridade?

As regras do time dizem hoje que a autoaprovação do fundador não satisfaz os gates G2/G3 do REAL.

A. O fundador decide e é o único revisor; não existe outro aprovador
B. O fundador decide, mas o advogado tem veto sobre a liberação na mainnet para convidados
C. Not identified
X. Other (please specify)

[Answer]: A

## Q7. [Produto] Há exigência de comunicação ou de relatório?

A. Sem cadência formal: as decisões ficam nos registros e nos gates do AI-DLC; os convidados são avisados no app antes de cada liberação e quando o kill switch for acionado
B. Relatório semanal do piloto
C. Not applicable
X. Other (please specify)

[Answer]: A

## Q8. [Produto] O que fica fora desta primeira versão? (select all that apply)

A. Vault ou programa on-chain próprio (delegação do G2 do PRD)
B. Auditoria externa (G3)
C. Público aberto e cobrança
D. Outras redes além da Solana e outros roteadores além da Jupiter
E. None
X. Other (please specify)

[Answer]: A, B, C, D

## Q9. [Produto] Quais modos existem e qual é o padrão?

A. Três modos: Assinada (padrão), Automática nas saídas (ordens condicionais da Jupiter) e Automática total (carteira do bot com teto); o usuário troca quando quiser: voltar para um modo mais restrito é imediato, subir pede consentimento assinado; o foco desta intent é a Automática total
B. Só a Automática total
C. Só Assinada e Automática nas saídas
D. Not yet defined
X. Other (please specify)

[Answer]: A

## Q10. [Produto] Qual é o teto da carteira do bot por usuário?

Esse valor é uma decisão do fundador; ele limita a perda máxima por usuário.

A. Not yet defined (decidir na etapa de viabilidade, com um valor para o fundador e outro para os convidados)
B. 1 SOL por usuário
C. 5 SOL por usuário
D. Um valor em reais ou dólares, convertido na hora do depósito
X. Other (please specify)

[Answer]: A

## Q11. [Jurídico] Como tratar a pergunta de custódia da carteira do bot?

A chave da carteira do bot fica com o sistema (KMS), e o sistema move o saldo sem o usuário; o projeto proíbe guardar chave privada do usuário (M2).

A. Posição do fundador: não é custódia, pela responsabilidade compartilhada da carteira; o parecer escrito do advogado fica recomendado antes de liberar a mainnet para convidados, sem bloquear a construção
B. Exigir o parecer escrito antes de liberar a mainnet para convidados
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Q12. [Produto] Como será a liberação?

A. Tudo em devnet primeiro; depois mainnet só com a carteira do fundador; depois até 20 convidados; cada liberação só vale após 24 horas com tudo verde; nenhum fundo real se move sem aprovação explícita do fundador (M3)
B. Devnet e depois todos os convidados de uma vez
C. Not yet defined
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
