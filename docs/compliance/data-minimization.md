# Data minimization — adopter guidelines

GDPR Article 5(1)(c) requires personal data to be "adequate, relevant and limited to
what is necessary." sdui-kmp's protocol gives the server full control over what flows
to the client, which is great for product velocity — and risky for privacy if you don't
think it through.

## Patterns to prefer

### Bind, don't literal

```kotlin
// AVOID: ships the user's email through the screen JSON every render.
Text(
    id = NodeId("greeting"),
    content = Value.ofString("Welcome, jane.doe@example.com"),
)

// PREFER: bind to client-local state. The email never re-leaves the device.
Text(
    id = NodeId("greeting"),
    content = Value.Template("Welcome, {{user.email}}"),
)
```

The protocol's `Value.Template` resolves against `LocalStateStore` on the client; the
server never re-serializes the bound value into the next screen.

### Server-side templating happens before serialization, not after

If you must ship a personalised string from the server, template it server-side and
ship the **result**, not the raw inputs. Don't embed `firstName`, `lastName`, and
`emailAddress` as separate state and let the client concat — that triples the on-wire
PII surface.

### Avoid `Value.Literal(JsonElement)` for user-typed values

`Value.Literal<JsonElement>` is the most flexible variant; it lets servers ship
arbitrary JSON. That flexibility is dangerous when the JSON contains PII because it
ends up in:

* the `Action.Submit` payload (sent back to your server — fine)
* the `transport-cache` on-device store (cached for the configured TTL)
* studio audit log entries when an editor saved the screen with that literal in place

### Action payloads should reference paths, not values

```kotlin
// AVOID: hard-codes the user's password into the action graph.
Action.Submit(
    endpoint = "/api/login",
    payload = mapOf("password" to Value.ofString(userPassword)),
)

// PREFER: bind to the form's StatePath. The value never lives in the action JSON.
Action.Submit(
    endpoint = "/api/login",
    payload = mapOf("password" to StatePath("form.password")),
)
```

## Patterns to avoid

| Anti-pattern | Why it's bad |
|---|---|
| Shipping arbitrary JSON in `Value.Literal` from a logged-in user's session into a screen tree | `transport-cache` may persist it; studio editor might pick it up; the audit log could capture it. |
| Logging the full screen tree at INFO level on the server | Every server log line containing a personalized screen tree is now a personal-data record. Use DEBUG and redact the `Value.Literal` content before INFO. |
| Wiring `SduiTelemetry` to ship per-render JSON to a third party | Third-party telemetry should receive **types and counts**, not values. The default `OpenTelemetryTelemetry` adapter does this correctly — node *type*, not node *content*. Custom telemetry adapters must follow the same rule. |
| Using `Action.UpdateState` to copy server-supplied PII into the StateStore for later use | Once it's in the StateStore it lives until the screen leaves composition, and may be referenced by a `Value.Bind` from a child sub-tree. Treat the StateStore as a privacy boundary; copy only what the user just typed, not what the server sent. |

## Studio audit log

The `studio-server` admin backend records, per save:

* editor user id (a user identifier, internal to the adopter's IAM)
* timestamp
* screen id
* a hash of the new screen's JSON (NOT the JSON itself by default)

If your team needs the full diff for compliance reasons, the `studioAudit.captureFullDiff`
flag can be enabled — but doing so means every literal value the editor pasted into the
screen tree is now retained in the audit log for the configured retention window. Read
the trade-off carefully before flipping it on.

## Form drafts

`samples/sample-server` ships an in-progress draft store keyed on a server session id.
Drafts contain *user-typed* form values. The default 30-day retention is a balance
between "user comes back in two weeks and expects their draft" and "we don't keep
unsubmitted PII forever." Adopters with stricter notices should shorten the retention
window or disable drafts entirely.

## Quick checklist for new screens

Before adding a screen to your server's catalogue:

- [ ] Does any `Value.Literal<String>` contain user-typed text? Replace with
      `Value.Bind` or `Value.Template`.
- [ ] Does any `Action.Submit.payload` map ship a literal value? Replace with a
      `StatePath` reference.
- [ ] Have you reviewed which fields the screen tree might end up in the
      `transport-cache`? If yes, treat the cache TTL as a retention question.
- [ ] If the screen is server-personalized, is the personalization scoped to the
      smallest possible field?
- [ ] Is the screen's telemetry wiring respecting `LocalConsentSource`? Check that
      `ConsentAwareTelemetry` (or an equivalent host-built decorator) is wired in.
