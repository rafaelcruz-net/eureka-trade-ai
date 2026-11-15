# Dependências externas — Trading REAL

Conversa: português (Brasil). Fontes: `bolt-plan.md`, `inception/contract-design/contract-summary.md` (X1 a X4 e Open questions), `inception/requirements-analysis/requirements.md`, `inception/domain-design/components.md`, `inception/units-generation/unit-of-work.md` e `delivery-planning-questions.md` (Q6, Q7).

Um **Bolt** é uma passada de construção sobre uma Unit que termina em algo que roda.

## Mapa

| Dependência | Dono | Prazo estimado | Bloqueia | Se atrasar |
|---|---|---|---|---|
| Registro das mudanças de regra do ADR-008 em `project.md` e `team.md` (REAL, geração de chave, execução no navegador, exceção dos termos para leitura e saque) | fundador, pelo AI-DLC | antes do B1 | início da Construction | nada começa até o registro |
| Conta AWS nova com KMS (Ed25519) | fundador | dias | prova Ed25519 real do B1; TxSigner fora do local; mainnet | o B1 segue com KMS simulado; a prova real vira condição de saída para a devnet |
| Plano pago da CoinGecko (exibição com atribuição) | fundador e fornecedor | dias a semanas | U2 em produção (não no local) | o B2 usa respostas gravadas; o REAL não opera com a fonte simulada |
| API da Jupiter (cotação, swap) | Jupiter | já disponível | B4 na mainnet | teste no validador com contas clonadas |
| API de condicionais (Trigger) da Jupiter | Jupiter | a confirmar | B7 | o B7 é Should e não bloqueia os convidados |
| Dois provedores de RPC Solana | fundador | dias | mainnet | devnet e validador local seguem |
| Parecer de custódia | fundador e jurídico | a definir | entrada dos convidados na mainnet | o piloto continua só com a carteira do fundador |
| Ajustes no `scripts/verify.sh` e no `ci.yml` (specs novos de ponta a ponta, chave HMAC, convite de anonimização) | fundador (só ele altera) | a cada Bolt | ponto de controle de cada Bolt | o Bolt não fecha até o spec entrar no script |
| Validador local Solana (ferramenta de teste) | fundador | horas | B1 em diante | sem ele, o esqueleto não roda |

## Por Bolt

| Bolt | Dependências externas |
|---|---|
| Antes do B1 | registro das mudanças de regra do ADR-008 |
| B1 | validador local; conta AWS e KMS (prova Ed25519 real, com KMS simulado enquanto não sai); spec novo no `verify.sh` |
| B2 | plano pago da CoinGecko (só para produção) |
| B3 | nenhuma |
| B4 | API da Jupiter (validador clonado) |
| B5 | nenhuma além do `verify.sh` |
| B6 | nenhuma além do `verify.sh` |
| B7 | API de condicionais da Jupiter |
| Mainnet do fundador | conta AWS e KMS reais; dois provedores de RPC |
| Convidados | parecer de custódia |

## Dados e segredos

Nenhuma dependência envia dado pessoal ou segredo para fora; credenciais de fornecedor ficam em `.env` local e, na nuvem, no gerenciador de segredos.
