import com.jakewharton.mosaic.layout.Layout
import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.ui.Text
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.Composable

fun main() = runBlocking {
    runMosaic {
        setContent {
            Layout(content = {
                Text("Node 1")
                Text("Node 2")
            }) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints) }
                layout(20, 10) {
                    placeables[0].place(0, 0)
                    placeables[1].place(10, 5)
                }
            }
        }
    }
}
