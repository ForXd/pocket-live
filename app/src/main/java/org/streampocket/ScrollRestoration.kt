// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** A restored screen is not a filter change. Store only the last applied scalar keys,
 * never results, screen objects or a history of queries. The holder has fixed page slots. */
@Composable internal fun ScrollToTopOnChange(vararg keys:String,scrollToTop:suspend ()->Unit) {
    val current=keys.toList()
    var applied by rememberSaveable(stateSaver=listSaver<List<String>,String>(save={it},restore={it})) {
        mutableStateOf(current)
    }
    LaunchedEffect(current) {
        if(applied!=current) {
            scrollToTop()
            applied=current
        }
    }
}
