# MCP hardening patch release 1.18.1

Date: 2026-11-01. The tool inventory remains 116. Companion skill: 0.1.2,
minimum compatible server: 1.17.2.

This release contains the reviewed landscape follow-ups and their corrective
refactor. See [review evidence](HARDENING_REVIEW_2026-11-01.md) for findings,
tests, live coverage and known limits.

## Migration

Existing write deployments with `MCP_DESTRUCTIVE_APPROVAL=off` must choose
`auto`, `elicitation`, or `loopback`, or explicitly accept model-supplied
confirmation using `MCP_ALLOW_MODEL_CONFIRMED_WRITES=true`. Human approval
applies to destructive tools, not every mutation. Read-only defaults remain.

HTTP validates Origin/Host and binds sessions to their authenticated owner.
Configure exact additional origins/hosts for reverse proxies. Sessions expire
after 24 idle hours; clients must initialize again on 404. Tools/list and
manifest entries are narrowed by grants and accessible accounts, including
STDIO's `stdio` identity. Invalid/shared-write anti-spam overrides now prevent
startup. Check policy files and recognized Windows shared-group ACLs.

The release platform inventory smoke explicitly uses `elicitation`, so it
does not bypass the new startup guard or execute Telegram mutations.

## Local verification before publication

- Full clean JVM run: 1042 passed, 2 unsupported Windows symlink cases skipped.
- Python suite: 11 passed. Skill packaging/validation passed.
- Release-version bootJar/native/release metadata gates passed for 1.18.1.
- Independent SDK STDIO/HTTP/bridge and daemon restart checks passed on the
  reviewed source. Live baratnor read-only STDIO/HTTP checks passed against
  one kodobeast history record; no messages sent.
- Focused conformance: six checks succeeded, but the Windows Node/libuv
  runner crashed; end-to-end conformance is not claimed as passing.

Tag-triggered CI separately gates four runtime packages, signed container
digests, artifact checksums and registry publication. Presence of this file
does not imply that remote publication or post-release verification succeeded.
Published evidence will be recorded after those operations complete.
