# Requisitos de segurança — U4 Descoberta e avaliação

Requisitos de segurança da Unit `u4-discovery`, derivados das regras de [rules.md](../functional-design/rules.md), dos fluxos de [functional-spec.md](../functional-design/functional-spec.md), do NFR4 a NFR8 de [requirements.md](../../../inception/requirements-analysis/requirements.md), dos contratos C2, C9, C11, C12 e C19 de [contract-summary.md](../../../inception/contract-design/contract-summary.md) e das regras do `project.md` e do `team.md`. As decisões vêm de [nfr-requirements-questions.md](nfr-requirements-questions.md) (Q3, Q4).

## Classificação dos dados da U4

| Dado | Classe | Onde aparece | Proteção |
|---|---|---|---|
| Tokens, observações, histórico e estado das fontes | Público (dado de mercado) | Banco, lista, detalhe | Sem dado de cliente; leitura só com sessão (C2); escrita só pela coleta |
| Avaliações de risco e versões de pesos | Interno | Banco, lista, detalhe | Leitura com sessão; criação e ativação de versões só pelo administrador, com auditoria |
| Contador de requisições por pessoa | Interno | Memória do processo da API | Chave pelo identificador da pessoa, nunca pela carteira; não aparece em log |
| Credenciais de fontes reais (futuras) | Restrito | `.env` local | Nunca no repositório, em log ou em métrica; não existem enquanto as fontes são simuladas |

Nenhum dado da U4 pertence a um tenant: os dados de mercado e as avaliações são globais (functional-spec.md, "Cantos tratados").

## Requisitos

| ID | Requisito | Critério verificável | Origem |
|---|---|---|---|
| NFR6.1 | Rotas só com sessão | `GET /api/v1/scanner/tokens` e `GET /api/v1/tokens/{chain}/{mint}/assessment` sem token respondem `401 UNAUTHENTICATED`; com token de convidado ou de administrador válido para a rota do convidado respondem conforme o contrato. Registro de rotas com as duas rotas marcadas como globais (sem dado de cliente) | C2, NFR6 |
| NFR6.2 | Entrada validada | Filtros, ordenação, `cursor`, `limit`, `chain` e `mint` validados conforme a BR2.7: decimal mal formado, negativo ou com mais de 30 dígitos, inteiro fora do intervalo, texto acima do tamanho do contrato (`chain` 64, `mint` 44), parâmetro desconhecido e cursor de outra consulta respondem `400 VALIDATION_FAILED` com a lista de campos, sem SQL nem stack trace. Consultas sempre parametrizadas | BR2.7, DP-3 |
| NFR6.3 | Limite de requisições por pessoa | Cada rota aceita até 60 requisições por minuto por pessoa (janela deslizante); acima disso, `429` com `Retry-After` em segundos e `ProblemDetail` com código `RATE_LIMITED` (acréscimo ao contrato). O limite conta por pessoa autenticada, não por endereço IP. Teste: a 60ª requisição em um minuto passa; a 61ª recebe `429`; após o tempo do `Retry-After`, volta a passar | Q3, NFR6 |
| NFR6.4 | Versões de pesos só pelo administrador | `RiskWeightAdministration` recusa ator que não seja `ADMIN` com `403 FORBIDDEN_ROLE` auditado; cada ativação publica exatamente um `RISK_WEIGHTS_ACTIVATED` na transação da ativação (contrato C9); reativar a versão já ativa não publica evento. Nenhum código de produção da U4 cria contexto de administrador | BR4.2, DP-2 |
| NFR6.5 | Dados das fontes tratados como não confiáveis | Toda resposta de adaptador é validada antes de gravar (F1 passo 4): chain e mint com tamanho e alfabeto do contrato, símbolo até 32 caracteres e nome até 120, sem caracteres de controle; decimais não negativos com no máximo 30 dígitos; no máximo 5 000 tokens por coleta. Registro inválido é descartado e contado. Texto vindo da fonte é exibido como texto, nunca interpretado como HTML | F1, BR1.5 |
| NFR6.6 | Endereços das fontes só da configuração | O endereço de cada fonte real (futura) vem da configuração do ambiente, nunca de dado recebido; adaptadores não seguem redirecionamentos e não aceitam destinos fora da lista configurada. Enquanto as fontes são simuladas, nenhum adaptador faz chamada de rede | C19, C-06 |
| NFR6.7 | Dependência nova sob os portões da CI | Resilience4j entra com versão fixa no `pom.xml`, coberta pelo OWASP Dependency-Check e pelo CodeQL; achado Crítico ou Alto bloqueia o merge | Q4, `team.md` |
| NFR6.8 | Textos sem promessa de lucro nem garantia de fraude | O teste do catálogo de termos proibidos (BR5.1) roda na CI sobre todos os textos do app do convidado; falha bloqueia o merge | FR6.4, AC3.2.3, `project.md` |
| NFR6.9 | Nenhuma ação de operação a partir da avaliação | A avaliação só informa `decisionHint`; nenhuma rota ou tarefa da U4 cria ordem, assina ou envia transação. Teste de arquitetura: o módulo RiskAssessment não depende de módulos de bots, ordens ou ledger | `project.md` (REAL desligado), U4 fronteiras |
| NFR4.1 | Indicador determinístico e exato | Mesmas entradas e mesma versão dão o mesmo indicador, faixa e contribuições; cálculos em decimal exato com arredondamento meio para cima onde as regras dizem; nenhum ponto flutuante em valores de mercado, pesos, cobertura ou indicador. Teste de propriedade com entradas aleatórias de semente fixa | AC3.1.4, BR3.2, `project.md` |
| NFR4.2 | `unknown` nunca vira zero nem segurança | Campo ausente é `Unknown` do adaptador à tela; fator `Unknown` vale risco 100 e nunca permite nível Baixo; avaliação indisponível dá `NO_TRADE`. Testes obrigatórios de risco `unknown` (team.md) cobrem lista, filtro, ordenação, avaliação e C11 | FR4.4, FR6.2, AC2.2.2, AC3.2.2 |
| NFR4.3 | Gravação da coleta tudo ou nada | Uma coleta interrompida não deixa valores parciais: tokens, observações, histórico, estado da fonte e marca na mesma transação (F1) | F1 |
| NFR5.1 | Nenhum dado de cliente na U4 | Nenhuma tabela da U4 tem `tenantId`; as rotas da U4 não leem dados de outras Units com tenant. O teste do registro de rotas (U1) confere que as duas rotas estão marcadas como globais | NFR5, DP-1 |
| NFR7.1 | Nada pessoal nos logs da U4 | Logs e métricas da U4 não contêm carteira, token de sessão nem identificador da pessoa do limite de requisições; o `ProblemDetail` de `429` não inclui a contagem de outras pessoas | NFR7 |
| NFR8.1 | Avaliação reproduzível | Cada avaliação guardada traz a versão dos pesos e, por fator, valor, fonte e horário de observação usados; recalcular com esses dados e essa versão dá o mesmo resultado (base para o NFR8 da U5) | NFR8, AC3.1.4 |

