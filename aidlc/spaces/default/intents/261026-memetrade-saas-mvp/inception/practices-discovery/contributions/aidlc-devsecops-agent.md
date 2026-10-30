**Collaborator:** aidlc-devsecops-agent

## Contribution

### 1. Leitura geral

O rascunho cobre bem branches, testes por risco e as regras de domínio, mas as
cinco seções práticas quase não falam de segurança da cadeia de entrega:
varredura de segredos, varredura de dependências, SAST/DAST, imagens de
contêiner e integridade da CI. Isso importa mais agora do que depois, por três
motivos:

- **Ainda não há nenhum commit.** Um segredo que entre no primeiro commit fica
  no histórico. Os controles de segredo precisam estar ativos **antes** do
  primeiro commit, e não só quando a CI existir (Sprint 1).
- **O ecossistema de carteiras Solana já sofreu ataques à cadeia de
  suprimentos.** Exemplo (fato público, a citar com fonte no estágio de
  segurança): as versões maliciosas 1.95.6 e 1.95.7 do pacote npm
  `@solana/web3.js` (dezembro de 2024) exfiltravam chaves privadas. Também houve
  pacotes npm populares comprometidos em 2025. O produto não guarda chaves
  (C-02), mas o frontend vai rodar no navegador ao lado da carteira do usuário,
  então uma dependência comprometida no frontend atinge diretamente o usuário.
- **Orçamento zero (C-12).** Tudo o que proponho abaixo usa ferramentas de
  código aberto ou recursos gratuitos. Onde a gratuidade depende do plano ou da
  visibilidade do repositório, marquei **verificar**.

Proposta de proporcionalidade: controles em **camadas por marco**, para não
travar um desenvolvedor solo com burocracia antes da hora.

| Camada | Quando entra | Controles | Custo |
|---|---|---|---|
| 0 | Antes do primeiro commit | `.gitignore` de segredos, `.env.example` sem valores, hook de pré-commit com Gitleaks, varredura única da árvore atual, `.gitattributes`/`.editorconfig` | Zero |
| 1 | Junto com a CI (Sprint 1) | Gitleaks na CI, varredura de dependências, SAST, lint de segurança, lockfiles obrigatórios, Actions fixadas por SHA, `permissions` mínimas na CI, atualização automática de dependências | Zero (verificar plano) |
| 2 | Antes do beta multiusuário (G1) | DAST baseline (OWASP ZAP) contra o Docker Compose, varredura de imagem (Trivy), SBOM por tag de release, threat model STRIDE leve de autenticação, tenancy e PAPER | Zero |
| 3 | Antes de REAL (G2/G3) | Revisão e auditoria independentes, pentest, KMS/HSM para chaves operacionais | Fora do orçamento atual; não é prática desta etapa |

### 2. Texto sugerido para integrar nas seções do `team-practices.md`

Não existe uma seção "Segurança" entre as cinco seções do `team.md`. Sugiro
distribuir assim (todos os itens como **sugestão** até a entrevista).

**Way of Working** — acrescentar:

- **Higiene de segredos (sugestão, camada 0)**: nenhum segredo entra no
  repositório. Variáveis ficam em `.env` local (ignorado pelo git) e o
  repositório guarda só um `.env.example` sem valores. Um hook de pré-commit
  com Gitleaks bloqueia o commit que contenha segredo. Antes do primeiro
  commit, fazemos uma varredura única da árvore de trabalho atual (há arquivos
  ainda não rastreados, como `PromptInicial.txt` e `.claude/`, que precisam ser
  conferidos) e confirmamos que `.claude/settings.local.json` está ignorado.
- **Se um segredo vazar (sugestão)**: revogar e trocar o segredo primeiro, e só
  depois limpar o histórico. Limpar o histórico sem revogar não resolve.
- **Portões de segurança na CI (sugestão, camada 1)**: a CI roda varredura de
  segredos, de dependências e SAST em toda mudança. Achado Crítico ou Alto
  bloqueia o merge; Médio gera aviso. Exceções ficam num arquivo versionado de
  exceções, cada uma com justificativa, data de expiração (máximo 90 dias) e
  aprovação registrada do responsável do projeto. Exceção vencida volta a
  bloquear.
