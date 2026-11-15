# Decisões de tecnologia — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `aidlc/spaces/default/codekb/eureka-trader-ai/technology-stack.md`, `construction/u1-bot-wallet/functional-design/functional-spec.md`, `inception/requirements-analysis/requirements.md`, `inception/contract-design/contract-summary.md`, as regras de `team.md` e `project.md` e `nfr-requirements-questions.md` (Q1 a Q4).

## Decisões

| Área | Escolha | Por quê |
|---|---|---|
| Backend (API e worker) | O monólito Spring Boot com Java 21 que já existe, com o módulo novo `botwallet` e mudanças em `ledger`, `audit` e `identity` | segue a stack e as fronteiras de módulo atuais |
| TxSigner | Módulo Maven próprio `apps/txsigner`, Java 21 e Spring Boot com o mínimo de dependências, processo separado [Q2] | isolar a credencial que assina (ADR-010); a mesma linguagem facilita teste e revisão |
| Assinatura | AWS KMS, chave Ed25519 assimétrica não exportável [Q1] | chave fora do código; a prova técnica confirma o formato |
| Cliente do KMS | AWS SDK for Java v2, só o cliente do KMS, só como dependência do `apps/txsigner`; o monólito não tem dependência do SDK da AWS para KMS | menor superfície; a regra do enforcer de dependências proíbe o cliente do KMS fora do TxSigner |
| Fronteiras do TxSigner | Regras ArchUnit próprias no `apps/txsigner`: o domínio da política não depende de web nem de infraestrutura; nenhuma classe gera chave fora do adaptador do KMS | mesma prática dos módulos do monólito |
| Ed25519 e Base58 | Implementação nativa do Java para conferir Ed25519; Base58 e serialização mínima de transação Solana próprios | regra de `project.md`: sem SDK Solana na produção |
| Banco | PostgreSQL atual; esquema e usuário próprios para o TxSigner no local; banco separado na nuvem | separação de credenciais |
| Migrações | A mesma ferramenta de migração do projeto, com um conjunto de migrações só para o TxSigner | expand-contract (`team.md`) |
| KMS no local | Contêiner `kms-sim` só de teste, em `infra/` [Q3] | não entra no artefato de produção |
| Rede no local | `solana-test-validator` em contêiner no Compose de teste [Q4] | testes determinísticos sem rede pública |
| Testes | JUnit 5 e Testcontainers no backend e no TxSigner; JaCoCo com piso de 80% nos dois; Playwright no spec de ponta a ponta da carteira | práticas de `team.md` |
| Frontend (tela mínima) | `apps/web` existente com Next.js e TypeScript em modo estrito; tipos gerados do OpenAPI | regra de contrato |

## Qualidade (NFR8)

| ID | Requisito |
|---|---|
| NFR8.1 | Testes antes do código na lógica da carteira, do saque, do saldo e da política do TxSigner (`team.md`) |
| NFR8.2 | Piso de 80% de linhas no backend, no TxSigner e no frontend |
| NFR8.3 | Na CI, testes obrigatórios para: REAL desligado por padrão, saque só para a carteira principal, política do TxSigner, separação REAL e PAPER, isolamento e idempotência |
| NFR8.4 | Nenhum teste da CI usa devnet, mainnet ou KMS real |

## Acessibilidade (NFR7)

| ID | Requisito |
|---|---|
| NFR7.1 | A tela mínima segue a acessibilidade básica do beta: teclado, foco visível, rótulos e contraste, com estados em texto |

## A confirmar

- A prova técnica do KMS Ed25519 (início do B1).
- A forma do mTLS na nuvem, no Infrastructure Design. O mTLS em si já está decidido (NFR1.5).
