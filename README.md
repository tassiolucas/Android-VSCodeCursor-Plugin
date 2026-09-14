# Android Studio Experience (ASE)

Extensão para **VS Code / Cursor** que replica a experiência de desenvolvimento Android/Kotlin do Android Studio — com **motor LSP próprio** (Kotlin + LSP4J), indexação de símbolos **cross-module persistida em RocksDB** e navegação entre arquivos/módulos do projeto.

> **Filosofia:** 100% independente. Sem `compose-ai-tools`, sem Paparazzi, sem `kotlin-lsp` de terceiros. O engine é nosso, do zero.

---

## Status Atual

| Fase | Descrição | Status |
|------|-----------|--------|
| **Fase 1** | Motor LSP próprio (Kotlin + LSP4J) + client TypeScript | ✅ Completa |
| **Fase 2** | Indexação cross-module com RocksDB + navegação | ✅ Completa |
| **Fase 3** | Libs externas (`.jar`/`.class`) + decompilação Vineflower | ✅ Completa |
| **Fase 3.5** | Paridade de navegação/leitura com Android Studio (signature help, hierarquias, recursos XML, Java, rename, keymap AS) | ✅ Completa |
| **Fase 4** | Preview renderer Compose/XML | 🔲 Planejada |

### O que funciona hoje

- ✅ Servidor LSP `stdio` (Kotlin/JVM 21, LSP4J 0.24.0) — **15 capabilities**
- ✅ **Signature Help** (`Ctrl+P`, estilo Android Studio *Parameter Info*) — call-site parser próprio, parâmetro ativo, overloads, fontes: in-file → índice → libs (descritor JVM traduzido para assinatura legível)
- ✅ **Hover** com assinatura + KDoc — in-file **e cross-file** (índice v2 carrega o contrato completo do símbolo)
- ✅ **Go-to-definition** in-file, cross-module, libs externas (decompilação Vineflower em `ase-lib:`) **e recursos Android** (`R.string.x` → `strings.xml`, com todas as configurações `values*`)
- ✅ **Go-to-implementation** (`Ctrl+Alt+B`) e **type hierarchy** (`Ctrl+H`) via índice de supertypes
- ✅ **Go to Type Declaration** (`Ctrl+Shift+B`) via tipo declarado
- ✅ **Call hierarchy** (`Ctrl+Alt+H`) — incoming/outgoing estruturais
- ✅ **Rename** (`Shift+F6`) com `prepareRename` — cross-file via índice de referências
- ✅ **Document highlight** — ocorrências do símbolo no arquivo (lexer-aware)
- ✅ **Document symbols hierárquicos** (`Ctrl+F12` File Structure, outline + breadcrumbs) — classes → membros, propriedades de construtor, enum entries, typealias, construtores secundários
- ✅ **Workspace symbols** (`Ctrl+T`) com **camelCase** (`"Lh"` → `LegacyHelper`)
- ✅ **Semantic highlighting** — lexer próprio + resolução de tipos (in-file → índice → libs → heurística)
- ✅ **Find references** (`Alt+F7`) — lexer-aware (sem hits em comentários/strings) + filtro de ambiguidade por pacote/import
- ✅ **CodeLens "N usages"** sobre declarações + **Inlay hints** de nome de parâmetro (estilo AS)
- ✅ **Arquivos Java indexados** (analyzer estrutural Java próprio) — projetos mistos Kotlin+Java
- ✅ **Fontes geradas KSP/KAPT** indexadas (setting `ase.indexing.generatedSources`, default true)
- ✅ **Índice persistente** RocksDB (schema v2 com migração automática); incremental em `didOpen`/`didSave`/file-watcher
- ✅ **Keymap Android Studio** para editor Kotlin/Java: `Ctrl+P`, `Ctrl+B`, `Ctrl+Alt+B`, `Ctrl+Shift+B`, `Alt+F7`, `Ctrl+F12`, `Ctrl+Q`, `Ctrl+H`, `Ctrl+Alt+H`, `Shift+F6`, `Ctrl+W`
- ✅ Indicador de status na barra inferior (flame `ASE LSP`)