- **Integridade da CI (sugestão, camada 1)**: ações de terceiros na CI são
  fixadas pelo SHA do commit (não por tag); cada workflow declara
  `permissions` mínimas (por padrão só leitura do conteúdo); nenhum segredo de
  produção fica disponível em workflows disparados por branch ou fork não
  confiável.
- **Atualização de dependências (sugestão, camada 1)**: Dependabot ou Renovate
  abre PRs de atualização. Para pacotes do frontend, aguardamos um período
  mínimo antes de adotar uma versão recém-publicada (por exemplo, 3 a 7 dias),
  salvo correção de segurança, para reduzir a exposição a versões maliciosas
  publicadas e retiradas logo depois.

**Code Style** — acrescentar (lint e formatação com foco em segurança):

- **Frontend (sugestão)**: além de Prettier/ESLint, ativar regras que proíbem
  `eval`/`new Function`, sinalizam `dangerouslySetInnerHTML` (exigir
  sanitização e justificativa no código) e proíbem colocar segredos em
  variáveis `NEXT_PUBLIC_*`, que vão para o navegador.
- **Backend (sugestão)**: SpotBugs com o plugin FindSecBugs (código aberto)
  junto com o linter Java escolhido, rodando no build.
- **Regras do projeto como verificação automática (sugestão)**: transformar
  regras proibidas em regras Semgrep próprias (Semgrep Community Edition é
  gratuito), em vez de depender só de revisão. Exemplos: bloquear campos,
  parâmetros ou rotas com nomes como `seed`, `mnemonic`, `privateKey`,
  `secretKey`; bloquear uso de APIs de importação de chave secreta (por
  exemplo, `Keypair.fromSecretKey`) e de bibliotecas de mnemônico no código de
  produção; bloquear log de nonce, assinatura de login, token de sessão ou
  cabeçalho `Authorization`.
- **Arquitetura verificada por teste (sugestão)**: usar ArchUnit (gratuito) para
  impedir que controllers web chamem diretamente o módulo Execution e que os
  módulos de PAPER e REAL compartilhem repositórios de ledger ou saldo. Isso
  dá prova automática para as regras candidatas de separação REAL/PAPER.

**Testing Posture** — acrescentar:

- **Testes de segurança (sugestão)**: os testes negativos de tenant já
  propostos ganham dois casos obrigatórios: acesso a recurso de outro tenant
  por ID (IDOR) devolve 404 ou 403 e nunca o dado; e jobs assíncronos também
  validam `tenant_id`. Somar testes de autenticação: nonce de login reutilizado
  é rejeitado, assinatura expirada é rejeitada, assinatura de login não serve
  para nenhuma rota de autorização de trading.
- **DAST (sugestão, camada 2)**: OWASP ZAP em modo baseline contra o ambiente
  Docker Compose antes do beta multiusuário (G1) e a cada release depois disso.
  No início, em modo de aviso; achado Alto bloqueia o gate G1.

**Deployment** — acrescentar:

- **Imagens e artefatos (sugestão, camada 2)**: imagens base fixadas por digest,
  processo rodando como usuário não root, varredura com Trivy antes de publicar
  a imagem (Crítico bloqueia) e SBOM (CycloneDX ou Syft) gerado para cada tag de
  release e guardado com o artefato.
- **Aprovação de gates de REAL (sugestão)**: deixar escrito que a autoaprovação
  do responsável do projeto pode valer para staging e produção do beta PAPER,
  mas **não** satisfaz G2/G3: o PRD e a C-02 exigem revisão independente.

### 3. Regras candidatas a acrescentar no `discovered-rules.md`

Todas como **candidatas**, com origem documentada, a confirmar na entrevista.

Mandated:

- ALWAYS bloquear commits e merges que contenham segredos, com varredura no pré-commit e na CI. (candidata; origem: PRD §12 "segredos em gerenciador dedicado", fase Construction "nunca hardcode de credenciais")
- ALWAYS versionar os lockfiles (npm/pnpm e Maven/Gradle com wrapper verificado) e instalar dependências a partir deles na CI. (candidata; origem: supply chain, PRD §12)
- ALWAYS fixar por SHA as ações de terceiros usadas na CI. (candidata; origem: integridade da CI)

