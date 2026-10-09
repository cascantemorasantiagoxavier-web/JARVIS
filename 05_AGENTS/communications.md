# Communications Agent

The `CommunicationAgent` unifies messaging across platforms (WhatsApp, Telegram, Slack) through decoupled provider abstractions, enforcing explicit user confirmation for sending messages.

---

## Capabilities & Real Providers
- Read and search conversations and messages.
- Draft replies securely.
- Enforce explicit confirmation (`confirmed = true`) for any external write/send actions.
- **`TelegramProvider`**: Integrates with official Telegram Bot API using token configuration.
- **`WhatsAppBusinessProvider`**: Integrates with official Meta WhatsApp Cloud API using token and phone ID configuration.
- **`SlackProvider`**: Integrates with official Slack Web API using bot token configuration.