### Limitação atual

O analyzer é **estrutural** (regex/linhas com máscara de comentários/strings), não semântico. Isso significa: sem resolução precisa de sobrecargas por assinatura, sem receiver-type (`flow.map` vs `list.map`), sem type inference. As mitigações pragmáticas implementadas: definition **multi-candidato** (o peek do VS Code lista todas as opções, como o AS faz quando ambíguo), filtro de ambiguidade por pacote/import no find usages, e supertypes nomeados para hierarquias. A precisão total exige um motor semântico sobre `kotlin-compiler` — roadmap de fase futura.

---

## Arquitetura

```
┌───────────────────────────── VS Code / Cursor ─────────────────────────────┐
│                                                                             │
│  src/extension.ts (client TypeScript)                                       │
│    • vscode-languageclient spawna o servidor via stdio                      │
│    • Windows: cmd.exe /c ase-lsp-server.bat                                 │
│    • Linux/macOS: ase-lsp-server (script shell)                             │
│    • Status bar + commands (ase.indexProject, ase.showReferences...)        │
│    • TextDocumentContentProvider para ase-lib: (código decompilado)         │
│    • initializationOptions.generatedSources (setting → servidor)            │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ LSP (JSON-RPC over stdio)
┌──────────────────────────────────▼──────────────────────────────────────────┐
│  server/ (Kotlin + LSP4J 0.24.0)  — "ase-lsp-server"                        │
│                                                                             │
│  Main.kt ──► AndroidLspServer.kt ──► TextDocumentService.kt (15 handlers)   │
│                             │        WorkspaceService.kt                    │
│                             ▼                                               │
│  analysis/   KotlinAnalyzer (hierárquico) + JavaAnalyzer + KotlinMask       │
│              CallSiteParser (signature help) + SemanticTokensProvider       │
│  documents/  DocumentManager (sync incremental)                             │
│  index/      IndexManager ──► ProjectIndexer ──► RocksDbIndex (schema v2)   │
│              ResourceIndexer (R.*, @layout/, ids)                           │
│              LibraryIndexer (ASM) + ClasspathResolver + LibraryDecompiler   │
│        └── índice em <workspace>/.ase-index/                                │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Layout do índice RocksDB (single column family, chaves prefixadas)

| Prefixo | Conteúdo |
|---------|----------|
| `sym:{symbolId}` | `JSON(SymbolInfo)` — **v2**: signature, doc, params, returnType, superTypes, package, visibility, container |
| `name:{name}` | ids de símbolos separados por vírgula |
| `ref:{symbolId}` | `JSON(List<SymbolReference>)` — referências persistidas (CodeLens) |
| `res:{type}:{name}` | `JSON(List<ResourceEntry>)` — recursos Android (R.string.x → XML) |
| `libsym:` / `libname:` | símbolos de bibliotecas externas (ASM) |
| `file:{path}` | stamp mtime+size (skip de re-index) |
| `meta:{key}` | metadados (`schemaVersion`, `indexedAt`, `symbolCount`, `classpathStamp`) |

> Migração de schema: `meta:schemaVersion` ≠ 2 → índice limpo e reconstruído automaticamente.

---

## Estrutura do Repositório

```
├── src/                          # Client da extensão (TypeScript)
│   ├── extension.ts              # Ativação, spawn do LanguageClient, status bar, ase-lib:, ase.showReferences
│   └── test/suite/               # Testes de integração (VS Code headless)
├── server/                       # Servidor LSP (Kotlin)
│   └── src/main/kotlin/com/androidstudioexperience/lsp/
│       ├── Main.kt               # Entry point LSP4J (stdio)
│       ├── AndroidLspServer.kt   # Root server, capabilities, inicia IndexManager
│       ├── TextDocumentService.kt# hover/definition/signatureHelp/rename/hierarchies/codeLens/inlayHint/...
│       ├── WorkspaceService.kt   # workspace/symbol (+camelCase), didChangeWatchedFiles incremental
│       ├── documents/DocumentManager.kt
│       ├── analysis/             # KotlinAnalyzer, JavaAnalyzer, KotlinMask, CallSiteParser, SemanticTokensProvider
│       └── index/                # SymbolInfo(v2), RocksDbIndex, ProjectIndexer, IndexManager,
│                                 # ResourceIndexer, LibraryIndexer, ClasspathResolver, LibraryDecompiler, JvmSignatures
├── scripts/test-lsp.js           # Smoke test stdio — 20 checks (15 capabilities + fluxos)
├── test-workspace/               # Fixture: 2 módulos (app/core), Kotlin+Java+res/
├── docs/                         # DEVELOPMENT_HISTORY.md + agent-handoffs/EXECUTION.md
└── package.json                  # Manifesto (commands, settings, keymap AS)
```

---

## Pré-requisitos

| Ferramenta | Versão | Nota |
|------------|--------|------|
| JDK | **21** | Toolchain do Gradle (`jvmToolchain(21)`); JDK 17 **não** funciona |
| Node.js | 18+ | Build do client e testes |
| VS Code | 1.85+ | ou Cursor |
| Gradle | via `gradlew` | wrapper do projeto (`server/gradlew`) |

> `node_modules` e `server/build/` **não** são commitados (ver `.gitignore`). Após clonar, rode os passos de build abaixo.

---

## Build e Desenvolvimento

### 1. Instalar dependências do client

```bash
npm install
```

### 2. Compilar o client (TypeScript → `out/`)

```bash
npm run compile          # tsc -p ./
```

### 3. Buildar o servidor LSP

```bash
# Na raiz do repositório:
npm run build:server     # = gradle -p server installDist