Forbidden:

- NEVER registrar em log segredos, tokens de sessão, cabeçalhos `Authorization`, nonces ou assinaturas de login. (candidata; origem: PRD §12 "logs sem segredos", PRD §15 US15)
- NEVER expor segredo em variável `NEXT_PUBLIC_*` ou em qualquer código entregue ao navegador. (candidata; origem: PRD §12, C-02)
- NEVER incluir no código de produção APIs de importação ou geração de chave secreta ou de frase mnemônica. (candidata; origem: C-02, PRD §7; complementa a regra de seed phrase com uma verificação automática)

Comentário sobre a regra existente de tenant: sugiro que ela venha acompanhada
de prova automática (teste negativo multi-tenant obrigatório para todo endpoint
e job novo), senão vira regra sem verificação.

### 4. Perguntas para a entrevista

1. **Antes do primeiro commit**: aceita instalar Gitleaks como hook de
   pré-commit e rodar uma varredura única dos arquivos atuais antes do primeiro
   commit?
2. **Visibilidade do repositório**: o repositório remoto vai ser público ou
   privado? Isso muda o que é gratuito (por exemplo, CodeQL e a proteção de
   push de segredos do GitHub são gratuitos em repositórios públicos; em
   repositório privado pessoal, verificar). Se for privado, aceita Semgrep CE e
   Gitleaks na CI como alternativa gratuita?
3. **Política de severidade**: confirma bloquear o merge em achados Críticos e
   Altos (segredos, dependências, SAST) e só avisar nos Médios?
4. **Exceções**: confirma exceções versionadas com justificativa e expiração
   de no máximo 90 dias, aprovadas por você?
5. **Atualização de dependências**: Dependabot ou Renovate? Aceita esperar
   alguns dias antes de adotar versões novas de pacotes npm, salvo correções de
   segurança?
6. **DAST**: aceita OWASP ZAP baseline como requisito do gate G1 (antes de
   abrir o beta para mais de um usuário), em vez de rodar em todo PR?
7. **Endereços de carteira em logs**: endereço público de carteira ligado a uma
   conta pode ser dado pessoal pela LGPD (C-07). Prefere mascarar endereços em
   logs e métricas (por exemplo, só os 4 primeiros e 4 últimos caracteres) ou
   registrar completo só na trilha de auditoria?
8. **Regras automáticas**: quer que as regras proibidas de seed phrase, chave
   privada e log de segredos virem verificações Semgrep que bloqueiam o merge?
9. **Revisão independente**: confirma que a autoaprovação vale para o beta
   PAPER, mas que G2/G3 de REAL exigem um revisor externo ainda a nomear (C-14)?

## Positions

- AGREE: Trunk-based com squash-merge e um único tronco sem branches de release; é o modelo mais simples para um desenvolvedor solo e facilita rastrear cada mudança.
- AGREE: Decidir `master` vs `main` antes do primeiro commit; o custo é quase zero agora.
- AGREE: Merge nunca com CI vermelha; é a base para os portões de segurança propostos aqui.
- AGREE: Testes obrigatórios por risco (tenant, REAL/PAPER, idempotência, limites, `unknown`); sugiro só acrescentar IDOR, jobs assíncronos e antirreplay do login.
- AGREE: Todas as regras candidatas de `discovered-rules.md`; são controles de segurança diretos de C-02 a C-05 e do PRD.
- AGREE: Adotar `.gitattributes` e `.editorconfig` desde o primeiro commit; também evita arquivos com fim de linha misto que escondem diferenças em revisão.
- OBJECT: Way of Working e Code Style não trazem nenhum controle de segredos nem de dependências; como ainda não há commit, a varredura de segredos precisa entrar antes do primeiro commit e não só com a CI da Sprint 1.
- OBJECT: Os portões de CI citam só lint e formatação; sem SAST, varredura de dependências e fixação de Actions por SHA, a CI não protege um frontend que roda ao lado da carteira do usuário.
- OBJECT: A aprovação de produção do projeto solo está aberta sem dizer que a autoaprovação não satisfaz G2/G3; a C-02 e o PRD exigem revisão independente para REAL, e isso deve ficar escrito na prática de Deployment.
