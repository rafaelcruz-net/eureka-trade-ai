# Declaração da intenção — Termos de uso e LGPD

## Problem Statement

O beta PAPER não pode receber convidados sem termos de uso definitivos e sem uma política de privacidade que cumpra a LGPD. Hoje os termos são uma versão provisória (v1) e não existe política de privacidade publicada. [Q1]

O pedido é ter termos gerais de uso definitivos (v2) e uma política de privacidade com retenção de dados, como em qualquer site. Isso inclui páginas públicas `/termos` e `/privacidade`, links no rodapé dos apps e no convite, novo aceite quando a versão muda, canal para pedidos do titular (acesso, correção, exclusão) e uma tabela de retenção alinhada aos expurgos existentes. [desc]

O atendimento de pedidos de exclusão precisa respeitar o ledger append-only: erros só se corrigem com lançamentos compensatórios. [desc] [memory:M1]

## Target Customer

| Cliente | O que ganha | Fonte |
|---|---|---|
| Convidados do beta (titulares dos dados) | Saber, antes de entrar, o que podem fazer, quais dados guardamos, por quanto tempo e como pedir acesso, correção ou exclusão | [Q2] |
| Responsável do projeto (controlador dos dados) | Regras claras para cumprir a LGPD e responder pedidos | [Q2] |

## Success Metrics

| Métrica | Meta | Fonte |
|---|---|---|
| Contas ativas que aceitaram a versão vigente dos termos e da política antes de usar o produto | 100% | [Q3] |
| Pedidos de titular respondidos em até 15 dias | 100% | [Q3] |
| Dados da tabela de retenção com expurgo ou regra de guarda testada | todos | [Q3] |
| Páginas de termos e privacidade acessíveis sem login, por teclado e com contraste legível | as duas | [Q3] |

## Initiative Trigger

Regulação: o parecer jurídico (C-09), aprovado em 2026-11-09, exige termos e política de privacidade antes de convidar pessoas. [Q4] [desc]

## Initial Scope Signal

- Escopo do workflow: `feature` (workflow-selected). [scope]
- Fronteira do produto confirmada para esta primeira versão. Ficam de fora: banner de cookies de terceiros (o produto não usa rastreamento, só armazenamento local necessário), exportação automática dos dados pelo próprio titular (o pedido é atendido pelo responsável), textos em outros idiomas, termos do modo REAL (que continua bloqueado) e encarregado externo contratado. [Q8]
- Os textos não prometem lucro nem garantia de detecção de fraude. [memory:M2]

## Assumptions & Open Questions

None.
