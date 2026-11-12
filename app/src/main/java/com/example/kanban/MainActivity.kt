package com.example.kanban

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.kanban.ink.isOnyxDevice
import com.example.kanban.ui.BoardViewModel
import com.example.kanban.ui.KanbanApp
import com.example.kanban.ui.theme.KanbanTheme
import org.lsposed.hiddenapibypass.HiddenApiBypass

class MainActivity : ComponentActivity() {

    private val viewModel: BoardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Das Onyx Pen SDK nutzt interne Android-APIs, die ab Android 9 gesperrt sind.
        if (isOnyxDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        enableEdgeToEdge()
        setContent {
            KanbanTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    KanbanApp(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
