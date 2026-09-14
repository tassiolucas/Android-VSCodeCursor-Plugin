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

## 11. Migração para Linux + verificação (2026-09-13)

Migração do ambiente Windows para **Linux (Omarchy/Arch, `~/.local/share/mise`)**, clone do repo via GitHub, e revalidação completa do build/testes — com um **bug de Linux corrigido** descoberto na verificação.

### Ambiente

| Item | Valor |
|------|-------|
| Repo | clonado em `~/Projetos/Android-VSCodeCursor-Plugin` |
| JDK | **21.0.2** via `mise use -g java@21` (não havia JDK no sistema) |
| Node | 26.7.0 (mise) |
| Workspace de teste | `git clone git@github.com:android/kotlin-multiplatform-samples.git ~/Projetos/kotlin-multiplatform-samples` → projeto **`Fruitties`** (módulos `:shared`, `:androidApp`) |
| Host key | `ssh-keyscan github.com >> ~/.ssh/known_hosts` (primeiro clone falhava com `Host key verification failed`) |

### Bug crítico corrigido: URI → path relativo no Linux

**Sintoma:** `uriToPath("file:///home/user/x.kt")` retornava `home/user/x.kt` (sem `/` inicial) porque usava `removePrefix("file:///")`. No Windows o resultado é correto (`d:/...`); no Linux o path virava **relativo ao CWD** → `didOpen`/`didSave`/`definition` cross-file tentavam ler `/cwd/.../x.kt` e falhavam. O smoke test mostrou `Failed to read file .../Android-VSCodeCursor-Plugin/tmp/demo/Hello.kt`.

**Correção:** utilitário central `server/.../util/Uris.kt` com `uriToPath()` que, em plataforma não-Windows, **prepend `/`** quando o path decodificado não começa com `/`. As 3 cópias duplicadas (`AndroidLspServer`, `TextDocumentService`, `WorkspaceService`) agora delegam ao utilitário. `DocumentManager.uriToPath` (via `Paths.get(URI.create)`) já estava correto.

**Testes:** novo `UrisTest` (4 testes) cobrindo path absoluto Linux com `%20`, uri não-`file`, decode percent e uri malformada → **7 unit tests, 0 falhas**.

### Smoke test real (`scripts/test-lsp.js`)

Reescrito para validar indexação cross-module de verdade:
- Workspace via `ASE_TEST_WORKSPACE` (fallback: `test-workspace/`); `rootUri` montado com `pathToFileURL`.
- `workspace/symbol(*)` **falha** se retornar 0 (garante que o índice cross-module populou).
- Posição de hover/definition escolhida automaticamente sobre uma declaração que o analyzer estrutural reconhece (nome após a keyword, alinhado com o cálculo de coluna do `KotlinAnalyzer`).

Resultado contra `Fruitties`:

```
Found 3 modules: [Fruitties, androidApp, shared]
Indexed 82 symbols from 3 modules
[OK] hover  -> fun/val assinatura (ex.: `val dbFile`, _Declared in_ Factory.native.kt:33)
[OK] definition -> file:///home/tassiolucas/.../Factory.native.kt (URI absoluta correta)
[OK] workspace/symbol(*) -> 50
== ALL CHECKS PASSED ==
```

### Teste de integração VS Code

`src/test/suite/extension.real.test.ts` ficou **agnóstico de projeto** (antes esperava o Weave App: `composeApp/.../App.kt`). Agora varre o workspace, acha qualquer declaração analisável e verifica hover.

```
✔ LSP activates and hover works on a real KMP source file (8089ms)
1 passing
Integration tests passed.
```

### Notas

- `server/gradlew` veio **sem permissão de execução** no clone → `chmod +x gradlew` (configurar `core.fileMode` ou `.gitattributes` em repo futuro se recorrente).
- Build: `./gradlew installDist --no-daemon` → **BUILD SUCCESSFUL** (warnings de depreciação do LSP4J apenas).

---

## 12. Fase 3 — Libs externas: classpath real + indexação ASM + decompilação (2026-09-13)

