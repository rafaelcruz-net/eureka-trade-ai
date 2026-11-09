# Rascunhos de telas — Termos de uso e LGPD

Conversa: português (Brasil). Baixa fidelidade. Base: `ideation/intent-capture/intent-statement.md`, `ideation/scope-definition/scope-document.md`, `intent-backlog.md` e as respostas de `rough-mockups-questions.md`. Tema escuro e componentes atuais dos apps (Q4). Computador e celular: no celular, as colunas viram uma só (Q5).

## T1 — `/termos` (pública, nos dois apps)

```
+--------------------------------------------------------------+
| MemeTrade Analytics                         [Entrar]          |
+--------------------------------------------------------------+
| # Termos de uso                                               |
| Versão v2 · vigente desde <data>                              |
|                                                              |
| [i] Tudo neste beta é simulado (PAPER). Nenhum dinheiro real. |
|     Não prometemos lucro.                                     |
|                                                              |
| ## Resumo                                                     |
|  - <tópico 1 em linguagem simples>                            |
|  - ... (5 a 7 tópicos)                                        |
|                                                              |
| ## Índice                                                     |
|  1. Quem somos  2. O que é o beta  3. Sua conta  ...          |
|                                                              |
| ## 1. Quem somos                                              |
|  <texto>                                                      |
| ## 2. ...                                                     |
+--------------------------------------------------------------+
| Termos de uso · Privacidade                                   |
+--------------------------------------------------------------+
```

Acessibilidade: h1 "Termos de uso", h2 por seção; regiões cabeçalho, conteúdo principal e rodapé; o foco do teclado começa no link "Pular para o conteúdo".

## T2 — `/privacidade` (pública, nos dois apps)

```
+--------------------------------------------------------------+
| # Política de privacidade                                     |
| Versão v2 · vigente desde <data>                              |
| [i] Tudo é simulado (PAPER). Não usamos rastreamento.         |
| ## Resumo  (5 a 7 tópicos)                                    |
| ## Índice                                                     |
| ## Quais dados guardamos e para quê                           |
| ## Base legal                                                 |
| ## Por quanto tempo (tabela de retenção)                      |
|  | Dado            | Para quê     | Por quanto tempo | No fim  ||
|  | Carteira pública| entrar       | enquanto a conta | anonim. ||
|  | Feedback        | melhorar     | <prazo>          | apagado ||
|  | Trilha auditoria| segurança    | 12 meses         | apagada ||
|  | Lançamentos     | P&L simulado | <prazo>          | sem vínculo com você ||
| ## Com quem compartilhamos                                    |
| ## Onde os dados ficam  (<local definido na implantação>)     |
| ## Seus direitos                       [Fazer um pedido]      |
| ## Encarregado: <e-mail definido na implantação>              |
| ## Mudanças nesta política                                    |
+--------------------------------------------------------------+
```

As linhas da tabela são exemplos de forma. Os prazos reais vêm dos expurgos do sistema nas próximas etapas. Acessibilidade: h1, h2 por seção; a tabela tem cabeçalho de coluna; o botão "Fazer um pedido" leva à Conta (ou à entrada, sem login).

## T3 — Rodapé (todas as telas dos dois apps)

```
+--------------------------------------------------------------+
| Termos de uso · Privacidade · Versão aceita: v2               |
+--------------------------------------------------------------+
```

"Versão aceita" aparece só com login. Acessibilidade: região de rodapé; links com texto visível.

## T4 — Convite (mudança na tela existente)

```
+--------------------------------------------------------------+
| # Entrar com convite                                          |
| [i] Tudo é simulado (PAPER).                                  |
| Resumo dos termos (5 a 7 tópicos)                             |
| Ler completo: [Termos de uso]  [Política de privacidade]      |
| [ ] Li e aceito os Termos de uso e a Política de privacidade  |
| [ ] Entendo o aviso de risco                                  |
| [Conectar carteira]  (só habilitado com as duas marcadas)     |
+--------------------------------------------------------------+
```

Acessibilidade: h1; cada caixa tem rótulo clicável; o botão desabilitado explica por quê em texto.

## T5 — Conta (mudança na tela existente)

```
+--------------------------------------------------------------+
| # Conta                                                       |
| Carteira: 7xKX...9fA2   Termos e política aceitos: v2 em <data>|
|                                                              |
| ## Meus dados (LGPD)                                          |
| Tipo do pedido: ( ) Acesso ( ) Correção ( ) Exclusão ( ) Outro|
| Detalhes: [_______________________________]                   |
| [Enviar pedido]                                               |
|                                                              |
| ## Meus pedidos                                               |
| | Data     | Tipo     | Situação    | Prazo     |             |
| | 09/11    | Acesso   | Em análise  | 24/11     |             |
| | 01/11    | Correção | Concluído   | —         |             |
+--------------------------------------------------------------+
```

No pedido de exclusão, a tela explica antes de enviar: a conta será desativada, os dados que identificam a pessoa serão apagados ou trocados, e os lançamentos simulados ficam sem vínculo com ela. Acessibilidade: h1, h2; a situação aparece em texto, não só em cor.

## T6 — Administrador: pedidos de titular

```
+--------------------------------------------------------------+
| # Pedidos de titular                                          |
| Filtro: [Abertos v]                                           |
| | Recebido | Tipo     | Conta    | Prazo  | Situação   |      |
| | 09/11    | Exclusão | a1b2...  | 24/11  | Em análise | [>]  |
|                                                              |
| ## Pedido a1b2...                                             |
| Tipo: Exclusão · Recebido 09/11 · Prazo 24/11 (15 dias)       |
| Detalhes do titular: <texto>                                  |
| Resposta: [_____________________]                             |
| [Concluir pedido]                                             |
| [Anonimizar conta]                                            |
|   -> "Isto não pode ser desfeito. Digite ANONIMIZAR" [____]   |
|      [Confirmar anonimização]                                 |
+--------------------------------------------------------------+
```

Acessibilidade: h1, h2; a dupla confirmação usa texto digitado e não depende de cor; o prazo vencido aparece com ícone e texto.

## Tela de novo aceite (existente)

Ganha os links para T1 e T2 e o resumo do que mudou desde a versão aceita.
