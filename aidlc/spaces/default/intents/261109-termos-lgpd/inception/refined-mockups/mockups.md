# Telas refinadas — Termos de uso e LGPD

Conversa: português (Brasil). Média fidelidade, tema escuro dos apps. Base: `ideation/rough-mockups/wireframes.md` e `user-flow.md`, `inception/user-stories/stories.md`, `inception/requirements-analysis/requirements.md`, `inception/practices-discovery/team-practices.md` e as respostas de `refined-mockups-questions.md`. Componentes em `design-system-mapping.md`, interações em `interaction-spec.md`, acessibilidade em `accessibility-checklist.md`.

## T1 — `/termos` (US1.1, US2.1, US2.6)

Pública, nos dois apps, adaptável de 320 px em diante. Usa `LegalDocument` dentro de um cabeçalho simples (marca e "Entrar") e de `SiteFooter`.

```
+----------------------------------------------------------------------+
| [Pular para o conteúdo]                                              |
| MemeTrade Analytics                                       [Entrar]   |
+----------------------------------------------------------------------+
|  # Termos de uso                                                     |
|  Versão v2 · vigente desde 15/11/2026                                |
|  .notice: [i] Tudo neste beta é simulado (PAPER). Nenhuma operação   |
|           usa dinheiro real. Não prometemos lucro.                   |
|                                                                      |
|  ## Resumo                         | ## Nesta página                 |
|  - <tópico 1>                      | 1. Quem somos                   |
|  - ...  (5 a 7)                    | 2. O que é o beta               |
|                                    | 3. Sua conta e a carteira       |
|  ## 1. Quem somos                  | ...                             |
|  <texto, até 72 caracteres/linha>  | Versões anteriores (US2.6)      |
|  ## 2. ...                         |                                 |
+----------------------------------------------------------------------+
| Termos de uso · Privacidade                                          |
+----------------------------------------------------------------------+
```

No celular, o índice vai para o topo, recolhido em "Nesta página".

**Estados:**
- carregando: três `.skeleton-row` no lugar do texto;
- erro: `.notice-error` "Não foi possível carregar os termos. [Tentar de novo]".

## T2 — `/privacidade` (US1.1, US1.2, US2.1, US2.4, US2.5)

Mesma estrutura de T1, com seções próprias e a tabela de retenção.

```
|  # Política de privacidade                                           |
|  Versão v2 · vigente desde 15/11/2026                                |
|  .notice: Tudo é simulado (PAPER). Não usamos rastreamento.          |
|  ## Resumo (5 a 7 tópicos)                                           |
|  ## Quais dados guardamos e para quê                                 |
|  ## Base legal                                                       |
|  ## Por quanto tempo guardamos                                       |
|   | Dado              | Para quê        | Prazo              | No fim |
|   | Carteira pública  | entrar          | enquanto a conta   | apagada na exclusão |
|   | Trilha de auditoria| segurança      | 12 meses           | apagada |
|   | ... (linhas de FR3.4)                                            |
|  ## Com quem compartilhamos                                          |
|  ## Onde os dados ficam: <local configurado>                         |
|  ## Seus direitos                    [Fazer um pedido]                |
|   Sem conseguir entrar? Escreva para <e-mail do encarregado>.        |
|  ## Encarregado: <e-mail configurado>                                |
|  ## Mudanças nesta política                                          |
```

**Estado parcial:** com um campo de implantação ausente, o valor aparece como "a definir", com `.notice-warning` no topo: "Esta versão ainda não foi publicada para convidados".

**Tabela de retenção no celular (abaixo de 768 px):** cada linha vira um bloco empilhado (Dado, Para quê, Prazo, No fim), com os rótulos visíveis. Não há rolagem horizontal na página.

**"Fazer um pedido":**
- com sessão, leva à Conta, na seção "Meus dados (LGPD)";
- sem sessão, leva à entrada (`/entrar`), e logo abaixo do botão a página diz: "Sem conseguir entrar (conta anonimizada ou carteira perdida)? Escreva para <e-mail do encarregado>".

## T3 — Rodapé `SiteFooter` (US2.2)

```
+----------------------------------------------------------------------+
| Termos de uso · Privacidade                  Versão aceita: v2       |
+----------------------------------------------------------------------+
```

"Versão aceita" só aparece com login. No celular, os links ficam empilhados.