Rota decidida com o usuário: **pragmática (ASM + classpath)** — a Kotlin Analysis API standalone se provou **não publicável de forma consumível** (ver 12.1). Objetivo: navegar para `.kt`/`.class` de libs e colorir classes sem resolução de tipos completa.

### 12.1 Por que NÃO Analysis API standalone

Pesquisa de viabilidade (Milestone 1) concluiu que o artifact **não existe publicado**:

| Verificação | Resultado |
|---|---|
| `org.jetbrains.kotlin:analysis-api-standalone` (Central / IntelliJ Deps / KotlinDev) | **404 em todos** |
| `analysis-api-standalone-for-ide` (único existente) | Só **dev builds** (`2.3.0-dev-*`, `2.3.20-dev-*`, `2.5.0-dev-*`) |
| POM do `-for-ide` | Referencia `analysis-api-standalone-base`/`-fir-standalone-base`/`analysis-api-standalone` **não publicados** → resolução Gradle falha (testado) |
| Sub-módulos `kotlin-analysis-api-*` | Versão `0.1.0.0-dev-*`, outra linha de build |
| Runtime | Exige `intellij-core` (IntelliJ core repackeado) mesmo assim |

Conclusão: a Analysis API só é distribuída para tooling baseado em IntelliJ. **Spike do Milestone 1 virou decisão:** construir o próprio motor semântico sobre `kotlin-compiler` (PSI/FIR) era API de baixo nível (semanas); opção pragmática escolhida.

### 12.2 Arquivos novos

| Arquivo | Responsabilidade |
|---------|------------------|
| `server/.../index/ClasspathResolver.kt` | Roda `gradlew aseDumpClasspath` (init script embutido como resource) e parseia o classpath real por módulo |
| `server/src/main/resources/ase-dump-classpath.init.gradle.kts` | Init script: task que imprime `*CompileClasspath` (sem test/metadata/klib) |
| `server/.../index/LibraryIndexer.kt` | Lê `.class` de jars (e `classes.jar` de aars) com **ASM** e extrai símbolos (limpa `getX`→property, remove sintéticos/anônimos) |
| `server/.../index/LibrarySymbol.kt` | Modelo de símbolo de lib (`className` interno, `memberName`, `jarPath`, `artifact`) + `toUri()` `ase-lib:` |
| `server/.../index/LibraryDecompiler.kt` | Decompila `.class` sob demanda com **Vineflower** (`BaseDecompiler` + `IResultSaver` em memória) |
| `server/src/test/.../LibraryIndexerTest.kt` | 3 testes (gera bytecode via ASM, valida indexação, parse do classpath) |
| `scripts/test-libraries.js` | Teste e2e de libs (polling até Gradle indexar, definition → `ase-lib:`, decompile) |
| `src/extension.ts` | `AseLibContentProvider` (scheme `ase-lib:`) + setTextDocumentLanguage(java) |

### 12.3 Fluxo implementado

1. `IndexManager.indexExternalLibraries()` (background, após `indexProject`) → `ClasspathResolver` roda `gradlew aseDumpClasspath` → lista jars/aars do compile classpath real.
2. `LibraryIndexer` (ASM) indexa classes/interfaces/enums + membros de cada jar/aar → RocksDB (`libsym:`/`libname:` prefixos).
3. `textDocument/definition` com 3º fallback: projeto in-file → índice cross-file → **índice de libs** → devolve `Location` com URI `ase-lib:<jar>!<class>[#member]`.
4. VS Code abre o scheme `ase-lib:` → `AseLibContentProvider` chama `workspace/executeCommand ase.decompile` → servidor decompila o `.class` (Vineflower) → documento virtual read-only (Java).

### 12.4 Resultados verificados (Fruitties)

```
Found 3 modules: [Fruitties, androidApp, shared]   Indexed 82 symbols (project)
Resolving classpath: gradlew aseDumpClasspath ...   (~40s primeira execução, --no-daemon)
Indexed 40413 external library symbols from 89 jars/aars
workspace/symbol(Room)  -> ase-lib:/.../room-runtime.aar!androidx/room/Room
textDocument/definition (Room.databaseBuilder) -> ase-lib:/...room-runtime.aar!androidx/room/Room
workspace/executeCommand ase.decompile           -> "public object Room { ... }"
Integração VS Code: go-to-definition abre doc virtual decompilado ✔ (2 passing no total)
```

