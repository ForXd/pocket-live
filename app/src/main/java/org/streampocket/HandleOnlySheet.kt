// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable internal fun HandleOnlySheet(onDismissRequest:()->Unit,content:@Composable ColumnScope.()->Unit) {
    FrostedDialog(onDismissRequest,placement=GlassPlacement.BOTTOM,tag="glassSheet") {
        Column(Modifier.navigationBarsPadding().imePadding(),content=content)
    }
}
