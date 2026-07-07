# Telegram MCP servers compared: TDLib vs Telethon vs Bot API

Choosing a Telegram MCP server is mostly choosing a **risk posture**: what can the
model see, what can it change, and what happens when it makes a mistake on the
account you actually use. This page compares the three architectural families and
shows where this server sits.

If you want the dated, engineering-grade review of specific competing repositories
(including features this project deliberately declined), see
[PUBLIC_BENCHMARK_AND_ROADMAP.md](PUBLIC_BENCHMARK_AND_ROADMAP.md).

## The three architectures

### Bot API servers

Wrap `api.telegram.org` with a bot token. Simple and safe to operate, but the agent
only sees what a **bot** can see: no personal chat history, no folders, no drafts,
no read receipts, and only chats where the bot was explicitly added. Good for
notification-style automation; not usable for "summarize my inbox".

### Telethon / GramJS user-session servers

Log into a **real user account** via MTProto from Python (Telethon) or Node
(GramJS). Full account access — and usually full exposure: most of these servers
advertise every tool to the model from the first request, including sends, deletes,
and sometimes a raw MTProto escape hatch. Installation typically requires a Python
or Node toolchain, and sessions often live unencrypted next to the script.

### TDLib servers (this project)

TDLib is Telegram's official client library — the same core that powers Telegram X
and the desktop clients. It provides a real user account with first-party update
handling, local database encryption, and battle-tested session management. This
server builds on TDLib (via [tdlight-java](https://github.com/tdlight-team/tdlight-java))
and adds an MCP-specific safety layer on top.

## Feature comparison

| Capability | This server (TDLib) | Typical Telethon/GramJS MCP | Typical Bot API MCP |
|---|---|---|---|
| Real user account | ✅ | ✅ | ❌ bot subset only |
| Personal inbox, folders, drafts, read receipts | ✅ | ✅ (varies) | ❌ |
| Read-only **by default** | ✅ | ❌ rare | ❌ n/a |
| Write/quota tools **hidden** from the model until opt-in | ✅ removed from the tool list, not merely blocked | ❌ | ❌ |
| Task-scoped tool profiles (`reader`, `inbox`, `research`, …) | ✅ | ❌ | ❌ |
| Confirmation gating for destructive actions | ✅ | rare | rare |
| Multi-account with isolated sessions, no cross-account read fan-out | ✅ mandatory account selection | rare; sometimes implicit fan-out | ❌ |
| Untrusted-content marking (prompt-injection surface reduction) | ✅ | ❌ | ❌ |
| Audit log + anti-spam limits + chat allow-list | ✅ | ❌ | ❌ |
| STDIO transport | ✅ | ✅ | ✅ |
| Streamable HTTP transport | ✅ | rare | rare |
| Docker image | ✅ signed GHCR digests | varies | varies |
| Windows / Linux x64+ARM64 / Apple-silicon bundles | ✅ runtime-inclusive, no JDK/Python/Node to install | ❌ needs pip/uvx or npx | ❌ needs pip/npx |
| SBOM + build provenance | ✅ | ❌ | ❌ |
| Raw MTProto escape hatch | ❌ **deliberately declined** | sometimes ✅ | ❌ |

"Typical" describes the pattern across the most-starred public servers reviewed on
2026-06-28 (see the [benchmark](PUBLIC_BENCHMARK_AND_ROADMAP.md) for the exact
repositories and dated star counts); individual projects differ.

## Why "hidden, not blocked" matters

Most servers that offer a read-only flag still *advertise* write tools and reject
them at call time. That leaves two problems: the model wastes turns attempting
calls that fail, and a prompt-injection payload inside a Telegram message can
still steer the model toward a tool that *exists*. In this server, a read-only or
profile-restricted surface means the write tools are **absent from `tools/list`**
— the model has nothing destructive to reason about, attempt, or be steered into.

## What this project deliberately does not do

Features found in competitors and declined here, because they don't belong in a
model-callable surface attached to a real account:

- **Raw MTProto escape hatch** — bypasses every guardrail above.
- **Chat ownership transfer and channel deletion** — account-losing actions.
- **Bulk contact import/export** — conflicts with the local-first privacy posture.

## When another family is the right choice

- You only need to push notifications into a group → a **Bot API** server is
  simpler and structurally safer.
- You are hacking on a throwaway account and want a raw, unrestricted API surface
  in Python → a **Telethon** server may be more convenient.
- You want an AI agent connected to an account you actually care about, with the
  smallest surface that still does the job → that is what this server is for.

## See it in action

Start with the [recipes](recipes/README.md) — copy-paste configurations and
prompts for inbox summaries, finding lost messages, public-group research, and
opt-in drafting.
