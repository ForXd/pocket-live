// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Only the request's own parameters vary between searches. Keep port, path and provider credentials. */
internal fun searchEndpointKey(url:HttpUrl):String = hash(url.newBuilder()
    .removeAllQueryParameters("ac").removeAllQueryParameters("wd").fragment(null).build().toString())

internal fun distinctSearchEndpoints(sources:List<Source>,
    resolvedEndpoint:(String)->String={ searchEndpointKey(it.toHttpUrl()) }):List<Source> = sources.distinctBy {
    it.kind to if(it.kind==SourceKind.VOD) resolvedEndpoint(it.url) else it.url
}
