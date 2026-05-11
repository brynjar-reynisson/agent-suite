# Tool Selection Per Agent — Design Spec

**Date:** 2026-05-11  
**Branch:** feature/tool-selection-per-agent

## Problem

Currently, `AiController#chat` decides which AI tools to activate based solely on whether `rootDirectory` is set — if it is, `UnixTools` is always passed; otherwise no tools are used. This is too coarse: different agent prompts need different tool sets, and future tool groups (not requiring a root directory) cannot be selectively enabled.

## Goal

Allow each `PROMPT_BANK` entry in the frontend to declare which tool groups it needs. Those groups are sent to the backend as a `tools` request parameter, and the backend instantiates the appropriate tool objects.

## Scope

**In scope:**
- Add `tools: string[]` to each `PROMPT_BANK` entry in `App.tsx`
- Resolve and forward the selected prompt's tool list in `chatStream()` calls
- Add `tools` param to `api.ts` `chatStream()` params object
- Add `@RequestParam(defaultValue = "") String tools` to `AiController#chat`
- Replace the current rootDirectory-only tool construction with a switch on tool group names

**Out of scope:**
- UI display of active tool groups
- Manual override of tool groups for free-text prompts (tools are always empty for free-text)
- A `ToolGroupFactory` or registry abstraction (deferred until 4+ groups exist)

## Design

### Frontend — `App.tsx`

Each `PROMPT_BANK` entry gains a `tools: string[]` field:

```ts
const PROMPT_BANK = [
  { name: 'Code-request classifier',  text: '...', tools: ['unix'] },
  { name: 'Implementation-planner',   text: '...', tools: ['unix'] },
  { name: 'Specification-writer',     text: '...', tools: ['unix'] },
];
```

`PromptComboboxProps` is unchanged (works with `name`/`text`; `tools` is invisible to it).

In `handleSend`, resolve both prompt text and tool groups from the matched entry:

```ts
const matched = PROMPT_BANK.find(p => p.name === prompt);
const resolvedPrompt = matched?.text ?? prompt;
const resolvedTools  = (matched?.tools ?? []).join(',');
```

`resolvedTools` is passed as a `tools` field in the `chatStream()` params object. For free-text prompts (no match), `resolvedTools` is `""`.

### Frontend — `api.ts`

Add optional `tools?: string` to the `chatStream()` params object. Append it as a query/body param when non-empty.

### Backend — `AiController#chat`

New parameter:
```java
@RequestParam(defaultValue = "") String tools
```

Replace the one-liner tool array construction with:
```java
List<String> toolGroups = tools.isBlank()
    ? List.of()
    : Arrays.asList(tools.split(","));

List<Object> toolInstances = new ArrayList<>();
for (String group : toolGroups) {
    switch (group.trim()) {
        case "unix" -> { if (!rootDirectory.isEmpty()) toolInstances.add(new UnixTools(rootDirectory)); }
        // add future groups here
    }
}
Object[] toolArray = toolInstances.toArray();
```

- Unknown group names are silently ignored (no crash, safe default).
- `UnixTools` is only added when both `unix` is in the list AND `rootDirectory` is non-empty.
- The `tools` param is also logged alongside the existing log statement.

## Tool Group Identifiers

| Identifier | Class       | Requires rootDirectory |
|------------|-------------|------------------------|
| `unix`     | `UnixTools` | yes                    |

## Extending with New Groups

Add a new `case` to the switch in `AiController#chat` and add the identifier to any relevant `PROMPT_BANK` entries. No other changes needed until a factory abstraction becomes warranted.
