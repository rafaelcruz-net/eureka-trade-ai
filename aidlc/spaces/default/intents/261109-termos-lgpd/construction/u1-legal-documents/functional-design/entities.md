# Entidades — U1 `u1-legal-documents`

Conversa: português (Brasil). Fontes: `inception/units-generation/unit-of-work.md`, `inception/user-stories/stories.md` (US1.1–US1.3, US2.1, US2.2, US2.4–US2.6), `inception/domain-design/components.md` (Identity: TermsVersion), `inception/contract-design/contract-summary.md` (L1, J5) e `functional-design-questions.md` (Q1 a Q5). Agnóstico de tecnologia.

## Modelo

```yaml
entities:
  - name: LegalVersion
    description: Uma versão dos documentos legais (termos, política, aviso de risco), lida do catálogo versionado
    attributes:
      - { name: version, type: text, required: true, unique: true, constraints: "1 a 32 caracteres: letras, dígitos, ponto, sublinhado, hífen" }
      - { name: effectiveAt, type: instant, required: true, constraints: "UTC" }
      - { name: markedCurrent, type: boolean, required: true, default: false, constraints: "exatamente uma versão marcada" }
      - { name: legacy, type: boolean, required: true, default: false, constraints: "dispensa os campos de revisão; só para versões anteriores a esta Unit" }
      - { name: legalReviewedAt, type: date, required: false }
      - { name: legalReviewedBy, type: text, required: false, constraints: "1 a 120 caracteres" }
      - { name: terms, type: LegalText, required: true }
      - { name: privacy, type: LegalText, required: false, constraints: "obrigatório em versão não legacy; ausente na v1 legacy" }
      - { name: riskNotice, type: LegalText, required: true }
      - { name: retention, type: list of RetentionItem, required: false, constraints: "obrigatório em versão não legacy" }
    constraints:
      - "uma versão é revisada quando tem legalReviewedAt e legalReviewedBy, ou é legacy"
      - "versões ficam imutáveis: texto novo é uma versão nova"
    relationships: []

  - name: LegalText
    description: Um documento de uma versão, em texto simples
    attributes:
      - { name: summary, type: list of text, required: false, min: 5, max: 7, constraints: "obrigatório para termos e política da v2 em diante" }
      - { name: sections, type: list of LegalSection, required: true, min: 1 }
    relationships:
      - { to: LegalVersion, cardinality: "N para 1", direction: "pertence a" }

  - name: LegalSection
    description: Uma seção de um documento
    attributes:
      - { name: id, type: text, required: true, constraints: "âncora derivada do título, única no documento" }
      - { name: title, type: text, required: true }
      - { name: paragraphs, type: list of text, required: true, min: 1, constraints: "texto simples, nunca HTML" }

  - name: RetentionItem
    description: Uma linha da tabela de retenção mostrada na política
    attributes:
      - { name: dataItem, type: text, required: true }
      - { name: purpose, type: text, required: true }
      - { name: period, type: text, required: true }
      - { name: endAction, type: text, required: true }

  - name: DeploymentFields
    description: Campos da política que dependem do ambiente, vindos da configuração
    attributes:
      - { name: storageLocation, type: text, required: false, constraints: "ausente = a definir" }
      - { name: internationalTransfer, type: text, required: false, constraints: "ausente = a definir" }
      - { name: dpoEmail, type: email, required: false, constraints: "ausente = a definir" }

  - name: LegalSettings
    description: Configuração do ambiente para a ativação
    attributes:
      - { name: requirePublishable, type: boolean, required: true, default: false, constraints: "ligado só em produção" }

  - name: ActiveLegalDocuments
    description: Resultado da ativação, calculado na subida
    attributes:
      - { name: current, type: LegalVersion, required: true }
      - { name: publishable, type: boolean, required: true }
      - { name: deployment, type: DeploymentFields, required: true }
      - { name: history, type: list of LegalVersion, required: true, constraints: "vigente e anteriores, mais nova primeiro, sem futuras nem ignoradas" }
      - { name: ignored, type: list of text, required: true, constraints: "versões ignoradas na ativação, com o motivo, para o aviso no log" }
```

## Resumo

- **LegalVersion:** uma pasta do catálogo, imutável. Uma é marcada vigente; só vale se estiver revisada (ou for legacy).
- **LegalText / LegalSection:** o texto de cada documento, com resumo e seções, sempre texto simples.
- **RetentionItem:** a tabela de retenção da política. Na U1 vem do arquivo da versão; na U2, do catálogo de retenção, pela porta J5.
- **DeploymentFields:** local de armazenamento, transferência internacional e e-mail do encarregado, por ambiente.
- **LegalSettings:** a exigência de publicação, ligada só em produção.
- **ActiveLegalDocuments:** o que a API serve: a versão vigente efetiva, se é publicável, os campos, o histórico e as versões ignoradas.
