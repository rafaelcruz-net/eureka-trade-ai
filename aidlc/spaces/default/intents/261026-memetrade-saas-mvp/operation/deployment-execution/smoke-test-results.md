# Resultados dos smokes

Conversa: português (Brasil). Smoke conforme `operation/deployment-pipeline/cd-config.md`: `bash scripts/verify.sh` com saída 0 e 10 testes Playwright. A release só termina quando o smoke passa (`team.md`). Linha de base: Build and Test (`construction/build-and-test/test-results.md`), com `verify.sh` 10/10.

## Execuções

| # | Versão | Quem rodou | Testes | Duração Playwright | Varredura de logs (carteira completa, texto de feedback, código de convite) | Saída |
|---|---|---|---|---|---|---|
| 1 | `0cd9ccf` (anterior, ensaio de rollback) | responsável do projeto | 10/10 | 2,9 min | ok | 0 |
| 2 | `8fd7a7b` (candidata) | responsável do projeto | 10/10 | 2,9 min | ok | 0 |
| 3 | `8fd7a7b` (candidata) | responsável do projeto | 10/10 | 2,9 min | ok | 0 |

Os três terminaram com "walking skeleton verified" e "environment removed and smoke key deleted".

## Testes da execução 2 e da 3 (iguais nas duas)

| Teste | Resultado |
|---|---|
| U1: S1 e S3 só com teclado | ok |
| U1: S3 mostra a lista com valores em até 2 s | ok |
| U1 F7: login, scanner com fonte, horário e `unknown`; nonce reutilizado é recusado | ok |
| U2: token de convidado na rota da trilha recebe 403 e é auditado; trilha saudável | ok |
| U4: filtro, contador de `unknown`, painel de avaliação e Esc de volta à linha | ok |
| U5: wizard, iniciar, pausar, retomar e parar um bot PAPER | ok |
| U7: P&L, ledger e reconciliação de conta sem operações | ok |
| U8: entrada do administrador, status, convites, kill switch e feedback | ok |
| U9: o painel mostra uma pausa feita em outra aba sem recarregar | ok |
| U3: convite → conta criada → tela Conta → Sair | ok |

## Portões locais (no lugar da CI até o push)

| Suíte | Resultado |
|---|---|
| Web (Jest com cobertura) | 391/391, 33 suítes; limite de cobertura do Jest atendido |
| Admin (Jest com cobertura) | 85/85, 10 suítes; limite de cobertura do Jest atendido |
| Backend | sem mudança desde `0cd9ccf`; vale o Build and Test |

Nenhum teste instável apareceu nestas execuções.
