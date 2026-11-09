# Fluxos — Termos de uso e LGPD

Conversa: português (Brasil). Base: `ideation/intent-capture/intent-statement.md`, `ideation/scope-definition/scope-document.md`, `intent-backlog.md` e `rough-mockups-questions.md` (Q2).

## Fluxo do convidado

```mermaid
flowchart TD
  A[Convite T4] --> B[Lê o resumo]
  B --> C{Quer ler completo?}
  C -->|sim| D[T1 /termos e T2 /privacidade]
  D --> E[Volta ao convite]
  C -->|não| E
  E --> F[Marca as duas caixas]
  F --> G[Conecta a carteira e entra]
  G --> H[Usa o beta PAPER]
  H --> I[Conta T5: faz um pedido]
  I --> J[Acompanha a situação e o prazo]
```

Texto: convite → resumo → (opcional) páginas completas → aceite → entra → usa o beta → Conta: pedido → acompanha a situação.

## Fluxo do administrador

```mermaid
flowchart TD
  A[T6: lista de pedidos abertos] --> B[Abre o pedido]
  B --> C{Tipo}
  C -->|acesso, correção, outro| D[Responde e conclui]
  C -->|exclusão| E[Anonimizar conta]
  E --> F[Digita ANONIMIZAR e confirma]
  F --> G[Conta desativada, sessões revogadas, dados pessoais apagados ou trocados]
  G --> D
```

Texto: lista → pedido → acesso, correção ou outro: responde e conclui; exclusão: anonimizar com dupla confirmação → conta desativada, sessões revogadas, dados pessoais apagados ou trocados → conclui.

## Mudança de versão

Versão nova publicada → no próximo acesso, a tela de novo aceite mostra o resumo do que mudou e os links → o convidado aceita → continua.

## Arquitetura da informação

| Lugar | Conteúdo |
|---|---|
| Páginas públicas | `/termos`, `/privacidade` |
| Rodapé | links para as duas páginas e a versão aceita, quando há login |
| Convite | resumo, links e aceite |
| Conta | versão aceita, pedidos de titular |
| Administrador | pedidos de titular e anonimização |
