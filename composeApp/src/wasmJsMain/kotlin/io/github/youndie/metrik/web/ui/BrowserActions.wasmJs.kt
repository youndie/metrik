@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.youndie.metrik.web.ui

actual fun platformBrowserActions(): BrowserActions =
    object : BrowserActions {
        override fun copyText(text: String) = writeText(text)

        override fun address(fragment: String): String = pageAddress() + fragment

        override val opensTabs: Boolean = true

        override fun openInNewTab(fragment: String) = openTab(address(fragment))

        override fun setTitle(title: String) = setDocumentTitle(title)
    }

@JsFun("(title) => { document.title = title }")
private external fun setDocumentTitle(title: String)

/** Адрес страницы без фрагмента: дашборд может стоять не в корне хоста. */
@JsFun("() => window.location.href.split('#')[0]")
private external fun pageAddress(): String

@JsFun("(link) => { window.open(link, '_blank', 'noopener') }")
private external fun openTab(link: String)

/**
 * `navigator.clipboard.writeText`, а где он отказал (нет защищённого контекста, нет разрешения) —
 * выделенная `textarea` и `document.execCommand('copy')`, который такие страницы ещё пускают.
 */
@JsFun(
    """(text) => {
        const byHand = () => {
            let area = null;
            try {
                area = document.createElement('textarea');
                area.value = text;
                area.setAttribute('readonly', '');
                area.style.position = 'fixed';
                area.style.opacity = '0';
                document.body.appendChild(area);
                area.select();
                document.execCommand('copy');
            } catch (e) {
            } finally {
                if (area) area.remove();
            }
        };
        try {
            navigator.clipboard.writeText(text).catch(byHand);
        } catch (e) {
            byHand();
        }
    }""",
)
private external fun writeText(text: String)
