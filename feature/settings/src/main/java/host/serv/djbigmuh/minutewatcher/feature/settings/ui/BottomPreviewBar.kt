package host.serv.djbigmuh.minutewatcher.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import host.serv.djbigmuh.minutewatcher.core.model.WidgetUiState
import host.serv.djbigmuh.minutewatcher.core.resources.R
import host.serv.djbigmuh.minutewatcher.feature.settings.ui.components.WidgetPreview

@Composable
fun BottomPreviewBar(uiState: WidgetUiState?) {
    Column(
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .navigationBarsPadding()
                .padding(16.dp),
    ) {
        // Text is used for headers and setting labels.
        // style = MaterialTheme.typography.titleSmall for section headers.
        // style = MaterialTheme.typography.bodyMedium for labels.
        Text(
            text = stringResource(id = R.string.preview),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Box provides widget preview centering.
        // contentAlignment = Alignment.Center centers content.
        // wrapContentHeight() grows the preview to fit any layout mode, including 6-line mode.
        // background(MaterialTheme.colorScheme.surface) provides preview background.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            uiState?.let {
                WidgetPreview(
                    uiState = it,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