### 12.5 Testes

- Unit (Kotlin): **10 testes** (3 analyzer + 4 Uris + 3 LibraryIndexer) — 0 falhas
- `npm run test:lsp` — ALL CHECKS PASSED (smoke contra `test-workspace/`)
- `ASE_TEST_WORKSPACE=... npm run test:libraries` — ALL LIBRARY CHECKS PASSED
- `ASE_TEST_WORKSPACE=... npm run test:integration` — **2 passing** (hover + definition para lib decompilada)

### 12.6 Observações / limitações

- Classpath resolution roda `gradlew` no startup (background); ~40s na primeira execução. **Candidato a cache/daemon (M6).**
- Símbolos de lib são por **nome simples** (sem FQCN resolve) — go-to-definition escolhe classe sobre membro; pode haver ambigüidade entre libs.
- Decompilação é sob demanda com cache em memória; ponto 0,0 (topo do arquivo) — sem mapeamento linha exata ainda.
- `-metadata` jars (KMP common) são ignorados (sem `.class`); a indexação usa o classpath JVM/Android.

---

## 13. Navegação completa estilo Android Studio (2026-09-13)

Usuário reportou símbolos não navegáveis no Fruitties: `CartItemDetails`, `AppDatabase`, `fruittieDao()`, `loadMapped()`, `database`.

### Causa raiz

O parser estrutural do `KotlinAnalyzer` só reconhecia linhas começando **exatamente** com `fun/class/interface/object/val/var/enum class`. Qualquer **modificador** (`abstract`, `data`, `private`, `suspend`, `override`, `sealed`, `internal`, `expect/actual`, ...) ou **anotação na mesma linha** fazia o símbolo ser ignorado → não indexado → sem go-to-definition nem hover.

| Símbolo reportado | Declaração real | Motivo da falha |
|---|---|---|
| `CartItemDetails` | `data class CartItemDetails(` | `data` |
| `AppDatabase` | `abstract class AppDatabase : RoomDatabase()` | `abstract` |
| `fruittieDao()` | `abstract fun fruittieDao(): FruittieDao` | `abstract` |
| `loadMapped()` | `suspend fun loadMapped(...)` | `suspend` |
| `database` | `private var database: AppDatabase,` (construtor) | `private` |

### Correções

**`KotlinAnalyzer.kt` — parser reescrito** (`FUN`/`CLASS_LIKE`/`VAL_VAR` regex):
- Prefixo opcional de anotações (mesma linha, `@Anno(args)` com parênteses balanceados) + modificadores antes da keyword
- Suporta `data/sealed/abstract/open/final/inner/annotation/enum class`, `companion object`, `expect/actual`, `override`, `suspend/inline/infix/operator/tailrec/const/lateinit`
- Extensões `fun Receiver.name(...)` e genéricas `fun <T> Receiver<T>.name(...)`
- `fun interface`, propriedades em construtor primário (`private var x: T,`)
- `nameRange` mapeado na linha original (coluna exata)
- Novos métodos: `declarationSymbol`, `definitionOf`, `hoverOnDeclaration`

**`TextDocumentService.kt` — definition/hover universais:**
- Cadeia: `extractSymbolAtPosition` → declaração in-file por nome → índice do projeto → índice de libs
- Funciona para **qualquer identificador** (propriedades, tipos, chamadas), não só declarações/calls
- Hover fora de declaração mostra assinatura do símbolo (projeto: `kind name` + arquivo:linha; lib: `package.Class.member` + artifact)

### Verificação

```
[OK] CartItemDetails -> fruitties/model/Fruittie.kt:34   (data class)
[OK] AppDatabase     -> fruitties/database/AppDatabase.kt:25 (abstract class)
[OK] fruittieDao     -> fruitties/database/AppDatabase.kt:26 (abstract fun)
[OK] loadMapped      -> fruitties/database/FruittieDao.kt:46 (suspend fun)
[OK] database        -> DataRepository.kt:31              (private var construtor)
```