# Ou sem Gradle instalado, usando o wrapper (commitado no repo):
cd server
./gradlew installDist --no-daemon      # Linux/macOS
.\gradlew.bat installDist --no-daemon  # Windows
```

Gera o launcher em `server/build/install/ase-lsp-server/bin/`:
- Windows: `ase-lsp-server.bat`
- Linux/macOS: `ase-lsp-server` (script shell)

> O Gradle Wrapper (`server/gradlew`, `gradlew.bat`, `gradle/wrapper/`) **está commitado** — baixa o Gradle 8.10.2 automaticamente na primeira execução. Só é preciso o JDK 21 instalado.

### 4. Build completo

```bash
npm run build:all        # npm run compile && npm run build:server
```

### 5. Rodar no VS Code (F5)

1. Abra o repositório no VS Code
2. Pressione `F5` (launch config de extensão)
3. Uma janela *Extension Development Host* abre — abra um projeto Kotlin/KMP nela
4. Confira a barra inferior: flame `ASE LSP` = servidor rodando

---

## Testes

### Smoke test do LSP (sem VS Code, via stdio)

```bash
npm run test:lsp         # node scripts/test-lsp.js
npm run test:libraries   # libs externas (exige ASE_TEST_WORKSPACE real)
```

Cobre: `initialize` → `didOpen` → `hover` → `definition` → `documentSymbol` → `workspace/symbol` → `shutdown`.

### Testes unitários do servidor (Kotlin)

```bash
cd server
./gradlew test --no-daemon     # 3 testes do KotlinAnalyzer
```

### Teste de integração VS Code (headless)

```bash
# Windows (PowerShell):
$env:ASE_TEST_WORKSPACE="D:\caminho\para\projeto-kmp"
npm run test:integration

