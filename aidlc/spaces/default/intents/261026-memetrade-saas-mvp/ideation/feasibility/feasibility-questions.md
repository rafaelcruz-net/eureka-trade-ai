# Perguntas de viabilidade e restrições — MemeTrade Analytics

Responda apenas o que já estiver definido. “Ainda não definido” é uma resposta válida; itens pendentes serão registrados como riscos, premissas ou dependências para o discovery.

## Contexto já confirmado em etapas anteriores / no PRD

- Público inicial: traders convidados no Brasil; sem data externa obrigatória; estimativas serão revistas após Sprint 0.
- Sem orçamento aprovado para provedores, auditoria ou assessoria jurídica; nenhuma contratação nesta etapa.
- Responsáveis humanos por operações, segurança, jurídico e auditoria externa ainda serão definidos.
- Trading REAL automatizado segue posterior e bloqueado até validação da delegação/revogação, auditoria e avaliação jurídica; PAPER compõe o beta inicial.

## Integrações e plataforma

### 1. Quais fontes e integrações devem entrar na prova de viabilidade do Gate G0?
O PRD cita Pump.fun/PumpSwap, Raydium/LaunchLab, Meteora, BONK.fun e Bags para descoberta; Jupiter para cotações; DexScreener e Birdeye como fontes complementares; e Solana RPC/WebSocket. Nenhum fornecedor foi selecionado.

- [ ] Comparar primeiro duas fontes de dados candidatas e validar cobertura, frescor, quotas, custo e licença antes de selecionar (recomendado)
- [ ] Incluir a lista completa de fontes do PRD na prova inicial
- [ ] Já existe uma lista diferente de fornecedores ou integrações prioritários
- [ ] Ainda não definido; manter a seleção para discovery
- [ ] X. Other (please specify)

[Answer]: Comparar duas fontes candidatas.

### 2. Há uma conta ou organização AWS disponível para discovery e futura implantação?
O PRD propõe AWS como destino futuro, sem serviço, conta ou região escolhidos.

- [ ] Ainda não há conta/organização AWS; avaliar opções durante o discovery (recomendado)
- [ ] Há conta AWS; informar região e serviços já usados, sem compartilhar IDs ou credenciais
- [ ] A implantação futura deve considerar outro provedor ou ambiente
- [ ] Ainda não definido
- [ ] X. Other (please specify)

[Answer]: Ainda não há conta/organização AWS.

## Conformidade, privacidade e operação

### 3. Existe uma exigência de localização para armazenar ou processar dados do beta?
O público inicial confirmado é de traders convidados no Brasil. O PRD ainda não fixa residência de dados; esta resposta orienta o levantamento, sem substituir parecer jurídico.

- [ ] Sem exigência de região por enquanto; avaliar opções e confirmar com jurídico (recomendado)
- [ ] Dados devem permanecer hospedados/processados no Brasil
- [ ] Há exigência de uma região ou jurisdição específica; informar qual
- [ ] Ainda não definido
- [ ] X. Other (please specify)

[Answer]: Ainda não definido.

### 4. Que dados pessoais ou de carteira devem ser retidos, e por quanto tempo?
O PRD prevê endereços públicos de carteira, dados de conta/tenant e trilhas de auditoria, mas deixa a política de retenção pendente.

- [ ] Ainda não definido; inventariar dados e propor retenção mínima para validação jurídica e de privacidade (recomendado)
- [ ] Já existe política interna; descrever categorias e períodos sem incluir dados reais
- [ ] Minimizar retenção e manter apenas o necessário para conta, segurança, auditoria e histórico solicitado
- [ ] X. Other (please specify)

[Answer]: Inventariar dados e propor retenção mínima para validação jurídica e de privacidade.

## Capacidade e limites

### 5. Qual é a familiaridade disponível com a stack candidata do PRD?
O PRD propõe Next.js/React/TypeScript, Java 21/Spring Boot e Docker Compose em Windows. O levantamento do workspace não detectou uma stack existente; isso não contradiz a stack candidata prescrita pelo PRD.

- [ ] Experiência ainda não mapeada; incluir uma verificação de capacidade na Sprint 0 (recomendado)
- [ ] Há experiência suficiente na stack proposta; indicar eventuais lacunas
- [ ] Há lacunas que exigem capacitação ou apoio antes de estimar
- [ ] A stack precisa ser reavaliada por restrições de capacidade
- [ ] X. Other (please specify)

[Answer]: Mapear a experiência com a stack na Sprint 0.

### 6. Há bloqueios de calendário ou prioridades concorrentes para o discovery e o beta?
O PRD não fixa data externa; as estimativas devem ser revistas depois da Sprint 0. O responsável ainda definirá os responsáveis humanos por operações, segurança, jurídico e auditoria externa.

- [ ] Nenhum bloqueio conhecido; confirmar prioridades na Sprint 0 (recomendado)
- [ ] Há mudança de calendário, congelamento ou prioridade concorrente
- [ ] A disponibilidade do responsável ou dos especialistas limita o ritmo
- [ ] Ainda não definido
- [ ] X. Other (please specify)

[Answer]: Nenhum bloqueio conhecido; confirmar prioridades na Sprint 0.

## Consolidated Summary Confirmation

Confirma este resumo antes de eu gerar os artefatos?

- Looks correct
- Request changes

[Answer]: Looks correct

