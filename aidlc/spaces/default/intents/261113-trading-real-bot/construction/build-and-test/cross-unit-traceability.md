 |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met | |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met | |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met | |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met | |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met | |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met | |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met | |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met | |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met | |  |  |  | 0 NFR detalhados em todas as Units (matriz: ) | Met |# Rastreabilidade entre Units — portão final de cobertura (Step 10)

Comando: `aidlc engine sensor-traceability --stage build-and-test` (executado da raiz do repositório em 2026-11-18, código de saída 0, saída JSON de uma linha).

## Veredito

**FAIL** (`"pass": false`).

| Medida | Valor |
|---|---|
| IDs avaliados (FR e NFR de `requirements.md`, AC de `stories.md`) | 134 |
| Cobertos segundo o sensor | 0 |
| Não cobertos | 134 (48 FR, 10 NFR, 76 AC) |
| Motivos registrados pelo sensor | 84, todos `u2-real-market-data: NFRx.y: target file does not exist: …` |
| IDs com construção e teste verdes pelo mapa complementar (abaixo) | 124 de 134 |

Todo ID não coberto é um achado do Build and Test e precisa aparecer no portão de aprovação.

## Por que o sensor não reconhece a cobertura

O sensor (`.claude/tools/aidlc-sensor-traceability.ts`, função `finalCoverage`) só aceita como construído um ID que tenha **linha própria `OK`** numa tabela `code-generation/traceability.json` cujo `target` seja **um único caminho de arquivo relativo ao repositório que exista**. As tabelas das sete Units foram escritas em outro formato:

1. **Critérios de aceite (AC):** nenhuma tabela da Code Generation tem linhas `AC…`; elas rastreiam regras (`BR…`), histórias (`US…`), cenários (`S…`) e NFRs. O caminho AC → BR está nas tabelas da Functional Design, que o sensor não segue. Por isso todos os 76 AC e, por consequência, os 48 FR (cobertos "através" dos AC das histórias) aparecem como não construídos.
2. **NFR detalhados:** o sensor tira os IDs `NFRx.y` dos arquivos de `nfr-requirements/` de cada Unit, inclusive de dentro de `U3-NFR3.1`, `U4-NFR…` e assim por diante, e procura linhas `NFR3.1` na tabela da Unit. U3 a U7 usam o prefixo (`U3-NFR3.1`), então não casam; a U1 não tem nenhuma linha de NFR na Code Generation; a U2 usa `NFRx.y`, mas os alvos são texto livre com nomes de arquivo sem caminho (`CoinGeckoHttp.java; CoinGeckoHttpTest…`), daí os 84 motivos "target file does not exist".
3. Os 10 NFR de nível superior (`NFR1` a `NFR10`) dependem de cada Unit ter construído os seus `NFRx.y`, o que falha pelos dois pontos acima.

Isto é um problema de **formato dos registros da Code Generation**, não de código de produto: a matriz do `build-and-test-summary.md` mostra os testes executados para cada regra e NFR.

## Correção candidata

Reescrever o `code-generation/traceability.json` de cada Unit no formato do sensor: uma linha `OK` por AC (a partir do mapa AC → BR da Functional Design) e por `NFRx.y` (sem o prefixo da Unit), com `target` igual a um caminho de arquivo existente (o teste principal ou a classe que implementa). É trabalho só de registro, sem código de produto, mas exige reabrir a Code Generation das sete Units (Plan Approval e revisão novas).

- **Esforço:** 3 a 5 horas, quase todo mecânico (gerado por script a partir das tabelas atuais e da matriz deste estágio).
- **Custo financeiro:** nenhum.
- **Risco:** baixo para o produto (nenhum arquivo de código muda); médio de processo, porque reabre a Code Generation de todas as Units e invalida as revisões atuais delas.

## IDs não cobertos (lista do sensor)

