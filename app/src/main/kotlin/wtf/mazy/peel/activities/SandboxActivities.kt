package wtf.mazy.peel.activities

/**
 * Sandbox activity subclasses for process isolation.
 *
 * Each webapp with sandbox enabled runs in its own Android process. These are empty subclasses -
 * all logic is in WebViewActivity. The process isolation is configured in AndroidManifest.xml via
 * android:process attribute.
 *
 * Maximum 8 concurrent sandboxed webapps are supported (containers 0-7).
 */
class SandboxActivity0 : WebViewActivity()

class SandboxActivity1 : WebViewActivity()

class SandboxActivity2 : WebViewActivity()

class SandboxActivity3 : WebViewActivity()

class SandboxActivity4 : WebViewActivity()

class SandboxActivity5 : WebViewActivity()

class SandboxActivity6 : WebViewActivity()

class SandboxActivity7 : WebViewActivity()
