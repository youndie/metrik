package ru.workinprogress.metrik.cli

/**
 * Where the client points and how it identifies itself.
 *
 * The dashboard sits behind an authenticating proxy and logs in with a browser; a terminal client
 * cannot do that. It talks to the MCP endpoint instead, which is the door built for machines — one
 * token, one bypass, no second authentication scheme to keep secure (see research §2.4).
 */
data class CliConfig(
    val url: String,
    val token: String,
    /** How much history the charts show. Ten minutes of minute windows fits a normal terminal. */
    val windowMinutes: Int = 60,
    val refreshSeconds: Int = 30,
    val colour: Boolean = true,
    val unicode: Boolean = true,
) {
    companion object {
        const val USAGE: String =
            """
metrik — terminal client

  metrik [service]

Environment:
  METRIK_URL      base URL, e.g. https://metrik.example.com   (required)
  METRIK_TOKEN    MCP token; the same one the server is given (required)
  METRIK_WINDOW   minutes of history to chart, default 60
  METRIK_REFRESH  seconds between refreshes, default 30
  NO_COLOR        set to any value to disable colour
  METRIK_ASCII    set to any value to use the fallback glyph set

Keys:
  ↑ ↓ / j k   move          enter   open service
  esc         back          r       refresh now
  q           quit
"""

        /**
         * Reads the configuration, or explains what is missing.
         *
         * Returning a message rather than throwing is deliberate: a client that dies with a stack
         * trace because an environment variable is unset teaches nothing.
         */
        fun fromEnv(read: (String) -> String?): Result<CliConfig> {
            val url = read("METRIK_URL")?.trimEnd('/')
            val token = read("METRIK_TOKEN")

            if (url.isNullOrBlank()) return Result.failure(IllegalStateException("METRIK_URL is not set"))
            if (token.isNullOrBlank()) return Result.failure(IllegalStateException("METRIK_TOKEN is not set"))

            return Result.success(
                CliConfig(
                    url = url,
                    token = token,
                    windowMinutes = read("METRIK_WINDOW")?.toIntOrNull()?.coerceIn(5, 24 * 60) ?: 60,
                    refreshSeconds = read("METRIK_REFRESH")?.toIntOrNull()?.coerceIn(5, 3600) ?: 30,
                    // NO_COLOR is a convention, not our invention: any value means off.
                    colour = read("NO_COLOR").isNullOrEmpty(),
                    unicode = read("METRIK_ASCII").isNullOrEmpty(),
                ),
            )
        }
    }
}
