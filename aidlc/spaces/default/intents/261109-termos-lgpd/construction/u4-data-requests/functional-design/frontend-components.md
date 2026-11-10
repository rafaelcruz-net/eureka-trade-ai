# Componentes de frontend — U4 `u4-data-requests`

Conversa: português (Brasil). Textos de interface em pt-BR, `data-testid` nos elementos interativos e tipos gerados do contrato.

## App do convidado (`apps/web`)

| Componente | Onde | Props e estado | Interação |
|---|---|---|---|
| `DataRequestsSection` (T5) | tela Conta | lista e estados `loading`, `error`, `ready` | mostra o formulário e a lista |
| `DataRequestForm` | T5 | tipo (4 opções com rótulo), texto (contador até 1000), `submitting` | envia com chave de idempotência; `409` mostra "Você já tem um pedido aberto deste tipo"; `400` marca o campo |
| `DataRequestList` | T5 | pedidos | tipo, data, situação e prazo; abre o detalhe |
| `DataRequestDetail` | T5 | pedido | resposta, motivo e `AccessSummaryView` só quando encerrado |
| `AccessSummaryView` | T5 e admin | resumo | carteira mascarada, datas, versões, contagens e pedidos |

## App do administrador (`apps/admin`)

| Componente | Onde | Props e estado | Interação |
|---|---|---|---|
| `AdminDataRequestList` (T6) | `/pedidos` | filtro de situação | linhas com tipo, origem, conta, recebimento, prazo e situação; alerta com ícone e texto (a 3 dias ou menos do prazo, ou vencido) |
| `AdminDataRequestDetail` (T6a) | `/pedidos/[id]` | pedido | texto, resposta editável com modelo, ações de transição, prévia do resumo e caixa "Liberar resumo" |
| `RegisterEmailRequestForm` (T6b) | `/pedidos/novo` | tipo, texto, contato, data, conta opcional | registro com validação dos campos |
| `VerificationPanel` (T6c) | detalhe | mensagem, validade e estado | botão para copiar a mensagem, campo da assinatura e "Conferir" |
| `ReplyToContact` | detalhe (EMAIL encerrado) | texto pronto | copiar e "Marcar como enviada" |

## Acessibilidade

- **Teclado:** a ordem segue a leitura (filtro, lista, detalhe, ações), e o foco é sempre visível.
- **Rótulos:** todos os campos têm rótulo visível.
- **Mensagens:** ficam numa região `aria-live`.
- **Alertas de prazo:** sempre trazem ícone e texto, nunca só a cor.
