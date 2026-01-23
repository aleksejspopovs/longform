package lv.popovs.longform

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import lv.popovs.longform.ui.theme.LongformTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArticleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Untitled Article"
        setContent {
            LongformTheme {
                ArticleScreen(text = text, title = title)
            }
        }
    }

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_TITLE = "extra_title"
    }
}

@Composable
fun ArticleScreen(text: String, title: String) {
    val context = LocalContext.current
    Scaffold(
        floatingActionButton = {
            Column {
                FloatingActionButton(onClick = {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val currentDate = sdf.format(Date())
                    val filename = "$currentDate $title"

                    val sendIntent = Intent(context, SendToEinkActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, text)
                        putExtra(Intent.EXTRA_SUBJECT, filename)
                        type = "text/plain"
                    }
                    context.startActivity(sendIntent)
                }) {
                    Icon(Icons.Filled.Send, contentDescription = "Send to e-ink")
                }
                Spacer(modifier = Modifier.height(16.dp))
                FloatingActionButton(onClick = {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, text)
                        type = "text/plain"
                    }
                    val shareIntent = Intent.createChooser(sendIntent, null)
                    context.startActivity(shareIntent)
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share article")
                }
            }
        }
    ) { innerPadding ->
        TextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        )
    }
}
