# Requisitos de segurança e isolamento — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/functional-design/rules.md` (BR2.3, BR6.3, BR7.1, BR7.3), `inception/requirements-analysis/requirements.md` (NFR2, NFR6, NFR8), `team.md` (Code Style, Testes obrigatórios por risco), `project.md` e `nfr-requirements-questions.md` (Q2, Q8).

O NFR1 da intent (chaves no KMS, assinatura) não se aplica à U3: a barreira não toca chave nem segredo.

| ID | Requisito | Verificação |
|---|---|---|
| U3-NFR6.1 | O tenant e o usuário vêm do bot ou da autenticação, nunca do corpo; toda consulta das tabelas da U3 filtra por tenant, exceto a lista de bloqueio, que é global | teste de isolamento: bot de outro tenant devolve `REAL_PAUSED` / `NO_BOT_WALLET` e nenhum dado |
| U3-NFR6.2 | A lista de bloqueio só muda pela M5 `blockToken` e `unblockToken`. Uma regra ArchUnit permite chamá-las só do módulo `administration` (área do administrador da U5, papel ADMIN). Toda mudança efetiva gera evento de auditoria | ArchUnit com teste que comprova a falha; teste do evento |
| U3-NFR6.3 | Logs mascaram mint e endereço como hoje (FR11.2); os detalhes da recusa trazem só números da própria carteira | teste do mascaramento e do conteúdo do detalhe |
| U3-NFR2.1 | A barreira usa só o ledger e o saldo REAL; o saldo PAPER nunca entra na conta | teste de separação REAL/PAPER (S7) |
| U3-NFR8.1 | ArchUnit: `riskcontrol.real` depende só de `botwallet.api`, `ledger.api`, `marketdata.api`, `riskassessment.api`, `audit.api`, `shared..` e do próprio módulo; nunca de classes internas de outros módulos nem do pacote PAPER da RiskControl | regra ArchUnit com teste que comprova a falha |
| U3-NFR8.2 | As regras ArchUnit que já existem (sem ponto flutuante em dinheiro, sem rede fora dos adaptadores) cobrem o pacote novo | as regras existentes, rodando sobre o pacote |
| U3-NFR8.3 | Fora dos perfis `test` e `local`, a API e o worker não sobem com um limite mais frouxo que o padrão (tabela abaixo) | teste de subida com cada chave |

## Limites na configuração (U3-NFR8.3)

| Chave (`memetrade.real.risk.*`) | Padrão | Mais frouxo é | Faixa válida |
|---|---|---|---|
| `order-max-bps` | 1000 | maior | 1 a 1000 |
| `daily-loss-max-bps` | 2000 | maior | 1 a 2000 |
| `daily-buys-max` | 20 | maior | 1 a 20 |
| `signal-max-age` | 60 s | maior | 5 s a 60 s |
| `reservation-ttl` | 5 min | maior | 1 min a 5 min |

Valores fora da faixa recusam a subida em qualquer perfil. Nos perfis `test` e `local`, a faixa pode ser estendida só para testes.
