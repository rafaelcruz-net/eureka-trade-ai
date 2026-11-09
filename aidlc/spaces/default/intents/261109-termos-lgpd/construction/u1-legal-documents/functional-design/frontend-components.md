# Componentes de tela — U1 `u1-legal-documents`

Conversa: português (Brasil). Fontes: `inception/refined-mockups/mockups.md` (T1, T2, T3), `interaction-spec.md`, `design-system-mapping.md`, `accessibility-checklist.md`, `functional-spec.md` e `rules.md`.

## Hierarquia

```
App do convidado (apps/web) e App do administrador (apps/admin)
├── /termos            -> LegalPage(kind="terms")
│    ├── LegalHeader (marca, "Entrar")
│    ├── LegalDocument
│    │    ├── VersionBanner (versão, vigência, faixa de versão antiga, aviso não publicável)
│    │    ├── SimulationNotice (aviso PAPER)
│    │    ├── Summary (5 a 7 tópicos)
│    │    ├── TableOfContents (nav "Nesta página"; recolhido abaixo de 768 px)
│    │    ├── Section* (h2, parágrafos)
│    │    └── VersionHistory (Could)
│    └── SiteFooter
├── /privacidade       -> LegalPage(kind="privacy")
│    └── LegalDocument + RetentionTable + DeploymentFields + RequestButton
└── AppShell (todas as telas com sessão) -> SiteFooter(acceptedVersion)
```

## Props e estado

| Componente | Props | Estado |
|---|---|---|
| `LegalPage` | `kind`, `version?` (de `?versao=`) | dados vindos do servidor (sem estado no navegador) |
| `LegalDocument` | `document`, `kind`, `isCurrent`, `publishable` | índice aberto ou recolhido |
| `RetentionTable` | `items` | — |
| `DeploymentFields` | `storageLocation?`, `internationalTransfer?`, `dpoEmail?` | — (nulo vira "a definir") |
| `RequestButton` | `hasSession`, `dpoEmail?` | — |
| `SiteFooter` | `acceptedVersion?` | — |
| `VersionHistory` | `versions` | — |

## Fluxos de interação

- **Carregando e erro:** a página é montada no servidor. Se a API falha, mostra `.notice-error` com "Tentar de novo" (recarrega).
- **Versão antiga:** `?versao=vN` válida e não vigente mostra a faixa "Esta não é a versão vigente" com link para a vigente. Uma versão inexistente mostra "Versão não encontrada".
- **Índice:** cada link leva à âncora da seção e põe o foco no título dela.
- **"Fazer um pedido":** com sessão, vai para `/conta`; sem sessão, para `/entrar`, com o texto do e-mail do encarregado logo abaixo.

## Validação de formulário

Não há formulário nesta Unit.

## Integração com a API

| Chamada | Uso |
|---|---|
| `GET /api/v1/legal/current` | T1, T2 |
| `GET /api/v1/legal/versions` | histórico (Could) |
| `GET /api/v1/legal/versions/{version}` | `?versao=` |

Tipos gerados do OpenAPI, como nos demais. Nenhum segredo nem variável `NEXT_PUBLIC_*`: a origem da API é lida no servidor.

## Acessibilidade

Ponto de entrada "Pular para o conteúdo"; h1 com o nome do documento, h2 por seção; regiões `header`, `main`, `nav` (índice) e `footer`; a tabela de retenção tem `caption` e cabeçalhos de coluna e fica empilhada abaixo de 768 px; contraste AA nas páginas legais. As páginas legais não mostram o aviso "só para computador".