FR1.1, FR1.2, FR1.3, FR1.4, FR2.1, FR2.2, FR2.3, FR2.4, FR3.1, FR3.2, FR3.3, FR3.4, FR3.5, FR4.1, FR4.2, FR4.3, FR5.1, FR5.2, FR5.3, FR5.4, FR5.5, FR5.6, FR6.1, FR6.2, FR6.3, FR6.4, FR7.1, FR7.2, FR7.3, FR7.4, FR8.1, FR8.2, FR8.3, FR9.1, FR9.2, FR10.1, FR10.2, FR10.3, FR10.4, FR11.1, FR11.2, FR11.3, FR12.1, FR12.2, FR13.1, FR14.1, FR15.1, FR15.2, NFR1, NFR2, NFR3, NFR4, NFR5, NFR6, NFR7, NFR8, NFR9, NFR10, AC1.1.1, AC1.1.2, AC1.1.3, AC1.1.4, AC1.2.1, AC1.2.2, AC1.2.3, AC1.2.4, AC1.2.5, AC2.1.1, AC2.1.2, AC2.1.3, AC2.1.4, AC2.2.1, AC2.2.2, AC2.2.3, AC3.1.1, AC3.1.2, AC3.1.3, AC3.1.4, AC3.2.1, AC3.2.2, AC3.2.3, AC3.2.4, AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5, AC3.4.1, AC3.4.2, AC4.1.1, AC4.1.2, AC4.1.3, AC4.2.1, AC4.2.2, AC4.3.1, AC5.1.1, AC5.1.2, AC5.1.3, AC5.1.4, AC5.2.1, AC5.2.2, AC6.1.1, AC6.1.2, AC6.1.3, AC6.1.4, AC6.1.5, AC6.1.6, AC6.2.1, AC6.2.2, AC6.2.3, AC6.3.1, AC6.3.2, AC6.3.3, AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4, AC7.2.1, AC7.2.2, AC7.3.1, AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4, AC8.2.1, AC8.2.2, AC8.3.1, AC8.3.2, AC8.3.3, AC8.3.4, AC8.4.1, AC8.4.2, AC8.5.1, AC8.5.2.

## IDs que continuam abertos também pelo mapa complementar

| ID | Situação | Motivo |
|---|---|---|
| AC8.1.3 | Unverified | depende de U7 BR1.3, adiada pelo portão BR0 |
| FR8.1, FR8.2, FR8.3 | Unverified | cobertos através de AC8.1.x, que inclui AC8.1.3 |
| NFR1 | Not Met | U1:NFR1.9 (limites de uso) não implementado; U1:NFR1.1, NFR1.2, NFR1.12 dependem de ambiente; U7-NFR1.1 e U7-NFR1.3 adiados pelo BR0 |
| NFR3 | Not Met | U3-NFR3.7 (linha do REAL no catálogo de retenção); metas de carga sem teste |
| NFR4 | Unverified | U1:NFR4.1 (disponibilidade), U1:NFR4.8 (backup) e U2:NFR4.1 (frescor no mês) dependem de ambiente |
| NFR5 | Not Met | U1:NFR5.1 (métrica de carteiras ativas), U1:NFR5.14 (erros e throttling do KMS) e U1:NFR5.19 (alerta do KMS) faltam; U3-NFR5.11 (alarme na nuvem) adiado |
| NFR8 | Not Met | desvios da U4 (U4-NFR8.4, U4-NFR8.5, U4-NFR8.7); U7-NFR8.2 adiado pelo BR0 |
| NFR9 | Unverified | custos (U1:NFR9.1, U2:NFR9.1) só verificáveis com a conta e o plano contratados |

## Cobertura por ID

Colunas: o que o sensor diz; a Unit e as regras que constroem o ID segundo a Functional Design (para AC) ou os NFR detalhados (para NFR); os testes que rodaram no run `ci` 44228007586, tirados da matriz; e o pior veredito da matriz entre as partes. Para FR, a coluna de arquivos lista os AC pelos quais o FR é coberto.

