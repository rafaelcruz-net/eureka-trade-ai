# Requisitos de segurança e isolamento — U7 `u7-exits-and-fiscal`

Conversa: português (Brasil). Fontes:
- `nfr-requirements-questions.md` (Q2);
- `construction/u7-exits-and-fiscal/functional-design/rules.md` (BR0, BR1, BR2, BR7, BR9);
- `project.md` e `team.md`.

| ID | Requisito | Verificação |
|---|---|---|
| U7-NFR1.1 | A `TriggerPolicy` recusa cada caso de BR2.4 e grava o motivo | um teste de ataque por motivo, escrito antes do código, sobre as transações gravadas (BR0.2) |
| U7-NFR1.2 | Sem a gravação real, o TxSigner recusa `TRIGGER_CREATE` e `TRIGGER_CANCEL` com `POLICY_NOT_RECORDED` | teste no TxSigner |
| U7-NFR1.3 | O `TRIGGER_CANCEL` é assinável com o kill switch ligado, o REAL desligado e a restauração em curso | teste no TxSigner e na API |
| U7-NFR1.4 | A API recusa preços fora dos limites de BR2.6 antes de pedir assinatura | teste de unidade e de rota |
| U7-NFR1.5 | A chave de API da Jupiter fica num segredo só do worker; nunca entra em log, resposta ou no navegador | teste de log mascarado; ArchUnit sem acesso fora do adaptador |
| U7-NFR1.6 | A exportação traz só os dados da pessoa do token: nada de outra pessoa, nada do PAPER, nenhum endereço completo da carteira principal | teste de isolamento por tenant e pessoa; teste da coluna de endereços |
| U7-NFR1.7 | A proteção contra fórmula vale nos campos de texto do CSV (BR7.5) | teste de unidade com `=`, `+`, `-`, `@`, tabulação e retorno |
| U7-NFR1.8 | A rede só é usada no adaptador da Jupiter e no RPC que já existe; nenhuma assinatura acontece fora do TxSigner | regras ArchUnit, cada uma com um teste que comprova a falha |
| U7-NFR1.9 | Criar e cancelar condicionais exigem `Idempotency-Key` e o tenant do token | teste de rota e de isolamento (outra conta recebe `404`) |
| U7-NFR2.1 | O PAPER nunca entra nas condicionais nem na exportação | ArchUnit e teste da exportação |
| U7-NFR7.1 | Preços e quantidades em decimal exato ou unidades base, sem ponto flutuante | ArchUnit (sem `double` e `float` no domínio) e o lint das pastas do REAL no web |
