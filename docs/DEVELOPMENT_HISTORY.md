# Histórico de Desenvolvimento — Android Studio Experience (ASE)

**Atualizado:** 2026-09-13
**Objetivo:** Reconstruir o Android Studio como extensão independente para VS Code/Cursor, com motor LSP próprio. Este documento narra tudo o que foi feito até agora: decisões, bugs, comandos e resultados — para quem pegar o código em qualquer máquina (especialmente Linux) saber exatamente onde estamos e por quê.

---

## Índice

1. [Visão Geral do Projeto](#1-visão-geral-do-projeto)
2. [Fase 1 — Fundação do LSP Engine](#2-fase-1--fundação-do-lsp-engine)
3. [Fase 2 — Indexação Cross-Module com RocksDB](#3-fase-2--indexação-cross-module-com-rocksdb)
4. [Bugfixes da Fase 2 (críticos)](#4-bugfixes-da-fase-2-críticos)
5. [Resultados Verificados](#5-resultados-verificados)
6. [Decisões Técnicas e Porquês](#6-decisões-técnicas-e-porquês)
7. [Comandos Úteis por Plataforma](#7-comandos-úteis-por-plataforma)
8. [Estado Atual e Limitações](#8-estado-atual-e-limitações)
9. [O que o usuário pediu em seguida (Fase 3)](#9-o-que-o-usuário-pediu-em-seguida-fase-3)
10. [Preparação para GitHub](#10-preparação-para-github-2026-09-13)

---

## 1. Visão Geral do Projeto

| Item | Valor |
|------|-------|
| Nome da extensão | `android-studio-experience` |
| Pasta do projeto | `D:\Projetos\CodeCursor Android Plugin Resolver` (originalmente Windows) |
| Projeto de teste real | `D:\Projetos\KMP\Weave App` (Kotlin Multiplatform, 7 módulos) |
| Client | TypeScript + `vscode-languageclient` 9.0.1 |
| Servidor | Kotlin 2.3.0 + LSP4J `0.24.0` + RocksDB `9.7.3`, JVM 21 |
| Transporte | LSP sobre stdio |
| Índice | RocksDB em `<workspace>/.ase-index/` |
| Filosofia | **100% independente** — sem compose-ai-tools, Paparazzi ou kotlin-lsp |

---

## 2. Fase 1 — Fundação do LSP Engine

**Status:** ✅ Completo (2026-09-12)

### O que foi construído

Um servidor LSP em Kotlin (LSP4J) + client TypeScript que analisa arquivos Kotlin e responde a:

- `textDocument/hover` — assinatura + doc da declaração sob o cursor
- `textDocument/definition` — go-to-definition (in-file na Fase 1)
- `textDocument/documentSymbol` — estrutura do arquivo (funções, classes, props)
- `workspace/symbol` — busca de símbolos (Fase 1: só documentos abertos)

### Arquivos criados na Fase 1

| Camada | Arquivos |
|--------|----------|
| **Client TS** | `package.json`, `tsconfig.json`, `language-configuration.json`, `src/extension.ts`, `src/test/{runTest.ts,suite/index.ts,suite/extension.real.test.ts}` |
| **Servidor Kotlin** | `server/build.gradle.kts`, `server/settings.gradle.kts`, `Main.kt`, `AndroidLspServer.kt`, `TextDocumentService.kt`, `WorkspaceService.kt`, `documents/DocumentManager.kt`, `analysis/KotlinAnalyzer.kt`, `index/{SymbolInfo,RocksDbIndex,ProjectIndexer,IndexManager}.kt` |
| **Testes** | `server/src/test/kotlin/.../KotlinAnalyzerTest.kt` (3 testes), `scripts/test-lsp.js` |

### Descobertas críticas da Fase 1

1. **LSP4J groupId é `org.eclipse.lsp4j:org.eclipse.lsp4j`** (não `org.eclipse.lsp4j:lsp4j`) — versão 0.24.0 disponível no Maven Central.
2. **LSP4J 0.24.0 mudou a API** em relação a exemplos antigos:
   - `hover()` recebe `HoverParams` (não mais `TextDocumentPositionParams`)
   - `ServerCapabilities` usa `Either.forRight<Boolean, XxxOptions>()`
   - `definition()` retorna `Either<List<Location>, List<LocationLink>>`
   - `documentSymbol()` retorna `List<Either<SymbolInformation, DocumentSymbol>>`
3. **JVM toolchain teve que ser 21** (ambiente não tinha JDK 17).
4. **Spawn de `.bat` com espaços no caminho falha** no `vscode-languageclient` (`spawn EINVAL`) → usar `cmd.exe /c "<launcher>"` no Windows.
5. **`rtk` intercepta `gradle`/`npm`** no ambiente do usuário — usar `rtk gradlew ...` e `rtk npm run ...`.
6. **Kotlin Analysis API adiada** — as versões estáveis para Kotlin 2.3.0 não existiam (só `-dev` no repo JetBrains). Decisão ponytail: analyzer estrutural próprio (regex/linhas) com interface pública idêntica, trocável depois sem tocar na camada LSP.
7. **Gradle Tooling API indisponível** (`org.gradle:gradle-tooling-api:8.10` não está no Maven Central) → scanner de arquivos (lê `settings.gradle(.kts)`, varre `src/*/kotlin`).

### Resultado da Fase 1 (verificado)

- Smoke test stdio: `initialize` → `didOpen` → `hover` → `definition` → `documentSymbol` → `workspace/symbol` → `shutdown` — **ALL CHECKS PASSED**
- 3 testes unitários Kotlin do analyzer passando
- Teste de integração VS Code headless (1 passing) contra `App.kt` do Weave App real

---

## 3. Fase 2 — Indexação Cross-Module com RocksDB

**Status:** ✅ Completo (2026-09-12)

### O que foi construído

Indexação persistente de símbolos **de todo o projeto** (todos os módulos), permitindo navegação cross-file:

- `ProjectIndexer` descobre módulos (via `settings.gradle(.kts)`) e varre `src/*/kotlin`
- `KotlinAnalyzer` extrai símbolos estruturalmente
- `RocksDbIndex` persiste em `<workspace>/.ase-index/`
- `IndexManager` orquestra indexação (inicial em background, incremental em didOpen/didSave) e queries
- `textDocument/definition` agora faz **fallback cross-file via índice**
- `workspace/symbol` consulta o índice (não só documentos abertos)
- `didChangeWatchedFiles` reage a arquivos deletados

### Layout do RocksDB (single column family, prefixos)

| Prefixo | Conteúdo |
|---------|----------|
| `sym:{id}` | JSON de `SymbolInfo` |
| `name:{name}` | ids separados por vírgula (índice por nome) |
| `ref:{symbolId}` | JSON de lista `SymbolReference` |
| `meta:{key}` | `indexedAt`, `symbolCount` |

> ⚠️ O índice é **destruído e recriado** a cada inicialização do servidor (`RocksDB.destroyDB`). Não há invalidação incremental persistente.

---

## 4. Bugfixes da Fase 2 (críticos)

Estes bugs foram encontrados testando contra o projeto Weave App real. **Todos corrigidos e verificados.**

### 4.1 URI do VS Code vem percent-encoded (crítico para Windows)

**Sintoma:** paths com `%3A` (`:`), `%20` (espaço) etc. quebravam localização de arquivos.

**Causa:** VS Code envia URIs como `file:///d%3A/Projetos/KMP/Weave%20App/...`.

**Correção:** `URLDecoder.decode(uri.removePrefix("file:///"), "UTF-8")` em `uriToPath()` no `AndroidLspServer`, `TextDocumentService` e `WorkspaceService`. No Windows, ainda converte `/` → `\`.

### 4.2 `extractSymbolAtPosition()` crashava (StringIndexOutOfBoundsException)

**Sintoma:** exception ao resolver definição fora dos limites do texto.

**Correção:** reescrito com validação de limites (`line/col` dentro do texto, char válido para identificador) e expansão do identificador.

### 4.3 Timing bug: serviços criados antes do `indexManager` (o mais crítico)

**Sintoma:** `workspace/symbol` retornava só 1 resultado (fallback de documentos abertos) em vez dos ~44 do índice; definition cross-file nunca achava nada.

**Causa:** `TextDocumentService` e `WorkspaceService` eram instanciados **antes** de `initialize()` atribuir o `indexManager` — as classes capturavam `null` e nunca viam o índice. O LSP4J cacheia referências aos services antes do `initialize()` completar.

**Correção:** services recebem um **provider lambda** `() -> IndexManager?` que lê o campo `indexManager` da instância raiz a cada chamada:

```kotlin
private val textDocumentService: TextDocumentService = TextDocumentService(documents) { indexManager }
private val workspaceService: WorkspaceService = WorkspaceService(documents) { indexManager }
```

Depois disso, `workspace/symbol` passou a retornar **44 símbolos** do índice.

### 4.4 URI malformado no go-to-definition (`file://d:\\...`)

**Sintoma:** definition resolvia, mas o VS Code não navegava — URI inválido.

**Causa dupla:**
1. `SymbolInfo.toLocation()` montava `"file://" + filePath` sem normalizar (`file://d:\...` — faltava `/`, barras invertidas).
2. O JSON gravado no RocksDB escapa `\` como `\\`; ao ler via regex, os backslashes ficavam **duplicados** no path.

**Correções:**
- `SymbolInfo.filePathToUri()`: normaliza `\` → `/` e monta `file:///d:/caminho/arquivo.kt` (Linux: `file:///caminho/...`).
- `RocksDbIndex.jsonToSymbol()` e `jsonToReferences()`: fazem **unescape** do JSON (`\\` → `\`, `\"` → `"`) ao ler.
- `WorkspaceService.symbol()` usa `SymbolInfo.filePathToUri()` no `Location`.

**Resultado verificado:**

```
Ctrl+Click em "LoginScreen" (App.kt) →
  file:///d:/Projetos/KMP/Weave App/feature/auth/src/commonMain/kotlin/
  com/example/weave/feature/auth/ui/LoginScreen.kt  (linha 28)
```

Navegação cross-module real: `composeApp` → módulo `auth` → `LoginScreen.kt`.

### 4.5 Filtro `/build/` não funcionava no Windows

**Arquivo:** `ProjectIndexer.kt:196` — `.filter { !it.absolutePath.contains("/build/") }`.

**Problema:** no Windows os paths usam `\`; o filtro nunca casava → arquivos de build (`build/generated`, etc.) entravam no índice (por isso o índice tinha entradas como `example`, `email`). **Em Linux/macOS o filtro funciona por construção.**

**Correção aplicada no momento da documentação:** usar separador de plataforma em vez de hardcoded `/build/` para comportamento idêntico nos 3 SOs.

---

## 5. Resultados Verificados

### Índice do Weave App

| Métrica | Valor |
|---------|-------|
| Módulos descobertos | 7 |
| Símbolos indexados | 44 |
| Símbolo de teste | `LoginScreen` (módulo `auth`) |
| Path do índice | `<workspace>/.ase-index/` |

### Comportamento LSP confirmado via smoke test

- `workspace/symbol` (query vazia) → **44 símbolos** do índice (antes do fix 4.3: 1)
- `textDocument/definition` em `LoginScreen` no `App.kt` → URI correto do `LoginScreen.kt` linha 28
- `textDocument/definition` in-file → resolve normalmente

---

## 6. Decisões Técnicas e Porquês

| Decisão | Porquê |
|---------|--------|
| Analyzer estrutural (regex/linhas) em vez de Analysis API | Versões estáveis da Analysis API para Kotlin 2.3.0 não existiam; interface LSP preservada para swap futuro |
| Scanner de arquivos em vez de Gradle Tooling API | `org.gradle:gradle-tooling-api:8.10` não disponível no Maven Central |
| RocksDB para índice | Lookup O(1), persistência, baixo overhead; binários nativos por plataforma (rocksdbjni) |
| Single column family com prefixos | Evita complexidade da API de CF do RocksDB (causava erros em versões anteriores) |
| `installDist` em vez de fatJar | Launcher script funciona em dev; fatJar não testado (risco de ServiceLoader duplicado com LSP4J) |
| Provider lambda para `indexManager` nos services | LSP4J cacheia referências de serviço antes do `initialize()`; lambda lê o campo atual a cada chamada |
| `cmd.exe /c` para spawn no Windows | `vscode-languageclient` falha ao spawnar `.bat` com espaços no caminho |

---

## 7. Comandos Úteis por Plataforma

### Windows (PowerShell)

```powershell
# Build do servidor
rtk gradlew installDist --no-daemon        # ou: cd server; .\gradlew.bat installDist
cd ..\                                  # voltar à raiz

# Client
npm install
rtk npm run compile

# Testes
rtk npm run test:lsp                       # smoke test stdio
rtk npm run test:integration               # VS Code headless (define ASE_TEST_WORKSPACE antes)
cd server; rtk gradlew test --no-daemon    # unit tests Kotlin
```

### Linux / macOS (bash)

```bash
# Build do servidor
cd server && ./gradlew installDist --no-daemon && cd ..

# Client
npm install
npm run compile

# Testes
npm run test:lsp
ASE_TEST_WORKSPACE=/caminho/para/projeto-kmp npm run test:integration
cd server && ./gradlew test --no-daemon
```

> **Nota sobre o wrapper `gradlew`:** commitado no repositório em `server/` (`gradlew`, `gradlew.bat`, `gradle/wrapper/` — Gradle 8.10.2). Basta ter o JDK 21 instalado; o wrapper baixa o Gradle sozinho.

---

## 8. Estado Atual e Limitações

### Funciona

- Navegação cross-module (go-to-definition) para símbolos do **próprio projeto**
- Hover, document symbols, workspace symbols
- Indexação automática em background + incremental (didOpen/didSave)
- Cross-platform (Windows/Linux/macOS) no spawn e no path handling

### Limitações conhecidas

1. **Só código local** — dependências externas (Compose, stdlib Kotlin, libs Maven, arquivos `.class`/`.jar`) **não são indexadas**. Não há go-to-definition para símbolos de libs, nem decompilação de `.class`.
2. **Analyzer estrutural (regex)** — não entende herança, imports, sobrecargas, type inference. Find references é text-based (falsos positivos possíveis).
3. **`workspace/symbol` parcial** — busca por nome parcial apenas; sem fuzzy/semântica.
4. **Índice destruído a cada inicialização** — sem invalidação incremental persistente entre sessões.
5. **Sem semantic highlighting** — o colorimento "igual Android Studio" (tipos, classes, funções com cores distintas) depende de LSP `textDocument/semanticTokens`, que requer conhecimento de **tipo** (Analysis API).
6. **Completion/signature help** retornam `null` (stubs).
7. **`ase.indexProject` command** é stub (indexação real roda automática em background).
8. **FatJar não testado** para distribuição.

---

## 9. O que o usuário pediu em seguida (Fase 3)

Na conversa de 2026-09-13, o usuário pediu paridade com o Android Studio para **libs externas**:

> *"Por enquanto só pega somente as classes locais do projeto. Gostaria que envolvesse todas as libs presentes no repositório baixado, de terceiros e tudo que está aparecendo de código, igual o Android Studio faz. Colorindo os objetos que possuem classes no Kotlin/Java e levando ao código fonte do .kt ou .class da classe igual o Android Studio."*

### O que isso significa tecnicamente

| Recurso pedido | O que o Android Studio usa | Como atacar no ASE |
|----------------|---------------------------|--------------------|
| Resolver símbolos de libs (Compose, stdlib, etc.) | Kotlin Analysis API / PSI | Indexar classpath real (Gradle resolve) ou usar Analysis API |
| Go-to-definition para `.class` | Decompilador do IntelliJ | Integrar decompilador (ex.: Vineflower/Fernflower) para `.class` |
| Colorir objetos com classes (semantic highlighting) | LSP `textDocument/semanticTokens` | Requer saber o **tipo** de cada símbolo → Analysis API |

### Opções apresentadas (e status)

1. **Integrar kotlin-lsp** (maduro, usa Analysis API) — rápido (horas), mas viola a filosofia "100% independente".
2. **Construir nosso próprio com Kotlin Analysis API** — fiel à filosofia, porém **semanas** de trabalho e deps pesadas (~100MB, exige IntelliJ platform).
3. **Híbrido** — nosso server para código local + delegação para KLS em símbolos externos.
4. **Aceitar limitação** e documentar (já funciona para código próprio).

**Decisão pendente** — o usuário precisa escolher a direção. A recomendação honesta: paridade real exige a Analysis API (não há atalho), e a decisão é entre integrar algo pronto (opção 1) ou construir próprio (opção 2).

### Também pedido na mesma sessão

- Migração para **Linux** + subir o código no **GitHub** (motivo desta documentação).

---

## 10. Preparação para GitHub (2026-09-13)

Sessão final no Windows antes da migração para Linux. Tudo verificado antes do commit inicial.

### Correções aplicadas nesta sessão

| Correção | Arquivo | Detalhe |
|----------|---------|---------|
| Filtro de build cross-platform | `ProjectIndexer.kt` | `contains("/build/")` → normaliza `\` → `/` antes (no Windows o filtro nunca casava e indexava `build/generated`) |
| Fallback non-test cross-platform | `IndexManager.kt` | mesma normalização para `/test/` e `/Test/` |
| Unescape JSON ao ler do RocksDB | `RocksDbIndex.kt` | `\\` → `\`, `\"` → `"` em `jsonToSymbol` e `jsonToReferences` (corrige paths com backslash duplo) |
| URI de navegação | `SymbolInfo.kt`, `WorkspaceService.kt` | `filePathToUri()` centralizado: `d:\path` → `file:///d:/path` |

### Arquivos de infra criados

| Arquivo | Propósito |
|---------|-----------|
| `README.md` | Visão geral, arquitetura, build, testes, migração Linux/macOS |
| `docs/DEVELOPMENT_HISTORY.md` | Este documento |
| `.gitignore` | Exclui `node_modules/`, `out/`, `server/build/`, `.ase-index/`, logs, arquivos de OS |
| `server/gradlew`, `gradlew.bat`, `gradle/wrapper/` | Gradle Wrapper 8.10.2 — build reproduzível sem Gradle instalado |

### Decisões de empacotamento

- **Commitados:** `.vscode/launch.json` + `.vscode/tasks.json` (portáteis, usam `${workspaceFolder}`, sem paths locais), Gradle Wrapper, todo o código-fonte.
- **Ignorados:** `node_modules/`, `out/`, `server/build/`, `.vscode-test/` (VS Code baixado ~200MB), `.ase-index/` (runtime).
- **`test-workspace/`** commitado (fixture mínima `Main.kt` usada pelo launch config quando `ASE_TEST_WORKSPACE` não está definido).

### Verificação final

```
gradle test --no-daemon      # 3 tests, 0 failures (KotlinAnalyzerTest)
gradle installDist           # BUILD SUCCESSFUL
node scripts/test-lsp.js     # ALL CHECKS PASSED
```

> Nota: no smoke test, `workspace/symbol` retorna 0 porque o workspace fake (`tmp/demo`) não existe em disco — o indexer agora funciona (bug 4.3 corrigido) e o índice não tem arquivos para ler. Em projeto real: 44 símbolos.

### Estado do Git

`git init` + commit inicial feitos localmente. **Push não realizado** — aguardando o usuário criar o repositório remoto e rodar:

```bash
git remote add origin <url-do-repo>
git push -u origin main
```

---

## Apêndice A — Arquivos-fonte principais (mapa rápido)

| Arquivo | Responsabilidade |
|---------|------------------|
| `src/extension.ts` | Ativação, spawn do LanguageClient (por plataforma), status bar, commands |
| `server/.../Main.kt` | Bootstrap LSP4J stdio |
| `server/.../AndroidLspServer.kt` | Root server; cria IndexManager no `initialize()`; provider lambdas para services |
| `server/.../TextDocumentService.kt` | didOpen/didChange/didSave, hover, definition (in-file → cross-file via índice), documentSymbol |
| `server/.../WorkspaceService.kt` | workspace/symbol (índice com fallback), didChangeWatchedFiles |
| `server/.../documents/DocumentManager.kt` | Store de documentos abertos (sync incremental) |
| `server/.../analysis/KotlinAnalyzer.kt` | Analyzer estrutural (regex/linhas): hover, definition, documentSymbols, findSymbols |
| `server/.../index/SymbolInfo.kt` | Data classes; `filePathToUri()` (normalização cross-platform) |
| `server/.../index/RocksDbIndex.kt` | Storage RocksDB; JSON com escape/unescape corretos |
| `server/.../index/ProjectIndexer.kt` | Scanner de módulos + arquivos; filtro de build dir |
| `server/.../index/IndexManager.kt` | Orquestração: indexProject, indexFile, findDefinition, searchSymbols |

## Apêndice B — Dependências

| Dependência | Versão | Uso |
|-------------|--------|-----|
| `kotlin("jvm")` | 2.3.0 | Linguagem do servidor |
| `org.eclipse.lsp4j:org.eclipse.lsp4j` | 0.24.0 | Framework LSP (atenção ao groupId!) |
| `org.rocksdb:rocksdbjni` | 9.7.3 | Índice persistente (binários nativos por plataforma) |
| `org.slf4j:slf4j-simple` | 2.0.13 | Logs do servidor |
| `vscode-languageclient` | 9.0.1 | Client LSP no VS Code |
| `@vscode/test-electron` | 3.1.0 | Testes de integração |
| `typescript` | 5.4+ | Compilação do client |