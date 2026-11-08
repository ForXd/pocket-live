// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Call after health ordering. Preserve subscriptions and choose one enabled representative
 * per exact normalized HTTP host; do not collapse unrelated subdomains or public suffixes. */
internal fun distinctSearchDomains(sources:List<Source>,resolvedHost:(String)->String?={it.toHttpUrlOrNull()?.host}):List<Source> = sources.distinctBy {
    resolvedHost(it.url)?.trimEnd('.')?.lowercase(java.util.Locale.ROOT) ?: it.key
}
