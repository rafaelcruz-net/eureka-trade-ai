# Declaração da intenção — Trading REAL

## Problem Statement

O produto hoje só opera em PAPER, e as regras do projeto mantêm o trading REAL automático desligado até os gates G2 e G3 e as aprovações jurídicas [memory:M1]. A iniciativa permite que o bot opere com dinheiro real, inclusive sem o usuário online, pelo modo Automática total com a carteira do bot com teto; a ordem assinada continua sendo o padrão, e o risco fica limitado por teto, limites e kill switch [Q1].

A carteira do bot é uma carteira por usuário gerada pelo sistema, com a chave no KMS, depósito até um teto e saque só para a carteira principal do usuário [desc].

A execução é pela Jupiter, com ledger REAL separado do PAPER e reconciliado com a blockchain, limites por ordem e por dia e kill switch [desc]. REAL e PAPER continuam estritamente separados em fundos, ordens, ledger, posições e P&L [memory:M4].

## Target Customer

| Cliente | Quando | Fonte |
|---|---|---|
| O fundador, operando com a própria carteira | primeiro | [Q2] |
| Até 20 convidados do beta | depois do fundador | [Q2] |

## Success Metrics

| Métrica | Meta | Fonte |
|---|---|---|
| Ordens acima dos limites autorizados (por ordem, por dia e teto da carteira do bot) | zero | [Q3] |
| Transações reais rastreáveis pela assinatura na blockchain | 100% | [Q3] |
| Divergência entre o ledger REAL e a blockchain | sempre conciliada e sinalizada | [Q3] |
| Saques para endereço diferente da carteira principal do usuário | zero | [Q3] |
| Incidentes de chave | zero | [Q3] |

## Initiative Trigger

Decisão do fundador de lançar o uso real já no beta, como startup no início; o G3 (auditoria externa) fica dispensado nesta fase e será reavaliado com o crescimento [Q4]. A descrição inicial também registra que não há G3 nesta fase e que o fundador solo é o único revisor [desc].

## Modos de operação

- **Assinada (padrão):** o usuário assina cada ordem [Q9].
- **Automática nas saídas:** as saídas usam ordens condicionais da Jupiter [Q9].
- **Automática total:** o bot opera pela carteira do bot com teto; é o foco desta intent [Q9].
- **Troca de modo:** a qualquer hora; voltar para um modo mais restrito é imediato, e subir pede consentimento assinado [Q9].

## Initial Scope Signal

- **Escopo do workflow (workflow-selected):** `classic`, com o plano sob medida aprovado para esta intent [scope].
- **Fronteira do produto confirmada pelo fundador — fora desta versão** [Q8]:
  - vault ou programa on-chain próprio (delegação do G2 do PRD); [Q8]
  - auditoria externa (G3); [Q8]
  - público aberto e cobrança; [Q8]
  - outras redes além da Solana e outros roteadores além da Jupiter. [Q8]
- **Liberação** [Q12]: tudo em devnet primeiro; depois a mainnet só com a carteira do fundador; depois até 20 convidados; cada liberação só vale após 24 horas com tudo verde.
- **Fundos reais:** nenhum fundo real se move sem aprovação explícita do fundador [memory:M3].
- **Teto da carteira do bot:** ainda não definido; será decidido na etapa de viabilidade, com um valor para o fundador e outro para os convidados [Q10].
- **Custódia:** posição do fundador: não é custódia, pela responsabilidade compartilhada da carteira; o parecer escrito do advogado fica recomendado antes de liberar a mainnet para convidados, sem bloquear a construção [Q11].
- **Chaves do usuário:** o projeto proíbe solicitar, receber ou armazenar a chave privada do usuário [memory:M2]; a chave guardada no KMS é a da carteira do bot, gerada pelo sistema [desc].

## Assumptions & Open Questions

None.
