# Requisitos de segurança — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/functional-design/functional-spec.md` e `rules.md` (BR1.x, BR4.x, BR5.x, BR6.x), `inception/requirements-analysis/requirements.md` (NFR1, NFR2, NFR6, NFR10), `inception/contract-design/contract-summary.md` (R1, R2, mensagem de consentimento, X4), `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`, as regras de `project.md` e `team.md` e `nfr-requirements-questions.md` (Q1 a Q3, Q8, Q10).

## Chaves e assinatura (NFR1)

| ID | Requisito | Verificação |
|---|---|---|
| NFR1.1 | A chave de cada carteira do bot é Ed25519, não exportável, criada no AWS KMS da conta AWS nova, uma chave por carteira [Q1] | prova técnica do início do B1; teste de integração com o KMS simulado |
| NFR1.2 | Menor privilégio no KMS: (a) o papel do TxSigner só pode CreateKey (com a etiqueta da aplicação), Sign, GetPublicKey e TagResource, e só nas chaves com a etiqueta da aplicação; (b) o papel do TxSigner tem negação explícita de ScheduleKeyDeletion, DisableKey, PutKeyPolicy, CreateGrant e Decrypt; (c) a política de cada chave só permite Sign ao papel do TxSigner; (d) o papel de administração é separado, exige MFA, não pode assinar e é só para emergência; (e) a API e o worker não têm permissão no KMS nem acesso ao banco do TxSigner; (f) o CloudTrail registra todo evento do KMS, e qualquer ação administrativa gera alerta; (g) a aplicação limita a criação de chaves a 10 por hora | política de acesso na Infrastructure Design; teste de que a credencial da API é recusada pelo KMS; revisão da política antes da mainnet |
| NFR1.3 | Nenhum código de produção gera, lê, importa ou exporta chave privada ou frase mnemônica; sem SDK Solana na produção; Ed25519 e Base58 próprios | ArchUnit e regra do enforcer de dependências; busca por APIs de geração de chave no artefato de produção |
| NFR1.4 | O TxSigner aceita só a forma exata do saque (BR5.1), confere de novo todo consentimento (BR5.3), falha fechado (BR5.2) e registra toda decisão | testes de ataque escritos antes do código (lista em `functional-spec.md`) |
| NFR1.5 | A API fala com o TxSigner só pela rede privada, com credencial de serviço própria: variável de ambiente no local e gerenciador de segredos na nuvem. O mTLS é obrigatório na nuvem; só a forma dele fica para o Infrastructure Design. A credencial é girada a cada 90 dias, com 24 h em que a antiga e a nova valem juntas, e um alerta avisa aos 80 dias [Q2] | teste: sem credencial 401, credencial de outro serviço 403; alerta de idade da credencial |
| NFR1.6 | O KMS simulado é um contêiner só de teste (`kms-sim`), fora do artefato de produção. Isso substitui o "KMS simulado no processo do TxSigner" do contrato X4. O perfil de produção do TxSigner recusa subir se houver endereço do KMS diferente do padrão da AWS, ou se a conta do ARN da chave for diferente da conta configurada [Q3] | teste de inicialização do perfil de produção com os dois casos; inspeção do artefato |
| NFR1.7 | Toda assinatura e toda recusa geram evento de auditoria. A referência da chave nunca aparece em resposta nem log. O endereço da carteira do bot só aparece por extenso para o próprio dono (ativação e leitura da carteira) e mascarado em todo o resto | teste que procura a referência em respostas e logs, e o endereço completo em logs e em respostas a outros usuários |
| NFR1.11 | Com o REAL desligado, o TxSigner só assina WITHDRAWAL, para carteiras já criadas | teste com o flag desligado |
| NFR1.12 | A chave do HMAC da carteira principal fica no gerenciador de segredos, separada da credencial do TxSigner, nunca em código nem em log; o giro segue a prática atual da auditoria | inspeção da configuração |

## Consentimento e repetição (NFR1)

| ID | Requisito | Verificação |
|---|---|---|
| NFR1.8 | A mensagem de consentimento segue o formato canônico de 10 linhas; a mensagem de login é sempre recusada; nonce de uso único nos dois lados (BR1.5, BR1.8) | testes de repetição e de troca de mensagem |
| NFR1.9 | Limites por usuário: até 10 desafios por minuto, 3 ativações por dia e 10 saques parciais por hora; acima, `429 RATE_LIMITED`. O saque total nunca é barrado por limite de uso. Entre a API e o TxSigner, até 60 pedidos por minuto por carteira [Q8] | teste de limite, inclusive que o saque total passa acima do limite |

## Separação e isolamento (NFR2, NFR6)

| ID | Requisito | Verificação |
|---|---|---|
| NFR2.1 | Lançamentos REAL ficam separados dos PAPER; toda consulta filtra o modo; o PAPER continua funcionando igual | teste obrigatório de separação |
| NFR6.1 | Tenant e usuário vêm só da autenticação; carteira, depósito, saque ou desafio de outra conta responde 404 e gera evento (BR6.4) | teste obrigatório de isolamento, inclusive no worker |

## Dados e segredos (NFR1, NFR10)

| ID | Requisito | Verificação |
|---|---|---|
| NFR1.10 | A carteira principal fica só como HMAC na auditoria; logs mascaram endereço e assinatura; nenhum segredo em `NEXT_PUBLIC_*`, log ou resposta | validador da auditoria; varredura de logs nos testes |
| NFR10.1 | Valores em lamports inteiros, nunca ponto flutuante; instantes em UTC | revisão de tipos e testes |

## Ameaças consideradas

Modelo de ameaças do ADR-005. Riscos tratados nesta Unit:
- API invadida pedindo saque para outro destino: o TxSigner recusa (NFR1.4).
- Instrução escondida ou nonce durável num saque: o TxSigner recusa (BR5.1).
- Repetição de consentimento: recusada pelos nonces (NFR1.8).
- Chave órfã: alerta diário.
- Vazamento de endereço ou assinatura em log: mascaramento (NFR1.10).

O risco residual aceito é a API invadida no momento da ativação (ADR-010, contract-summary R1).
