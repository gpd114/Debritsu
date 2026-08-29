package com.debritsu.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.debritsu.app.data.AniList

/**
 * Signs in to AniList inside the app.
 *
 * The sign-in normally hands the URL to a browser, which is fine on a phone and
 * useless on a television: most carry no browser at all. Android still resolves
 * the intent there, because the platform ships a stub activity whose whole job
 * is to answer with "no app can perform this action" — so the failure looks
 * like a broken button rather than a missing browser, and checking whether
 * anything can handle the intent is not enough to tell the difference.
 *
 * A WebView is present even where a browser is not, so this hosts the flow
 * itself and watches for the redirect rather than relying on the deep link
 * coming back through the launcher.
 */
class AuthActivity : Activity() {

    private var web: WebView? = null

    /**
     * Drives the page's selection from the remote.
     *
     * The page carries its own key listener too, but that only fires if WebView
     * passes the press through, and it takes d-pad keys for its own scrolling.
     * Handling them here as well means the selection moves either way.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val w = web
        if (w == null || event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val script = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT ->
                "window.__debritsuMove && window.__debritsuMove(1)"
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT ->
                "window.__debritsuMove && window.__debritsuMove(-1)"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                "window.__debritsuClick && window.__debritsuClick()"
            else -> return super.dispatchKeyEvent(event)
        }
        w.evaluateJavascript(script) { Log.d(TAG, "key ${event.keyCode} -> $it") }
        return true
    }

    companion object {
        private const val EXTRA_CLIENT_ID = "client_id"
        private const val TAG = "DebritsuAuth"

        /**
         * Gives the page keyboard navigation, because WebView has none.
         *
         * WebView is Chrome, and Chrome dropped spatial navigation: arrow keys
         * scroll the document rather than stepping between its links and
         * buttons. So a remote can reach the WebView and still touch nothing
         * inside it, which no amount of Android-side focus work changes.
         *
         * Movement is by document order rather than by position on screen.
         * Sign-in pages are a short vertical run of fields and a button, which
         * is exactly the case document order gets right and true spatial
         * navigation would be overkill for.
         *
         * The outline matters as much as the movement: a focused element in a
         * page has no styling of its own on a television, so without it the
         * selection would move invisibly.
         */
        private val REMOTE_NAV = """
            (function () {
              if (window.__debritsuNav) { return; }
              window.__debritsuNav = true;

              var style = document.createElement('style');
              style.textContent =
                ':focus { outline: 3px solid #8B5CF6 !important; outline-offset: 2px !important; }';
              document.documentElement.appendChild(style);

              function items() {
                var q = 'a[href], button, input:not([type=hidden]):not([disabled]), ' +
                        'select, textarea, [tabindex]:not([tabindex="-1"])';
                return Array.prototype.slice.call(document.querySelectorAll(q))
                  .filter(function (el) {
                    var r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0;
                  });
              }

              // Exposed so the Activity can drive this directly. Relying on the
              // page receiving the key press is the part that cannot be assumed:
              // WebView may swallow d-pad keys for its own scrolling before any
              // listener here sees them.
              window.__debritsuMove = function (step) {
                var list = items();
                if (!list.length) { return 'none'; }
                var i = list.indexOf(document.activeElement);
                var next = (i === -1) ? list[0]
                                      : list[(i + step + list.length) % list.length];
                next.focus();
                if (next.scrollIntoView) {
                  next.scrollIntoView({ block: 'center' });
                }
                return (next.tagName || '?') + ':' + (next.type || next.textContent || '')
                  .toString().slice(0, 24);
              };

              window.__debritsuClick = function () {
                var el = document.activeElement;
                if (!el) { return 'nothing'; }
                el.click();
                return el.tagName;
              };

              document.addEventListener('keydown', function (e) {
                console.log('debritsu keydown ' + e.key);
                if (e.key === 'ArrowDown' || e.key === 'ArrowRight') {
                  window.__debritsuMove(1); e.preventDefault();
                } else if (e.key === 'ArrowUp' || e.key === 'ArrowLeft') {
                  window.__debritsuMove(-1); e.preventDefault();
                }
              }, true);

              var first = items()[0];
              if (first) { first.focus(); }
              console.log('debritsu nav ready, ' + items().length + ' controls');
            })();
        """.trimIndent()

        fun intent(context: Context, clientId: String): Intent =
            Intent(context, AuthActivity::class.java)
                .putExtra(EXTRA_CLIENT_ID, clientId)

        /**
         * AniList uses the implicit grant, so the token comes back in the URL
         * fragment rather than as a query parameter.
         *
         * Shared with the deep-link path, which is still how a phone returns
         * from a real browser.
         */
        fun tokenFrom(uri: Uri?): String? {
            if (uri == null || uri.scheme != "debritsu") return null
            return uri.fragment
                ?.split("&")
                ?.firstOrNull { it.startsWith("access_token=") }
                ?.removePrefix("access_token=")
                ?.takeIf { it.isNotEmpty() }
        }

        /**
         * Whether opening a URL would actually reach a browser.
         *
         * The television stub reports itself as a handler, so the presence of a
         * resolver proves nothing; it has to be identified and discounted.
         */
        fun hasRealBrowser(context: Context): Boolean {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://anilist.co"))
            return context.packageManager.queryIntentActivities(probe, 0).any {
                !it.activityInfo.name.contains("Stubs", ignoreCase = true) &&
                    it.activityInfo.packageName != "com.google.android.tv.frameworkpackagestubs"
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clientId = intent.getStringExtra(EXTRA_CLIENT_ID).orEmpty()
        if (clientId.isBlank()) {
            finish()
            return
        }

        val web = WebView(this)
        this.web = web
        // Surfaces the injected script's console output in logcat, which is the
        // only way to see whether it ran and what it found.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d(TAG, "web: ${m.message()}")
                    return true
                }
            }
        }
        web.setBackgroundColor(Color.BLACK)
        web.settings.javaScriptEnabled = true
        // AniList's sign-in keeps the session in DOM storage; without this the
        // page loads and the login silently never completes.
        web.settings.domStorageEnabled = true

        // A WebView does not take focus by default, and without focus it never
        // sees the d-pad — so the consent page renders and nothing on it can be
        // reached. These three are what let it do its own spatial navigation
        // between the links and buttons on the page.
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.settings.setNeedInitialFocus(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = capture(request?.url)

            @Deprecated("Kept for API levels below 24, which minSdk still allows.")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                capture(url?.let(Uri::parse))

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Each new page starts with nothing selected, so the first press
                // of the remote would otherwise be spent giving the page focus
                // rather than moving within it. Focusing the first control makes
                // it visible where the selection is to begin with.
                view?.requestFocus()
                view?.evaluateJavascript(REMOTE_NAV, null)
            }
        }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    web,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        )

        web.requestFocus()
        web.loadUrl(AniList.authUrl(clientId))
    }

    /**
     * Takes the token out of the redirect and stops the WebView following it.
     *
     * AniList uses the implicit grant, so the token arrives in the fragment of
     * a `debritsu://auth` URL. Letting that load would fail — nothing serves
     * that scheme over HTTP — so it is intercepted here and handed back to
     * whoever started this screen.
     */
    private fun capture(url: Uri?): Boolean {
        if (url == null || url.scheme != "debritsu") return false
        setResult(RESULT_OK, Intent().setData(url))
        finish()
        return true
    }
}
