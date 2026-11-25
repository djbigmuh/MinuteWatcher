package com.github.djbigmuh.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.djbigmuh.minutewatcher.core.model.Constants
import com.github.djbigmuh.minutewatcher.core.resources.R
import kotlin.math.abs

@Composable
fun DayPickerComposeDialog(
    initialDay: Int,
    onDismiss: () -> Unit,
    onDaySelected: (Int) -> Unit,
) {
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = (initialDay - 1).coerceIn(0, Constants.AutoReset.MAX_DAY - 1),
        )
    val selectedDay by rememberSelectedDay(listState)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(id = R.string.choose_reset_day_dialog_title)) },
        text = {
            DayPickerContent(listState = listState, selectedDay = selectedDay)
        },
        confirmButton = {
            DayPickerConfirmButton(
                onConfirm = {
                    onDaySelected(selectedDay.coerceIn(Constants.AutoReset.MIN_DAY, Constants.AutoReset.MAX_DAY))
                },
            )
        },
        dismissButton = {
            DayPickerCancelButton(onDismiss = onDismiss)
        },
    )
}

@Composable
private fun DayPickerConfirmButton(onConfirm: () -> Unit) {
    Button(
        onClick = onConfirm,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Text(stringResource(id = R.string.set_button))
    }
}

@Composable
private fun DayPickerCancelButton(onDismiss: () -> Unit) {
    TextButton(onClick = onDismiss) {
        Text(stringResource(id = R.string.cancel_button))
    }
}

@Composable
private fun rememberSelectedDay(listState: LazyListState) =
    remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = layoutInfo.viewportStartOffset + layoutInfo.viewportSize.height / 2
            val centeredItem =
                layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }
            (centeredItem?.index ?: 0) + 1
        }
    }

@Composable
private fun DayPickerContent(
    listState: LazyListState,
    selectedDay: Int,
) {
    val days = (Constants.AutoReset.MIN_DAY..Constants.AutoReset.MAX_DAY).toList()
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(id = R.string.choose_reset_day),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp),
            textAlign = TextAlign.Center,
        )

        Box(
            modifier =
                Modifier
                    .height(200.dp)
                    .width(100.dp),
            contentAlignment = Alignment.Center,
        ) {
            SelectorHighlight()
            LazyColumn(
                state = listState,
                flingBehavior = snapFlingBehavior,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 75.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(days) { _, day ->
                    DayItem(day = day, isSelected = selectedDay == day)
                }
            }
        }
    }
}

@Composable
private fun SelectorHighlight() {
    Surface(
        modifier =
            Modifier
                .height(50.dp)
                .fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.medium,
    ) {}
}

@Composable
private fun DayItem(
    day: Int,
    isSelected: Boolean,
) {
    Text(
        text = day.toString(),
        fontSize = if (isSelected) 32.sp else 22.sp,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        color =
            if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            },
        modifier =
            Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth(),
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
    )
}
