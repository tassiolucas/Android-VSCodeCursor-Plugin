# Android Studio Experience (ASE)

Extensão para **VS Code / Cursor** que replica a experiência de desenvolvimento Android/Kotlin do Android Studio — com **motor LSP próprio** (Kotlin + LSP4J), indexação de símbolos **cross-module persistida em RocksDB** e navegação entre arquivos/módulos do projeto.

> **Filosofia:** 100% independente. Sem `compose-ai-tools`, sem Paparazzi, sem `kotlin-lsp` de terceiros. O engine é nosso, do zero.

---

## Status Atual

| Fase | Descrição | Status |
|------|-----------|--------|
| **Fase 1** | Motor LSP próprio (Kotlin + LSP4J) + client TypeScript | ✅ Completa |
| **Fase 2** | Indexação cross-module com RocksDB + navegação | ✅ Completa |
| **Fase 3** | Libs externas (`.jar`/`.class`), semantic highlighting, find usages real, preview renderer | 🔲 Próxima |

### O que funciona hoje

- ✅ Servidor LSP `stdio` (Kotlin/JVM 21, LSP4J 0.24.0)
- ✅ **Hover** com assinatura e doc (in-file)
- ✅ **Go-to-definition** in-file **e cross-module** (ex.: `LoginScreen` no `composeApp` → abre `LoginScreen.kt` no módulo `auth`)
- ✅ **Document symbols** (estrutura do arquivo)
- ✅ **Workspace symbols** (`Ctrl+T`) usando o índice RocksDB — ~44 símbolos de 7 módulos no projeto de teste
- ✅ Indexação automática em background na abertura do workspace
- ✅ Indexação incremental em `didOpen` / `didSave`
- ✅ Indicador de status na barra inferior (flame `ASE LSP`)
- ✅ Cross-platform: Windows (`cmd.exe /c`), Linux/macOS (script shell direto)

### Limitação atual (Fase 2)

O scanner indexa **apenas o código-fonte local do projeto** (arquivos `.kt`). Símbolos de dependências externas (Compose, stdlib, libs Maven/`.class`) **ainda não** são indexados — é exatamente o alvo da Fase 3 (paridade com Android Studio).

---

## Arquitetura

```
┌───────────────────────────── VS Code / Cursor ─────────────────────────────┐
│                                                                             │
│  src/extension.ts (client TypeScript)                                       │
│    • vscode-languageclient spawna o servidor via stdio                      │
│    • Windows: cmd.exe /c ase-lsp-server.bat                                 │
│    • Linux/macOS: ase-lsp-server (script shell)                             │
│    • Status bar + commands (ase.indexProject, ase.renderComposePreview...)  │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │ LSP (JSON-RPC over stdio)
┌──────────────────────────────────▼──────────────────────────────────────────┐
│  server/ (Kotlin + LSP4J 0.24.0)  — "ase-lsp-server"                        │
│                                                                             │
│  Main.kt ──► AndroidLspServer.kt ──► TextDocumentService.kt                 │
│                             │        WorkspaceService.kt                    │
│                             ▼                                               │
│  DocumentManager.kt  (documentos abertos, sync incremental)                 │
│  KotlinAnalyzer.kt   (analyzer estrutural próprio — regex/linhas)           │
│  IndexManager.kt ──► ProjectIndexer.kt ──► RocksDbIndex.kt                  │
│        │                                      (RocksDB 9.7.3)               │
│        └── índice em <workspace>/.ase-index/                                │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Como a navegação cross-module funciona

1. Na abertura do workspace, `IndexManager` indexa o projeto em background:
   - Lê `settings.gradle(.kts)` para descobrir os módulos
   - Varre `src/*/kotlin` de cada módulo
   - Extrai símbolos com o analyzer estrutural
   - Persiste em RocksDB (`<workspace>/.ase-index/`)
2. `textDocument/definition` tenta resolver **in-file** primeiro; se não achar, extrai o identificador na posição do cursor e consulta o índice por nome — achando o símbolo **em qualquer módulo**.
3. `workspace/symbol` consulta o índice por nome parcial.

### Layout do índice RocksDB (single column family, chaves prefixadas)

| Prefixo | Conteúdo |
|---------|----------|
| `sym:{symbolId}` | `JSON(SymbolInfo)` |
| `name:{name}` | ids de símbolos separados por vírgula |
| `ref:{symbolId}` | `JSON(List<SymbolReference>)` |
| `meta:{key}` | metadados (`indexedAt`, `symbolCount`) |

> ⚠️ O índice é **destruído e recriado** a cada abertura (`RocksDB.destroyDB`) — não há invalidação incremental persistente ainda.

---

## Estrutura do Repositório

```
├── src/                          # Client da extensão (TypeScript)
│   ├── extension.ts              # Ativação, spawn do LanguageClient, status bar
│   └── test/
│       ├── runTest.ts            # Bootstrap @vscode/test-electron
│       └── suite/                # Testes de integração (VS Code headless)
├── server/                       # Servidor LSP (Kotlin)
│   ├── build.gradle.kts          # Kotlin 2.3.0, LSP4J 0.24.0, RocksDB 9.7.3, JVM 21
│   └── src/main/kotlin/com/androidstudioexperience/lsp/
│       ├── Main.kt               # Entry point LSP4J (stdio)
│       ├── AndroidLspServer.kt   # Root server, inicia IndexManager
│       ├── TextDocumentService.kt# hover/definition/documentSymbol/completion
│       ├── WorkspaceService.kt   # workspace/symbol, didChangeWatchedFiles
│       ├── documents/DocumentManager.kt
│       ├── analysis/KotlinAnalyzer.kt      # Analyzer estrutural (regex/linhas)
│       └── index/                # SymbolInfo, RocksDbIndex, ProjectIndexer, IndexManager
├── scripts/
│   ├── test-lsp.js               # Smoke test stdio (initialize→hover→definition→symbols)
│   └── test-server.js
├── docs/                         # Documentação
│   ├── DEVELOPMENT_HISTORY.md    # Histórico completo do desenvolvimento
│   └── agent-handoffs/EXECUTION.md  # Handoff técnico das fases (Build→Reviewer)
├── language-configuration.json   # Auto-closing pairs, folding para Kotlin
├── package.json                  # Manifesto da extensão (commands, settings)
└── tsconfig.json
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

Settings disponíveis: `ase.server.javaPath`, `ase.indexing.generatedSources`, `ase.renderer.timeoutSeconds`, `ase.sdk.minApi`.

---

## Roadmap

- **Fase 3** — Kotlin Analysis API para resolução real de tipos
  - Indexação de dependências externas (libs Maven, stdlib, `.class`)
  - Semantic highlighting (LSP `textDocument/semanticTokens`)
  - Find usages / call hierarchy reais (hoje são text-based)
  - Go-to-definition para `.class` (decompilação)
- **Fase 4** — Preview renderer Compose/XML
- **Fase 5** — Run/debug pipeline, Git workflow estilo IntelliJ

---

## Documentação

- `docs/DEVELOPMENT_HISTORY.md` — histórico completo: decisões, bugs encontrados/corrigidos, comandos e resultados de cada fase
- `docs/agent-handoffs/EXECUTION.md` — handoff técnico detalhado (Build → Reviewer) por fase

---

## Licença

Apache-2.0 (ver `package.json`).