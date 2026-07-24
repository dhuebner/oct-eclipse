# Plan: Eclipse Plugin for Open Collaboration Tools

Build the `org.eclipse.oct` bundle in Java, reusing the bundled `oct-service-process` Node executable over stdio JSON-RPC (`org.eclipse.lsp4j.jsonrpc`). Keep the exact RPC protocol from [messages.ts](/Users/dhuebner/git/open-collaboration-tools/packages/open-collaboration-service-process/src/messages.ts). Map IntelliJ platform concepts onto Eclipse: OSGi services, EFS (`oct://`), JFace text + annotation painters, commands/views/preferences, and Equinox secure storage.

## Platform mapping reference
| IntelliJ concept | Eclipse equivalent |
|---|---|
| `@Service` app/project services | OSGi services / `Activator` singletons keyed by workspace |
| VFS `oct://` (`OCTSessionFileSystem`) | EFS `FileSystem`/`FileStore` provider, scheme `oct` |
| `WorkspaceFileSystemService` (host) | `IWorkspaceRoot` / `IFile` / `IContainer` |
| `Editor`, `Document`, listeners | `ITextEditor`, `IDocument`, `IDocumentListener`, `ISelectionListener` |
| Cursor renderers / range highlighters | `IPainter` / `AnnotationPainter` + `IAnnotationModel` |
| `AnAction` + `plugin.xml` group | `AbstractHandler` + `org.eclipse.ui.commands`/`menus` |
| ToolWindow / status widget | `ViewPart` / `WorkbenchWindowControlContribution` |
| `PersistentStateComponent` | `IEclipsePreferences` + `PreferencePage` |
| `PasswordSafe` | `ISecurePreferences` (Equinox security) |
| Notifications | `MessageDialog` / `StatusManager` / status bar |

## Steps

1. **Bundle setup & native executable** — In `META-INF/MANIFEST.MF` add `Require-Bundle`: `org.eclipse.lsp4j.jsonrpc`, `org.eclipse.core.resources`, `org.eclipse.core.filesystem`, `org.eclipse.ui`, `org.eclipse.ui.workbench.texteditor`, `org.eclipse.jface.text`, `org.eclipse.equinox.security`, `com.google.gson`. Bundle msgpack + jackson jars under `lib/` on the `Bundle-ClassPath`. Ship the platform executables in `bin/` and port `extractExecutable`/`startProcess` from `OCTServiceProcess.kt` into a `ServiceProcess` class (temp-dir copy, `setExecutable`, `--server-address`/`--auth-token` args, dispose on shutdown).

2. **Protocol types + transport** — Create Java records/POJOs mirroring `types.kt` (`Workspace`, `SessionData`, `Peer`, `InitData`, `FileSystemStat`, `FileContent`, `ClientTextSelection`, `TextDocumentInsert`, `FileChangeEvent`, `FileType`/`FileChangeEventType` enums). Port the `Launcher` wiring, `BinaryDataAdapter` (msgpack+base64 for `FileContent`), and `MessageTypeAdapter` (list→array fix). Declare `OCTService` and `@JsonSegment("fileSystem")` `FileSystemService` interfaces from the two `messageHandlers` with identical `@JsonRequest`/`@JsonNotification` method names.

3. **Session core & handlers** — Port `SessionService` (`OCTSessionService.kt`) and `CollaborationInstance` (`CollaborationInstance.kt`) keyed by `IProject`; implement create/join/close room, peer add/remove, and a Java `EventEmitter` (`EventEmitter.kt`) plus `PeerColors` (`PeerColorService.kt`) using SWT `Color`. Port the local `OCTMessageHandler`/`FileSystemMessageHandler` bodies. Wire lifecycle registration and cleanup in `Activator`. Run create/join in an `IRunnableWithProgress`/`Job` (replacing `Task.Backgroundable`).

