# Calendar Agent

The `CalendarAgent` manages calendar events, scheduling queries, and time conflict detection through decoupled calendar providers.

---

## Capabilities & Providers
- Query events and upcoming schedules.
- Detect overlapping time conflicts deterministically.
- Support Read and Write operations with security confirmation.
- **`GoogleCalendarProvider`**: Implements Google Calendar OAuth2 authentication (`GoogleOAuthToken`) with scope-limited token validation and audit logging.
