package com.cmts.mobile

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  enableEdgeToEdge()
  setContent {
   // Same resource name; Android picks drawable-nodpi (port) vs drawable-land-nodpi.
   val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
   Image(
    painter = painterResource(R.drawable.cmts_splash),
    contentDescription = if (landscape) "CMTS landscape splash" else "CMTS portrait splash",
    modifier = Modifier.fillMaxSize(),
    contentScale = ContentScale.Crop,
   )
   LaunchedEffect(Unit) {
    delay(900)
    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
    finish()
   }
  }
 }
}
