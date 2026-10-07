package com.example

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.database.AppDatabase
import com.example.data.repository.NumberRepository
import com.example.ui.components.AddNumberSection
import com.example.ui.components.BackupTab
import com.example.ui.components.FloatingControlCard
import com.example.ui.components.HistoryListTab
import com.example.ui.components.OverlayPermissionDialog
import com.example.ui.components.QueueListTab
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.MainViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val db = AppDatabase.getInstance(applicationContext)
        val repo = NumberRepository(db.numberDao())
        MainViewModelFactory(repo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val isFloatingRunning by viewModel.isFloatingServiceRunning.collectAsStateWithLifecycle()
    val queueCount by viewModel.queueCount.collectAsStateWithLifecycle()
    val queueNumbers by viewModel.queueNumbers.collectAsStateWithLifecycle()
    val historyNumbers by viewModel.historyNumbers.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val inputNote by viewModel.inputNote.collectAsStateWithLifecycle()
    val userMessage by viewModel.userMessage.collectAsStateWithLifecycle()

    var showPermissionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    val nextNumber = queueNumbers.firstOrNull()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NumQueue",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = "সিরিয়াল নম্বর কপিয়ার ও ফ্লোটিং উইজেট",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("bottom_nav_bar")
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { viewModel.setSelectedTab(0) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (queueCount > 0) {
                                    Badge { Text("$queueCount") }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FormatListNumbered,
                                contentDescription = "Queue Tab"
                            )
                        }
                    },
                    label = { Text("সারি (${queueCount})") },
                    modifier = Modifier.testTag("tab_queue")
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (historyNumbers.isNotEmpty()) {
                                    Badge { Text("${historyNumbers.size}") }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "History Tab"
                            )
                        }
                    },
                    label = { Text("হিস্ট্রি") },
                    modifier = Modifier.testTag("tab_history")
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { viewModel.setSelectedTab(2) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Backup,
                            contentDescription = "Backup Tab"
                        )
                    },
                    label = { Text("ব্যাকআপ") },
                    modifier = Modifier.testTag("tab_backup")
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))

            // Floating Widget Control Banner (Available on all tabs for quick toggling)
            FloatingControlCard(
                context = context,
                isServiceRunning = isFloatingRunning,
                queueCount = queueCount,
                nextNumber = nextNumber,
                onCopyAndPopClick = { viewModel.copyAndPopNext(context) },
                onRequestOverlayPermission = { showPermissionDialog = true }
            )

            Spacer(modifier = Modifier.height(10.dp))

            when (selectedTab) {
                0 -> {
                    // Queue Tab: Add number section + active queue items
                    Column(modifier = Modifier.fillMaxSize()) {
                        AddNumberSection(
                            inputText = inputText,
                            inputNote = inputNote,
                            onInputTextChange = { viewModel.setInputText(it) },
                            onInputNoteChange = { viewModel.setInputNote(it) },
                            onSaveNumbers = { viewModel.addNumbersFromInput() }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        QueueListTab(
                            queueList = queueNumbers,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.setSearchQuery(it) },
                            onCopyAndPopClick = { viewModel.copyAndPopNext(context) },
                            onDeleteItem = { viewModel.deleteQueueItem(it) },
                            onClearAll = { viewModel.clearQueue() }
                        )
                    }
                }

                1 -> {
                    // History Tab
                    HistoryListTab(
                        historyList = historyNumbers,
                        onRestoreItem = { viewModel.restoreHistoryItem(it) },
                        onDeleteItem = { viewModel.deleteHistoryItem(it) },
                        onClearAllHistory = { viewModel.clearHistory() }
                    )
                }

                2 -> {
                    // Backup Tab
                    BackupTab(
                        context = context,
                        queueCount = queueCount,
                        historyCount = historyNumbers.size,
                        onExportJson = { viewModel.exportJson() },
                        onExportPlainText = { viewModel.exportPlainText() },
                        onImportJson = { json, mode ->
                            viewModel.importJsonBackup(json, mode) { success, msg -> }
                        },
                        onImportPlainText = { raw, replaceAll ->
                            viewModel.importPlainText(raw, replaceAll) {}
                        },
                        onShowMessage = { viewModel.showMessage(it) }
                    )
                }
            }
        }
    }

    if (showPermissionDialog) {
        OverlayPermissionDialog(
            context = context,
            onDismiss = { showPermissionDialog = false },
            onPermissionGrantedCheck = {
                if (Settings.canDrawOverlays(context)) {
                    showPermissionDialog = false
                }
            }
        )
    }
}
