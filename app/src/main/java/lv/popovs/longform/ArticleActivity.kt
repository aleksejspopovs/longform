package lv.popovs.longform

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    BasicTextField(
        value = text,
        onValueChange = {},
        modifier = Modifier.fillMaxSize()
    )
}
