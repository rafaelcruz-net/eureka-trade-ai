# Perguntas da Units Generation — paper-real-unico

Conversa: português (Brasil). As respostas seguem a instrução do responsável, "Responda todos com os recomendados". Os pontos críticos continuam com ele.

Fontes: `inception/domain-design/components.md` e `decisions.md` (ADR-001 a ADR-011), `inception/requirements-analysis/requirements.md`, `inception/user-stories/stories.md` e o esqueleto descrito no `team.md` ("Fatia do intent `261121-paper-real-unico`").

## Q1. Critério de divisão das Units

Como as Units são divididas?

A. Por fatia de funcionalidade e risco, seguindo a ordem de entrega dos requisitos (o cálculo, a API, a tela e por último o ciclo). Cada Unit atravessa as camadas necessárias (API, banco e tela) e respeita as fronteiras de módulo dos ADRs. (recomendado)
B. Por camada técnica (backend, frontend)
X. Other (please specify)

[Answer]: A

## Q2. Quantas Units e quais

Quantas Units, e com o quê?

A. Quatro Units:
   - **U1 `u1-mode-skeleton`, o esqueleto.**
     - Cria o bot com o modo no assistente: o passo "Modo", a confirmação do real e a elegibilidade com o motivo mais forte (US1).
     - `mode == market` (US2.4) e a lista única com a coluna "Modo" (US2.1, US2.2).
     - O ModePnlView com os dois blocos. O bloco REAL traz o P&L por bot só das ordens do próprio bot, ainda sem rateio.
     - O PnlModeBlock com o SimulatedBadge e o aviso fixo.
     - Um spec novo de ponta a ponta: cria um bot PAPER e um REAL no validador local, faz uma compra em cada um e confere que os blocos não se somam.
   - **U2 `u2-real-bot-pnl`, o núcleo financeiro, com os testes escritos antes do código.**
     - A coluna `slot`, por migração aditiva (ADR-011).
     - O custo médio único, com a porta `RealCostMethod` e o teste de paridade.
     - O RealProration: rateio pelo maior resto, excedente, "Sem bot" e taxas.
     - O RealBotPnl completo e o total REAL.
     - A conferência com o ledger (FR3 e FR5).
   - **U3 `u3-pnl-views`, as telas.**
     - O filtro por modo.
     - O detalhe do bot com a origem de cada linha.
     - As linhas rateadas.
     - Os períodos (FR7).
     - Os totais do painel.
     - A comparação (FR8).
   - **U4 `u4-own-quantity-cycle`, o ciclo.**
     - O RealBotHoldings e o RealCycle vendendo só a própria quantidade (FR6).
     - Fica atrás de uma configuração desligada por padrão.
     - Entra por último, depois da Performance Validation do intent 261113.

   (recomendado)
B. Três Units (U3 junto com U2)
X. Other (please specify)

[Answer]: A

## Q3. Dependências e paralelismo

Como as Units dependem umas das outras?

A. Assim:
   - U2 depende de U1;
   - U3 e U4 dependem de U2 e não dependem uma da outra;
   - não há ciclos.

   O trabalho é feito por uma pessoa só, então o paralelismo entre U3 e U4 é só possível, não planejado. (recomendado)
B. Ordem estritamente linear
X. Other (please specify)

[Answer]: A

## Q4. Como as Units se integram

Por onde as Units conversam entre si?

A. Só pelas interfaces públicas Java (`pnl.api`, `ledger.api`, `bots.api`, `riskcontrol.api` e as portas `BotDirectory` e `RealCostMethod`) e pelo contrato OpenAPI `openapi-guest.yaml`. Os tipos do app web são gerados desse contrato. Não há evento novo nem tabela compartilhada entre módulos. (recomendado)
B. Eventos novos entre módulos
X. Other (please specify)

[Answer]: A

## Q5. Modelo de implantação

Como as Units são implantadas?

A. Junto com o monólito que já existe: a API e o worker são o mesmo artefato, e o `apps/web` vai junto. As quatro Units saem no mesmo pipeline. A U4 entra com a flag desligada e só é ligada numa implantação auditada separada. (recomendado)
B. Uma implantação separada por Unit
X. Other (please specify)

[Answer]: A

## Q6. Tipo de cada Unit

Qual o `kind` de cada Unit?

A. `service` para U1, U2 e U4, porque mudam a API ou o worker. `ui` para U3, que é principalmente a tela, com leituras pequenas da API. (recomendado)
B. Todas `service`
X. Other (please specify)

[Answer]: A

## Consolidated Summary Confirmation

- **Divisão:** as Units são fatias de funcionalidade e risco, na ordem do cálculo, da API, da tela e, por último, do ciclo [Q1].
- **As quatro Units** [Q2]:
  - **U1, esqueleto:** criar o bot no modo, a lista única com `mode == market`, os dois blocos (o REAL ainda sem rateio) e um spec de ponta a ponta novo.
  - **U2, núcleo financeiro com os testes antes do código:** o `slot`, o custo médio único, o rateio, a linha "Sem bot", as taxas e a conferência com o ledger.
  - **U3, telas:** o filtro, o detalhe, as linhas rateadas, os períodos, o painel e a comparação.
  - **U4, ciclo:** o bot vende só a própria quantidade. Fica com a flag desligada e entra depois da Performance Validation do REAL.
- **Dependências:** U1 → U2 → U3 e U4. Não há ciclos [Q3].
- **Integração:** só pelas interfaces públicas Java e pelo contrato OpenAPI. Não há evento novo [Q4].
- **Implantação:** junto com o monólito e o app web, no mesmo pipeline. A U4 é ligada numa implantação separada [Q5].
- **Tipos:** `service` para U1, U2 e U4. `ui` para U3 [Q6].

Does this all look correct before I generate the artifact?

- Looks correct
- Request changes

[Answer]: Looks correct