| ID | Coberto no sensor | Caminho que o sensor exige (through / not_built) | Unit dona e regras (functional-design) | Arquivos e testes que constroem (matriz) | Veredito na matriz |
|---|---|---|---|---|---|
| FR1.1 | não | 3 parte(s); não construídas: 3 | U2 | via AC6.3.1, AC6.3.2, AC6.3.3 | Met |
| FR1.2 | não | 3 parte(s); não construídas: 3 | U2 | via AC6.3.1, AC6.3.2, AC6.3.3 | Met |
| FR1.3 | não | 3 parte(s); não construídas: 3 | U2 | via AC6.3.1, AC6.3.2, AC6.3.3 | Met |
| FR1.4 | não | 3 parte(s); não construídas: 3 | U2 | via AC6.3.1, AC6.3.2, AC6.3.3 | Met |
| FR2.1 | não | 5 parte(s); não construídas: 5 | U1 | via AC1.2.1, AC1.2.2, AC1.2.3, AC1.2.4, AC1.2.5 | Met |
| FR2.2 | não | 5 parte(s); não construídas: 5 | U1 | via AC1.2.1, AC1.2.2, AC1.2.3, AC1.2.4, AC1.2.5 | Met |
| FR2.3 | não | 6 parte(s); não construídas: 6 | U3 (N/A); U6, U3, U5 | via AC2.1.1, AC2.1.2, AC2.1.3, AC2.1.4, AC7.2.1, AC7.2.2 | Met |
| FR2.4 | não | 5 parte(s); não construídas: 5 | U1 | via AC1.2.1, AC1.2.2, AC1.2.3, AC1.2.4, AC1.2.5 | Met |
| FR3.1 | não | 3 parte(s); não construídas: 3 | U1 | via AC2.2.1, AC2.2.2, AC2.2.3 | Met |
| FR3.2 | não | 3 parte(s); não construídas: 3 | U1 | via AC2.2.1, AC2.2.2, AC2.2.3 | Met |
| FR3.3 | não | 4 parte(s); não construídas: 4 | U1 | via AC5.1.1, AC5.1.2, AC5.1.3, AC5.1.4 | Met |
| FR3.4 | não | 6 parte(s); não construídas: 6 | U1, U4 | via AC5.1.1, AC5.1.2, AC5.1.3, AC5.1.4, AC5.2.1, AC5.2.2 | Met |
| FR3.5 | não | 7 parte(s); não construídas: 7 | U1 | via AC2.2.1, AC2.2.2, AC2.2.3, AC5.1.1, AC5.1.2, AC5.1.3, AC5.1.4 | Met |
| FR4.1 | não | 4 parte(s); não construídas: 4 | U4 (N/A); U6, U4 | via AC3.1.1, AC3.1.2, AC3.1.3, AC3.1.4 | Met |
| FR4.2 | não | 4 parte(s); não construídas: 4 | U4 (N/A); U6, U4 | via AC3.1.1, AC3.1.2, AC3.1.3, AC3.1.4 | Met |
| FR4.3 | não | 4 parte(s); não construídas: 4 | U4 | via AC3.2.1, AC3.2.2, AC3.2.3, AC3.2.4 | Met |
| FR5.1 | não | 5 parte(s); não construídas: 5 | U4 | via AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5 | Met |
| FR5.2 | não | 8 parte(s); não construídas: 8 | U4, U2 | via AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5, AC6.3.1, AC6.3.2, AC6.3.3 | Met |
| FR5.3 | não | 5 parte(s); não construídas: 5 | U4 | via AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5 | Met |
| FR5.4 | não | 9 parte(s); não construídas: 9 | U4 | via AC3.2.1, AC3.2.2, AC3.2.3, AC3.2.4, AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5 | Met |
| FR5.5 | não | 5 parte(s); não construídas: 5 | U4 | via AC3.3.1, AC3.3.2, AC3.3.3, AC3.3.4, AC3.3.5 | Met |
| FR5.6 | não | 4 parte(s); não construídas: 4 | U4 | via AC8.3.1, AC8.3.2, AC8.3.3, AC8.3.4 | Met |
| FR6.1 | não | 6 parte(s); não construídas: 6 | U3 | via AC6.1.1, AC6.1.2, AC6.1.3, AC6.1.4, AC6.1.5, AC6.1.6 | Met |
| FR6.2 | não | 6 parte(s); não construídas: 6 | U3 | via AC6.1.1, AC6.1.2, AC6.1.3, AC6.1.4, AC6.1.5, AC6.1.6 | Met |
| FR6.3 | não | 3 parte(s); não construídas: 3 | U3 | via AC6.2.1, AC6.2.2, AC6.2.3 | Met |
| FR6.4 | não | 6 parte(s); não construídas: 6 | U3 | via AC6.1.1, AC6.1.2, AC6.1.3, AC6.1.4, AC6.1.5, AC6.1.6 | Met |
| FR7.1 | não | 2 parte(s); não construídas: 2 | U4 | via AC4.2.1, AC4.2.2 | Met |
| FR7.2 | não | 2 parte(s); não construídas: 2 | U5 | via AC8.2.1, AC8.2.2 | Met |
| FR7.3 | não | 2 parte(s); não construídas: 2 | U5 | via AC8.2.1, AC8.2.2 | Met |
| FR7.4 | não | 2 parte(s); não construídas: 2 | U4 | via AC4.2.1, AC4.2.2 | Met |
| FR8.1 | não | 4 parte(s); não construídas: 4 | U5, U5 (N/A); U7 | via AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4 | Unverified |
| FR8.2 | não | 4 parte(s); não construídas: 4 | U5, U5 (N/A); U7 | via AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4 | Unverified |
| FR8.3 | não | 4 parte(s); não construídas: 4 | U5, U5 (N/A); U7 | via AC8.1.1, AC8.1.2, AC8.1.3, AC8.1.4 | Unverified |
| FR9.1 | não | 4 parte(s); não construídas: 4 | U1, U1 (N/A); U6 | via AC1.1.1, AC1.1.2, AC1.1.3, AC1.1.4 | Met |
| FR9.2 | não | 4 parte(s); não construídas: 4 | U1, U1 (N/A); U6 | via AC1.1.1, AC1.1.2, AC1.1.3, AC1.1.4 | Met |
| FR10.1 | não | 4 parte(s); não construídas: 4 | U5 | via AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4 | Met |
| FR10.2 | não | 4 parte(s); não construídas: 4 | U5 | via AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4 | Met |
| FR10.3 | não | 4 parte(s); não construídas: 4 | U5 | via AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4 | Met |
| FR10.4 | não | 4 parte(s); não construídas: 4 | U5 | via AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4 | Met |
| FR11.1 | não | 2 parte(s); não construídas: 2 | U1 | via AC8.5.1, AC8.5.2 | Met |
| FR11.2 | não | 2 parte(s); não construídas: 2 | U1 | via AC8.5.1, AC8.5.2 | Met |
| FR11.3 | não | 2 parte(s); não construídas: 2 | U1 | via AC8.5.1, AC8.5.2 | Met |
| FR12.1 | não | 2 parte(s); não construídas: 2 | U5 | via AC8.4.1, AC8.4.2 | Met |
| FR12.2 | não | 2 parte(s); não construídas: 2 | U5 | via AC8.4.1, AC8.4.2 | Met |
| FR13.1 | não | 2 parte(s); não construídas: 2 | U7 | via AC3.4.1, AC3.4.2 | Met |
| FR14.1 | não | 1 parte(s); não construídas: 1 | U7 | via AC4.3.1 | Met |
| FR15.1 | não | 1 parte(s); não construídas: 1 | U5 | via AC7.3.1 | Met |
| FR15.2 | não | 6 parte(s); não construídas: 6 | U5 | via AC7.1.1, AC7.1.2, AC7.1.3, AC7.1.4, AC7.2.1, AC7.2.2 | Met |
| NFR1 | não | 46 parte(s); não construídas: 46 | U1, U2, U4, U5, U6, U7 | 47 NFR detalhados em todas as Units (matriz: Unverified 5, Met 41, Not Met 1); abertos: U1:NFR1.1, U1:NFR1.2, U1:NFR1.12, U1:NFR1.9, U7-NFR1.1, U7-NFR1.3 | Not Met |
| NFR2 | não | 7 parte(s); não construídas: 7 | U1, U2, U3, U4, U5, U6, U7 | 7 NFR detalhados em todas as Units (matriz: Met 7) | Met |
| NFR3 | não | 57 parte(s); não construídas: 57 | U1, U2, U3, U4, U5, U6, U7 | 58 NFR detalhados em todas as Units (matriz: Unverified 17, Met 40, Not Met 1); abertos: U1:NFR3.1, U1:NFR3.2, U1:NFR3.3, U1:NFR3.4, U1:NFR3.6, U1:NFR3.9, U1:NFR3.10, U1:NFR3.8, U2:NFR3.1, U2:NFR3.2, U2:NFR3.3, U2:NFR3.4, U3-NFR3.7, U4-NFR3.4, U5-NFR3.1, U5-NFR3.2, U5-NFR3.3, U5-NFR3.4 | Not Met |
| NFR4 | não | 59 parte(s); não construídas: 59 | U1, U2, U3, U4, U5, U6, U7 | 59 NFR detalhados em todas as Units (matriz: Unverified 3, Met 56); abertos: U1:NFR4.1, U1:NFR4.8, U2:NFR4.1 | Unverified |
| NFR5 | não | 10 parte(s); não construídas: 10 | U2 | 82 NFR detalhados em todas as Units (matriz: Not Met 3, Met 78, Unverified 1); abertos: U1:NFR5.1, U1:NFR5.14, U1:NFR5.19, U3-NFR5.11 | Not Met |
| NFR6 | não | 10 parte(s); não construídas: 10 | U1, U2, U3, U4, U5 | 16 NFR detalhados em todas as Units (matriz: Met 16) | Met |
| NFR7 | não | 8 parte(s); não construídas: 8 | U2, U6, U7 | 9 NFR detalhados em todas as Units (matriz: Met 9) | Met |
| NFR8 | não | 10 parte(s); não construídas: 10 | U2, U3, U4, U5 | 46 NFR detalhados em todas as Units (matriz: Met 42, Not Met 3, Unverified 1); abertos: U4-NFR8.4, U4-NFR8.5, U4-NFR8.7, U7-NFR8.2 | Not Met |
| NFR9 | não | 2 parte(s); não construídas: 2 | U1, U2 | 2 NFR detalhados em todas as Units (matriz: Unverified 2); abertos: U1:NFR9.1, U2:NFR9.1 | Unverified |
| NFR10 | não | 2 parte(s); não construídas: 2 | U1, U2 | 7 NFR detalhados em todas as Units (matriz: Met 7) | Met |
| AC1.1.1 | não | linha própria em alguma tabela da Code Generation | U1: BR1.2; U6: BR2.1 | job backend: TermsV3RealRoutesTest · job frontend: ActivationScreen.test.tsx | Met |
| AC1.1.2 | não | linha própria em alguma tabela da Code Generation | U1: BR1.2; U6: BR2.3 | job backend: TermsV3RealRoutesTest · job frontend: ActivationScreen.test.tsx | Met |
| AC1.1.3 | não | linha própria em alguma tabela da Code Generation | U1 (N/A); U6: BR2.1 | texto dos termos v3 entregue por u6-real-modes-ui · job frontend: ActivationScreen.test.tsx | Met |
| AC1.1.4 | não | linha própria em alguma tabela da Code Generation | U1 (N/A); U6: BR2.2 | tela de erro do aceite entregue por u6-real-modes-ui · job frontend: ActivationScreen.test.tsx | Met |
| AC1.2.1 | não | linha própria em alguma tabela da Code Generation | U1: BR1.5, BR1.7 | job backend: ConsentVerificationTest; job txsigner: ConsentVerifierTest · job backend: ActivationRulesTest | Met |
| AC1.2.2 | não | linha própria em alguma tabela da Code Generation | U1: BR1.6 | job txsigner: KmsEd25519ProofTest | Met |
| AC1.2.3 | não | linha própria em alguma tabela da Code Generation | U1: BR1.5 | job backend: ConsentVerificationTest; job txsigner: ConsentVerifierTest | Met |
| AC1.2.4 | não | linha própria em alguma tabela da Code Generation | U1: BR1.4 | job backend: ActivationRulesTest | Met |
| AC1.2.5 | não | linha própria em alguma tabela da Code Generation | U1: BR1.3 | job backend: ActivationRulesTest | Met |
| AC2.1.1 | não | linha própria em alguma tabela da Code Generation | U3 (N/A); U6: BR5.1 | delivered by u1-bot-wallet (Preferences.capLamports) and u6-real-modes-ui (campo do teto)… · job frontend: money.test.ts, real-lint.test.ts; job admin: money.test.ts, real-lint.test.ts | Met |
| AC2.1.2 | não | linha própria em alguma tabela da Code Generation | U3 (N/A); U6: BR5.2 | delivered by u1-bot-wallet (Preferences.maxCapLamports) and u6-real-modes-ui · job frontend: money.test.ts, OverviewScreen.test.tsx; job admin: money.test.ts | Met |
| AC2.1.3 | não | linha própria em alguma tabela da Code Generation | U3: BR5.1, BR5.2; U6: BR8.2 | job backend: RealRiskPolicyTest, TransactionalRealRiskGateIT · job backend: RealRiskPolicyTest | Met |
| AC2.1.4 | não | linha própria em alguma tabela da Code Generation | U3: BR5.3, BR5.1; U6: BR5.3 | job backend: RealRiskPolicyTest · job backend: RealRiskPolicyTest, TransactionalRealRiskGateIT | Met |
| AC2.2.1 | não | linha própria em alguma tabela da Code Generation | U1: BR3.1 | job backend: DepositScannerTest | Met |
| AC2.2.2 | não | linha própria em alguma tabela da Code Generation | U1: BR3.3 | job backend: RealBalanceTest | Met |
| AC2.2.3 | não | linha própria em alguma tabela da Code Generation | U1: BR3.4 | job backend: RealBalanceTest | Met |
| AC3.1.1 | não | linha própria em alguma tabela da Code Generation | U4 (N/A); U6: | delivered by u1-bot-wallet (Preferences.initial em SIGNED); a U4 só lê o modo | Met |
| AC3.1.2 | não | linha própria em alguma tabela da Code Generation | U4: BR7.1; U6: BR4.3 | job backend: RealModeIT · job frontend: ModeScreen.test.tsx; job smoke+e2e: e2e/real-ui/real-ui.spec.ts | Met |
| AC3.1.3 | não | linha própria em alguma tabela da Code Generation | U4: BR7.2; U6: BR4.2, BR6.2 | job backend: RealModeIT · job frontend: ModeScreen.test.tsx; job smoke+e2e: e2e/real-ui/real-ui.spec.ts | Met |
| AC3.1.4 | não | linha própria em alguma tabela da Code Generation | U4: BR7.1, BR3.3; U6: BR4.3 | job backend: RealModeIT · job backend: OrderDriverIT | Met |
| AC3.2.1 | não | linha própria em alguma tabela da Code Generation | U4: BR4.2; U6: | job backend: SigningSessionIT | Met |
| AC3.2.2 | não | linha própria em alguma tabela da Code Generation | U4: BR4.2, BR5.1; U6: BR3.1, BR3.7 | job backend: SigningSessionIT · job backend: OrderDriverIT | Met |
| AC3.2.3 | não | linha própria em alguma tabela da Code Generation | U4: BR4.2; U6: BR3.2, BR3.3 | job backend: SigningSessionIT · job backend: SigningSessionServerTimeIT; job frontend: countdown.test.ts, SigningBanner.test.tsx | Met |
| AC3.2.4 | não | linha própria em alguma tabela da Code Generation | U4: BR4.2; U6: BR3.4 | job backend: SigningSessionIT · job frontend: SigningBanner.test.tsx | Met |
| AC3.3.1 | não | linha própria em alguma tabela da Code Generation | U4: BR1.2, BR2.1, BR4.1, BR5.1 | job backend: DecisionConsumerIT · job backend: DecisionConsumerIT, RealSettlementIT | Met |
| AC3.3.2 | não | linha própria em alguma tabela da Code Generation | U4: BR3.2 | job backend: OrderDriverIT, RealOrderStateMachineTest | Met |
| AC3.3.3 | não | linha própria em alguma tabela da Code Generation | U4: BR2.2 | job backend: RealExecutionPrivilegesIT, DecisionConsumerIT | Met |
| AC3.3.4 | não | linha própria em alguma tabela da Code Generation | U4: BR3.1 | job backend: OrderDriverIT, MessageBuilderTest | Met |
| AC3.3.5 | não | linha própria em alguma tabela da Code Generation | U4: BR5.1, BR6.1 | job backend: OrderDriverIT · job backend: RealLedgerEntriesTest, RealSettlementIT | Met |
| AC3.4.1 | não | linha própria em alguma tabela da Code Generation | U7: BR1.2 | job backend: ConditionalCreationIT | Met |
| AC3.4.2 | não | linha própria em alguma tabela da Code Generation | U7: |  | Met |
| AC4.1.1 | não | linha própria em alguma tabela da Code Generation | U4: BR5.1, BR6.1, BR10.2; U6: BR8.5, BR8.7 | job backend: OrderDriverIT · job backend: RealLedgerEntriesTest, RealSettlementIT | Met |
| AC4.1.2 | não | linha própria em alguma tabela da Code Generation | U4: BR5.3; U6: BR8.4 | job backend: ConfirmationAndAmbiguousIT, RealOrderStateMachineTest · job frontend: base.test.tsx, OrdersScreen.test.tsx | Met |
| AC4.1.3 | não | linha própria em alguma tabela da Code Generation | U4 (N/A); U6: BR8.1 | delivered by u6-real-modes-ui (estados de tela) · job frontend: base.test.tsx, OrdersScreen.test.tsx | Met |
| AC4.2.1 | não | linha própria em alguma tabela da Code Generation | U4: BR6.2; U6: BR7.2 | job backend: RealPnlCalculatorTest, RealRoutesIT · job frontend: DashboardRealPnl.test.tsx, OverviewScreen.test.tsx | Met |
| AC4.2.2 | não | linha própria em alguma tabela da Code Generation | U4: BR6.1; U6: BR8.5 | job backend: RealLedgerEntriesTest, RealSettlementIT · job frontend: OrdersScreen.test.tsx | Met |
| AC4.3.1 | não | linha própria em alguma tabela da Code Generation | U7: BR7.1, BR7.3 | job backend: FiscalCsvWriterTest, FiscalExportIT · job backend: FiscalRowMapperTest | Met |
| AC5.1.1 | não | linha própria em alguma tabela da Code Generation | U1: BR4.1, BR5.1; U6: BR6.5 | job txsigner: WithdrawalPolicyTest · job frontend: DepositWithdrawScreen.test.tsx; job smoke+e2e: e2e/real-ui/real-ui.spec.ts | Met |
| AC5.1.2 | não | linha própria em alguma tabela da Code Generation | U1: BR4.2; U6: BR6.4 | job backend: RealOffByDefaultRoutesIT, TermsReacceptanceRoutesIT · job frontend: DepositWithdrawScreen.test.tsx | Met |
| AC5.1.3 | não | linha própria em alguma tabela da Code Generation | U1: BR4.3; U6: | job backend: WithdrawalRulesTest | Met |
| AC5.1.4 | não | linha própria em alguma tabela da Code Generation | U1: BR4.4; U6: | job backend: WithdrawalRulesTest | Met |
| AC5.2.1 | não | linha própria em alguma tabela da Code Generation | U4: BR8.1, BR8.2; U6: | job backend: LiquidationIT | Met |
| AC5.2.2 | não | linha própria em alguma tabela da Code Generation | U4: BR8.2; U6: | job backend: LiquidationIT | Met |
| AC6.1.1 | não | linha própria em alguma tabela da Code Generation | U3: BR4.1 | job backend: RealRiskPolicyTest | Met |
| AC6.1.2 | não | linha própria em alguma tabela da Code Generation | U3: BR4.3, BR4.4, BR4.5 | job backend: RealRiskPolicyTest, TransactionalRealRiskGateIT, RealRiskJobsIT, RealLedgerPositionsIT (+1) · job backend: RealRiskJobsIT, RealRiskJobsTest, TransactionalRealRiskGateIT | Met |
| AC6.1.3 | não | linha própria em alguma tabela da Code Generation | U3: BR4.2 | job backend: RealRiskPolicyTest, JdbcRealRiskStoreIT | Met |
| AC6.1.4 | não | linha própria em alguma tabela da Code Generation | U3: BR6.1, BR6.2 | job backend: WalletLockIT, TransactionalRealRiskGateIT · job backend: TransactionalRealRiskGateIT, RealRiskJobsIT | Met |
| AC6.1.5 | não | linha própria em alguma tabela da Code Generation | U3: BR6.2 | job backend: TransactionalRealRiskGateIT, RealRiskJobsIT | Met |
| AC6.1.6 | não | linha própria em alguma tabela da Code Generation | U3: BR6.3 | job backend: TransactionalRealRiskGateIT, RealGateInputsReaderIT | Met |
| AC6.2.1 | não | linha própria em alguma tabela da Code Generation | U3: BR2.1 | job backend: RealRiskPolicyTest, RealGateInputsReaderTest | Met |
| AC6.2.2 | não | linha própria em alguma tabela da Code Generation | U3: BR2.4 | job backend: RealRiskPolicyTest | Met |
| AC6.2.3 | não | linha própria em alguma tabela da Code Generation | U3: BR2.2, BR2.3 | job backend: RealRiskPolicyTest, TransactionalRealRiskGateIT · job backend: RealRiskArchitectureTest | Met |
| AC6.3.1 | não | linha própria em alguma tabela da Code Generation | U2: BR1.1, BR1.2, BR1.8; U3 (N/A) | job backend: CoinGeckoAdapterTest · job backend: ValueParserTest, CoinGeckoCollectionIT | Met |
| AC6.3.2 | não | linha própria em alguma tabela da Code Generation | U2: BR1.5, BR1.6; U3: BR1.2 | job backend: MarketSourceGuardTest, CoinGeckoApiStartupIT · job backend: RealRiskPolicyTest, TransactionalRealRiskGateIT | Met |
| AC6.3.3 | não | linha própria em alguma tabela da Code Generation | U2: BR1.4; U3: BR3.1, BR3.2, BR3.2 | job backend: FieldFreshnessTest · job backend: RealRiskPolicyTest | Met |
| AC7.1.1 | não | linha própria em alguma tabela da Code Generation | U5: BR1.1 | job backend: ReleaseAndGatesIT, RealOpsMigrationIT | Met |
| AC7.1.2 | não | linha própria em alguma tabela da Code Generation | U5: BR1.2, BR1.4; U6: BR2.5, BR9.1 | job backend: ReleaseRulesTest, ReleaseAndGatesIT, RealAdminRoutesIT · job backend: GateEvaluatorTest, GitHubCiStatusTest, ConfiguredCiStatusTest, ReleaseAndGatesIT | Met |
| AC7.1.3 | não | linha própria em alguma tabela da Code Generation | U5: BR1.3 | job backend: ReleaseRulesTest, ReleaseAndGatesIT | Met |
| AC7.1.4 | não | linha própria em alguma tabela da Code Generation | U5: BR1.5 | job backend: RealOpsStartupChecksTest | Met |
| AC7.2.1 | não | linha própria em alguma tabela da Code Generation | U5: BR4.1; U6: BR5.4, BR9.1 | job backend: CapRulesTest, CapAndCommandsIT, RealAdminRoutesIT · job backend: BotWalletViewCapSetByTest, RealReleaseRouteIT; job frontend: OverviewScreen.test.tsx | Met |
| AC7.2.2 | não | linha própria em alguma tabela da Code Generation | U5: BR4.2; U6: BR9.1 | job backend: RealAdminRoutesIT · job admin: real-admin.test.tsx | Met |
| AC7.3.1 | não | linha própria em alguma tabela da Code Generation | U5: BR5.1; U6: BR9.5 | job backend: RealAdminRoutesIT · job admin: real-admin.test.tsx | Met |
| AC8.1.1 | não | linha própria em alguma tabela da Code Generation | U5: BR3.1; U6: BR9.4 | job backend: KillSwitchIT, RealRiskPolicyGlobalPausesTest, RealStateGlobalPausesTest, RealExecutionRecheckTest · job admin: real-admin.test.tsx, layout.test.tsx | Met |
| AC8.1.2 | não | linha própria em alguma tabela da Code Generation | U5: BR3.1, BR5.1, BR5.2 | job backend: KillSwitchIT, RealRiskPolicyGlobalPausesTest, RealStateGlobalPausesTest, RealExecutionRecheckTest · job backend: RealAdminRoutesIT | Met |
| AC8.1.3 | não | linha própria em alguma tabela da Code Generation | U5 (N/A); U7: BR6.1, BR6.5, BR1.3 | delivered by u7-exits-and-fiscal (lê a M7 que BR3.1 liga) · job backend: ConditionalCancelJobIT | Unverified |
| AC8.1.4 | não | linha própria em alguma tabela da Code Generation | U5: BR3.1; U6: BR6.4, BR9.4 | job backend: KillSwitchIT, RealRiskPolicyGlobalPausesTest, RealStateGlobalPausesTest, RealExecutionRecheckTest · job frontend: DepositWithdrawScreen.test.tsx | Met |
| AC8.2.1 | não | linha própria em alguma tabela da Code Generation | U5: BR2.1 | job backend: ReconciliationPolicyTest, ReconciliationIT, ChainWatcherOpsTest | Met |
| AC8.2.2 | não | linha própria em alguma tabela da Code Generation | U5: BR2.2, BR2.3; U6: BR8.3, BR9.6 | job backend: ReconciliationIT, RealOpsGaugesIT · job backend: CompensationRulesTest, ReconciliationIT, RealAdminRoutesIT; job smoke+e2e: divergence.spec.ts, e… | Met |
| AC8.3.1 | não | linha própria em alguma tabela da Code Generation | U4: BR5.1; U6: BR8.4 | job backend: OrderDriverIT · job frontend: base.test.tsx, OrdersScreen.test.tsx | Met |
| AC8.3.2 | não | linha própria em alguma tabela da Code Generation | U4: BR5.2 | job backend: ConfirmationAndAmbiguousIT | Met |
| AC8.3.3 | não | linha própria em alguma tabela da Code Generation | U4: BR5.2, BR9.1; U6: BR8.3, BR8.4 | job backend: ConfirmationAndAmbiguousIT · job backend: UpstreamFailuresIT | Met |
| AC8.3.4 | não | linha própria em alguma tabela da Code Generation | U4: BR5.2, BR3.1 | job backend: ConfirmationAndAmbiguousIT · job backend: OrderDriverIT, MessageBuilderTest | Met |
| AC8.4.1 | não | linha própria em alguma tabela da Code Generation | U5: BR6.1 | job backend: BotWalletAnonymizationIT, NoWalletAddressAfterAnonymizationIT | Met |
| AC8.4.2 | não | linha própria em alguma tabela da Code Generation | U5: BR6.2 | job backend: BotWalletAnonymizationIT, RealOpsMigrationIT | Met |
| AC8.5.1 | não | linha própria em alguma tabela da Code Generation | U1: BR6.1 | job backend: RealAuditValidatorTest, RealAuditPayloadTest, WalletAuditIT | Met |
| AC8.5.2 | não | linha própria em alguma tabela da Code Generation | U1: BR6.2 | job backend: RealAuditValidatorTest, RealAuditPayloadTest | Met |
