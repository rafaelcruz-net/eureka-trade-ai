# Projeto de segurança e isolamento — U3 `u3-real-risk`

Conversa: português (Brasil). Fontes: `construction/u3-real-risk/nfr-requirements/security-requirements.md` (U3-NFR6.1 a U3-NFR8.3) e o código atual das regras ArchUnit em `apps/api/src/test/java/com/memetrade/architecture/`.

| Requisito | Como o projeto atende |
|---|---|
| U3-NFR6.1 (tenant) | `OrderIntent` recebe tenant e usuário de quem chama: o ciclo do bot passa os do bot, e a liquidação os da autenticação. Toda consulta do `JdbcRealRiskStore` tem `tenant_id = ?`. O `RealGateInputsReader` confere que o bot e a carteira são do tenant; se não forem, devolve `NO_BOT_WALLET`. |
| U3-NFR6.2 (lista de bloqueio) | `blockToken` e `unblockToken` só são chamados pela `administration` (regra ArchUnit nova, com teste que comprova a falha). A rota é da U5, com o papel ADMIN. O evento de auditoria leva o mint no campo tipado e o administrador como ator. |
| U3-NFR6.3 (logs e detalhe) | Os logs usam o `LogMasking` que já existe para mint e endereço. `figures` só leva números da carteira avaliada. |
| U3-NFR2.1 (REAL/PAPER) | `riskcontrol.real` não depende do pacote PAPER da RiskControl nem do saldo PAPER (regra ArchUnit). As tabelas REAL não têm coluna de modo nem ligação com as PAPER. |
| U3-NFR8.1 (fronteiras) | Uma regra ArchUnit nova permite a `riskcontrol.real` só `botwallet.api`, `ledger.api`, `marketdata.api`, `riskassessment.api`, `audit.api`, `shared..` e o próprio módulo. |
| U3-NFR8.2 (regras existentes) | As regras de ponto flutuante e de rede valem para o pacote sem mudança. |
| U3-NFR8.3 (limites) | `RealRiskLimits` confere na subida a faixa de cada chave. Fora de `test` e `local`, recusa valor mais frouxo que o padrão, com mensagem que nomeia a chave e não o valor. |