4. **File systems** — *Host:* port `WorkspaceFileSystemService.kt` using `IWorkspaceRoot`/`IFile`/`IFolder` with `stat/readFile/readDir/mkdir/writeFile/delete/rename` inside `WorkspaceModifyOperation`; broadcast local edits via an `IResourceChangeListener` (replacing `OCTFileListener`). *Guest:* implement an EFS provider registered on `org.eclipse.core.filesystem.filesystems` for scheme `oct`, backed by `FileSystemService` RPC — a `FileSystem` + lazy `FileStore` (`childNames`/`fetchInfo`/`openInputStream`/`openOutputStream`/`mkdir`/`delete`) replacing `OCTSessionFileSystem.kt` + `OCTSessionVirtualFile.kt`. On join, create a temporary project and link its content to `oct://<root>` (replacing IntelliJ's non-persistent module); refresh on `change` notifications.

5. **Editor collaboration** — Track open editors via `IPartListener2` on the active `IWorkbenchPage` and resolve `ITextEditor`→`IDocument`. Port `EditorListeners.kt`: `IDocumentListener` emitting `TextDocumentInsert` (offset/oldLength/newText) with a `sendUpdates` guard, plus caret/selection via `ITextViewer.getSelectionProvider()` emitting `ClientTextSelection`. Apply remote `updateDocument` through `DocumentRewriteSession`/`replace` with the guard set, mirroring `EditorManager.updateDocument`. Render peer cursors/selections with a per-editor `IAnnotationModel` + a custom `AnnotationPainter.IDrawingStrategy` (caret bar + translucent selection), replacing `CursorRenderer`/range highlighters. Implement follow-mode by opening the followed peer's file (`IDE.openEditor`) and `revealRange`.

6. **UI, preferences & auth** — Declare commands/handlers + menu/toolbar contributions (Join/Host/Close/Logout) via `org.eclipse.ui.commands`/`menus`/`handlers` in `plugin.xml` (replacing the `plugin.xml` action group and `Actions.kt`). Add a session `ViewPart` (peer list + follow toggle, ) and a `WorkbenchWindowControlContribution` status item (). Add a `FieldEditorPreferencePage` for the server URL backed by `IEclipsePreferences` (`OCTSettings.kt`). Port `AuthenticationService` (`AuthenticationService.kt`) using SWT `Browser` for web/login flows, JFace dialogs for form auth, and `ISecurePreferences` for token storage.

## Build-system decision (finalized)
1. **Use Tycho with a PDE bundle + a `.target` target platform.** Rationale: reproducible headless CI builds, easy Eclipse-release resolution of `lsp4j.jsonrpc`/`equinox.security`, and standard update-site/feature packaging. Add a `feature` project + `p2` update site later for distribution.
2. **Third-party jars** (msgpack, jackson) via `lib/` on `Bundle-ClassPath` (or an Orbit/target entry) — recommend a Gradle/Ant task mirroring `copyExecutableToResources` to stage `bin/` executables per platform.

## Open questions before implementation
1. Multi-platform executables: bundle all OS/arch binaries in `bin/` (larger plugin) or fetch on first use? Recommend bundling mac/win/linux x64+arm64.
2. Guest workspace representation: temp `IProject` linked to `oct://` EFS (recommended) vs. a dedicated Eclipse working set — confirm temp-project approach and cleanup-on-close.
3. Confirm target Eclipse release (2024-x/2025-x) for the `.target` file and `JavaSE-21` (already in the manifest).

## Source reference map (IntelliJ → to port)
- `OCTServiceProcess.kt` — process launch, executable extraction, JSON-RPC `Launcher`, `BinaryDataAdapter`, `MessageTypeAdapter`
- `types.kt` — protocol data classes
- `messageHandlers/OCTMessageHandler.kt` — `OCTService` interface + local notifications (auth, init, peer events, session lifecycle)
- `messageHandlers/FileSystemMessageHandler.kt` — `FileSystemService` interface + host FS request handlers
- `OCTSessionService.kt` — session lifecycle, create/join/close room, process map
- `CollaborationInstance.kt` — per-session state, peer list, shared-folder init
- `fileSystem/WorkspaceFileSystemService.kt` — host workspace FS + local change listener
- `fileSystem/OCTSessionFileSystem.kt` + `OCTSessionVirtualFile.kt` — guest virtual FS (→ EFS)
- `editor/EditorManager.kt` — editor tracking, remote apply, cursor decorations, follow-mode
- `editor/EditorListeners.kt` — document/caret/selection listeners
- `editor/CursorRenderer.kt` — cursor painting (→ AnnotationPainter drawing strategy)
- `PeerColorService.kt` — peer color assignment
- `AuthenticationService.kt` — auth flows + token storage
- `settings/OCTSettings.kt` — server URL + stored tokens
- `sessionView/SessionViewFactory.kt` — session/peer view
- `sessionView/StatusBarSessionWidget.kt` — status bar widget
- `actions/Actions.kt` — host/join/close/logout/follow actions
- `util/EventEmitter.kt` — lightweight event bus
- `plugin.xml` — extension registrations (actions, VFS, view, status widget, listeners)

## Protocol reference (must match exactly)
Source: `open-collaboration-service-process/src/messages.ts`

To service process:
- `login` → token
- `room/joinRoom` (roomId) → `SessionData`
- `room/createRoom` (workspace) → `SessionData`
- `room/closeSession`
- `awareness/openDocument` (type, documentUri, text)
- `awareness/getDocumentContent` (path) → `BinaryData<FileContent>`
- `awareness/updateTextSelection` (path, selections)
- `awareness/updateDocument` (path, updates)
- `fileSystem/*` — `stat`, `readFile`, `readDir`, `mkdir`, `writeFile`, `delete`, `rename`, `change`

From service process:
- `authentication` (token, metadata)
- `init` (InitData)
- `peerInfo` (Peer)
- `editorOpened` (documentPath, peerId)
- `room/joinSessionRequest` (user) → boolean
- `peerJoined` / `peerLeft` (Peer)
- `sessionClosed`
- `error` (message, stack?)

Binary params: always wrapped as `BinaryData { type: 'binaryData', data: base64(msgpack) }`.

---

# Deep-dive designs

## A. Guest virtual file system (EFS) — method-by-method contract

Replaces IntelliJ `OCTSessionFileSystem.kt` + `OCTSessionVirtualFile.kt`. The guest never touches real files: every operation is a synchronous JSON-RPC round trip to the host's `FileSystemService` (target = host peer id). Eclipse EFS is inherently blocking, which matches the IntelliJ `.get()` usage.

### Registration
`plugin.xml`:
```xml
<extension point="org.eclipse.core.filesystem.filesystems">
  <filesystem scheme="oct">
    <run class="org.eclipse.oct.internal.fs.OctFileSystem"/>
  </filesystem>
</extension>
```
URI shape: `oct://<sessionId>/<sharedRoot>/<relative/path>` where the authority carries the session id so a `FileStore` can resolve back to the owning `CollaborationInstance` (multiple concurrent sessions supported). Path segment 0 after authority = shared root name, matching `roots[path.getName(0)]` logic.

### `OctFileSystem extends FileSystem`
| Method | Behavior |
|---|---|
| `getStore(URI uri)` | Return a cached `OctFileStore` for the URI; cache keyed by normalized URI. Never returns null. |
| `attributes()` | `EFS.ATTRIBUTE_READ_ONLY` not set (writable); no exec bit tracking needed. |
| `canDelete()` / `canWrite()` | `true`. |
| `fetchFileTree(...)` | Optional; default is fine (falls back to recursive `childStores`). |

### `OctFileStore extends FileStore`
Constructor holds: owning `SessionRegistry` (to resolve `FileSystemService` + host id), the `URI`, and an optional cached `IFileInfo`. Map protocol `FileType` → EFS: `Directory`→directory, `File`/`SymbolicLink`→file.

| EFS method | Maps to protocol | Notes / contract |
|---|---|---|
| `getName()` | — | Last path segment; root store returns shared-root name. |
| `getParent()` | — | Return parent `OctFileStore`, or `null` at the shared root. |
| `toURI()` | — | The stored `oct://` URI. |
| `fetchInfo(int options, IProgressMonitor)` | `fileSystem/stat(path, target)` | Build `FileInfoImpl`: `setExists(stat != null)`, `setDirectory`, `setLength(size)`, `setLastModified(mtime)`, `setName`. On null stat → `exists=false` (never throw). Cache result; invalidate on writes/`change`. |
| `childNames(int options, IProgressMonitor)` | `fileSystem/readDir(path, target)` | Return `String[]` of entry names. Empty array (not null) for non-dirs or missing dirs, mirroring `readDir` fallback `mapOf()`. |
| `childStores(...)` | — | Default impl calls `childNames` + `getChild`; can override to also seed child `IFileInfo` from a single `readDir` to cut round trips. |
| `getChild(String name)` | — | Return child `OctFileStore` with appended path segment. |
| `openInputStream(int options, IProgressMonitor)` | `awareness/getDocumentContent(path)` first, else `fileSystem/readFile(path, target)` | Return `ByteArrayInputStream(content)`. IntelliJ reads open-doc content via `getDocumentContent` (see `OCTSessionFileSystem.readFile`) so live editor state is served; fall back to `readFile` for non-open files. Throw `CoreException` on RPC failure. |
| `openOutputStream(int options, IProgressMonitor)` | buffer → `fileSystem/writeFile(path, content, target)` | Return a `ByteArrayOutputStream` subclass whose `close()` sends the full buffer as `FileContent`, then invalidates cached info. `EFS.APPEND` → prefetch existing bytes first. |
| `mkdir(int options, IProgressMonitor)` | `fileSystem/mkdir(path, target)` | `SHALLOW` vs deep: create parents when `SHALLOW` not set. Return the store. |
| `delete(int options, IProgressMonitor)` | `fileSystem/delete(path, target)` | Recursive delete is host-side; just invalidate caches. |
| `putInfo(IFileInfo, int options, IProgressMonitor)` | — | No-op or rename via `move`; attribute changes unsupported → ignore silently. |
| `move(IFileStore dest, ...)` | `fileSystem/rename(old, new, target)` | Only supported when dest is another `OctFileStore` in the same session; else throw `CoreException`. |
| `toLocalFile(int options, IProgressMonitor)` | — | Return `null` (not a local FS) unless `EFS.CACHE` requested → let super cache to a temp file via `openInputStream`. |

### Cache invalidation & refresh
- Each `OctFileStore` caches `IFileInfo` + optionally child names; invalidate on local write/delete/move and when the host sends `fileSystem/change`.
- On `change(FileChangeEvent)` (see `FileSystemMessageHandler.change`), map each `FileChange.path` to its `OctFileStore`, drop caches, then call `IResource.refreshLocal(DEPTH_ONE)` on the linked resource so the Eclipse resource tree updates (replaces IntelliJ `refreshAndFindFileByPath` + `ProjectView.refresh`).

### Wiring a guest project to the EFS
On join (port of `CollaborationInstance.initializeSharedFolders`):
1. Create a temporary `IProject` named after `workspace.name` with a custom location OR keep default and add linked resources.
2. For each `workspace.folders` root, create a linked folder: `folder.createLink(new URI("oct://<sessionId>/<root>"), IResource.REPLACE, monitor)`.
3. Register the project in the session registry; on `sessionClosed`/close, delete the project (keep contents = false) and clear EFS caches — mirrors `tempProjectsToDelete` cleanup.

### Threading
EFS calls arrive on arbitrary threads and block — do NOT wrap in `Display.asyncExec`. Only marshal to the UI thread for editor/annotation updates. Guard RPC `.get()` with a timeout + `CoreException` translation to avoid deadlocking the resource lock.

## B. Peer cursor / selection rendering (annotation painter)

Replaces IntelliJ `EditorManager.createPeerCursor` + `CursorRenderer.kt` (range highlighters + custom renderer). Eclipse approach: per-editor `IAnnotationModel` carrying peer annotations + a custom `AnnotationPainter.IDrawingStrategy` for the caret bar, and a text-style/highlight strategy for the selection band.

### Annotation types (`plugin.xml`)
```xml
<extension point="org.eclipse.ui.editors.annotationTypes">
  <type name="org.eclipse.oct.peerCursor"/>
  <type name="org.eclipse.oct.peerSelection"/>
</extension>
```
Do NOT declare static colors in `annotationTypes`/`markerAnnotationSpecification` because each peer needs its own color. Instead attach the painter/strategies programmatically per editor and resolve color from the annotation instance.

### Peer annotation model
- `PeerAnnotation extends Annotation` carrying `peerId` and resolved `RGB` (from `PeerColors`, ported from `PeerColorService.kt`, using SWT `Color`/`RGB` instead of `JBColor`).
- Maintain one `AnnotationModel` per open document (attach to the editor's `IAnnotationModelExtension` via `addAnnotationModel(KEY, model)` so it composes with existing markers).
- On `awareness/updateTextSelection(path, selections[])` (port of `EditorManager.updateTextSelection`):
  1. Marshal to UI thread (`Display.asyncExec`).
  2. Clear previous peer annotations for that path (`removeAllAnnotations` on the peer model, or diff by peerId).
  3. For each `ClientTextSelection`: clamp `start`/`end` to `document.getLength()`; add a `peerSelection` annotation over `Position(min, len)` when `start != end`, and a zero-length `peerCursor` annotation `Position(caretOffset, 0)`.
  4. `model.replaceAnnotations(removed, addedMap)` in one batch to avoid flicker.

### Drawing strategies (attached to the source viewer's `AnnotationPainter`)
Obtain the painter via the editor's `SourceViewerConfiguration` (or install a `PaintManager`/`AnnotationPainter` on the `ISourceViewer` if the editor doesn't expose one). Register:

- **Cursor bar** — `IDrawingStrategy` (port of `PeerCaretHighlighterRenderer.paint`): given `offset`, compute glyph rect via `StyledText.getLocationAtOffset(offset)` and draw a 2px vertical line `gc.fillRectangle(x, y, 2, lineHeight)` in the peer color. Registered with `painter.addDrawingStrategy(ID, cursorStrategy)` and `painter.addAnnotationType("org.eclipse.oct.peerCursor", ID)`.
- **Selection band** — either a second `IDrawingStrategy` filling each line box with a translucent color (alpha ~50, matching IntelliJ `Color(r,g,b,50)`), or simpler: `painter.addTextStyleStrategy(...)` / `setAnnotationTypeColor`. Prefer a drawing strategy with `gc.setAlpha(50)` for parity.
- Color source: since strategies are stateless per type, read the color from the `PeerAnnotation` passed into `draw(Annotation annotation, GC gc, StyledText textWidget, int offset, int length, Color color)` — cast to `PeerAnnotation` and use its `RGB` (cache `Color` objects per RGB, dispose on editor close).

### Lifecycle
- On editor open (`IPartListener2.partOpened` → `ITextEditor`): resolve `IDocument`, create/attach peer `AnnotationModel`, install drawing strategies, register document/caret/selection listeners (Step 5). Port of `EditorManager.registerEditor`.
- On editor close (`partClosed`): remove annotation model, dispose cached SWT `Color`s, remove listeners. Port of `EditorManager.editorReleased`.
- Follow-mode: on remote selection whose `peer == followingPeerId`, open the file via `IDE.openEditor` and `ITextEditor.selectAndReveal(offset, 0)` / `sourceViewer.revealRange` (port of the `followingPeerId` branch in `updateTextSelection`).

### Why annotations over `StyledText.setStyleRanges`
Annotations compose with syntax highlighting and other markers without clobbering the editor's own style ranges, survive reconciliation, and are the idiomatic Eclipse mechanism — whereas raw `setStyleRanges` would fight the presentation reconciler.

## C. Related: remote document apply & echo suppression
Port of `EditorManager.updateDocument` + `EditorDocumentListener`:
- Keep a per-path `sendUpdates` boolean guard. When applying remote `awareness/updateDocument` edits, set `sendUpdates=false`, apply each `TextDocumentInsert` via `document.replace(startOffset, (endOffset ?? startOffset) - startOffset, text.replace("\r\n","\n"))` inside a `DocumentRewriteSession` (`startRewriteSession(UNRESTRICTED)`), then restore the guard in a `finally`.
- Local `IDocumentListener.documentChanged` emits `TextDocumentInsert(offset, offset+lengthReplaced, newText)` only when `sendUpdates` is true (mirror `EditorDocumentListener.documentChanged`), and runs the `getDocumentContent` reconciliation resync (`syncDocument`) guarded by an `isSyncing` flag.
- Marshal all document mutations to the UI thread (`Display.syncExec`) since JFace document changes must run there.

## D. JSON-RPC transport & binary encoding

. The service process is a Node executable launched per session; communication is LSP4J JSON-RPC over the process `stdin`/`stdout`.

### `ServiceProcess`
```
class ServiceProcess implements AutoCloseable {
  ServiceProcess(String serverUrl, List<BaseMessageHandler> handlers)
  <T extends BaseRemoteInterface> T getOctService()   // jsonRpc.getRemoteProxy()
  void close()                                          // destroy process + null out launcher
}
```
- **Executable extraction** (`extractExecutable`): resolve the bundled binary from the bundle (`FileLocator.toFileURL(bundle.getEntry("bin/oct-service-process[.exe]"))`), copy to a temp dir (`Files.createTempDirectory`), `file.setExecutable(true)`, remember path; delete temp dir on process exit. Determine OS/arch via `Platform.getOS()`/`Platform.getOSArch()` (replaces `SystemInfo.isWindows`).
- **Launch** (`startProcess`): read saved auth token from `AuthenticationService.getAuthToken(serverUrl)`; `new ProcessBuilder(exe, "--server-address="+serverUrl, "--auth-token="+token).start()`. Register `process.onExit()` cleanup.
- **Launcher wiring**:
  ```
  Launcher.Builder<BaseRemoteInterface>()
     .setLocalServices(handlers)
     .setClassLoader(OCTService.class.getClassLoader())
     .setRemoteInterfaces(handlers.stream().map(h -> h.remoteInterface).toList())
     .setInput(process.getInputStream())
     .setOutput(process.getOutputStream())
     .configureGson(gson -> gson.registerTypeAdapter(FileContent.class, new BinaryDataAdapter(FileContent.class)))
     .create();
  launcher.startListening();
  ```

### `BinaryDataAdapter<T>` (Gson `TypeAdapter`)
Wraps binary payloads as `{ "type":"binaryData", "data": base64(msgpack(value)) }`:
- `write`: `objectMapper.writeValueAsBytes(value)` (Jackson + `MessagePackFactory`) → base64 → emit `BinaryData`.
- `read`: parse `BinaryData`, base64-decode, `objectMapper.readValue(bytes, type)`.
- ObjectMapper = `new ObjectMapper(new MessagePackFactory())` (Jackson; no Kotlin module needed in Java). Only `FileContent` is registered (see `binaryDataTypes`).

### `MessageTypeAdapter` (list→array fix)
LSP4J/Gson serializes `List` params as nested arrays; port the adapter converting `List` params to arrays on `NotificationMessage`/`RequestMessage` before serialization so params match `OCPMessage { method, params[], target? }` shape expected by the service process.

### `BaseMessageHandler`
Port of the abstract base (`OCTMessageHandler.kt`): holds nullable `collaborationInstance`, an `executeOnSetInstance` deferred-callback list wired to `onSessionCreated`, `remoteInterface` field, and `Endpoint.request/notify` throwing "unhandled" for unknown methods.

## E. Host workspace file system service

, but over `org.eclipse.core.resources`. One instance per hosted `IProject`; the shared root is the project (or its content folders from `workspace.folders`). Paths are workspace-relative, prefixed with the root/project name (IntelliJ strips the leading workspace-dir name in `toRelativeWorkspacePath`).

| Method | Eclipse implementation |
|---|---|
| `stat(path)` | Resolve `IResource` via `project.findMember(rel)`; build `FileSystemStat(type, localTimeStamp, localTimeStamp, size, null)`; return null if missing (mirror `FileNotFoundException`→null). Type from `IResource.getType()`. |
| `readFile(path)` | `IFile.getContents()` → bytes → `FileContent`. |
| `readDir(path)` | `IContainer.members()` → map name→`FileType`. Empty map if not a container. |
| `mkdir(path)` | Create `IFolder` (and missing parents) inside `WorkspaceModifyOperation`. |
| `writeFile(path, content)` | `IFile.create/ setContents` with `content.content`; create parents as needed. |
| `delete(path)` | `IResource.delete(true, monitor)`. |
| `rename(old, newName)` | `IResource.move(newFullPath, true, monitor)`. |

- All mutating ops run inside a `WorkspaceModifyOperation` / `IWorkspaceRunnable` and return `CompletableFuture` (port of `runAsyncInWriteContext`), completing on the workspace job thread.
- File-type mapping: `IResource.FILE`→`File`, `FOLDER`/`PROJECT`→`Directory`, symlink detection via `IResource.isLinked()` if needed.

### Local change broadcasting (`OCTFileListener` → `IResourceChangeListener`)
- Register an `IResourceChangeListener` (`POST_CHANGE`). Walk the `IResourceDelta`; for each add/remove/move/content-change under a hosted project, build `FileChange(type, path)` and call `FileSystemService.change(FileChangeEvent(changes), "broadcast")`.
- Map deltas: `ADDED`→`Create`, `REMOVED`→`Delete`, `CHANGED` (content) → skip pure content (edits go via awareness), moved-from/to → `Delete`+`Create` (mirror `VFileMoveEvent` handling).

## F. Session core & lifecycle

### `SessionService` ()
State keyed by `IProject`:
- `Map<IProject, ServiceProcess> processes`
- `Map<IProject, CollaborationInstance> instances`
- `EventEmitter<CollaborationInstance> onSessionCreated`, `EventEmitter<IProject> onSessionClosed`
- `List<IProject> tempProjectsToDelete`

Operations:
- `createRoom(Workspace, IProject)`: create `ServiceProcess`, run `createRoom` in a `Job`/`IRunnableWithProgress`; on success show info (with "Copy Room ID"/"Copy URL" actions), then `sessionCreated(..., isHost=true)`.
- `joinRoom(roomToken, IProject?)`: create process, run `joinRoom`; on success create a temp `IProject`, link EFS folders, `sessionCreated(..., isHost=false)`.
- `closeCurrentSession(IProject)`: call `closeSession()` with a 5s timeout, dispose process + instance, remove from maps, fire `onSessionClosed`, refresh status contribution. Queue temp project for deletion if guest.
- `sessionCreated(...)`: if `authToken` present → `AuthenticationService.onAuthenticated`; construct `CollaborationInstance`, register in map, fire `onSessionCreated`, refresh status/view.
- `createServiceProcess(serverUrl)`: instantiate both handlers (`FileSystemMessageHandler`, `OCTMessageHandler`) wired to `onSessionCreated`.

Lifecycle listeners (replace IntelliJ `ProjectListener`): register a workbench/`IResourceChangeListener` `PRE_CLOSE`/`PRE_DELETE` for projects to close sessions and clean temp projects. Read server URL from preferences (`OCTSettings`).

### `CollaborationInstance` ()
Holds `remoteInterface` (proxy), owning `IProject`, `SessionData`, `isHost`, host/guests list, `identity`, `PeerColors`, `onPeersChanged`. Owns an `EditorManager`. Methods: `initPeers`, `peerJoined`, `peerLeft`, `updateTextSelection`, `updateDocument`, `editorOpened`, `handleFileSystemChange`, `initializeSharedFolders` (guest → link EFS folders), and file resolution (`resolveSessionFile`: host → workspace `IFile`; guest → `oct://` EFS resource).

### Message handlers (ported bodies)
- `OCTMessageHandler`: `authentication`→`AuthenticationService.authenticate`; `joinSessionRequest`→modal/notification Accept/Decline returning a `CompletableFuture<Boolean>`; `init/peerInfo/peerJoined/peerLeft`→delegate to instance (with deferred `executeOnSetInstance` when instance not yet set); `updateTextSelection/updateDocument/editorOpened`→delegate; `sessionClosed`→for guests, close the temp project; `error`→log to `ILog`.
- `FileSystemMessageHandler`: delegate `stat/readFile/readDir/mkdir/writeFile/delete/rename` to the host `WorkspaceFileSystemService`; `change`→`instance.handleFileSystemChange`.

## G. Authentication & secure storage

:
- `authenticate(serverUrl, token, metadata)`: show a provider chooser (JFace `PopupDialog`/`ElementListSelectionDialog`) over `metadata.providers`. Dispatch by `provider.type`:
  - `form` → build a JFace dialog with a field per `FormAuthProviderField`; on OK POST JSON `{token, ...fields}` to `serverUrl + provider.endpoint` (Apache HttpClient or `java.net.http.HttpClient`).
  - `web` → open external browser to `serverUrl + provider.endpoint?token=...` (`org.eclipse.ui.browser` / `Program.launch`).
  - fallback → open `metadata.loginPageUrl` in an SWT `Browser` dialog (replaces JCEF `AuthDialog`), or external browser.
- `onAuthenticated(authToken, serverUrl)`: close the auth dialog if open; store token in `ISecurePreferences` node keyed by `OCT-Auth-Token`/serverUrl (`put(url, token, true)`); record server URL in `OCTSettings.storedUserTokens`.
- `getAuthToken(serverUrl)`: read from `ISecurePreferences`.
- `LogoutAction`: clear all stored tokens (`ISecurePreferences.removeNode`/clear entries) and reset `storedUserTokens`.

## H. UI: commands, views, status bar, preferences

### Commands / handlers (`plugin.xml`)
Replace the IntelliJ action group + `Actions.kt`. Define `org.eclipse.ui.commands` for Join / Host / Close / Logout, `handlers` binding them to `AbstractHandler`s, and `menus` contributions (main menu + toolbar) with an OCT command group, using `share-icon.svg`. Enablement expressions mirror `update()` (Host enabled when project open & no session; Close enabled when session exists; Logout enabled when tokens stored).
- `HostSessionHandler`: build `Workspace("oct-session", rootNames)` from the selected/active `IProject` content roots → `SessionService.createRoom`.
- `JoinSessionHandler`: prompt for room id (`InputDialog`) → `SessionService.joinRoom`.
- `CloseSessionHandler`, `LogoutHandler`, plus a `ToggleFollowHandler(peerId)` for the view.

### Session view (`ViewPart`, )
- `org.eclipse.ui.views` view showing either a "No session" panel (Join/Host buttons) or the peer list.
- Peer list: current user first (`identity.name` + "(you • host)"/"(you)"), then remote host and guests, each with a `PeerColorIcon` (SWT-drawn dot) and a follow toggle button (`ToggleFollowHandler`). Rebuild on `onPeersChanged`/`onSessionCreated`/`onSessionClosed` via `Display.asyncExec`.

### Status bar ()
- `WorkbenchWindowControlContribution` showing "OCT: Sharing"/"OCT: Collaborating" when a session is active; click opens the OCT command menu. Refresh on session created/closed.

### Preferences ()
- `FieldEditorPreferencePage` (`org.eclipse.ui.preferencePages`) with a "Default server address" `StringFieldEditor`, default `https://api.open-collab.tools/`, stored in `IEclipsePreferences` (`InstanceScope`). Also persist `storedUserTokens`. Provide an `OCTSettings` accessor facade.

## I. Proposed package / file layout (`org.eclipse.oct/src`)
```
org.eclipse.oct.internal
  Activator.java                     // start/stop, service wiring
  SessionService.java
  CollaborationInstance.java
  PeerColors.java
  util/EventEmitter.java
  rpc/
    ServiceProcess.java
    BaseMessageHandler.java
    BinaryDataAdapter.java
    MessageTypeAdapter.java
    OCTService.java                  // remote interface
    FileSystemService.java           // remote interface (@JsonSegment "fileSystem")
    OCTMessageHandler.java           // local handler
    FileSystemMessageHandler.java    // local handler
  protocol/                          // POJOs from types.kt
    Workspace, SessionData, Peer, InitData, AuthMetadata, AuthProvider,
    FileSystemStat, FileContent, FileType, ClientTextSelection,
    TextDocumentInsert, FileChangeEvent, FileChange, FileChangeEventType, User ...
  fs/
    OctFileSystem.java               // EFS guest FS
    OctFileStore.java                // EFS guest store
    WorkspaceFileSystemService.java  // host FS
    WorkspaceChangeListener.java     // IResourceChangeListener
  editor/
    EditorManager.java
    DocumentSyncListener.java        // IDocumentListener
    SelectionSyncListener.java       // caret/selection
    PeerAnnotation.java
    PeerCursorDrawingStrategy.java
    PeerSelectionDrawingStrategy.java
  auth/
    AuthenticationService.java
    LoginBrowserDialog.java
    FormAuthDialog.java
  ui/
    commands/ (HostSessionHandler, JoinSessionHandler, CloseSessionHandler,
              LogoutHandler, ToggleFollowHandler)
    SessionView.java
    StatusBarContribution.java
  prefs/
    OCTSettings.java
    OCTPreferencePage.java
    OCTPreferenceInitializer.java
```
Plus resources: `bin/` (executables), `lib/` (msgpack/jackson jars), `icons/share-icon.svg`, updated `plugin.xml`, `MANIFEST.MF`.

## J. Build system (Tycho) — concrete structure
```
oct-eclipse/
  pom.xml                            // parent (packaging=pom), tycho version props
  org.eclipse.oct/                   // existing bundle (packaging=eclipse-plugin)
    pom.xml
  org.eclipse.oct.feature/           // packaging=eclipse-feature
    pom.xml, feature.xml
  org.eclipse.oct.repository/        // packaging=eclipse-repository (p2 site)
    pom.xml, category.xml
  target/org.eclipse.oct.target/     // .target file pinning Eclipse release + Orbit
```
- Parent POM: `tycho-maven-plugin`, `target-platform-configuration` referencing the `.target` (Eclipse 2025-x release repo + Orbit for msgpack/jackson/gson if available; otherwise bundle jars in `lib/`).
- Bundle POM: standard `eclipse-plugin`; add a `maven-antrun`/exec step mirroring `copyExecutableToResources` to stage `bin/` executables (build them via the service-process `npm run create:executable`, path configurable like `org.typefox.oct-project-path`).
- CI: `mvn -f oct-eclipse clean verify` produces the p2 repository.

## K. Implementation roadmap (milestones)
1. **M1 — Skeleton & transport:** manifest deps, `lib/` jars, `bin/` staging, `ServiceProcess` + `Launcher` + adapters, protocol POJOs, remote interfaces. Smoke-test `login`/`createRoom` round trip. (Steps 1–2)
2. **M2 — Host session:** `SessionService`, `CollaborationInstance`, handlers, host `WorkspaceFileSystemService` + change listener, Host/Close commands. A guest (VS Code/IntelliJ) can join a hosted Eclipse project. (Steps 3–4 host)
3. **M3 — Guest session:** EFS `OctFileSystem`/`OctFileStore`, temp project + linked folders, `change` refresh, Join command. Eclipse can join a room hosted elsewhere. (Step 4 guest)
4. **M4 — Editor collaboration:** editor tracking, document/selection listeners, remote apply + echo suppression, peer cursor/selection annotations, follow-mode. (Step 5)
5. **M5 — Auth & UI polish:** `AuthenticationService` + secure storage, provider chooser/form/web/browser flows, session `ViewPart`, status bar, preference page, logout. (Step 6)
6. **M6 — Packaging & CI:** Tycho parent/feature/repository, `.target`, executable build integration, signing, p2 update site.

### Testing strategy
- Unit: `BinaryDataAdapter` round-trip (msgpack), `MessageTypeAdapter` param shaping, path mapping helpers.
- Integration: launch a local `open-collaboration-server` + service process; run host↔guest scenarios cross-IDE (Eclipse host ↔ VS Code guest and vice versa).
- Manual/PDE: SWTBot smoke tests for commands, view, and cursor rendering.

### Known risks / watch-list
- LSP4J Gson param shaping must exactly match `OCPMessage` (nested-array pitfall) — covered by `MessageTypeAdapter`; verify with a trace.
- EFS blocking calls under the workspace lock can deadlock — enforce timeouts and never take the UI thread inside EFS.
- `AnnotationPainter` acquisition differs per editor type; provide a fallback that installs a painter on the `ISourceViewer` when the editor doesn't expose one.
- Cross-platform executable availability (arm64/x64, mac/win/linux) — bundle all or gate features when missing.
