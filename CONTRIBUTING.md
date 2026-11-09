# Contributing

Thank you for considering contributing to the Telegram MCP Server!

## Development Setup

1. **Fork and clone** the repository
2. Ensure you have **Java 25+** installed
3. Run the offline build and tests; no Telegram credentials or `.env` are needed.

```bash
./gradlew build
```

For opt-in live checks, configure a dedicated test account separately using
`.env.example` as a reference. The JVM test task removes `TDLIB_*` and
`TELEGRAM_*` variables from its environment. Do not reuse a personal session in
tests or run two clients against the same TDLib data directory.

## Code Style

- Follow idiomatic Kotlin conventions
- Use `data class` for DTOs and value objects
- Prefer `sealed class`/`sealed interface` for type hierarchies
- Add KDoc for all public classes and functions
- Keep functions small and focused

## Adding a New MCP Tool

1. Create a `@Component` class implementing `McpToolHandler` in the `tool/` package
2. Define the JSON Schema for inputs in `definition()`
3. Implement business logic in `execute()`
4. Add guardrail checks (`guardrailService.validateInput(...)`, `guardrailService.validateChatAccess(...)`)
5. Add metrics via `MeterRegistry`
6. Write unit tests

## Pull Request Process

1. Create a feature branch from `master`
2. Write tests for new functionality
3. Ensure all tests pass: `./gradlew test`
4. Keep commits atomic and descriptive
5. Open a PR with a clear description of changes

## Reporting Issues

- Use GitHub Issues
- Include steps to reproduce
- Include relevant logs (redact phone numbers, session data, tokens, and secrets)
- Do not report security vulnerabilities through public issues; see [SECURITY.md](SECURITY.md)

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
