# Regras descobertas — MemeTrade Analytics

> Restrições rígidas do projeto confirmadas pelo responsável do projeto na
> entrevista de descoberta de práticas (Q14, 2026-10-26).

## Mandated

- ALWAYS manter REAL e PAPER estritamente separados em fundos, ordens, ledger, posições e P&L. (origem: C-03, PRD §15 US12)
- ALWAYS manter o trading REAL automático desabilitado até que os gates G2 e G3 do PRD e as aprovações jurídicas sejam cumpridos; PAPER continua operacional. (origem: C-01, C-09, PRD §7 e §16)
- ALWAYS representar dado de mercado ausente como `unknown`, nunca como zero, exibindo fonte, timestamp, frescor e cobertura. (origem: C-05, PRD §4)
- ALWAYS deduplicar operações por chave de idempotência e verificar os limites autorizados e os controles de risco antes de cada ordem, inclusive em PAPER. (origem: C-04, PRD §14)
- ALWAYS validar o tenant no servidor em toda leitura e escrita de dados de cliente. (origem: PRD §11 e §16)
- ALWAYS registrar o ledger como append-only, corrigindo erros apenas com lançamentos compensatórios. (origem: PRD §13)
- ALWAYS gravar e trafegar instantes em UTC, convertendo para o fuso do usuário só na interface. (origem: revisão do desenvolvedor)
- ALWAYS bloquear commits e merges que contenham segredos, com varredura no pré-commit e na CI. (origem: PRD §12, fase Construction)
- ALWAYS versionar os lockfiles e instalar as dependências a partir deles na CI. (origem: cadeia de suprimentos, PRD §12)
- ALWAYS fixar pelo SHA do commit as ações de terceiros usadas na CI. (origem: integridade da CI)

## Forbidden

- NEVER solicitar, receber ou armazenar seed phrase, chave privada ou aprovação genérica irrestrita do usuário. (origem: C-02, PRD §7 e §16)
- NEVER tratar o login por mensagem assinada como autorização para operar. (origem: C-02)
- NEVER executar ordens no navegador depois que o bot é ativado. (origem: PRD §12)
- NEVER classificar um token com dado `unknown` como seguro nem exibir score quando a cobertura for insuficiente. (origem: C-16, PRD §9)
- NEVER usar dados futuros em treinamento ou backtest. (origem: PRD §5)
- NEVER movimentar fundos reais ou implantar contratos on-chain sem aprovação explícita do responsável do projeto. (origem: PRD §19, initiative brief)
- NEVER prometer lucro ou garantia de detecção de fraude em telas ou comunicações. (origem: C-16)
- NEVER usar ponto flutuante para valores monetários, quantidades de token ou P&L; usar decimal exato ou inteiros em unidades base. (origem: PRD §10 e métricas do beta)
- NEVER registrar em log segredos, tokens de sessão, cabeçalhos `Authorization`, nonces ou assinaturas de login. (origem: PRD §12, PRD §15 US15)
- NEVER expor segredo em variável `NEXT_PUBLIC_*` ou em qualquer código entregue ao navegador. (origem: PRD §12, C-02)
- NEVER incluir no código de produção APIs de importação ou geração de chave secreta ou de frase mnemônica. (origem: C-02, PRD §7)
