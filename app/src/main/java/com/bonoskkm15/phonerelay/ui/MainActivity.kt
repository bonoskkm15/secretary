package com.bonoskkm15.phonerelay.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bonoskkm15.phonerelay.rule.PhoneRule
import com.bonoskkm15.phonerelay.rule.RuleStore

class MainActivity : ComponentActivity() {
    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                PhoneRelayRoot(
                    refreshTick = refreshTick,
                    onRefresh = { refreshTick++ },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
    }
}

@Composable
private fun PhoneRelayRoot(refreshTick: Int, onRefresh: () -> Unit) {
    val tabs = listOf("규칙", "기록", "설정")
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var wizardOpen by remember { mutableStateOf(false) }
    var editRule by remember { mutableStateOf<PhoneRule?>(null) }
    var prefillSample by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (wizardOpen) {
        RuleWizardScreen(
            initialRule = editRule,
            prefillSample = prefillSample,
            onCancel = {
                wizardOpen = false
                editRule = null
                prefillSample = null
            },
            onSaved = { rule ->
                RuleStore.upsert(context, rule)
                wizardOpen = false
                editRule = null
                prefillSample = null
                selectedTab = 0
                onRefresh()
            },
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text((index + 1).toString()) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { paddingValues ->
        when (selectedTab) {
            0 -> RuleScreen(
                refreshTick = refreshTick,
                onRefresh = onRefresh,
                onAdd = {
                    editRule = null
                    prefillSample = null
                    wizardOpen = true
                },
                onEdit = { rule ->
                    editRule = rule
                    prefillSample = null
                    wizardOpen = true
                },
                modifier = Modifier.padding(paddingValues),
            )
            1 -> RecordScreen(
                refreshTick = refreshTick,
                onRefresh = onRefresh,
                onEditRaw = { raw ->
                    editRule = null
                    prefillSample = raw
                    wizardOpen = true
                    selectedTab = 0
                },
                modifier = Modifier.padding(paddingValues),
            )
            else -> SettingsScreen(
                refreshTick = refreshTick,
                onRefresh = onRefresh,
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}
