# Roteiro de Testes — Atalhos Android Studio (ASE)

> Status: **pendente de execução** — próxima sessão começa por aqui.
> Extensão alvo: **v0.1.0** (release GitHub: `tassiolucas/Android-VSCodeCursor-Plugin`).

## 0. Preparação

- [ ] VS Code/Cursor 1.85+ com a extensão **0.1.0** instalada (VSIX da release v0.1.0)
- [ ] **JDK 21** no PATH/`JAVA_HOME` da máquina (obrigatório)
- [ ] Abrir um projeto Android real (ex.: Fruitties)
- [ ] Aguardar toast **`ASE: Indexed N symbols (project) + M (libraries)`**
  - ⚠️ Se não aparecer: indexação falhou (hoje erro aparece em tela) — olhar `View > Output` → canal **ASE LSP**
- [ ] Flame **`ASE LSP`** verde na barra inferior

## 1. Navegação

| # | Atalho | Teste | Resultado esperado | OK? |
|---|--------|-------|--------------------|-----|
| 1 | `Ctrl+B` / `Ctrl+Click` | Símbolo de outro módulo | Abre o arquivo do símbolo transferido cross-module | |
| 2 | `Ctrl+Click` | Classe de lib externa (ex.: `ViewModel`) | Abre documento decompilado `ase-lib:` (read-only, Java) | |
| 3 | `Ctrl+Click` | `R.string.x` / `R.layout.y` / `R.id.z` | Abre o XML do recurso na linha da declaração | |
| 4 | `Ctrl+Alt+B` | Sobre interface (`FruittieDao`) | Peeks implementações | |
| 5 | `Ctrl+Shift+B` | Propriedade com tipo explícito (`database: AppDatabase`) | Vai para a declaração do tipo | |

## 2. Leitura / compreensão

| # | Atalho | Teste | Resultado esperado | OK? |
|---|--------|-------|--------------------|-----|
| 6 | `Ctrl+P` (cursor **dentro** dos parênteses de uma chamada) | `loadMapped(ids)` em DataRepository.kt | Assinatura com parâmetro ativo (ex.: `ids: List<Long>`) | |
| 7 | `Ctrl+P` | Chamada com overloads / classe com construtor | Lista de assinaturas, parâmetro ativo em destaque | |
| 8 | `Ctrl+Q` (com editor Kotlin focado) | Sobre símbolo | Quick Doc: assinatura + KDoc + `Declared in` | |
| 9 | `Ctrl+F12` | Arquivo com classes aninhadas | Outline hierárquico (membros dentro das classes) | |
| 10 | `Ctrl+T` → `DRep` | CamelCase | Acha `DataRepository` | |
| 11 | `Ctrl+T` → nome completo | Busca normal | Acha símbolos do projeto **e** libs | |
| 12 | — | Posicionar cursor em símbolo | Ocorrências destacadas no arquivo (document highlight) | |
| 13 | — | Chamadas com argumentos | Inlay hints `param:` antes dos argumentos | |
| 14 | — | Sobre declarações | CodeLens "N usages" (clique abre peek) | |

## 3. Hierarquias

| # | Atalho | Teste | Resultado esperado | OK? |
|---|--------|-------|--------------------|-----|
| 15 | `Ctrl+H` | Sobre interface/classe | Type Hierarchy (supertipos/subtipos) | |
| 16 | `Ctrl+Alt+H` | Sobre função (`addToCart`) | Call Hierarchy (incoming/outgoing) | |

## 4. Edição

| # | Atalho | Teste | Resultado esperado | OK? |
|---|--------|-------|--------------------|-----|
| 17 | `Alt+F7` | Sobre classe (`DataRepository`) **e** sobre função | Peek com usos (≥ declaração + usos em outros arquivos) | |
| 18 | `Shift+F6` | Símbolo declarado em outro arquivo (cross-file) | Rename com preview de todas as ocorrências | |
| 19 | `Ctrl+W` | Seleção em expressão aninhada | Extend selection expandindo | |

## 5. Ecossistema / robustez

- [ ] Arquivo `.java` navegável (símbolos no `Ctrl+T`, `Ctrl+B` resolve classes Java)
- [ ] Editar + salvar `.kt` ⇒ navegação reflete o arquivo novo (index incremental)
- [ ] Criar arquivo novo fora do VS Code ⇒ visível após watcher (index incremental)
- [ ] `Ctrl+B` em símbolo ambíguo (expect/actual, overloads) ⇒ peek com **múltiplos candidatos**

## 6. Conflitos conhecidos do ambiente (máquina atual)

| Atalho | Dono do conflito | Estado |
|--------|------------------|--------|
| `Ctrl+Alt+H` | Omarchy (completion) | ⚠️ capturado no nível do SO — **pendente**: repassar ao editor em `~/.config/hypr/` |
| `Ctrl+W` | Omarchy (fecha janela) | ⚠️ idem — **pendente** |
| `Ctrl+Alt+B` | VS Code (sidebar auxiliar/Copilot Chat) | ✅ resolvido via `~/.config/Code/User/keybindings.json` |
| `Ctrl+Q` | VS Code (quit no Linux) | ✅ resolvido com `when` restrito a editor Kotlin/Java — funciona **só com o editor focado** |

## 7. Troubleshooting

1. Sem toast de indexação → `Output > ASE LSP` e procurar `Indexing failed` / `Failed to open RocksDB`
2. Lock de índice (duas janelas no mesmo projeto) → toast de warning + índice temporário (funciona, não persiste)
3. "command not found" → extensão não ativou; `Reload Window` e conferir `~/.config/Code/logs/*/exthost/exthost.log`
4. Forçar re-index → apagar `<projeto>/.ase-index/`

## Próximos passos da próxima sessão

1. Rodar este roteiro (round 2 do teste visual, agora com v0.1.0)
2. Decidir o repasse dos atalhos do Omarchy (`Ctrl+Alt+H`, `Ctrl+W`) no Hyprland
3. Fase 4 (preview renderer) ou motor semântico — conforme decisão de prioridade
