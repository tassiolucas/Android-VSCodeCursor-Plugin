# Android Studio Experience (ASE)

Extensão para **VS Code / Cursor** que traz o fluxo de trabalho Android/Kotlin do Android Studio — seleção de módulos, árvore de projeto, variantes de build, run, logcat, debug, preview e keymap — **sem reimplementar análise de código ou sync Gradle**.

## Filosofia

| Responsabilidade | Dono |
|------------------|------|
| Sync Gradle, classpath, diagnóstico Kotlin/Java, navegação LSP | **[Kotlin by JetBrains](https://marketplace.visualstudio.com/items?itemName=JetBrains.kotlin-server)** (`kotlin-lsp`) |
| UX Android Studio: flags de módulo, árvore, variantes, Gradle run, adb, logcat, attach debug, preview | **ASE** (esta extensão) |

A ASE **não** embute servidor LSP próprio, índice RocksDB nem gerador de `workspace.json`. Ela delega import e análise ao `kotlin-lsp` e concentra-se em comandos, views e atalhos familiares ao Android Studio.

---

## Pré-requisitos

| Ferramenta | Nota |
|------------|------|
| **Kotlin by JetBrains** | Dependência obrigatória (`JetBrains.kotlin-server`). A ASE pede instalação na primeira ativação se faltar. |
| **JDK 21** | Toolchain esperado pelos monorepos Android/Gradle atuais. |
| **Android SDK** | `ANDROID_HOME` ou `ANDROID_SDK_ROOT`, ou `sdk.dir` em `local.properties`. |
| **adb** | `$SDK/platform-tools/adb`, path em `ase.adb.path`, ou `adb` no `PATH`. |
| **VS Code / Cursor** | Engine `^1.85.0` (ver `package.json`). |

---

## Configuração (exemplo base-app)

Ajuste no `.vscode/settings.json` do workspace (monorepo com super-app e variantes Inter):

```json
{
  "ase.autoReload": true,
  "ase.profile.moduleFlagsFile": "local.properties",
  "ase.profile.defaultFlagsFile": "includedBuilds/build-configuration/properties/modules.properties",
  "ase.profile.alwaysIncluded": {
    ":app": "app",
    ":super-app": "super-app",
    ":core-modules:app-common": "core-modules/base-modules/app-common"
  },
  "ase.profile.appModules": [":super-app", ":app", ":digital-goods-modules:marketplace-app"],
  "ase.profile.variants": [
    "interHmgDebug",
    "interProdDebug",
    "interMockServerMock",
    "interProdRelease",
    "pjHmgDebug",
    "pjProdDebug"
  ],
  "ase.adb.path": "",
  "ase.debug.port": 8700
}
```

### Formato das flags de módulo

No arquivo indicado por `ase.profile.moduleFlagsFile` (padrão: `local.properties`), entradas no formato:

```properties
# sdk.dir é preservado; não use slash no valor de flag de módulo
sdk.dir=/Users/me/Library/Android/sdk

core-modules.core-modules/base-modules/analytics=auto
mkt-projects.digital-goods/cart=true
```

- **`grupo.caminho/relativo/ao/modulo`** → Gradle `:grupo:<ultimo-segmento>` (ex.: `:core-modules:analytics`).
- Valores válidos: **`true`**, **`false`**, **`auto`**.

---

## Import Gradle (AGP) — experimental

A sincronização do projeto Android passa pelo **import Gradle do kotlin-lsp** (suporte AGP ainda **experimental**). Se o classpath ou a árvore de módulos parecerem errados:

1. **ASE: kotlin-lsp Diagnostics** (`ase.lspDiagnostics`) — menu com reload, restart, limpar caches, exportar workspace JSON e link para reportar issue.
2. **ASE: Reload Gradle Project** — reimport manual após mudar flags, `*.gradle.kts` ou `libs.versions.toml` (com `ase.autoReload` também dispara reload debounced).

Evite `intellij.buildTool=json` ou entradas `type: json` em `intellij.projects`; a ASE remove legado automaticamente na ativação.

O `kotlin-lsp` cuida da **análise** (classpath, navegação, diagnósticos, atalhos). O índice só sobe se o import Gradle completar.

No base-app, `isSyncing()` trata o Android Studio (sem tasks) **e** o import do kotlin-lsp (`prepareKotlinIdeaImport` / `prepareKotlinBuildScriptModel`) como sync: o sample não aplica suffix `.marketplace` nesse momento, o Google Services casa com o JSON e o classpath pode indexar com `marketplace-app=true`. **Run App** da ASE continua Gradle/`adb` (`install` da variante) e não desliga o sample.

Se o import AGP experimental falhar por outro motivo, Run/Assemble/Logcat/Debug attach seguem disponíveis. **Select App Module** → `:digital-goods-modules:marketplace-app`, variante `interHmgDebug` (ou mock) → `:digital-goods-modules:marketplace-app:installInterHmgDebug`. Se o reimport for ruidoso, defina `ase.autoReload` como `false`.

---

## Comandos

| Comando | ID | Descrição |
|---------|-----|-----------|
| Select Modules | `ase.selectModules` | Quick pick para incluir/excluir módulos via flags no arquivo configurado |
| Reload Gradle Project | `ase.reloadProject` | Pede ao kotlin-lsp para reimportar o workspace |
| kotlin-lsp Diagnostics | `ase.lspDiagnostics` | Menu de diagnóstico / troubleshooting |
| Refresh Android Project | `ase.refreshTree` | Atualiza a view **Android → Project** |
| Select Build Variant | `ase.selectVariant` | Escolhe a variante ativa (assemble/install) |
| Select App Module | `ase.selectAppModule` | Escolhe o módulo application para run/debug |
| Build (assemble) | `ase.assemble` | `assemble` da variante selecionada |
| Select Device | `ase.selectDevice` | Lista dispositivos via `adb devices -l` |
| Run App | `ase.runApp` | `install` + start da activity launcher |
| Show Logcat | `ase.logcat` | Terminal dedicado com log filtrado pelo app |
| Debug App (attach) | `ase.debugApp` | JDWP via port forward (`ase.debug.port`) |
| Run Compose Preview on Device | `ase.runComposePreview` | Preview Compose no dispositivo selecionado |

---

## Atalhos principais

| Atalho (Win/Linux) | macOS | Comando |
|--------------------|-------|---------|
| `Ctrl+Alt+A` | `Cmd+Alt+A` | Select Modules |
| `Ctrl+Shift+O` | `Cmd+Shift+O` | Reload Gradle Project (sem foco no editor) |
| `Shift+F10` | `Ctrl+R` | Run App |
| `Shift+F9` | `Ctrl+D` | Debug App |

### Keymap Android Studio (Kotlin/Java)

Ativos apenas com `editorLangId == kotlin || java`:

| Atalho | Ação |
|--------|------|
| `Ctrl+P` / `Cmd+P` | Parameter hints |
| `Ctrl+B` / `Cmd+B` | Go to definition |
| `Ctrl+Alt+B` / `Cmd+Alt+B` | Go to implementation |
| `Ctrl+Shift+B` / `Cmd+Shift+B` | Go to type definition |
| `Alt+F7` | Find references |
| `Ctrl+F12` / `Cmd+F12` | File structure |
| `Ctrl+Q` / `Ctrl+J` | Quick documentation (hover) |
| `Ctrl+H` | Type hierarchy |
| `Ctrl+Alt+H` | Call hierarchy |
| `Shift+F6` | Rename |
| `Ctrl+Alt+L` / `Cmd+Alt+L` | Format document |
| `Ctrl+Alt+O` | Organize imports |
| `Ctrl+W` / `Alt+Up` | Extend selection |

Fora de editores Kotlin/Java, atalhos nativos do VS Code (Quick Open em `Ctrl+P`, etc.) permanecem.

---

## Roadmap por fases

| Fase | Escopo | Status |
|------|--------|--------|
| **F1** | Integração kotlin-lsp: reload, watch de flags/Gradle, diagnósticos, keymap de editor | Entregue |
| **F2** | Perfil de projeto, flags em `local.properties`, seletor de módulos, árvore Android | Entregue |
| **F3** | Variantes, assemble/install Gradle, dispositivos adb, run, logcat, attach debug | Entregue |
| **F4** | Preview Compose no device, polish de UX | Em evolução |

---

## Desenvolvimento

```bash
npm install
npm run compile
npm run test          # compile + node --test out/test/
npm run package       # gera .vsix
```

Testes unitários (Node built-in): `src/test/*.test.ts` → compilados para `out/test/`.

Abra o repositório no VS Code e use **F5** (Extension Development Host) com um workspace Gradle/Android real.

---

## Licença

Apache-2.0 — ver campo `license` em `package.json`.
