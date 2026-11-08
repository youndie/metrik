package io.github.youndie.metrik.web.ui

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Desktop — отладочный таргет: буфер обмена настоящий, вкладок нет. */
actual fun platformBrowserActions(): BrowserActions =
    object : BrowserActions {
        override fun copyText(text: String) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }

        override fun address(fragment: String): String = fragment

        override val opensTabs: Boolean = false

        override fun openInNewTab(fragment: String) = Unit

        override fun setTitle(title: String) = Unit
    }
