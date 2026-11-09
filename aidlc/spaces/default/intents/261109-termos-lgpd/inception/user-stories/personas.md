# Personas — Termos de uso e LGPD

Conversa: português (Brasil). Base: `inception/requirements-analysis/requirements.md`, `aidlc/spaces/default/codekb/eureka-trader-ai/business-overview.md` e as respostas de `user-stories-questions.md` (Q1).

## P1 — Visitante (sem login)

- **Papel:** quem chega ao site sem conta ou sem sessão: um convidado antes de entrar, ou uma pessoa que perdeu o acesso.
- **Objetivos:** entender o que é o produto, que dados são tratados e como pedir algo sem conseguir entrar.
- **Dores:** texto jurídico longo e confuso; não saber a quem recorrer sem login.
- **Contexto:** computador ou celular; lê as páginas públicas `/termos` e `/privacidade`.

## P2 — Convidado (titular dos dados)

- **Papel:** pessoa convidada para o beta PAPER, que entra com a carteira Solana.
- **Objetivos:** aceitar termos claros, saber que tudo é simulado, acompanhar a versão aceita e exercer os direitos (acesso, correção, exclusão) com prazo conhecido.
- **Dores:** medo de expor a carteira; não ver o andamento de um pedido; ser bloqueado sem explicação quando os termos mudam.
- **Contexto:** usa o app do convidado, a tela Conta e o painel; nível técnico variado.

## P3 — Administrador (controlador e encarregado no beta)

- **Papel:** o responsável do projeto, que publica as versões, atende os pedidos e anonimiza contas.
- **Objetivos:** cumprir a LGPD com pouco trabalho manual, não perder prazo e nunca quebrar o ledger.
- **Dores:** prazo de 15 dias dependendo de uma pessoa só; risco de anonimização incompleta; risco de publicar texto diferente do sistema.
- **Contexto:** usa o app do administrador; único operador do beta.

## Relação e prioridade

| Prioridade | Persona | Por quê |
|---|---|---|
| 1 | Convidado | É o titular dos dados e o público do beta |
| 2 | Administrador | Faz os direitos do titular acontecerem |
| 3 | Visitante | Precisa ler sem login e ter um canal fora do app |

O Visitante vira Convidado ao entrar com convite. O Administrador atende os pedidos dos dois: os do Convidado pela Conta e os do Visitante pelo e-mail do encarregado.
