# Componentes de frontend — U3 `u3-reacceptance`

Conversa: português (Brasil). Esta parte vale só para o app do convidado (`apps/web`); o app do administrador não muda. Textos de interface em pt-BR.

## Hierarquia

| Componente | Onde | Props e estado | Interação |
|---|---|---|---|
| `ReacceptanceScreen` (T7) | `/novo-aceite` | `returnTo` (caminho interno validado); estados `loading`, `error`, `ready`, `submitting`, `outdated` | carrega L1; aceita via L2; volta a `returnTo` |
| `TermsConsentBoxes` | convite e T7 | `disabled`, `checked` (termos e política; aviso de risco), `onChange` | duas caixas com rótulo visível; motivo em texto quando o botão está desabilitado |
| `LegalSummary` | convite e T7 | o resumo da vigente (termos e política) e os links | os links abrem em outra aba (`target="_blank"`, `rel="noopener"`) |
| `ReacceptanceRedirect` | cliente da API do app | recebe a resposta | um `403 TERMS_REACCEPTANCE_REQUIRED` leva a `/novo-aceite?voltar=<rota>` |
| `TermsStatusPanel` | tela Conta | `terms: TermsStatus` | mostra a versão aceita e a data; com `upToDate=false`, o link "Aceitar a nova versão" |
| `InviteScreen` (existente) | `/convite/[codigo]` | passa a usar `TermsConsentBoxes` e `LegalSummary` | caixas desabilitadas enquanto carrega ou com falha |

## Validação e estados

- **Botão do novo aceite:** "Aceitar e continuar" fica desabilitado até as duas caixas estarem marcadas, com o texto "Marque as duas caixas para continuar."
- **`409 TERMS_VERSION_OUTDATED`:** aparece "Os termos mudaram enquanto você lia. Leia a versão nova." Os documentos são recarregados e as caixas desmarcadas.
- **`returnTo`:** só aceita um caminho que começa com `/` e não com `//`. Qualquer outro valor volta ao painel.

## Integração com a API

- **L1 (`/api/v1/legal/current`):** leitura do resumo e da versão.
- **L2 (`POST /api/v1/terms/acceptances`):** envia a chave de idempotência gerada uma vez por envio.
- **`GET /api/v1/account`:** o campo `terms` alimenta o `TermsStatusPanel`.

## Acessibilidade

- A ordem de foco é: título, resumo, links, caixas, botão.
- O foco é visível, e os rótulos das caixas ficam visíveis.
- As mensagens de erro e de estado ficam numa região `aria-live="polite"`.
- "Pular para o conteúdo" continua como primeiro foco.
- Tudo funciona só com teclado.
