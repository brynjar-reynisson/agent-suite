# AgentSuite Project Context

## Project Overview
AgentSuite is a Spring Boot application (Java 25) that provides a multi-provider AI chat interface. It leverages LangChain4j for integration with various Large Language Models (LLMs) and includes custom logic for advanced features like reasoning content handling for DeepSeek.

### Key Technologies
- **Java 25**
- **Spring Boot 3.5.0**
- **LangChain4j 0.36.2**: Used for Anthropic (Claude) and Google (Gemini) integrations.
- **Custom RestClient Implementation**: Used for DeepSeek to handle specific reasoning content and caching.
- **Unix4j**: Used within `UnixTools` to provide shell-like capabilities.

### Architecture
- **`AiController`**: Exposes the `/ai/chat` endpoint. Supports `message`, `prompt`, `rootDirectory`, and `model` parameters.
- **`ChatService`**: A unified interface for different LLM providers.
- **`ModelRegistry`**: Maps user-facing model aliases to specific `ChatService` implementations.
- **`UnixTools`**: An agentic toolset providing `ls` and `cat` functionality to the AI models.

## Building and Running

### Prerequisites
- Java 25+
- Maven
- API Keys (as environment variables):
    - `DEEPSEEK_API_KEY`
    - `GOOGLE_API_KEY`
    - `ANTHROPIC_API_KEY`

### Commands
- **Build**: `mvn clean install`
- **Run**: `mvn spring-boot:run`
- **Test**: `mvn test`

The application runs on port `8090` by default.

## Development Conventions

### Model Aliases
Use the following aliases when interacting with the `/ai/chat` endpoint:
- `deepseek-v4-pro` (Default)
- `sonnet-4.6` (Anthropic Claude 3.5 Sonnet)
- `opus-4.7` (Anthropic Claude 3 Opus)
- `haiku-4.5` (Anthropic Claude 3 Haiku)
- `gemini-2.5-pro` (Google Gemini 2.5 Pro)
- `gemini-2.5-flash` (Google Gemini 2.5 Flash)

### Tool Implementation
New tools should be added to classes (like `UnixTools`) and annotated with:
- `@Tool("description")` on the method.
- `@P("description")` on the method parameters.

The `ChatService` implementations (especially `DeepSeekService` and the LangChain4j-based ones) handle the tool execution loop automatically.

### Root Directory Security
The `AiController` has a hardcoded `ALLOWED_ROOT_DIRECTORIES` set. When using `UnixTools`, ensure the `rootDirectory` passed to the controller is within this allowed set.

### Reasoning Cache
`DeepSeekService` implements a `reasoningCache` to persist reasoning content across tool execution loops, ensuring the model maintains context during multi-step tasks.
