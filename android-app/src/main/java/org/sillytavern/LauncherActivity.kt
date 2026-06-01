package org.sillytavern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.sillytavern.ui.AppRoot
import org.sillytavern.ui.theme.SillyTavernTheme

/**
 * 应用入口 Activity：承载 Compose 控制台（首页 + 配置/日志/数据/关于）。
 * 导航与各页面在 [org.sillytavern.ui.AppRoot]，本类只负责主题与窗口装饰。
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SillyTavernTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}