- Unit: **12 testes** (5 analyzer, 3 LibraryIndexer, 4 Uris) — 0 falhas
- `test:lsp`, `test:libraries` verdes; integração VS Code **2 passing**
- Novo script `scripts/verify-navigation.js` (`npm run test:navigation`) cobre os 5 símbolos

### Limites honestos (sem sistema de tipos)

- Membros herdados (`toString`, `equals`), overloads por assinatura e resolução por tipo do receiver (`flow.map` vs `list.map`) continuam limitados — exigem motor semântico (roadmap).
- `expect`→`actual` resolve pelo mesmo nome via índice (sem distinção de alvo).

---

## 14. Fase 3 (cont.) — Semantic tokens, find references e índice persistente (2026-09-13)

### 14.1 Semantic highlighting (`textDocument/semanticTokens/full`)

Como o plugin **não tem gramática TextMate**, os arquivos Kotlin não tinham *nenhuma* coloração. Agora há um provider próprio (`analysis/SemanticTokensProvider.kt`):

- **Lexer próprio** (state machine por caractere) que classifica comentários (`//`, `/* */`), strings (`"..."`, `"""..."""`), chars, números e anotações (`@...`) — cobrindo o highlighting básico que faltava.
- **Identificadores** são resolvidos por nome na cadeia: declaração in-file → índice do projeto → índice de libs → **heurística** (inicial maiúscula → class).
- Legend: class/interface/enum/enumMember/function/property/variable/keyword/string/number/comment/decorator + modificadores `declaration` e `defaultLibrary`.
- `package.json` → `contributes.configurationDefaults["[kotlin]"]["editor.semanticHighlighting.enabled"] = true` — garante que o highlight funcione **mesmo em temas que não habilitam semanticHighlighting** (ex.: tokyo-night, que não define `semanticHighlighting: true`).

> Por que isso importa: o usuário usa o tema tokyo-night, que tem `semanticTokenColors` mas não habilita o recurso; sem o `configurationDefaults` nada mudaria visualmente.

### 14.2 Find references (`textDocument/references`)

- Novo handler `references()` no `TextDocumentService` usando o `IndexManager.findReferences` (text-based) — busca `\b<nome>\b` em todos os arquivos indexados; respeita `includeDeclaration`.
- Corrigido o bug de URI em `SymbolReference.toLocation` (`file://` sem `/` → agora usa `SymbolInfo.filePathToUri`).
- Capability `referencesProvider` registrada.

### 14.3 Índice persistente (sem re-index a cada abertura)

O índice era **destruído e recriado a cada startup** (e as libs re-indexadas com Gradle+ASM ~40s). Agora:

| Antes | Depois |
|---|---|
| `RocksDB.destroyDB` na abertura | Abre persistente |
| Re-index de todos os arquivos | Pula arquivos inalterados (stamp mtime+size, chave `file:{path}`) |
| Libs re-indexadas sempre | Cache por stamp de build files (`classpathStamp`); se inalterado → reutiliza (sem Gradle/ASM) |

Novos métodos em `RocksDbIndex`: `removeFileSymbols`, `setFileStamp`/`getFileStamp`, `deleteByPrefix`, `clearLibraries`, `countByPrefix`. `ProjectIndexer` pula arquivos inalterados e limpa símbolos ao re-indexar. `IndexManager.removeFile` agora deleta de verdade (era stub).

**Resultado medido (Fruitties):** 1ª execução ~16s (Gradle + 40.413 símbolos de libs); 2ª ~1.6s com `Reusing cached library index`.

**Bugfix pós-persistência — corrupção do RocksDB:** com o índice persistente, um `SIGKILL` durante escrita (restart loop do VS Code) deixava o DB corrompido (`MANIFEST` apontando para `.sst` inexistente) e o servidor crashava em loop no `initialize`. Correções:
- `RocksDbIndex.openOrCreate` agora **detecta corrupção e recupera** (destroy + recria vazio → re-index do zero). Lock contention (outro processo com o LOCK) **não** destrói — retorna null graciosamente.
- `Main.kt`: shutdown hook fecha o DB em `exit`/SIGTERM (reduz chance de corrupção; SIGKILL é coberto pela recuperação).

