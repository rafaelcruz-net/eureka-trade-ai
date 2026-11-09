# Regras de negócio — U1 `u1-legal-documents`

Conversa: português (Brasil). Fontes: `inception/user-stories/stories.md`, `inception/requirements-analysis/requirements.md` (FR1, FR2, FR8, NFR3, NFR4), `inception/contract-design/contract-summary.md` (L1, J5), `entities.md` e `functional-design-questions.md` (Q1 a Q5). Os nomes concretos de arquivos e chaves ficam em "Decisões de implementação registradas", em `functional-spec.md`.

## Regras

```yaml
rules:
  - id: BR1.1
    statement: A versão vigente efetiva é a marcada como vigente, desde que ativável; senão, a ativável mais recente anterior a ela
    category: policy
    applies_to: LegalVersion
    trigger: subida da API
    logic: >
      Uma versão é ATIVÁVEL quando (a) está revisada (data e responsável da revisão jurídica) ou é legacy, (b) sua data de
      vigência não é posterior ao instante atual (relógio injetável), e (c) é publicável quando a exigência de publicação
      está ligada; versões legacy são isentas de (c). SE a marcada é ativável ENTÃO ela é a vigente; SENÃO a vigente é a
      ativável com a data de vigência mais recente entre as de vigência anterior à marcada (ordem pela data de vigência,
      nunca pelo nome), e um aviso vai para o log com a versão ignorada e o motivo.
    violation: se nenhuma versão é ativável, a API não sobe e diz o motivo
    source: FR1.4; AC1.3.1; AC1.3.2; AC1.3.3; Q1; Q3
  - id: BR1.2
    statement: Exatamente uma versão é marcada como vigente; versões repetidas, nomes inválidos ou textos vazios impedem a subida
    category: validation
    applies_to: LegalVersion
    trigger: subida da API
    logic: SE o catálogo não tem exatamente uma marcada, ou tem versão repetida, nome fora do padrão ou texto vazio ENTÃO falha com mensagem clara
    violation: a API não sobe
    source: FR1.1
  - id: BR1.3
    statement: Uma versão não legacy traz termos, política e aviso de risco, resumos de 5 a 7 tópicos para termos e política, e a tabela de retenção
    category: validation
    applies_to: LegalVersion
    trigger: subida da API
    logic: SE uma versão não legacy não tem a política, um dos resumos ou a tabela de retenção, ou um resumo tem menos de 5 ou mais de 7 tópicos ENTÃO a versão é inválida. Versões legacy podem não ter política, resumos nem tabela.
    violation: a API não sobe e diz o que falta
    source: FR1.1; AC1.1.1
  - id: BR1.4
    statement: O texto é simples, com seções e parágrafos marcados por convenção, e nunca é interpretado como marcação
    category: constraint
    applies_to: LegalText
    trigger: leitura do catálogo e exibição
    logic: SE um texto não tem nenhuma seção ENTÃO a versão é inválida; a tela mostra o texto como texto, nunca como marcação
    violation: a API não sobe
    source: FR1.1; Q2
  - id: BR1.5
    statement: A política de uma versão não legacy traz as seções exigidas e o aviso de simulação, e nenhum texto usa termo da lista versionada de promessas proibidas do produto
    category: validation
    applies_to: LegalVersion
    trigger: suíte de testes
    logic: SE falta uma das seções (dados e finalidade, base legal, retenção, compartilhamento, direitos e canal, encarregado, local e transferência, "não usamos rastreamento") ou falta o aviso PAPER ou aparece termo da lista de promessas proibidas (a mesma usada pelas telas) ENTÃO o teste falha
    violation: a CI fica vermelha
    source: FR1.2; AC1.1.2; AC1.1.3
  - id: BR1.6
    statement: Os campos de implantação vêm da configuração do ambiente; um campo ausente aparece como "a definir" e torna a versão não publicável
    category: policy
    applies_to: DeploymentFields
    trigger: subida da API e exibição
    logic: SE algum dos três campos está vazio ENTÃO publicável=falso e o campo vai como ausente; a exigência de publicação (ligada só em produção) entra na regra de ativação BR1.1
    violation: em produção, uma versão não legacy não publicável não é servida
    source: FR1.3; FR1.4; AC1.2.1; AC1.2.2; AC1.3.3; Q3
  - id: BR1.7
    statement: Os documentos são públicos e somente leitura
    category: authorization
    applies_to: L1
    trigger: requisição de leitura
    logic: SE a requisição é de leitura nos documentos ENTÃO responde sem sessão, a partir das origens dos dois apps e sem credenciais; outras operações são recusadas
    violation: operação não permitida
    source: FR2.1; AC2.1.1
  - id: BR1.8
    statement: A tabela de retenção mostrada vem da fonte de retenção registrada por outro módulo; sem ela, da tabela declarada na versão vigente
    category: policy
    applies_to: RetentionItem
    trigger: montagem da resposta
    logic: SE existe uma fonte de retenção registrada por outro módulo ENTÃO usa ela; SENÃO usa a tabela da versão vigente; SE a vigente é legacy e não declara tabela ENTÃO a tabela vai vazia e a política é tratada como ausente (BR1.15)
    violation: nenhuma
    source: FR3.4; J5; Q2
  - id: BR1.9
    statement: O histórico lista a vigente e as anteriores, mais nova primeiro; versões futuras ou ignoradas não aparecem; legacy aparece como versão provisória
    category: policy
    applies_to: ActiveLegalDocuments
    trigger: leitura do histórico
    logic: SE a versão tem vigência depois do instante atual, ou depois da vigente, ou foi ignorada na ativação ENTÃO não aparece; SE é legacy ENTÃO vai marcada como provisória
    violation: nenhuma
    source: FR8.1; AC2.6.1; Q4
  - id: BR1.10
    statement: Versão pedida que não está no histórico responde não encontrada
    category: validation
    applies_to: L1
    trigger: leitura de uma versão
    logic: SE a versão não está no histórico ENTÃO responde não encontrada (404)
    violation: 404
    source: FR8.1; AC2.6.1; Q5
  - id: BR1.11
    statement: O rodapé mostra os links das duas páginas em todas as telas e a versão aceita só com sessão
    category: policy
    applies_to: SiteFooter
    trigger: exibição de qualquer tela
    logic: SE há sessão ENTÃO mostra "Versão aceita: vX"; SENÃO mostra só os links
    violation: nenhuma
    source: FR2.2; AC2.2.1; AC2.2.2
  - id: BR1.12
    statement: O botão "Fazer um pedido" leva à Conta com sessão e à entrada sem sessão, com o e-mail do encarregado para quem não consegue entrar
    category: policy
    applies_to: página de privacidade
    trigger: clique
    logic: SE há sessão ENTÃO Conta; SENÃO entrada, e a página mostra o e-mail do encarregado (ou "a definir"); vale também quando a política está ausente (BR1.15)
    violation: nenhuma
    source: FR2.4; AC2.4.1; AC2.5.1
  - id: BR1.13
    statement: As páginas legais são acessíveis, se adaptam de 320 px em diante e carregam com p95 até 2 s
    category: constraint
    applies_to: páginas de termos e privacidade
    trigger: exibição
    logic: >
      "Pular para o conteúdo" é o primeiro foco; títulos h1 a h3 em ordem; regiões de página; tabela de retenção
      empilhada em telas estreitas; nenhuma rolagem horizontal. O p95 é medido por um cenário de carga com 20 acessos
      simultâneos às duas páginas, no mesmo script de carga do produto.
    violation: o spec de ponta a ponta, o teste de componente ou o cenário de carga falha
    source: NFR3; NFR4; AC2.1.2; AC2.1.3
  - id: BR1.14
    statement: Erros e versões antigas são mostrados com clareza, sem guardar nada no navegador
    category: policy
    applies_to: páginas de termos e privacidade
    trigger: falha da API ou versão antiga
    logic: SE a API falha ENTÃO aviso de erro com "tentar de novo"; SE a versão é antiga ENTÃO faixa "Esta não é a versão vigente"; SE não existe ENTÃO "Versão não encontrada" com link para a vigente
    violation: nenhuma
    source: AC2.1.4; Q5
  - id: BR1.15
    statement: Quando a vigente efetiva é legacy e não tem política, a página de privacidade mostra que a política ainda não foi publicada, com o e-mail do encarregado e o botão de pedido
    category: policy
    applies_to: página de privacidade e L1
    trigger: vigente legacy sem política
    logic: SE a vigente não tem política ENTÃO a resposta traz a política e os resumos ausentes e a tabela vazia, e a página mostra versão, vigência, aviso PAPER, o texto "A política de privacidade desta versão ainda não foi publicada", o e-mail do encarregado e o botão de pedido; a página de termos mostra os termos da versão legacy sem resumo e sem índice quando não há seções além de uma
    violation: nenhuma
    source: FR2.1; AC2.1.1; AC2.5.1
  - id: BR1.16
    statement: O aceite existente (no convite e onde mais for gravado) só aceita a vigente efetiva, e a busca de versão só acha versões do histórico
    category: constraint
    applies_to: aceite de termos (Identity)
    trigger: aceite no convite e consulta de versão
    logic: SE o aceite informa uma versão diferente da vigente efetiva ENTÃO é recusado como hoje (versão desatualizada); SE se busca uma versão ignorada ou futura ENTÃO não é encontrada; uma versão ignorada nunca é aceitável nem exibida como vigente
    violation: recusa do aceite
    source: FR1.4; FR4.2
```

## Resumo

| Regra | Assunto | Categoria |
|---|---|---|
| BR1.1 | Vigente efetiva: ativável (revisada ou legacy, sem data futura, publicável em produção, legacy isenta) ou a anterior por data | policy |
| BR1.2 | Uma marcada, nomes válidos, textos não vazios | validation |
| BR1.3 | Não legacy com três documentos, resumos e tabela | validation |
| BR1.4 | Texto simples com seções | constraint |
| BR1.5 | Seções exigidas, aviso PAPER, lista de promessas proibidas | validation |
| BR1.6 | Campos de implantação e publicável | policy |
| BR1.7 | Leitura pública | authorization |
| BR1.8 | Retenção pela fonte registrada ou pela versão | policy |
| BR1.9 | Histórico sem futuras nem ignoradas, legacy marcada | policy |
| BR1.10 | Versão fora do histórico: 404 | validation |
| BR1.11 | Rodapé | policy |
| BR1.12 | Destino do "Fazer um pedido" | policy |
| BR1.13 | Acessibilidade, responsividade e p95 por cenário de carga | constraint |
| BR1.14 | Erros e versão antiga | policy |
| BR1.15 | Vigente legacy sem política | policy |
| BR1.16 | Aceite e busca respeitam a ativação | constraint |
