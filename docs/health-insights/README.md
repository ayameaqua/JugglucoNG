# Health timeline and agent access — design draft

This directory contains a reviewable design, not an implemented Android feature.
All records under `examples/` are synthetic. No SDK binary, real health capture,
device identifier, signing key or MCP credential is included.

Development branch: `feature/samsung-health-insights`, based on
`build/custom-dub-final` at `a1b81069e980225034fd8a129201bc76d81ab789`.

- [Product design, in Chinese](design.zh-CN.md)
- [Data and MCP contract](data-contract.md)
- [Record schema](record.schema.json)
- [Child series schema](series.schema.json)
- [Initial field catalog](field-catalog.json)
- [Interactive screen draft](preview.fragment.html)
- [Samsung SDK capability inventory](capabilities.json)
- [Synthetic examples](examples/)

## Target agent: ChatGPT

The target is ChatGPT on the user's phone. This is not evidence that ChatGPT is
a native phone-local MCP client. Its documented custom MCP connection uses the
web surface with a reachable HTTPS server or Secure MCP Tunnel; a phone loopback
URL cannot be pasted into that connection as a directly reachable server.
Android app usage, account access, authentication and phone-side bridge runtime
remain validation gates. The screen draft shows these gates rather than claiming
an operational connection. File export remains available independently.

- [ChatGPT custom MCP connection](https://developers.openai.com/api/docs/guides/custom-mcp-server)
- [Prepare a reachable endpoint](https://developers.openai.com/plugins/deploy/connect-chatgpt)
- [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)

## Local SDK policy

The Samsung Health Data SDK is an optional, user-supplied local dependency.
Obtain it yourself from [Samsung](https://developer.samsung.com/health/data/overview.html)
and comply with its licence. Do not commit or upload its ZIP, AAR, JAR, source
archive, tools, or SDK-containing APK to GitHub or GitHub Actions artifacts.
Ignoring files is one guard; CI and distribution must also exclude the SDK variant.

Planned build interface, **not wired into Gradle yet**:

- Normal builds and CI: SDK disabled; ordinary phone/watch builds remain possible.
- Personal phone build: `-PsamsungHealthEnabled=true` and
  `-PsamsungHealthSdkPath=/absolute/path/to/samsung-health-data-api.aar`.
- Enabling the integration without a valid compatible local AAR fails clearly.
  Disabling it must not resolve or package Samsung classes.
- Keep the SDK adapter in a separate optional phone-only source set or module,
  behind an SDK-free interface and a disabled implementation. Wear stays SDK-free.
- Reconcile the SDK's supported Android versions with the optional phone build;
  do not silently increase the ordinary app's minimum Android version.

Each developer must enable Samsung Health's **Data Read developer mode** and grant
the required read permissions to their actual app package/signature. Permission
for the separate probe app does not authorize JugglucoNG or its DUB package.
[Samsung's developer-mode policy](https://developer.samsung.com/health/data/guide/developer-mode.html)
limits that mode to testing/debugging. Formal distribution requires partner
registration. This design requests no Samsung WRITE permissions.

## Verification boundary

The separate private probe already demonstrated six read families on
SM-S9180 / Android 16 / Samsung Health 7.00.6.012 with SDK 1.1.0.
This is evidence for the proposed adapter, not proof that every listed SDK type,
the JugglucoNG integration, or a ChatGPT/MCP connection has been tested.
Those validations belong to subsequent implementation work.
