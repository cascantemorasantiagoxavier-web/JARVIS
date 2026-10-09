# Email Agent

The `EmailAgent` coordinates email search, thread retrieval, draft preparation, and safe send confirmation workflows.

---

## Capabilities & Providers
- Read and search email messages by thread, subject, or sender.
- Draft email replies safely.
- Enforce explicit confirmation (`confirmed = true`) for any external write action (sending emails).
- **`GmailProvider`**: Implements Gmail OAuth2 authentication (`GoogleOAuthToken`) with scope-limited token validation and audit logging.