## T4 — Convite (US2.3; mudança na tela existente)

```
+----------------------------------------------------------------------+
| # Entrar com convite                                                 |
| .notice: Tudo é simulado (PAPER).                                    |
| Resumo dos termos e da política (5 a 7 tópicos)                      |
| Ler completo: [Termos de uso ↗] [Política de privacidade ↗]          |
| [ ] Li e aceito os Termos de uso e a Política de privacidade         |
| [ ] Entendo o aviso de risco                                         |
| [Conectar carteira]  (desabilitado)                                  |
| .muted: Marque as duas caixas para continuar.                        |
+----------------------------------------------------------------------+
```

Os links abrem as páginas na mesma aba e, ao voltar, as marcações continuam. Com os documentos carregando, as caixas ficam desabilitadas e aparecem as `.skeleton-row`. Com erro, aparece `.notice-error` com "tentar de novo".

## T5 — Conta: "Meus dados (LGPD)" (US5.1, US5.2, US5.5)

```
+----------------------------------------------------------------------+
| # Conta                                                              |
| Carteira 7xKX…9fA2 · Phantom                                         |
| Termos e política aceitos: v2 em 15/11/2026  [Ver termos] [Ver política]|
|                                                                      |
| ## Meus dados (LGPD)                                                 |
| Tipo do pedido (obrigatório)                                         |
|  (•) Acesso  ( ) Correção  ( ) Exclusão  ( ) Outro                   |
| Detalhes (até 1000 caracteres)          0/1000                      |
| [__________________________________________]                        |
| [Enviar pedido]                                                      |
| .muted: Respondemos em até 15 dias corridos.                         |
|                                                                      |
| ## Meus pedidos                                                      |
| | Recebido | Tipo     | Situação         | Prazo    |                |
| | 15/11    | Acesso   | [Em andamento]   | 30/11    | [Ver]          |
| | 01/11    | Correção | [Concluído]      | —        | [Ver]          |
|                                                                      |
| ### Pedido de Correção · Concluído                                   |
| Resposta: "<texto da resposta>"                                      |
|                                                                      |
| ### Pedido de Acesso · Recusado                                      |
| Motivo da recusa: "<motivo escrito pelo administrador>"              |
| Resposta: "<texto da resposta>"                                      |
+----------------------------------------------------------------------+
```

**Estados de T5:**

| Estado | O que aparece |
|---|---|
| Carregando | `.skeleton-row` em "Meus pedidos"; o formulário fica visível |
| Vazio | "Você ainda não fez nenhum pedido. Use o formulário acima." |
| Enviando | "Enviar pedido" desabilitado, com "Enviando…" |
| Sucesso | `.notice`: "Pedido recebido em dd/mm. Respondemos até dd/mm." A lista é atualizada e o foco vai para o aviso |
| Erro de validação (`400`) | `.field-error` junto do campo: "Escolha um tipo" ou "Use até 1000 caracteres" |
| Pedido repetido (`409`) | `.field-error`: "Você já tem um pedido de <tipo> em aberto." |
| Erro técnico (5xx, rede) | `.notice-error`: "Não foi possível enviar agora. Seu texto foi mantido. [Tentar de novo]" |
| Erro ao carregar a lista | `.notice-error` no lugar da lista, com "Tentar de novo" |

Na Exclusão, um aviso aparece antes do envio: "A conta será desativada, os dados que identificam você serão apagados e o histórico simulado fica sem vínculo com você. Isso não pode ser desfeito".

Ao concluir um Acesso, o detalhe mostra o resumo liberado (US5.4).

**Estados:**
- vazio: "Você ainda não fez nenhum pedido";
- erro `409`: `.field-error` "Você já tem um pedido de Acesso em aberto";
- erro `400`: o campo mostra o limite.

## T6 — Administrador: pedidos de titular (US5.3, US5.4, US5.6, US5.7, US5.9)