# Linux/macOS:
ASE_TEST_WORKSPACE=/caminho/para/projeto-kmp npm run test:integration
```

Baixa o VS Code 1.137.0 via `@vscode/test-electron`, ativa a extensão, abre um `.kt` real e verifica hover. Se `ASE_TEST_WORKSPACE` não for definido, usa `test-workspace/`.

---

## Migração Windows → Linux/macOS

O código é cross-platform por construção; o que muda é apenas o launcher:

| Ponto | Windows | Linux/macOS |
|-------|---------|-------------|
| Spawn do servidor | `cmd.exe /c ase-lsp-server.bat` | `ase-lsp-server` (direto) |
| URI → path | `URLDecoder` + `\` | `URLDecoder` (mantém `/`) |
| Separador de path | `\` | `/` |

Já tratado em código (`process.platform === 'win32'` e `System.getProperty("os.name")`). **Não há alterações necessárias** — apenas rebuildar o servidor no host de destino (`./gradlew installDist`), pois os binários RocksDB nativos são por plataforma.

> Nota: ao indexar em Linux, o filtro `/build/` do `ProjectIndexer` exclui corretamente diretórios de build (no Windows isso dependia do separador — ver `docs/DEVELOPMENT_HISTORY.md`).

---

## Comandos da Extensão

| Comando | Atalho | Descrição |
|---------|--------|-----------|
| `ASE: Index Project` | `Ctrl+Alt+G` / `Cmd+Alt+G` | Re-indexa o projeto (stub — indexação automática em background) |
| `ASE: Render Compose Preview` | — (editor title) | Preview de `@Preview` Compose (Fase 4) |
| `ASE: Render Layout Preview` | — | Preview de layout XML (Fase 4) |
| `ASE: Show References` | — (CodeLens) | Peek de usos a partir do CodeLens "N usages" |

### Keymap Android Studio (ativo em editores Kotlin/Java)

| Atalho | Ação |
|--------|------|
| `Ctrl+P` | Parameter Info (signature help) |
| `Ctrl+B` | Go to Declaration |
| `Ctrl+Alt+B` | Go to Implementation(s) |
| `Ctrl+Shift+B` | Go to Type Declaration |
| `Alt+F7` | Find Usages |
| `Ctrl+F12` | File Structure |
| `Ctrl+Q` | Quick Documentation (hover) |
| `Ctrl+H` | Type Hierarchy |
| `Ctrl+Alt+H` | Call Hierarchy |
| `Shift+F6` | Rename |
| `Ctrl+W` | Extend Selection |
| `Ctrl+T` | Workspace Symbols (nativo do VS Code) |

> Todos os rebinds têm `when` restrito a `editorLangId == kotlin || java` — fora de editores Kotlin/Java, os atalhos padrão do VS Code (Quick Open em `Ctrl+P`, sidebar em `Ctrl+B`, etc.) seguem intactos.

Settings disponíveis: `ase.server.javaPath`, `ase.indexing.generatedSources`, `ase.renderer.timeoutSeconds`, `ase.sdk.minApi`.

---

## Roadmap

- **Fase 3 (completa)** — Libs externas: classpath real via Gradle + ASM + decompilação Vineflower ✅
- **Fase 3.5 (completa)** — Paridade de navegação/leitura com Android Studio:
  - ✅ Índice v2 (contrato completo do símbolo: assinatura, doc, params, returnType, superTypes, package, visibilidade)
  - ✅ Analyzer hierárquico (multi-linha, construtores, typealias, enum entries, companion)
  - ✅ Signature Help (`Ctrl+P`), implementations, type definition, call/type hierarchy, rename, document highlight
  - ✅ Recursos Android (`R.string.x` → XML), arquivos `.java`, fontes KSP/KAPT
  - ✅ CodeLens "N usages", inlay hints de parâmetros, workspace symbol camelCase, keymap AS
- **Fase 4** — Preview renderer Compose/XML
- **Fase 5** — Run/debug pipeline, Git workflow estilo IntelliJ
- **Futuro** — Motor semântico sobre `kotlin-compiler` (resolução precisa de overloads, receiver-type, inference)

---

## Documentação

- `docs/DEVELOPMENT_HISTORY.md` — histórico completo: decisões, bugs encontrados/corrigidos, comandos e resultados de cada fase
- `docs/agent-handoffs/EXECUTION.md` — handoff técnico detalhado (Build → Reviewer) por fase

---

## Licença

Apache-2.0 (ver `package.json`).