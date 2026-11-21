# Regras descobertas — rascunho do líder (intent `261121-paper-real-unico`)

> **Rascunho do Step 2, 2026-11-21.** As seções `## Mandated` e `## Forbidden` recebem só restrições rígidas
> declaradas pela pessoa. Neste rascunho nenhuma regra nova foi declarada ainda: as duas seções ficam vazias até a
> entrevista. As candidatas abaixo vêm da evidência e das decisões da Ideation; cada uma só entra em `## Mandated` ou
> `## Forbidden` se o responsável confirmar na entrevista. As regras já afirmadas em `project.md` (por exemplo,
> REAL e PAPER separados, saque sempre disponível, chave do bot só no KMS) continuam valendo e não são repetidas.

## Mandated

<!-- Nenhuma regra declarada pela pessoa até a entrevista. -->

## Forbidden

<!-- Nenhuma regra declarada pela pessoa até a entrevista. -->

## Candidatas para a entrevista (não promover sem confirmação)

| # | Forma proposta | Origem | Divergência |
|---|---|---|---|
| C1 | `NEVER` abrir a devnet aos usuários; a devnet serve só aos testes do fundador e à pré-produção do mesmo SHA. | Decisões D2 e D3 do `decision-log.md` da Ideation (D3 veio de uma suposição aceita, não de uma frase do responsável) | DIV-03 |
| C2 | `NEVER` somar ou converter implicitamente os totais PAPER (US$) e REAL (SOL) num total geral, em tela, API ou relatório. | CT-01 e CT-10 do `constraint-register.md`; D12 do `decision-log.md` | DIV-14 |
| C3 | `ALWAYS` implantar na mainnet só um SHA já implantado e verde na devnet, com a aprovação do fundador no Environment `mainnet`. | Q2 e Q3 do `deployment-pipeline-questions.md` do intent `261113-trading-real-bot` | DIV-02 |
| C4 | `NEVER` dar ao papel de implantação do GitHub permissão para implantar o TxSigner, usar o KMS ou ligar o REAL; esses passos ficam com o fundador, com MFA. | SP-01 do `security-posture-review.md` e seção 4.6 do `cd-config.md` do intent `261113-trading-real-bot` | DIV-02, DIV-06 |
| C5 | `ALWAYS` manter o repositório privado (ou, se a resposta for mantê-lo público: `NEVER` versionar dado que identifique contas AWS, domínios ou endereços reais de produção). | `team.md` (Hospedagem) e o estado público informado pelo GitHub | DIV-01 |
| C6 | `ALWAYS` exigir a CI verde antes de um commit chegar a `main` (proteção de branch). | `team.md` ("nenhum merge acontece com a CI vermelha") e os 18 runs vermelhos em `main` | DIV-05 |