```
+----------------------------------------------------------------------+
| # Pedidos de titular                    [Registrar pedido por e-mail] |
| Situação: [Abertos v]  (Should)                                       |
| | Recebido | Tipo     | Origem | Conta     | Prazo        | Situação  |
| | 15/11    | Exclusão | E-mail | a1b2…     | 30/11 ⚠ 2 dias| [Aguardando verificação] |
| | 10/11    | Acesso   | Conta  | c3d4…     | 25/11        | [Em andamento] |
|                                                                      |
| ## Pedido a1b2… · Exclusão · Origem e-mail                           |
| Recebido 15/11 · Prazo 30/11 · Contato: <e-mail informado>           |
| Detalhes: <texto>                                                    |
|                                                                      |
| ### Verificação de identidade                                        |
| Mensagem (válida até 16/11 14:32):                                   |
| [ MemeTrade · verificação de pedido de titular · pedido a1b2… ...]   |
| [Copiar mensagem]                                                    |
| Assinatura recebida: [_______________________]  [Conferir]           |
| .notice: Identidade confirmada.  |  .notice-error: Assinatura inválida.|
|                                                                      |
| ### Resposta                                                         |
| [Usar resposta modelo de Correção]                                    |
| [__________________________________________]                         |
| [Ver resumo de Acesso ▸]   (painel expansível, US5.4)                |
| [Marcar em andamento] [Concluir] [Recusar…] [Anonimizar conta…]      |
| Texto pronto para enviar ao contato: [Copiar]  [Registrar envio]     |
+----------------------------------------------------------------------+
```

- O painel de verificação só aparece em pedidos com origem e-mail.
- "Anonimizar conta…" só aparece em pedidos de Exclusão em andamento.
- O vencido usa ícone e texto: "⚠ vencido há 1 dia".

### T6a — Registrar pedido por e-mail (US5.6, AC5.6.1)

```
+----------------------------------------------------------------------+
| ## Registrar pedido recebido por e-mail                              |
| Tipo (obrigatório)  (•) Acesso ( ) Correção ( ) Exclusão ( ) Outro   |
| Contato para resposta (obrigatório)  [______________________]        |
| Detalhes do pedido (até 1000)        [______________________] 0/1000 |
| Conta (opcional): carteira ou identificador [____________] [Buscar]  |
|   Conta encontrada: a1b2… (carteira 7xKX…9fA2)   [Remover vínculo]   |
| Recebido em (obrigatório) [dd/mm/aaaa]  (o prazo conta desta data)   |
| [Cancelar]  [Registrar]                                              |
+----------------------------------------------------------------------+
```

- **Validação:** tipo, contato em formato de e-mail, detalhes com até 1000 caracteres e data de recebimento não futura. Cada erro aparece em `.field-error` junto do campo.
- **Busca de conta:** "Nenhuma conta encontrada" quando não acha; o pedido pode ser registrado sem conta.
- **Sucesso:** `.notice` "Pedido registrado. Prazo: dd/mm". O pedido abre no detalhe.

### T6b — Verificação de identidade (US5.6, AC5.6.2 a AC5.6.9)

Só aparece em pedidos com origem e-mail. A tabela mostra o painel em cada estado:

| Estado | Painel | Ações |
|---|---|---|
| Sem conta ligada | `.notice-warning`: "Este pedido não tem conta ligada; não é possível verificar por assinatura. Ligue a conta (T6a) ou responda com orientação." | "Ligar conta", "Recusar…" com a resposta modelo de orientação |
| Pronto para verificar (Acesso ou Exclusão, com conta) | "Para liberar dados ou anonimizar, confirme a identidade." | **Pedir verificação** |
| Aguardando verificação | Mensagem com "Copiar mensagem", "Válida até dd/mm hh:mm" e campo "Assinatura recebida" | **Conferir** |
| Conferindo | "Conferir" desabilitado, com "Conferindo…" | — |
| Confirmada | `.notice`: "Identidade confirmada em dd/mm hh:mm." O pedido vai para "Em andamento" | Ações normais do pedido |
| Inválida | `.notice-error`: "Assinatura inválida ou de outra carteira. O pedido continua aguardando." | **Conferir** de novo |
| Já usada | `.notice-error`: "Esta assinatura já foi usada. Peça uma nova verificação se necessário." | — |
| Vencida (24 h sem assinatura válida) | `.notice-warning`: "Verificação vencida: pedido recusado com o motivo 'identidade não confirmada'." | Texto pronto para o contato |
| Correção ou Outro | Sem painel: esses tipos não liberam dados nem anonimizam | Ações normais |

**Orientação ao contato** (carteira perdida, conta anonimizada ou verificação vencida), como texto pronto para copiar: "Não conseguimos confirmar que você é titular da conta. Por segurança, não liberamos dados nem fazemos alterações sem essa confirmação. Se ainda tiver acesso à carteira, responda a este e-mail para receber uma nova mensagem de verificação." O texto não traz nenhum dado pessoal.