Verificado: rodando contra o índice corrompido → `Corrupted index detected ... recreating from scratch` e servidor sobe normal.

### 14.4 Testes

- Unit: **15 testes** (5 analyzer, 3 LibraryIndexer, 3 SemanticTokensProvider, 4 Uris) — 0 falhas
- `test:lsp`, `test:libraries`, `test:navigation` verdes; integração VS Code **2 passing**
- Capabilities do servidor agora: hover, definition, documentSymbol, workspaceSymbol, references, semanticTokens, executeCommand

---

## 15. Fase 3.5 — Paridade de navegação/leitura com Android Studio (2026-09-13)

Auditoria do escopo "indexação, navegação e leitura de funções/classes/objetos" comparando com o Android Studio. Achados principais na varredura do código: `signatureHelp` e `completion` eram stubs; `SymbolInfo` não guardava assinatura/doc; `documentSymbol` era flat; `containerName` nunca populado; colisão de mesmo nome por arquivo; prefixo `ref:` ocioso; zero suporte a recursos XML e `.java`; `didChangeWatchedFiles` com TODOs.

### 15.1 Índice v2 + analyzer hierárquico (fundação)

- **`KotlinMask`** (novo): lexer que mascara comentários/strings preservando colunas — base de todo o matching estrutural (parser, documentHighlight, find usages, inlay hints).
- **`KotlinAnalyzer` reescrito**: modelo hierárquico real (classes → membros em `children`), assinaturas multi-linha, construtores (primário via propriedades `val/var` + secundários), `typealias`, enum entries, `companion object`, visibilidade, `superTypes`, `packageName`, `parameters` (nome/tipo/default), `returnType`, `qualifiedName` (`Classe.membro` — fim da colisão de mesmo nome).
- **Schema v2** (`SymbolInfo` + `RocksDbIndex`): persistidos signature/doc/params/returnType/superTypes/package/visibility; migração automática via `meta:schemaVersion` (clear + re-index).
- Consequências imediatas: hover cross-file rico (assinatura + KDoc = `Ctrl+Q` do AS), outline/breadcrumbs hierárquicos, `Ctrl+T` com container.

### 15.2 Signature Help — o `Ctrl+P` do Android Studio

- **`CallSiteParser`** (novo): scan reverso balanceado de parênteses a partir do cursor (sobre texto mascarado), callee antes do `(`, skip de genéricos, parâmetro ativo = vírgulas top-level; keywords (`if`/`when`/...) rejeitadas.
- Candidatos: in-file → índice v2 → **libs** (descritor JVM `(Ljava/lang/String;I)V` traduzido para assinatura legível via `JvmSignatures`).
- `LibraryIndexer` agora indexa **construtores** (`<init>` com id próprio — antes colidiam com a classe).
- Keybinding `Ctrl+P` → `triggerParameterHints` com `when` restrito a editor Kotlin/Java (Quick Open preservado fora).

### 15.3 Navegação avançada (handlers LSP novos)

`documentHighlight`, `implementation` (índice de superTypes), `typeDefinition` (tipo declarado), **definition multi-candidato** (`LocationLink[]` — resolve expect/actual e ambiguidades no peek, como o AS), `callHierarchy` (incoming via refs + `enclosingDeclaration`; outgoing via refs do corpo da função), `typeHierarchy` (supertypes/subtypes), `rename` + `prepareRename` (cross-file via índice; in-file lexer-aware), `workspace/symbol` camelCase (`"Lh"` → `LegacyHelper`), `didChangeWatchedFiles` incremental real (Created/Changed/Deleted, com detecção de módulo).

### 15.4 Ecossistema Android + Java + gerados

- **`ResourceIndexer`** (novo): `res/values/*.xml` (string/color/dimen/style/...), file-based (layout/drawable/menu/...), `@+id/` — chave `res:{type}:{name}`, múltiplas configurações, incremental por arquivo.
- `definition` resolve `R.<type>.<name>` → localização exata no XML (etapa 0 da cadeia).
- **`JavaAnalyzer`** (novo): estrutural para `.java` (classes/interfaces/enums, métodos, construtores, campos, superTypes de extends/implements, javadoc) — projetos mistos indexados.
- **KSP/KAPT**: setting `ase.indexing.generatedSources` agora funciona — client passa `initializationOptions.generatedSources`, scanner inclui `build/generated/**` (filtro `/build/` ajustado).

