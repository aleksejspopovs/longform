package lv.popovs.longform

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lv.popovs.longform.ui.theme.LongformTheme

class ArticleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        setContent {
            LongformTheme {
                ArticleScreen(text = text)
            }
        }
    }

    companion object {
        const val EXTRA_TEXT = "extra_text"
    }
}

@Composable
fun ArticleScreen(text: String) {
    Scaffold { innerPadding ->
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
