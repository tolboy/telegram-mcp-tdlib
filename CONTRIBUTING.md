# Contributing

Thank you for considering contributing to the Telegram MCP Server!

## Development Setup

1. **Fork and clone** the repository
2. Ensure you have **Java 25+** installed
3. Copy `.env.example` to `.env` and configure a dedicated test account or bot

```bash
cp .env.example .env
./gradlew build
```

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
