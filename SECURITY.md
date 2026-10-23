# Security Policy

## Scope

`apex-kit-browser` is a library that executes JavaScript inside a WebView on
behalf of an AI agent. The interesting attack surface is therefore **not** a
network listener but the boundary where model-supplied strings become
page-executed JavaScript.

Threat model in one line:

```
web page content  ->  snapshot shown to the model  ->  model echoes a value
back as a "ref"  ->  library interpolates it into JS  ->  executes in page
```

That chain is closed as long as every such string goes through
`JsLiteral.string` and is never concatenated raw. `guard-rails.yml` fails the
build if an injection point appears that bypasses it.

## Reporting

Please report suspected vulnerabilities privately via
[GitHub Security Advisories](https://github.com/Ultra-Guru/apex-kit-browser/security/advisories/new)
rather than opening a public issue.

Include: the affected module/version, the injection point, a reproducing `ref`
or selector payload, and the observed behaviour (executed script, syntax error,
or element not found).

We aim to acknowledge within a few days.

## What counts as a vulnerability here

- A string that reaches `evaluateJavascript` without `JsLiteral.string`
  encoding, i.e. page-controlled data executing script.
- WebView settings that weaken the sandbox (file/content access enabled,
  mixed content allowed, universal file access, remote debugging on in release).
- Navigations to non-`http(s)` schemes (`file:`, `javascript:`, `content:`) that
  are not blocked.
- SSL errors that are ignored rather than cancelled.
- Use of `addJavascriptInterface` **without** `@JavascriptInterface` on every
  bridged method (see below).

## Static analysis: enable the `security-extended` suite

If you turn on CodeQL for this repo, pick the **`security-extended`** suite
(`security-extended.qls`), **not** the default `security.qls`.

This matters specifically for a WebView library: every query that would catch
a WebView mistake lives in the extended suite, not the default one —

| Query | Suite |
|---|---|
| `java/android/websettings-javascript-enabled` | **security-extended** |
| `java/android/webview-addjavascriptinterface` | **security-extended** |
| `java/android/websettings-file-access` | **security-extended** |
| `java/android/websettings-allow-content-access` | **security-extended** |
| `java/android/webview-debugging-enabled` | **security-extended** |
| `java/android/unsafe-android-webview-fetch` | **security-extended** |
| `java/improper-webview-certificate-validation` | **security-extended** |
| `java/xss` (writing user data into a WebView) | default |

CodeQL's Kotlin support requires a build, so use **default setup**
(Settings -> CodeQL -> default setup), which is free on public repositories.
Do not hand-roll a CodeQL workflow; it is both more work and less correct.

## Background: the two rules that matter most for WebView bridges

Both come from Google's WebView security guidance and the associated CVEs;
they are the reason the rules above exist.

1. **`addJavascriptInterface` below API 17 is remote code execution.** JS could
   reach `getClass().forName(...)` on the injected object and call arbitrary
   Java ([CVE-2012-6636](https://www.cve.org/CVERecord?id=CVE-2012-6636),
   [CVE-2013-4710](https://nvd.nist.gov/vuln/detail/CVE-2013-4710), Metasploit
   module [EDB-41675](https://www.exploit-db.com/exploits/41675)). This library
   requires `minSdk 26` and uses no `addJavascriptInterface` at all — the only
   channel into the page is `evaluateJavascript`, which is exactly why the
   escaping boundary above is the thing to protect.

2. **String-concatenated `evaluateJavascript` is called out explicitly.** Google:
   *"Calling `evaluateJavascript` using unsanitized input from untrusted Intents
   lets attackers execute harmful scripts in the affected WebView."* The
   accepted pattern — used by Playwright and browser-use — is that the *only*
   legal ways to get a dynamic value into a script are a real JSON encoder
   (`JSON.stringify` / kotlinx.serialization) or an argument channel; never
   string concatenation. `JsLiteral.string` is this library's equivalent.

   Note that `</script>`, `` ` `` and `$` need no escaping here: the script goes
   to `evaluateJavascript`, not into an HTML `<script>` block. `\`, newlines and
   U+2028/U+2029 do, and are handled.

## What is out of scope

- The agent acting on user instructions — automating what the user asked for is
  the product, not a bug.
- Pages that deliberately exfiltrate their own data after the user navigated
  to them.
- Denial of service caused by a hostile page (resource exhaustion).
- Missing hardening that the host app is expected to own (e.g. declaring
  `SYSTEM_ALERT_WINDOW`, which is deliberately left to the host).