package com.example.minutewatcher.feature.main.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.minutewatcher.core.model.R

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior

@Composable
fun DayPickerComposeDialog(
    initialDay: Int,
    onDismiss: () -> Unit,
    onDaySelected: (Int) -> Unit
) {
    val days = (1..31).toList()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (initialDay - 1).coerceAtLeast(0))
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.choose_reset_day_dialog_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(id = R.string.choose_reset_day), 
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                    textAlign = TextAlign.Center
                )
                
                Box(
                    modifier = Modifier
                        .height(200.dp)
                        .width(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Central highlight / Selector
                    Surface(
                        modifier = Modifier
                            .height(50.dp)
                            .fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = MaterialTheme.shapes.medium
                    ) {}
                    
                    LazyColumn(
                        state = listState,
                        flingBehavior = snapFlingBehavior,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 75.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        itemsIndexed(days) { _, day ->
                            val isSelected by remember { 
                                derivedStateOf { listState.firstVisibleItemIndex == day - 1 } 
                            }
                            
                            Text(
                                text = day.toString(),
                                fontSize = if (isSelected) 32.sp else 22.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .padding(vertical = 8.dp)
                                    .fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selectedDay = listState.firstVisibleItemIndex + 1
                    onDaySelected(selectedDay.coerceIn(1, 31))
                },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(stringResource(id = R.string.set_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.cancel_button))
            }
        }
    )
}