### 15.5 Find usages de qualidade + CodeLens + Inlay hints

- `findReferences` lexer-aware (máscara de comentários/strings) + filtro de ambiguidade por pacote/import/FQN quando o nome é ambíguo.
- Referências **persistidas** na indexação (dois passes: símbolos → refs) — `replaceFileReferences` por arquivo.
- **CodeLens "N usages"** sobre declarações (contagem via refs persistidas; clique dispara `ase.showReferences` → `vscode.executeReferenceProvider` → peek nativo).
- **Inlay hints** de nome de parâmetro em call-sites (split top-level de argumentos; skip de named args e keywords).
- **Keymap AS completo** no `package.json` (11 rebinds com `when` Kotlin/Java): Ctrl+P, Ctrl+B, Ctrl+Alt+B, Ctrl+Shift+B, Alt+F7, Ctrl+F12, Ctrl+Q, Ctrl+H, Ctrl+Alt+H, Shift+F6, Ctrl+W.

### 15.6 Limites conhecidos (estrutural, sem motor semântico)

- Overloads precisos por assinatura, receiver-type (`flow.map` vs `list.map`), type inference: fora do escopo estrutural — mitigados por definition multi-candidato e filtro de ambiguidade. Motor semântico sobre `kotlin-compiler` segue no roadmap.
- Membros de classes em linha única (`class A { fun f() {} }`) não são indexados (mesma limitação da Fase 1).
- `typeDefinition` exige tipo declarado explícito (não infere `val x = Foo()`).
- CodeLens "N usages" usa refs persistidas (aproximado entre edits; o clique mostra o resultado exato query-time).

### 15.7 Testes e verificação

- Unit: **37 testes, 0 falhas** (KotlinAnalyzer 10, CallSiteParser 11, RocksDbIndex 2, ResourceIndexer 1, JavaAnalyzer 3, LibraryIndexer 3, SemanticTokens 3, Uris 4).
- `test:lsp` reescrito sobre fixture novo (`test-workspace/` = 2 módulos, Kotlin+Java+res): **20 checks** — 15 capabilities + hover, definition (cross-module e `R.string`), signatureHelp, documentSymbol hierárquico, documentHighlight, implementation, typeDefinition, prepareRename, references, callHierarchy, typeHierarchy, codeLens, inlayHint, workspace/symbol (+camelCase). **ALL CHECKS PASSED.**
- Capabilities do servidor agora (15): hover, definition, documentSymbol, workspaceSymbol, references, signatureHelp, documentHighlight, implementation, typeDefinition, rename(+prepare), callHierarchy, typeHierarchy, codeLens, inlayHint, semanticTokens, executeCommand.

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
| `server/.../analysis/KotlinAnalyzer.kt` | Analyzer estrutural hierárquico (regex/linhas): modelo com signature/params/superTypes |
| `server/.../analysis/KotlinMask.kt` | Lexer de máscara (comentários/strings preservando colunas) |
| `server/.../analysis/JavaAnalyzer.kt` | Analyzer estrutural Java (classes, métodos, campos, superTypes) |
| `server/.../analysis/CallSiteParser.kt` | Parser de call-site para signature help (parâmetro ativo) |
| `server/.../index/JvmSignatures.kt` | Descritor JVM → assinatura Kotlin legível (hover/signature help de libs) |
| `server/.../index/ResourceIndexer.kt` | Recursos Android (values XML, layouts, ids) → `R.*` navegável |
| `server/.../index/SymbolInfo.kt` | Data classes (schema v2); `filePathToUri()` (normalização cross-platform) |
| `server/.../index/RocksDbIndex.kt` | Storage RocksDB; JSON com escape/unescape corretos; camelCase; refs persistidas |
| `server/.../index/ProjectIndexer.kt` | Scanner de módulos + arquivos (kt/kts/java + gerados); refs em 2 passes; resources |
| `server/.../index/IndexManager.kt` | Orquestração: indexProject, indexFile, find(All)Definitions, implementations, resources |

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