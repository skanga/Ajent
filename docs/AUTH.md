# Authentication and credentials

Ajent supports Anthropic API keys, Anthropic's interactive OAuth flow, API keys
for OpenAI-compatible hosted providers, and keyless local providers. This
document describes resolution order, storage, refresh, and troubleshooting.

## Quick start

Interactive Anthropic sign-in:

```powershell
.\ajent.cmd login
.\ajent.cmd status
.\ajent.cmd --workspace .
```

One-process API key:

```powershell
$env:ANTHROPIC_API_KEY = "..."
.\ajent.cmd --provider anthropic --workspace .
```

OpenAI-compatible API key:

```powershell
$env:OPENAI_API_KEY = "..."
.\ajent.cmd --provider openai --model MODEL_ID --workspace .
```

Local Ollama and llama.cpp endpoints do not require authentication.

## Resolution precedence

For the active provider, credentials are resolved in this order:

1. the one-shot `--key` CLI option;
2. the provider-specific environment variable;
3. a saved encrypted provider credential;
4. no credential, only when the provider is explicitly keyless/local.

Environment variables intentionally override saved values so CI and temporary
shell sessions do not need to rewrite the credential file. Settings determine
the default provider/model, but a CLI provider/model selection wins for that
process.

Common environment variables include:

- `ANTHROPIC_API_KEY`
- `OPENAI_API_KEY`
- `GROQ_API_KEY`
- `OPENROUTER_API_KEY`
- `TOGETHER_API_KEY`
- `CEREBRAS_API_KEY`

Provider presets and their exact environment names are defined by
`ProviderRegistry`; `ajent --help` is the executable reference for the current
build.

## Anthropic OAuth flow

`ajent login` offers browser-based Anthropic OAuth as well as manual API-key
entry. OAuth uses a public client with PKCE:

1. Ajent creates a high-entropy verifier, S256 challenge, and state.
2. It opens the Anthropic authorization URL in the default browser.
3. The terminal accepts the returned authorization value. Joined
   `code#state` input is supported.
4. Ajent verifies state and exchanges the code for access/refresh tokens.
5. The encrypted credential, token type, and calculated expiry are persisted.

If browser launch is unavailable, copy the displayed URL manually. The login
modal includes a copy action and never paints an entered API key.

Ajent currently does not implement ChatGPT/Codex OAuth. OpenAI-compatible
providers use API keys. This is not an AgenTTY parity omission; adding it would
be an Ajent extension requiring a separately supported public OAuth contract.

## Refresh during a turn

An OAuth credential is treated as expired using its stored expiry plus the
native safety window. If a request encounters an eligible authorization
failure and has a refresh token, the reducer parks the turn, runs a refresh,
and prevents queued work from using the expired in-memory header.

On success:

- the new credential is installed in memory;
- encrypted persistence is attempted best-effort;
- the parked provider request restarts with a fresh cancellation signal;
- queued turns remain ordered.

On failure, the turn receives a typed authentication error and returns to a
safe terminal state. Ajent does not retry a refresh when visible uncommitted
provider output could be duplicated.

## Credential file

Ajent uses AgenTTY-compatible credential paths and a versioned authenticated
envelope. `CredentialPaths` selects the platform configuration root. The
payload is encrypted as `v1` with:

- a machine/user-specific seed (Windows MachineGuid plus user identity, or the
  corresponding POSIX machine/user sources);
- HKDF-SHA256 using the AgenTTY credential context;
- AES-256-GCM with a fresh nonce and authenticated envelope metadata;
- atomic replacement and private file permissions where the platform permits.

The file is bound to the machine/user seed by design. Copying it to another
machine is not a portable backup. Re-run `ajent login` on the destination.

Legacy plaintext credentials are accepted only for migration. A successful
read rewrites them in the encrypted format. Authentication failure or envelope
tampering is reported; Ajent does not silently treat corrupted ciphertext as a
valid empty credential.

## Commands

```powershell
.\ajent.cmd login
.\ajent.cmd status
.\ajent.cmd logout
```

`status` reports the source and expiry state without printing secrets. It can
distinguish environment, saved API key, valid OAuth, expiring OAuth, expired
OAuth, and absent credentials. `logout` removes the saved credential
idempotently; it cannot remove an environment variable from the parent shell.

The interactive command palette also exposes login and provider switching.
Provider-key input is owned by the login modal, is not echoed, and is cleared
on close or thread-wide UI reset.

## Custom and local endpoints

OpenAI-compatible custom hosts use the Chat Completions dialect. A custom URL
is normalized into an endpoint specification and can use `--key` or its saved
provider key. Treat a custom server as trusted: prompts can contain source
code, tool results, memory, and attachment bodies.

Ollama uses its native API and llama.cpp uses an OpenAI-compatible endpoint.
Keyless authorization is permitted only for provider specifications marked
local; a missing key for a hosted provider fails closed.

## Network overrides

Provider and OAuth HTTP clients share the environment-aware JDK client. Proxy,
air-gap, and native dial/host overrides are applied before requests are sent.
Debug transport logging is best-effort and redacts authorization and secret
fields.

When diagnosing a custom proxy, first verify `status`, then model listing, then
a minimal turn. OAuth authorization and token exchange may use different hosts
from the provider API, so both must be reachable.

## Security guidance

- Prefer environment variables for CI secrets and saved encryption for local
  interactive use.
- Do not pass `--key` in shared shell history or process listings.
- Never commit credential/config files or paste them into an issue.
- Review custom provider and MCP endpoints before allowing repository access.
- Use `minimal` or `ask` profiles for untrusted workspaces.
- Treat terminal transcripts and debug logs as potentially source-sensitive,
  even though Ajent redacts credentials.

## Troubleshooting

### `no credential for provider`

Check that the selected provider matches the environment variable or saved key.
Run `ajent status`, then pass `--provider` explicitly to rule out stale settings.

### OAuth code rejected

Start a new login attempt. PKCE verifier and state belong to one attempt; a
code from an older browser tab cannot be exchanged by a newer attempt. Paste
the complete returned value when the provider supplies `code#state`.

### Saved credential cannot be decrypted

Machine identity, user identity, or the credential file changed. Preserve the
file for diagnosis, run `logout` only if you accept removing it, then sign in
again. Do not edit the encrypted JSON manually.

### Environment key appears to ignore logout

This is expected precedence. `logout` removes saved credentials; unset the
environment variable in the current shell separately.

### Token refresh loops or immediately expires

Check the system clock and proxy response. Ajent calculates expiry from the
token response and applies a safety margin. Run `status`; if the refresh token
is absent or rejected, perform a fresh login.

## Implementation references

- `ajent-provider/.../auth/CredentialResolver.java`
- `CredentialStore`, `CredentialCrypt`, `CredentialPaths`, and `MachineSeed`
- `AnthropicOAuthLogin` and `AnthropicOAuthClient`
- `ajent-runtime/.../CredentialOAuthRefreshPort.java`
- CLI `AuthCommands` and the terminal `LoginModal`