### T6c — Recusar pedido (US5.3, AC5.3.2, AC5.3.3)

```
+--------------------------------------------------+
| ## Recusar pedido a1b2…                          |
| Motivo da recusa (obrigatório, a pessoa vê)      |
| [______________________________________] 0/500   |
| Resposta (opcional)                              |
| [______________________________________]         |
| [Cancelar]  [Recusar pedido]                     |
+--------------------------------------------------+
```

- "Recusar pedido" fica desabilitado até haver motivo: "Escreva o motivo para recusar".
- O motivo aparece para a pessoa no detalhe do pedido em T5.
- Com `409` (pedido já Concluído ou Recusado), o diálogo mostra: "Este pedido já foi encerrado e não pode mudar de situação." e atualiza o detalhe.

### Estados de T6

| Estado | O que aparece |
|---|---|
| Carregando | `.skeleton-row` na lista e no detalhe |
| Vazio | "Nenhum pedido aberto." Com filtro (Should): "Nenhum pedido nesta situação." |
| Erro ao carregar | `.notice-error` com "Tentar de novo" |
| Ação enviando | O botão da ação fica desabilitado, com "Salvando…" |
| Sucesso | `.notice` com a nova situação ("Pedido concluído", "Pedido em andamento") e o foco no aviso |
| Erro técnico ao salvar | `.notice-error`: "Não foi possível salvar. Nada foi alterado. [Tentar de novo]" |
| `409 IDENTITY_NOT_VERIFIED` ao concluir um Acesso ou anonimizar | `.notice-error`: "A identidade deste pedido não está confirmada (pode ter vencido agora). Confirme antes de liberar dados." O painel de verificação é recarregado |

## T7 — Novo aceite (US4.1, US4.2)

Tela inteira, no lugar do conteúdo, mantendo o rodapé.

```
+----------------------------------------------------------------------+
| # Os termos mudaram                                                  |
| Você aceitou a v1. A versão vigente é a v2, desde 15/11/2026.        |
| ## O que mudou                                                       |
|  - <tópico 1>  ...                                                   |
| [Termos de uso ↗] [Política de privacidade ↗]                        |
| [ ] Li e aceito a nova versão                                        |
| [Aceitar e continuar]   [Sair]                                       |
| .muted: Seus bots de simulação continuam rodando. Criar ou alterar   |
|         bots fica disponível depois do aceite.                       |
+----------------------------------------------------------------------+
```

Com `409 TERMS_VERSION_OUTDATED`, a tela recarrega com a versão nova e avisa: "Uma versão mais nova foi publicada agora".

**Estados de T7:**

| Estado | O que aparece |
|---|---|
| Carregando | `.skeleton-row` no lugar do resumo; a caixa e o botão desabilitados |
| Pronto | Caixa desmarcada; "Aceitar e continuar" desabilitado, com "Marque a caixa para continuar" |
| Enviando | "Aceitar e continuar" desabilitado, com "Registrando aceite…" |
| Sucesso | Volta à tela de origem com `.notice`: "Aceite da versão v2 registrado." |
| Versão superada (`409`) | Recarrega com a versão nova e o aviso acima; a caixa volta desmarcada |
| Erro técnico (5xx, rede) | `.notice-error`: "Não foi possível registrar o aceite. Tente de novo." A caixa continua marcada |
| Erro ao carregar o resumo | `.notice-error` com "Tentar de novo"; os links para as páginas completas continuam funcionando |

## T8 — Diálogo de anonimização (US6.1)

```
+--------------------------------------------------+
| ## Anonimizar a conta a1b2…                      |
| Isto não pode ser desfeito. Vai:                 |
|  - desativar a conta e revogar as sessões;       |
|  - apagar a carteira, o feedback e os nomes dos  |
|    bots, e parar os bots;                        |
|  - manter o histórico simulado sem vínculo.      |
| Digite ANONIMIZAR para confirmar                 |
| [____________]                                   |
| [Cancelar]  [Anonimizar] (só com o texto exato)  |
+--------------------------------------------------+
```

Respostas de erro:
- com `409 IDENTITY_NOT_VERIFIED`: "Confirme a identidade antes (pedido por e-mail)";
- com `409` de estado inválido: "Este pedido não permite anonimização".
