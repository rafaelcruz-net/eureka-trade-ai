# Pipeline — U1 `u1-bot-wallet`

Conversa: português (Brasil). Fontes: `construction/u1-bot-wallet/nfr-requirements/tech-stack-decisions.md` (NFR8.x), `construction/u1-bot-wallet/nfr-design/` e as regras de `team.md` (Way of Working, Testing Posture, Deployment). Fonte das decisões: `infrastructure-design-questions.md` (Q5, Q7).

## Quem altera o quê

O fundador é a única pessoa que altera `.github/workflows/*.yml` e `scripts/verify.sh`. As mudanças abaixo são entregues a ele como texto pronto no Code Generation [Q7].

## Etapas da CI (em toda mudança e no merge para `main`)

1. **Segredos:** varredura de segredos do repositório inteiro, inclusive `apps/txsigner`.
2. **Backend:** Maven `verify` do monólito, com testes de unidade e integração (Testcontainers) e JaCoCo com piso de 80%. Entram também o ArchUnit, inclusive a regra que proíbe o cliente do KMS fora do TxSigner, e o enforcer de dependências, que proíbe o SDK Solana.
3. **TxSigner (job novo):** Maven `verify` do `apps/txsigner`, com piso de 80%, ArchUnit próprio e enforcer. Inclui os testes de ataque da política e o teste do JSON das políticas do KMS.
4. **Frontend:** lint, verificação de tipos e Jest com cobertura no `apps/web`. O `check:api` confere que o OpenAPI gerado bate com o versionado.
5. **Dependências e SAST:** também no TxSigner. Achado Crítico ou Alto bloqueia; Médio gera aviso (`team.md`).
6. **Regras de alerta e IaC:**
   - `promtool check rules` nas regras de `infra/observability/`;
   - `terraform fmt -check` e `terraform validate` em `infra/terraform/`;
   - varredura de IaC gratuita (por exemplo Checkov) com a mesma regra de bloqueio das outras varreduras.

   A CI nunca roda `terraform plan` nem `apply` contra a conta, e não tem credencial da AWS.
7. **Ponta a ponta:** `bash scripts/verify.sh`. O fundador acrescenta o validador Solana e o `kms-sim` ao Compose de teste, e o spec novo da carteira (ativar, depositar, sacar, recusar outro destino).
8. **Imagens:** API, worker e TxSigner marcados com o SHA do commit.

| Etapa | Portão |
|---|---|
| Segredos, testes, cobertura, ArchUnit, enforcer | falha bloqueia o merge |
| SAST e dependências | Crítico ou Alto bloqueia |
| `verify.sh` | falha bloqueia o merge |
| Desempenho | informativo, sem portão (`team.md`) |

Nenhum teste da CI usa devnet, mainnet nem KMS real (NFR8.4). O caminho de FAILED por blockhash vencido é testado com um dublê da ChainWatcher [Q5].

## Implantação (quando a conta AWS existir)

1. Aplicar o Terraform: rede, bancos, KMS, segredos e serviços.
2. Rodar as migrações do TxSigner (`txsigner-migrate`) e depois as da aplicação (`db-migrate`), sempre no padrão expand-contract.
3. Implantar o TxSigner primeiro e esperar que ele fique pronto (banco e `ListKeys`).
4. Implantar o worker e a API.
5. Rodar o smoke test. A implantação só termina com ele verde.

**Promoção:** local → devnet → mainnet. A mainnet só é liberada pelos marcos do `bolt-plan.md`, com aprovação manual do fundador.

**Rollback:** voltar à imagem anterior pelo SHA, na ordem inversa (API e worker, depois TxSigner). As migrações expand-contract não removem colunas na mesma implantação. Para o REAL, o rollback começa desligando o flag ou acionando o kill switch, sempre mantendo o saque (`team.md`).

## Integridade e segredos na CI

O job novo do TxSigner e as etapas novas seguem as regras de `team.md`:

- **Ações de terceiros fixadas por SHA** de commit, nunca por tag.
- **Permissões mínimas:** cada workflow declara `permissions: contents: read`.
- **Sem segredo de produção:** os testes usam a credencial de serviço de teste e o `kms-sim`. Workflows de branch ou de fork não confiável não têm acesso a segredos.

## Quem aplica o Terraform

O fundador aplica o Terraform da máquina dele, com o papel de administração e MFA. O estado fica no bucket S3 da conta, com versão, criptografia e trava. O `plan` é revisado por ele antes de todo `apply`.