## Modelo de ameaças (STRIDE) dos fluxos da U4

| Ameaça | Exemplo | Mitigação | Requisito |
|---|---|---|---|
| Falsificação | Chamada às rotas sem sessão válida | Autenticação obrigatória | NFR6.1 |
| Falsificação | Convidado tentando ativar uma versão de pesos | Só `ADMIN`, recusa auditada | NFR6.4 |
| Adulteração | Fonte devolvendo texto com marcação ou valores absurdos | Validação e descarte contado; texto exibido como texto | NFR6.5 |
| Adulteração | Versão de pesos alterada depois de ativada | Versões ativas e retiradas imutáveis | BR4.2, NFR6.4 |
| Repúdio | Administrador nega ter trocado os pesos | `RISK_WEIGHTS_ACTIVATED` com ator na trilha | NFR6.4 |
| Divulgação | Erro com SQL ou stack trace | `ProblemDetail` sem detalhes internos | NFR6.2 |
| Negação de serviço | Muitas consultas com filtros caros | Limite de 60 por minuto por pessoa, meta de p95 | NFR6.3, NFR3.1 |
| Negação de serviço | Fonte devolvendo milhões de tokens | Teto de 5 000 tokens por coleta | NFR6.5 |
| Negação de serviço | Fonte lenta travando a coleta | Tempo limite e novas tentativas (Resilience4j) | reliability-requirements.md |
| Elevação de privilégio | Avaliação usada para disparar operação | Nenhum caminho de operação na U4 | NFR6.9 |
| Elevação de privilégio | Dado `unknown` tratado como seguro | Risco 100, nunca Baixo, `NO_TRADE` | NFR4.2 |

## Fora do escopo

- Tela administrativa e rota de pesos: U8 (com a interface desta Unit).
- Credenciais e contratos de fornecedores reais: depois do G0.
- Criptografia em trânsito e em repouso: herdadas da U1 e da U2 (NFR7.6 da U2), revistas no G1.
