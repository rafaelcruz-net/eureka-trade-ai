# Decisões de tecnologia — U5 `u5-real-operations`

Conversa: português (Brasil). Fontes: os módulos atuais (`botwallet`, `administration`, `privacy`), `team.md` (Testing Posture) e `nfr-requirements-questions.md` (Q5, Q6).

| ID | Decisão | Motivo |
|---|---|---|
| U5-NFR8.3 | Liberação, reconciliação, divergência, saque limitado, restauração e comandos pendentes em `botwallet` | donos dos dados da carteira (Domain Design) |
| U5-NFR8.4 | Kill switch REAL e rotas R4 em `administration`; adaptador do GitHub em `administration.infra.github`, com o `RestClient` do Spring, tempo limite de 5 s e Resilience4j | padrão do projeto |
| U5-NFR8.5 | Anonimização em `privacy`, chamando a M1; a redação da trilha por função `SECURITY DEFINER`, no padrão da migração 0015 | padrão do projeto |
| U5-NFR8.6 | Migrações Liquibase só de expansão: tabelas novas, colunas anuláveis, `PauseReason` ampliado, `decision.mode` aceitando `REAL`, função de redação | expand-contract |
| U5-NFR8.7 | Testes antes do código para a reconciliação, a compensação, o saque limitado, a anonimização e os portões; integração com Testcontainers; spec de ponta a ponta da divergência no validador local, com o texto do `verify.sh` para o responsável; piso de 80% | núcleo financeiro e dados pessoais (`team.md`) |
| U5-NFR8.8 | Testes obrigatórios por risco tocados pela U5: saque sempre disponível (com kill switch, REAL desligado e sem liberação), REAL desligado por padrão, anonimização sem endereço, ledger sem edição, isolamento entre tenants | `team.md` |
| U5-NFR10.1 | Lamports e unidades base em `long`; instantes em UTC; nunca ponto flutuante | NFR10 |

Nenhuma biblioteca nova.
